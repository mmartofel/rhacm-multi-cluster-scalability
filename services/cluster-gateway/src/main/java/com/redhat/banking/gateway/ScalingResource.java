package com.redhat.banking.gateway;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Startup
@Path("/api/gateway")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ScalingResource {

    private static final String NS = "banking-demo";

    @Inject
    KubernetesClient k8s;

    private volatile int processorReplicas = -1;
    private volatile int accountReplicas   = -1;

    // fabric8 always completes an HTTP response (including Jackson deserialization of
    // the body) on its Vert.x event-loop thread regardless of which thread issued the
    // call, so the first-ever deserialization of the large, heavily-annotated
    // Deployment model blocks that shared event loop — confirmed live: 14+ seconds on
    // vert.x-eventloop-thread-0. @Startup + @PostConstruct forces that one-time cost to
    // happen during the (probe-budgeted) startup window instead of on the first live
    // @Scheduled poll, where it could stall other traffic sharing the same event loop.
    @PostConstruct
    void warmUpDeploymentDeserialization() {
        readyReplicas("account-service");
    }

    // Refresh replica counts on a background thread every 5 s — keeps the
    // blocking K8s API call off the Vert.x event loop entirely.
    @Scheduled(every = "PT5S")
    void refreshReplicas() {
        processorReplicas = readyReplicas("transaction-processor");
        accountReplicas   = readyReplicas("account-service");
    }

    @GET
    @Path("/scaling/summary")
    public Response scalingSummary() {
        return Response.ok(Map.of(
                "processorReplicas", processorReplicas,
                "accountReplicas",   accountReplicas
        )).build();
    }

    private int readyReplicas(String name) {
        try {
            Integer r = k8s.apps().deployments()
                    .inNamespace(NS).withName(name)
                    .get().getStatus().getReadyReplicas();
            return r != null ? r : 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
