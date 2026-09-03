package com.redhat.banking.dashboard;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Path("/api/backend")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class DashboardResource {

    @ConfigProperty(name = "ONPREM_GATEWAY_URL", defaultValue = "http://cluster-gateway:8080")
    String onpremGatewayUrl;

    @ConfigProperty(name = "CLOUD_GATEWAY_URL", defaultValue = "http://cloud-cluster-gateway:8080")
    String cloudGatewayUrl;

    // Onprem's practical generation capacity (TPS) — the threshold Auto Burst (weight=100)
    // uses to decide when cloud starts absorbing overflow. Single source of truth for both
    // the backend split formula and the frontend capacity line (see MetricsPayload).
    @ConfigProperty(name = "onprem.capacity.tps", defaultValue = "100")
    int onpremCapacityTps;

    int getOnpremCapacityTps() { return onpremCapacityTps; }

    // Tracks the total TPS last set via the Load Control API so ClusterPoller
    // can report the full rate (not just the onprem split portion).
    private final AtomicInteger lastTotalTps = new AtomicInteger(0);

    int getLastTotalTps() { return lastTotalTps.get(); }

    // Backend-authoritative traffic-split weight (0-100, % of generated TPS routed to onprem).
    // setGeneratorTps reads this to derive the per-cluster TPS split, replacing the old
    // hardcoded "onprem takes the first 100, cloud gets the rest" rule that ignored the
    // configured Traffic Split entirely.
    private final AtomicInteger currentTrafficWeight = new AtomicInteger(100);

    int getCurrentTrafficWeight() { return currentTrafficWeight.get(); }

    @PUT
    @Path("/traffic-weight")
    public Response setTrafficWeight(Map<String, Integer> body) {
        int onpremWeight = Math.max(0, Math.min(100, body.getOrDefault("trafficWeight", 100)));
        int cloudWeight = 100 - onpremWeight;
        currentTrafficWeight.set(onpremWeight);

        boolean onpremOk = httpPut(onpremGatewayUrl + "/api/gateway/traffic-weight",
                "{\"trafficWeight\":" + onpremWeight + "}");
        boolean cloudOk = httpPut(cloudGatewayUrl + "/api/gateway/traffic-weight",
                "{\"trafficWeight\":" + cloudWeight + "}");

        return Response.ok(Map.of(
                "trafficWeight", onpremWeight,
                "onprem", onpremWeight,
                "cloud", cloudWeight,
                "onpremUpdated", onpremOk,
                "cloudUpdated", cloudOk
        )).build();
    }

    // Splits total TPS across clusters according to the current Traffic Split weight:
    // - weight == 100 (Auto Burst): onprem takes up to its capacity, cloud absorbs overflow.
    // - otherwise: proportional to the configured weight.
    @PUT
    @Path("/generator/tps/{total}")
    public Response setGeneratorTps(@PathParam("total") int total) {
        int weight = currentTrafficWeight.get();
        int onpremTps;
        int cloudTps;

        if (weight == 100) {
            onpremTps = Math.min(total, onpremCapacityTps);
            cloudTps  = Math.max(0, total - onpremCapacityTps);
        } else {
            onpremTps = Math.round(total * weight / 100.0f);
            cloudTps  = total - onpremTps;
        }

        boolean onpremOk = httpPut(onpremGatewayUrl + "/api/gateway/generator/tps/" + onpremTps, "");
        boolean cloudOk  = httpPut(cloudGatewayUrl  + "/api/gateway/generator/tps/" + cloudTps,  "");
        lastTotalTps.set(total);

        return Response.ok(Map.of(
                "total", total,
                "onpremTps", onpremTps,
                "cloudTps", cloudTps,
                "trafficWeight", weight,
                "onpremUpdated", onpremOk,
                "cloudUpdated", cloudOk
        )).build();
    }

    private boolean httpPut(String url, String jsonBody) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(800))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }
}
