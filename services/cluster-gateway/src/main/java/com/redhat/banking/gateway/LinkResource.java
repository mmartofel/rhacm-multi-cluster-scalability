package com.redhat.banking.gateway;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Simulates an RHSI link failure by tearing down ONLY the Skupper Listeners (cloud,
// banking-infra) that expose onprem's Kafka/PostgreSQL/Apicurio to cloud — NOT the
// cross-cluster Link/inter-router connection itself.
//
// Why not the Link CR (tried first, reverted after live testing): dashboard-backend runs
// on onprem only and reaches cloud's cluster-gateway exclusively through a *separate*
// Skupper Listener (onprem, routing key cloud-cluster-gateway/cloud-ledger-service) /
// Connector (cloud) pair — confirmed live via `oc get listeners,connectors -n
// banking-infra` on both clusters. If the whole Link goes down, that control-plane
// channel goes down with it, and the dashboard's own "restore" button can never reach
// cloud again to undo it — a network partition can't heal itself by sending a command
// through the partition.
//
// Deleting only the kafka-bootstrap/postgresql-primary/apicurio-registry Listeners
// leaves the cluster-gateway/ledger-service Listener/Connector pair completely
// untouched — confirmed live: the Link stays Ready, SITES IN NETWORK stays 2, and
// onprem's dashboard-backend can still reach cloud's /api/gateway/health throughout —
// while still producing the full documented chaos effect: cloud transaction-processor's
// DB/Apicurio health checks go DOWN with real "connection attempt failed" errors, and
// transactions get rejected to the DLQ (confirmed live: rejectedTotal climbed during the
// test). MM2 now runs over its own dedicated tunnel (kafka-bootstrap-mm2:9094, see
// infra/skupper/{onprem/connectors,cloud/listeners}.yaml and CLAUDE.md's "MirrorMaker 2
// needs its own dedicated listener/tunnel" note) that this list intentionally does NOT
// touch, so unlike before, MM2 keeps mirroring transactions-raw uninterrupted through
// this simulated outage — a more realistic depiction of a resilient DR pipeline than
// coupling it to the app-tier outage being demonstrated here.
//
// Restore is a plain re-create using the static spec from the checked-in manifest
// (infra/skupper/cloud/listeners.yaml) — no secret material, no per-run randomness, so
// no in-memory caching or cache-loss/409 path is needed (unlike the Secret-based
// approach this replaced).
@Path("/api/gateway")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class LinkResource {

    private static final String NS = "banking-infra";

    private record ListenerSpec(String name, int port) {}

    // Mirrors infra/skupper/cloud/listeners.yaml verbatim.
    private static final List<ListenerSpec> LISTENERS = List.of(
            new ListenerSpec("kafka-bootstrap", 9092),
            new ListenerSpec("postgresql-primary", 5432),
            new ListenerSpec("apicurio-registry", 8080)
    );

    private static final ResourceDefinitionContext LISTENER_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("skupper.io")
            .withVersion("v2alpha1")
            .withKind("Listener")
            .withPlural("listeners")
            .withNamespaced(true)
            .build();

    @Inject
    KubernetesClient k8s;

    private volatile String linkStatus = "unknown";

    private NonNamespaceOperation<GenericKubernetesResource, ?, Resource<GenericKubernetesResource>> listenerClient() {
        return k8s.genericKubernetesResources(LISTENER_CONTEXT).inNamespace(NS);
    }

    @Scheduled(every = "PT5S")
    void refreshStatus() {
        try {
            long present = LISTENERS.stream()
                    .filter(l -> listenerClient().withName(l.name()).get() != null)
                    .count();
            linkStatus = present == LISTENERS.size() ? "active" : present == 0 ? "broken" : "unknown";
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
            for (ListenerSpec l : LISTENERS) {
                listenerClient().withName(l.name()).delete();
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
            for (ListenerSpec l : LISTENERS) {
                if (listenerClient().withName(l.name()).get() == null) {
                    k8s.resource(buildListener(l)).inNamespace(NS).create();
                }
            }
            linkStatus = "active";
            return Response.ok(Map.of("status", "active")).build();
        } catch (Exception e) {
            linkStatus = "unknown";
            return Response.status(500)
                    .entity(Map.of("status", "unknown", "error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    // These 3 Listeners are normally managed declaratively by `oc apply -f
    // infra/skupper/cloud/listeners.yaml` (bootstrap-phase1.sh Step 7 / bootstrap-phase2.sh
    // Step 3), which stores a kubectl.kubernetes.io/last-applied-configuration annotation on
    // create for future 3-way-merge diffing. A plain imperative create() (as fabric8's API
    // naturally produces) never sets that annotation — confirmed live: bootstrap-phase2.sh's
    // next `oc apply` run then warns "resource is missing the ... annotation ... will be
    // patched automatically" for exactly these 3 objects. Setting it ourselves here, matching
    // the annotation's exact JSON content, keeps restore indistinguishable from a plain
    // `oc apply` and avoids that warning on every subsequent script run after a UI restore.
    private static final String LAST_APPLIED_CONFIG_ANNOTATION = "kubectl.kubernetes.io/last-applied-configuration";

    private GenericKubernetesResource buildListener(ListenerSpec l) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("routingKey", l.name());
        spec.put("port", l.port());
        spec.put("host", l.name());
        String lastAppliedConfig = String.format(
                "{\"apiVersion\":\"skupper.io/v2alpha1\",\"kind\":\"Listener\",\"metadata\":{\"annotations\":{},\"name\":\"%s\",\"namespace\":\"%s\"},\"spec\":{\"host\":\"%s\",\"port\":%d,\"routingKey\":\"%s\"}}",
                l.name(), NS, l.name(), l.port(), l.name());
        return new GenericKubernetesResourceBuilder()
                .withApiVersion("skupper.io/v2alpha1")
                .withKind("Listener")
                .withNewMetadata()
                    .withName(l.name())
                    .withNamespace(NS)
                    .addToAnnotations(LAST_APPLIED_CONFIG_ANNOTATION, lastAppliedConfig)
                .endMetadata()
                .addToAdditionalProperties("spec", spec)
                .build();
    }
}
