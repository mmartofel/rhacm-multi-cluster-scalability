package com.redhat.banking.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ClusterPoller {

    @Inject
    MetricsBroadcaster broadcaster;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "ONPREM_GATEWAY_URL", defaultValue = "http://cluster-gateway:8080")
    String onpremGatewayUrl;

    @ConfigProperty(name = "CLOUD_GATEWAY_URL", defaultValue = "http://cloud-cluster-gateway:8080")
    String cloudGatewayUrl;

    @ConfigProperty(name = "ONPREM_LEDGER_URL", defaultValue = "http://ledger-service:8080")
    String onpremLedgerUrl;

    @ConfigProperty(name = "CLOUD_LEDGER_URL", defaultValue = "http://cloud-ledger-service:8080")
    String cloudLedgerUrl;

    @Scheduled(every = "PT0.5S")
    void poll() {
        List<ClusterMetrics> metrics = new ArrayList<>();
        metrics.add(pollCluster("onprem", onpremGatewayUrl, onpremLedgerUrl));
        metrics.add(pollCluster("cloud", cloudGatewayUrl, cloudLedgerUrl));

        MetricsPayload payload = new MetricsPayload(metrics, Instant.now().toEpochMilli());
        try {
            broadcaster.publish(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            // swallow serialization errors
        }
    }

    private ClusterMetrics pollCluster(String name, String gatewayUrl, String ledgerUrl) {
        ClusterMetrics m = new ClusterMetrics();
        m.cluster = name;
        m.timestamp = Instant.now().toEpochMilli();
        m.healthy = false;

        try {
            String gatewayJson = httpGet(gatewayUrl + "/api/gateway/metrics/summary");
            JsonNode gw = mapper.readTree(gatewayJson);
            m.tps = gw.path("tps").asDouble(0);
            m.trafficWeight = gw.path("trafficWeight").asInt(0);
            m.healthy = true;
        } catch (Exception e) {
            m.tps = 0;
        }

        try {
            String ledgerJson = httpGet(ledgerUrl + "/api/ledger/summary");
            JsonNode lg = mapper.readTree(ledgerJson);
            m.totalLedgerEntries = lg.path("totalLedgerEntries").asLong(0);
            m.processedSinceStart = lg.path("processedSinceStart").asLong(0);
        } catch (Exception e) {
            m.totalLedgerEntries = 0;
        }

        return m;
    }

    private String httpGet(String url) throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofMillis(400))
                .GET()
                .build();
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }
}
