package com.redhat.banking.gateway;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @ConfigProperty(name = "TRAFFIC_WEIGHT", defaultValue = "100")
    int initialTrafficWeight;

    // Traffic weight 0-100 (percentage of traffic routed to this cluster)
    private final AtomicInteger trafficWeight = new AtomicInteger(100);

    @PostConstruct
    void init() {
        trafficWeight.set(Math.max(0, Math.min(100, initialTrafficWeight)));
    }

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
    @Blocking
    public Response setTrafficWeight(Map<String, Integer> body) {
        int weight = body.getOrDefault("trafficWeight", trafficWeight.get());
        trafficWeight.set(Math.max(0, Math.min(100, weight)));

        int[] partitions = computeOwnedPartitions(weight);
        String partitionJson = buildPartitionJson(partitions);
        // Processor first — it claims the partitions before the generator starts sending to them
        httpPut("http://transaction-processor.banking-demo.svc.cluster.local:8080/api/processor/stats/partitions", partitionJson);
        httpPut("http://transaction-generator.banking-demo.svc.cluster.local:8080/api/generator/partitions", partitionJson);

        return Response.ok(Map.of(
                "cluster", cluster,
                "trafficWeight", trafficWeight.get(),
                "ownedPartitions", partitions
        )).build();
    }

    // 6-partition topic; onprem owns [0..n-1], cloud owns [n..5]
    private int[] computeOwnedPartitions(int myWeight) {
        int onpremCount = Math.round(6 * ("onprem".equals(cluster) ? myWeight : 100 - myWeight) / 100.0f);
        int start = "onprem".equals(cluster) ? 0 : onpremCount;
        int end   = "onprem".equals(cluster) ? onpremCount : 6;
        int[] result = new int[end - start];
        for (int i = 0; i < result.length; i++) result[i] = start + i;
        return result;
    }

    private static String buildPartitionJson(int[] partitions) {
        StringBuilder sb = new StringBuilder("{\"partitions\":[");
        for (int i = 0; i < partitions.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(partitions[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    private void httpPut(String url, String jsonBody) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(800))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    @PUT
    @Path("/generator/tps/{rate}")
    @Blocking
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
    @Path("/processor/stats")
    @Blocking
    public Response processorStats() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://transaction-processor.banking-demo.svc.cluster.local:8080/api/processor/stats"))
                    .timeout(Duration.ofMillis(400))
                    .GET()
                    .build();
            String body = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
            return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return Response.ok("{\"rejectedTotal\":0,\"rejectedByReason\":{}}").type(MediaType.APPLICATION_JSON).build();
        }
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
