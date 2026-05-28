package com.redhat.banking.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @ConfigProperty(name = "GENERATOR_URL", defaultValue = "http://transaction-generator:8080")
    String generatorUrl;

    private final Map<String, Long> prevProcessed = new ConcurrentHashMap<>();
    private volatile long prevPollMs = 0;

    @Scheduled(every = "PT0.5S")
    void poll() {
        long nowMs = Instant.now().toEpochMilli();
        double intervalSecs = prevPollMs > 0 ? (nowMs - prevPollMs) / 1000.0 : 0.5;
        prevPollMs = nowMs;

        List<ClusterMetrics> metrics = new ArrayList<>();
        metrics.add(pollCluster("onprem", onpremGatewayUrl, onpremLedgerUrl, intervalSecs));
        metrics.add(pollCluster("cloud", cloudGatewayUrl, cloudLedgerUrl, intervalSecs));

        MetricsPayload payload = new MetricsPayload(metrics, nowMs);
        try {
            broadcaster.publish(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            // swallow serialization errors
        }
    }

    private ClusterMetrics pollCluster(String name, String gatewayUrl, String ledgerUrl, double intervalSecs) {
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

            long prev = prevProcessed.getOrDefault(name, m.processedSinceStart);
            long delta = m.processedSinceStart - prev;
            m.committedTps = intervalSecs > 0 ? Math.max(0, delta / intervalSecs) : 0;
            prevProcessed.put(name, m.processedSinceStart);
        } catch (Exception e) {
            m.totalLedgerEntries = 0;
            m.committedTps = 0;
        }

        if ("onprem".equals(name)) {
            try {
                String genJson = httpGet(generatorUrl + "/api/generator/status");
                JsonNode gen = mapper.readTree(genJson);
                m.generatorTps = gen.path("tpsRate").asDouble(0);
            } catch (Exception e) {
                m.generatorTps = 0;
            }
        }

        return m;
    }

    private String httpGet(String url) throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(400))
                .GET()
                .build();
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }
}
