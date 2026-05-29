package com.redhat.banking.gateway;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/gateway")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ScalingResource {

    private static final String NS = "banking-demo";

    @Inject
    KubernetesClient k8s;

    @GET
    @Path("/scaling/summary")
    public Response scalingSummary() {
        return Response.ok(Map.of(
                "processorReplicas", readyReplicas("transaction-processor"),
                "accountReplicas",   readyReplicas("account-service")
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
