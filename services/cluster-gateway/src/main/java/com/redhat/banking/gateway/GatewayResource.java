package com.redhat.banking.gateway;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    @PUT
    @Path("/generator/tps/{rate}")
    public Response setGeneratorTps(@PathParam("rate") int rate) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://transaction-generator.banking-demo.svc.cluster.local:8080/api/generator/tps/" + Math.max(0, rate)))
                    .timeout(Duration.ofMillis(800))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
        return Response.ok(Map.of("tpsRate", rate)).build();
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
