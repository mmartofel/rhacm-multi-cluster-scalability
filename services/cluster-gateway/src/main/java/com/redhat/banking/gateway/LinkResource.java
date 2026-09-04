package com.redhat.banking.gateway;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
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

import java.util.Map;

// Manages the onprem-link-token Secret (banking-infra, type kubernetes.io/tls) that the
// Skupper Link CR (spec.tlsCredentials) uses to authenticate the RHSI cross-cluster
// tunnel — deleting it severs onprem<->cloud connectivity (MM2, cross-cluster JDBC),
// re-creating it restores the link. Only meaningful on cloud (onprem issues the
// AccessGrant; cloud redeems it into this AccessToken-backed Secret, so it only ever
// exists there) — see rbac-link.yaml, applied to the cloud overlay only. The name is a
// fixed literal from bootstrap-phase1.sh Step 6 (`AccessToken.metadata.name:
// onprem-link-token`), not environment-specific — confirmed live, NOT "skupper-link" as
// CLAUDE.md's chaos-scenario prose had shorthanded it.
@Path("/api/gateway")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class LinkResource {

    private static final String NS = "banking-infra";
    private static final String SECRET_NAME = "onprem-link-token";

    @Inject
    KubernetesClient k8s;

    private volatile String linkStatus = "unknown";

    // Backup of the live Secret captured immediately before deletion, so restore can
    // recreate it verbatim. Lost on pod restart — restore then returns 409 pointing at
    // the manual fallback rather than fabricating a fake secret.
    private volatile Secret cachedSecret;

    @Scheduled(every = "PT5S")
    void refreshStatus() {
        try {
            Secret s = k8s.secrets().inNamespace(NS).withName(SECRET_NAME).get();
            linkStatus = s != null ? "active" : "broken";
        } catch (Exception e) {
            linkStatus = "unknown";
        }
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
            Secret live = k8s.secrets().inNamespace(NS).withName(SECRET_NAME).get();
            if (live == null) {
                linkStatus = "broken";
                return Response.ok(Map.of("status", "broken", "alreadyBroken", true)).build();
            }
            cachedSecret = new SecretBuilder()
                    .withNewMetadata()
                        .withName(SECRET_NAME)
                        .withNamespace(NS)
                        .withLabels(live.getMetadata().getLabels())
                        .withAnnotations(live.getMetadata().getAnnotations())
                    .endMetadata()
                    .withType(live.getType())
                    .withData(live.getData())
                    .build();
            k8s.secrets().inNamespace(NS).withName(SECRET_NAME).delete();
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
            Secret live = k8s.secrets().inNamespace(NS).withName(SECRET_NAME).get();
            if (live != null) {
                linkStatus = "active";
                return Response.ok(Map.of("status", "active", "alreadyActive", true)).build();
            }
            if (cachedSecret == null) {
                return Response.status(409).entity(Map.of(
                        "status", "broken",
                        "error", "no-cached-secret",
                        "message", "cluster-gateway has no cached onprem-link-token Secret (pod likely restarted since " +
                                   "it was broken). Manual recovery: re-run the AccessGrant/AccessToken exchange " +
                                   "(bootstrap-phase1.sh Step 6) on onprem/cloud."
                )).build();
            }
            k8s.secrets().inNamespace(NS).resource(cachedSecret).create();
            cachedSecret = null;
            linkStatus = "active";
            return Response.ok(Map.of("status", "active")).build();
        } catch (Exception e) {
            linkStatus = "unknown";
            return Response.status(500)
                    .entity(Map.of("status", "unknown", "error", String.valueOf(e.getMessage())))
                    .build();
        }
    }
}
