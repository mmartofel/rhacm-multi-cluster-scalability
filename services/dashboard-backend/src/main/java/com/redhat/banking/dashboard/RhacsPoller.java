package com.redhat.banking.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Polls RHACS Central directly for security/risk data scoped to our app namespaces
// (banking-demo, banking-infra) on both clusters. Central is a single onprem-only
// source of truth (like Apicurio) that already sees both clusters' Sensors, so this
// talks to it in-cluster with no cluster-gateway/RHSI hop involved — see CLAUDE.md's
// RHACS notes for why centralEndpoint differs between onprem (self-monitoring) and
// cloud (remote via Route).
//
// Same @Startup + @PostConstruct eager-init pattern as ScalingResource/KafkaPartitionStats:
// forces the first (network-bound) poll onto startup instead of the first live request.
// The @Scheduled poll runs on a worker thread (Quarkus scheduler default), so — like
// ClusterPoller's own @Scheduled poll() — no @Blocking annotation is needed here.
@ApplicationScoped
@Startup
public class RhacsPoller {

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "RHACS_CENTRAL_URL", defaultValue = "https://central.stackrox.svc.cluster.local:443")
    String centralUrl;

    @ConfigProperty(name = "RHACS_API_TOKEN", defaultValue = "")
    String apiToken;

    private volatile ComplianceSnapshot snapshot = new ComplianceSnapshot();

    // Central's internal service cert is signed by StackRox's own internal CA, not
    // OpenShift's service-ca — the JVM default truststore won't trust it. This mirrors
    // the `curl -sk` precedent bootstrap-phase2.sh already uses against this exact
    // endpoint for init-bundle generation; it's an in-cluster call over the pod
    // network, not a call crossing a real trust boundary.
    //
    // A *plain* X509TrustManager here is NOT enough — confirmed empirically (a local
    // repro with a self-signed cert + java.net.http.HttpClient reproduced the exact
    // live failure: "No subject alternative DNS name matching
    // central.stackrox.svc.cluster.local found."). The JDK wraps a plain
    // X509TrustManager passed to SSLContext.init() and, because JSSE can't assume a
    // legacy-style trust manager performs endpoint identification itself, the wrapper
    // enforces hostname/SAN checking on its own regardless of the delegate's trust
    // decision. An earlier fix attempt (SSLParameters.setEndpointIdentificationAlgorithm(""))
    // on top of the plain TrustManager did NOT fix this — same repro, same failure.
    // The actual fix (confirmed by the same repro passing): implement the full
    // X509ExtendedTrustManager, overriding all six methods (2-arg, Socket-arg, and
    // SSLEngine-arg, both client and server) as no-ops, so JSSE calls them directly
    // instead of wrapping/enforcing hostname checks on top.
    private final HttpClient httpClient = buildTrustingHttpClient();

    private static HttpClient buildTrustingHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509ExtendedTrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
                public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());

            return HttpClient.newBuilder()
                    .sslContext(ctx)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
        } catch (Exception e) {
            return HttpClient.newHttpClient();
        }
    }

    @PostConstruct
    void warmUp() {
        poll();
    }

    @Scheduled(every = "30s")
    void scheduledPoll() {
        poll();
    }

    ComplianceSnapshot getSnapshot() {
        return snapshot;
    }

    synchronized void poll() {
        if (apiToken == null || apiToken.isBlank()) {
            markUnavailable("RHACS_API_TOKEN not configured");
            return;
        }
        try {
            ComplianceSnapshot next = new ComplianceSnapshot();
            next.clusters = fetchClusterHealth();
            fetchAlerts(next);
            fetchImageVulnSummary(next);
            fetchSummaryCounts(next);
            next.available = true;
            next.lastUpdated = Instant.now().toEpochMilli();
            snapshot = next;
        } catch (Exception e) {
            markUnavailable(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // Degrade in place: keep the last known-good data visible (per-field, all the
    // way through) but flag the snapshot as stale so the UI can say so, rather than
    // going blank on a transient Central outage — same philosophy as the rest of
    // this dashboard's best-effort proxy endpoints.
    private void markUnavailable(String reason) {
        ComplianceSnapshot prev = snapshot;
        ComplianceSnapshot next = new ComplianceSnapshot();
        next.clusters = prev.clusters;
        next.severity = prev.severity;
        next.topViolations = prev.topViolations;
        next.numAlerts = prev.numAlerts;
        next.numImages = prev.numImages;
        next.numDeployments = prev.numDeployments;
        next.numNodes = prev.numNodes;
        next.numSecrets = prev.numSecrets;
        next.imagesScanned = prev.imagesScanned;
        next.imagesWithCriticalVulns = prev.imagesWithCriticalVulns;
        next.lastUpdated = prev.lastUpdated;
        next.available = false;
        next.error = reason;
        snapshot = next;
    }

    private List<ComplianceSnapshot.ClusterSecurityHealth> fetchClusterHealth() throws Exception {
        JsonNode root = mapper.readTree(httpGet(centralUrl + "/v1/clusters"));
        JsonNode clusters = root.isArray() ? root : root.path("clusters");
        List<ComplianceSnapshot.ClusterSecurityHealth> result = new ArrayList<>();
        if (!clusters.isArray()) return result;
        for (JsonNode c : clusters) {
            String name = c.path("name").asText("unknown");
            JsonNode health = c.path("healthStatus");
            // Defensive: some RHACS versions may surface these at the top level
            // instead of nested under healthStatus — check both.
            String overall = health.path("overallHealthStatus").asText(c.path("overallHealthStatus").asText("UNKNOWN"));
            String sensor = health.path("sensorHealthStatus").asText(c.path("sensorHealthStatus").asText("UNKNOWN"));
            result.add(new ComplianceSnapshot.ClusterSecurityHealth(name, overall, sensor));
        }
        return result;
    }

    private static final String[] SEVERITY_ORDER = {
            "CRITICAL_SEVERITY", "HIGH_SEVERITY", "MEDIUM_SEVERITY", "LOW_SEVERITY"
    };

    private void fetchAlerts(ComplianceSnapshot snap) {
        try {
            String query = "Namespace:banking-demo,banking-infra+Violation State:ACTIVE";
            String url = centralUrl + "/v1/alerts?query=" + urlEncode(query) + "&pagination.limit=500";
            JsonNode root = mapper.readTree(httpGet(url));
            JsonNode alerts = root.isArray() ? root : root.path("alerts");
            if (!alerts.isArray()) return;

            ComplianceSnapshot.SeverityCounts counts = new ComplianceSnapshot.SeverityCounts();
            List<ComplianceSnapshot.TopViolation> violations = new ArrayList<>();

            for (JsonNode alert : alerts) {
                String severity = alert.path("policy").path("severity").asText("UNKNOWN");
                switch (severity) {
                    case "CRITICAL_SEVERITY" -> counts.critical++;
                    case "HIGH_SEVERITY" -> counts.high++;
                    case "MEDIUM_SEVERITY" -> counts.medium++;
                    case "LOW_SEVERITY" -> counts.low++;
                    default -> { /* unrecognized severity string — ignore for counting */ }
                }

                ComplianceSnapshot.TopViolation v = new ComplianceSnapshot.TopViolation();
                v.policyName = alert.path("policy").path("name").asText("Unknown policy");
                v.deploymentName = alert.at("/deployment/name").isMissingNode()
                        ? alert.at("/resource/name").asText("unknown")
                        : alert.at("/deployment/name").asText("unknown");
                v.cluster = alert.path("clusterName").asText(
                        alert.at("/deployment/clusterName").asText("unknown"));
                v.severity = severity;
                v.firstOccurred = parseTimeMillis(alert.path("time").asText(null));
                violations.add(v);
            }

            violations.sort(Comparator
                    .comparingInt((ComplianceSnapshot.TopViolation v) -> severityRank(v.severity))
                    .thenComparingLong((ComplianceSnapshot.TopViolation v) -> -v.firstOccurred));

            snap.severity = counts;
            snap.topViolations = violations.size() > 10 ? violations.subList(0, 10) : violations;
        } catch (Exception e) {
            // leave severity/topViolations at their zero-value defaults — best-effort,
            // same convention as ClusterPoller's per-field try/catch degrade.
        }
    }

    private void fetchImageVulnSummary(ComplianceSnapshot snap) {
        try {
            String url = centralUrl + "/v1/images?query=" + urlEncode("Namespace:banking-demo,banking-infra") + "&pagination.limit=200";
            JsonNode root = mapper.readTree(httpGet(url));
            JsonNode images = root.isArray() ? root : root.path("images");
            if (!images.isArray()) return;

            long total = 0;
            long withCritical = 0;
            boolean sawVulnCounter = false;
            for (JsonNode img : images) {
                total++;
                JsonNode criticalNode = img.at("/vulnCounter/critical/total");
                if (!criticalNode.isMissingNode()) {
                    sawVulnCounter = true;
                    if (criticalNode.asInt(0) > 0) withCritical++;
                }
            }
            snap.imagesScanned = total;
            snap.imagesWithCriticalVulns = sawVulnCounter ? withCritical : -1;
        } catch (Exception e) {
            // leave at -1 (unknown) — field shape varies across RHACS versions,
            // confirm live against the deployed Central before relying on this.
        }
    }

    private void fetchSummaryCounts(ComplianceSnapshot snap) {
        try {
            JsonNode root = mapper.readTree(httpGet(centralUrl + "/v1/summary/counts"));
            // Protobuf int64 fields are serialized as JSON strings by Central's
            // grpc-gateway — asLong() parses numeric strings fine either way.
            snap.numAlerts = root.path("numAlerts").asLong(-1);
            snap.numDeployments = root.path("numDeployments").asLong(-1);
            snap.numNodes = root.path("numNodes").asLong(-1);
            snap.numImages = root.path("numImages").asLong(-1);
            snap.numSecrets = root.path("numSecrets").asLong(-1);
        } catch (Exception e) {
            // leave at -1 (unknown)
        }
    }

    private static int severityRank(String severity) {
        for (int i = 0; i < SEVERITY_ORDER.length; i++) {
            if (SEVERITY_ORDER[i].equals(severity)) return i;
        }
        return SEVERITY_ORDER.length;
    }

    private static long parseTimeMillis(String rfc3339) {
        if (rfc3339 == null) return 0;
        try {
            return Instant.parse(rfc3339).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + apiToken)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("RHACS Central returned HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }
}
