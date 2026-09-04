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

    private record TpsSplit(int onpremTps, int cloudTps, boolean onpremOk, boolean cloudOk) {}

    // Splits total TPS across clusters according to the given Traffic Split weight and pushes
    // it to both generators immediately:
    // - weight == 100 (Auto Burst): onprem takes up to its capacity, cloud absorbs overflow.
    // - otherwise: proportional to the configured weight.
    private TpsSplit applyTpsSplit(int total, int weight) {
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

        return new TpsSplit(onpremTps, cloudTps, onpremOk, cloudOk);
    }

    @PUT
    @Path("/traffic-weight")
    public Response setTrafficWeight(Map<String, Integer> body) {
        int onpremWeight = Math.max(0, Math.min(100, body.getOrDefault("trafficWeight", 100)));
        int cloudWeight = 100 - onpremWeight;
        currentTrafficWeight.set(onpremWeight);

        boolean onpremWeightOk = httpPut(onpremGatewayUrl + "/api/gateway/traffic-weight",
                "{\"trafficWeight\":" + onpremWeight + "}");
        boolean cloudWeightOk = httpPut(cloudGatewayUrl + "/api/gateway/traffic-weight",
                "{\"trafficWeight\":" + cloudWeight + "}");

        // Re-apply the currently running TPS at the new split immediately, so a live weight
        // change redistributes generation without waiting for a new Load Control action.
        TpsSplit split = applyTpsSplit(lastTotalTps.get(), onpremWeight);

        return Response.ok(Map.of(
                "trafficWeight", onpremWeight,
                "onprem", onpremWeight,
                "cloud", cloudWeight,
                "onpremTps", split.onpremTps(),
                "cloudTps", split.cloudTps(),
                "onpremUpdated", onpremWeightOk && split.onpremOk(),
                "cloudUpdated", cloudWeightOk && split.cloudOk()
        )).build();
    }

    @PUT
    @Path("/generator/tps/{total}")
    public Response setGeneratorTps(@PathParam("total") int total) {
        int weight = currentTrafficWeight.get();
        TpsSplit split = applyTpsSplit(total, weight);

        return Response.ok(Map.of(
                "total", total,
                "onpremTps", split.onpremTps(),
                "cloudTps", split.cloudTps(),
                "trafficWeight", weight,
                "onpremUpdated", split.onpremOk(),
                "cloudUpdated", split.cloudOk()
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

    // Link-failure chaos control — only ever proxies to cloud, since the
    // onprem-link-token Secret (banking-infra) only exists there (onprem issues the
    // AccessGrant, cloud redeems it into this Secret).
    @PUT
    @Path("/link/break")
    public Response breakLink() {
        ProxyResult r = httpPutForBody(cloudGatewayUrl + "/api/gateway/link/break");
        return Response.status(r.status()).entity(r.body()).build();
    }

    @PUT
    @Path("/link/restore")
    public Response restoreLink() {
        ProxyResult r = httpPutForBody(cloudGatewayUrl + "/api/gateway/link/restore");
        return Response.status(r.status()).entity(r.body()).build();
    }

    private record ProxyResult(int status, String body) {}

    private ProxyResult httpPutForBody(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(800))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return new ProxyResult(resp.statusCode(), resp.body());
        } catch (Exception e) {
            return new ProxyResult(502, "{\"status\":\"unknown\",\"error\":\"gateway unreachable\"}");
        }
    }
}
