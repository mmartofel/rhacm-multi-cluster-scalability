package com.redhat.banking.gateway;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Path("/api/gateway")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class GatewayResource {

    private final String cluster = System.getenv().getOrDefault("SOURCE_CLUSTER", "unknown");

    // Traffic weight 0-100 (percentage of traffic routed to this cluster)
    private final AtomicInteger trafficWeight = new AtomicInteger(50);

    // Rolling TPS counter
    private final AtomicLong requestsThisSecond = new AtomicLong(0);
    private volatile double currentTps = 0.0;

    @Scheduled(every = "1s")
    void computeTps() {
        currentTps = requestsThisSecond.getAndSet(0);
    }

    @GET
    @Path("/metrics/summary")
    public Response metricsSummary() {
        return Response.ok(Map.of(
                "cluster", cluster,
                "trafficWeight", trafficWeight.get(),
                "tps", currentTps,
                "timestamp", Instant.now().toEpochMilli()
        )).build();
    }

    @GET
    @Path("/traffic-weight")
    public Response getTrafficWeight() {
        return Response.ok(Map.of(
                "cluster", cluster,
                "trafficWeight", trafficWeight.get()
        )).build();
    }

    @PUT
    @Path("/traffic-weight")
    public Response setTrafficWeight(Map<String, Integer> body) {
        int weight = body.getOrDefault("trafficWeight", trafficWeight.get());
        trafficWeight.set(Math.max(0, Math.min(100, weight)));
        return Response.ok(Map.of(
                "cluster", cluster,
                "trafficWeight", trafficWeight.get()
        )).build();
    }

    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(Map.of(
                "cluster", cluster,
                "status", "ok",
                "timestamp", Instant.now().toEpochMilli()
        )).build();
    }
}
