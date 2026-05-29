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

@Path("/api/backend")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class DashboardResource {

    @ConfigProperty(name = "ONPREM_GATEWAY_URL", defaultValue = "http://cluster-gateway:8080")
    String onpremGatewayUrl;

    @ConfigProperty(name = "CLOUD_GATEWAY_URL", defaultValue = "http://cloud-cluster-gateway:8080")
    String cloudGatewayUrl;

    @PUT
    @Path("/traffic-weight")
    public Response setTrafficWeight(Map<String, Integer> body) {
        int onpremWeight = Math.max(0, Math.min(100, body.getOrDefault("trafficWeight", 100)));
        int cloudWeight = 100 - onpremWeight;

        httpPut(onpremGatewayUrl + "/api/gateway/traffic-weight",
                "{\"trafficWeight\":" + onpremWeight + "}");
        httpPut(cloudGatewayUrl + "/api/gateway/traffic-weight",
                "{\"trafficWeight\":" + cloudWeight + "}");

        return Response.ok(Map.of(
                "trafficWeight", onpremWeight,
                "onprem", onpremWeight,
                "cloud", cloudWeight
        )).build();
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
}
