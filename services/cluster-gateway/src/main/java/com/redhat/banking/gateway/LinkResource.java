package com.redhat.banking.gateway;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

// Manages the RHSI cross-cluster link on cloud: the Skupper `Link` CR (skupper.io/v2alpha1)
// and the onprem-link-token Secret (banking-infra, type kubernetes.io/tls) its
// spec.tlsCredentials references.
//
// CRITICAL — confirmed live: deleting only the Secret has NO effect on an already-
// established link. Skupper's router authenticates and opens the inter-router AMQP/TLS
// connection once, using the cert material loaded at connection time; it does not watch
// the Secret and does not tear down a live session when the Secret disappears. `skstat -c`
// on the cloud skupper-router showed the inter-router connection still fully alive
// (TLSv1.3, x.509, uptime unaffected) minutes after the Secret was deleted, and cloud's
// processor kept writing to onprem PostgreSQL/Apicurio via the tunnel with zero rejects —
// the chaos scenario had no observable effect. The `Link` CR is the actual desired-state
// object the site controller watches: deleting IT is what commands the router to close the
// connection (confirmed live: `skstat -c` immediately loses the inter-router connection and
// `sites.skupper.io` SITES IN NETWORK drops 2 -> 1). So break/restore must operate on BOTH
// objects, Link first on break (severs immediately) and Secret first on restore (so the
// Link's tlsCredentials reference resolves as soon as it's created).
//
// Only meaningful on cloud (onprem issues the AccessGrant; cloud redeems it into this
// AccessToken-backed Secret/Link pair, so neither object exists on onprem) — see
// rbac-link.yaml, applied to the cloud overlay only. Both names are fixed literals from
// bootstrap-phase1.sh Step 6, not environment-specific.
@Path("/api/gateway")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class LinkResource {

    private static final String NS = "banking-infra";
    private static final String SECRET_NAME = "onprem-link-token";
    private static final String LINK_NAME = "onprem-link-token";

    private static final ResourceDefinitionContext LINK_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("skupper.io")
            .withVersion("v2alpha1")
            .withKind("Link")
            .withPlural("links")
            .withNamespaced(true)
            .build();

    @Inject
    KubernetesClient k8s;

    private volatile String linkStatus = "unknown";

    // Backups captured immediately before deletion, so restore can recreate both objects
    // verbatim. Lost on pod restart — restore then returns 409 pointing at the manual
    // fallback rather than fabricating fake objects.
    private volatile Secret cachedSecret;
    private volatile GenericKubernetesResource cachedLink;

    private NonNamespaceOperation<GenericKubernetesResource, ?, Resource<GenericKubernetesResource>> linkClient() {
        return k8s.genericKubernetesResources(LINK_CONTEXT).inNamespace(NS);
    }

    @Scheduled(every = "PT5S")
    void refreshStatus() {
        try {
            GenericKubernetesResource link = linkClient().withName(LINK_NAME).get();
            linkStatus = (link != null && isReady(link)) ? "active" : "broken";
        } catch (Exception e) {
            linkStatus = "unknown";
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isReady(GenericKubernetesResource link) {
        Object statusObj = link.getAdditionalProperties().get("status");
        if (!(statusObj instanceof Map)) {
            return false;
        }
        Object conditionsObj = ((Map<String, Object>) statusObj).get("conditions");
        if (!(conditionsObj instanceof List)) {
            return false;
        }
        for (Object c : (List<?>) conditionsObj) {
            if (c instanceof Map) {
                Map<?, ?> cond = (Map<?, ?>) c;
                if ("Ready".equals(cond.get("type"))) {
                    return "True".equals(cond.get("status"));
                }
            }
        }
        return false;
    }

    @GET
    @Path("/link/status")
    public Response status() {
        return Response.ok(Map.of("status", linkStatus)).build();
    }

    @PUT
    @Path("/link/break")
    @Blocking
    public Response breakLink() {
        try {
            GenericKubernetesResource liveLink = linkClient().withName(LINK_NAME).get();
            Secret liveSecret = k8s.secrets().inNamespace(NS).withName(SECRET_NAME).get();

            if (liveLink == null && liveSecret == null) {
                linkStatus = "broken";
                return Response.ok(Map.of("status", "broken", "alreadyBroken", true)).build();
            }

            if (liveLink != null) {
                cachedLink = cleanLink(liveLink);
            }
            if (liveSecret != null) {
                cachedSecret = cleanSecret(liveSecret);
            }

            // Link first — this is what actually severs the live connection.
            if (liveLink != null) {
                linkClient().withName(LINK_NAME).delete();
            }
            if (liveSecret != null) {
                k8s.secrets().inNamespace(NS).withName(SECRET_NAME).delete();
            }
            linkStatus = "broken";
            return Response.ok(Map.of("status", "broken")).build();
        } catch (Exception e) {
            linkStatus = "unknown";
            return Response.status(500)
                    .entity(Map.of("status", "unknown", "error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    @PUT
    @Path("/link/restore")
    @Blocking
    public Response restoreLink() {
        try {
            GenericKubernetesResource liveLink = linkClient().withName(LINK_NAME).get();
            if (liveLink != null) {
                linkStatus = isReady(liveLink) ? "active" : "broken";
                return Response.ok(Map.of("status", linkStatus, "alreadyActive", true)).build();
            }
            if (cachedSecret == null || cachedLink == null) {
                return Response.status(409).entity(Map.of(
                        "status", "broken",
                        "error", "no-cached-secret",
                        "message", "cluster-gateway has no cached onprem-link-token Secret/Link (pod likely restarted " +
                                   "since it was broken). Manual recovery: delete any stale AccessToken/Link objects " +
                                   "(oc delete accesstoken,link onprem-link-token -n banking-infra --context cloud), " +
                                   "then re-run the AccessGrant/AccessToken exchange (bootstrap-phase1.sh Step 6) — " +
                                   "confirmed live: re-applying onto an existing AccessToken only updates its spec " +
                                   "and does not recreate the Secret; only a fresh AccessToken create does."
                )).build();
            }
            // Secret first, then Link — mirrors bootstrap-phase1.sh's original ordering
            // so the Link's tlsCredentials reference resolves immediately on creation.
            if (k8s.secrets().inNamespace(NS).withName(SECRET_NAME).get() == null) {
                k8s.resource(cachedSecret).inNamespace(NS).create();
            }
            k8s.resource(cachedLink).inNamespace(NS).create();
            cachedSecret = null;
            cachedLink = null;
            linkStatus = "broken"; // real value confirmed by the next 5s refreshStatus() poll
            return Response.ok(Map.of("status", "active")).build();
        } catch (Exception e) {
            linkStatus = "unknown";
            return Response.status(500)
                    .entity(Map.of("status", "unknown", "error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    private Secret cleanSecret(Secret live) {
        return new SecretBuilder()
                .withNewMetadata()
                    .withName(SECRET_NAME)
                    .withNamespace(NS)
                    .withLabels(live.getMetadata().getLabels())
                    .withAnnotations(live.getMetadata().getAnnotations())
                .endMetadata()
                .withType(live.getType())
                .withData(live.getData())
                .build();
    }

    private GenericKubernetesResource cleanLink(GenericKubernetesResource live) {
        GenericKubernetesResourceBuilder builder = new GenericKubernetesResourceBuilder()
                .withApiVersion(live.getApiVersion())
                .withKind(live.getKind())
                .withNewMetadata()
                    .withName(LINK_NAME)
                    .withNamespace(NS)
                .endMetadata();
        Object specObj = live.getAdditionalProperties().get("spec");
        if (specObj != null) {
            builder.addToAdditionalProperties("spec", specObj);
        }
        return builder.build();
    }
}
