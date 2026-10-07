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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    // Optional (not plain String): RHACS_API_TOKEN is genuinely unset until
    // bootstrap-phase2.sh mints it. A plain String field with defaultValue = ""
    // crashes Quarkus at boot — SmallRye's String converter treats a resolved ""
    // as null, and eager @ConfigProperty validation rejects a null non-Optional
    // field before any application code (including the isBlank() check below)
    // ever runs. Optional<String> resolves to Optional.empty() instead, which
    // eager validation accepts.
    @ConfigProperty(name = "RHACS_API_TOKEN")
    Optional<String> apiToken;

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
        if (apiToken.isEmpty() || apiToken.get().isBlank()) {
            markUnavailable("RHACS_API_TOKEN not configured");
            return;
        }
        try {
            ComplianceSnapshot next = new ComplianceSnapshot();
            fetchClusters(next);
            fetchAlerts(next);
            fetchCounts(next);
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
        next.violations = prev.violations;
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

    // Everything below is scoped to these namespaces (RHACS search DSL: comma = OR
    // within a field, '+' = AND between fields).
    private static final String NAMESPACE_QUERY = "Namespace:banking-demo,banking-infra";

    // A violation's evidence list can run to hundreds of repeated runtime events.
    private static final int MAX_DETAIL_MESSAGES = 20;

    // Policy definitions change rarely and there are only a handful in play, so the
    // drill-down caches them for the life of the pod instead of refetching per click.
    private final Map<String, JsonNode> policyCache = new ConcurrentHashMap<>();

    private void fetchClusters(ComplianceSnapshot snap) throws Exception {
        JsonNode root = mapper.readTree(httpGet(centralUrl + "/v1/clusters"));
        JsonNode clusters = root.isArray() ? root : root.path("clusters");
        List<ComplianceSnapshot.ClusterSecurityHealth> result = new ArrayList<>();
        if (!clusters.isArray()) return;
        long nodes = 0;
        boolean nodesKnown = true;
        for (JsonNode c : clusters) {
            String name = c.path("name").asText("unknown");
            JsonNode health = c.path("healthStatus");
            // Defensive: some RHACS versions may surface these at the top level
            // instead of nested under healthStatus — check both.
            String overall = health.path("overallHealthStatus").asText(c.path("overallHealthStatus").asText("UNKNOWN"));
            String sensor = health.path("sensorHealthStatus").asText(c.path("sensorHealthStatus").asText("UNKNOWN"));
            result.add(new ComplianceSnapshot.ClusterSecurityHealth(name, overall, sensor));

            // There is no node-count endpoint; list each cluster's nodes and count.
            try {
                JsonNode list = mapper.readTree(httpGet(centralUrl + "/v1/nodes/" + c.path("id").asText())).path("nodes");
                if (list.isArray()) nodes += list.size(); else nodesKnown = false;
            } catch (Exception e) {
                nodesKnown = false;
            }
        }
        snap.clusters = result;
        snap.numNodes = nodesKnown && !result.isEmpty() ? nodes : -1;
    }

    private static final String[] SEVERITY_ORDER = {
            "CRITICAL_SEVERITY", "HIGH_SEVERITY", "MEDIUM_SEVERITY", "LOW_SEVERITY"
    };

    private void fetchAlerts(ComplianceSnapshot snap) {
        try {
            String query = NAMESPACE_QUERY + "+Violation State:ACTIVE";
            String url = centralUrl + "/v1/alerts?query=" + urlEncode(query) + "&pagination.limit=500";
            JsonNode root = mapper.readTree(httpGet(url));
            JsonNode alerts = root.isArray() ? root : root.path("alerts");
            if (!alerts.isArray()) return;

            ComplianceSnapshot.SeverityCounts counts = new ComplianceSnapshot.SeverityCounts();
            List<ComplianceSnapshot.Violation> violations = new ArrayList<>();

            for (JsonNode alert : alerts) {
                String severity = alert.path("policy").path("severity").asText("UNKNOWN");
                switch (severity) {
                    case "CRITICAL_SEVERITY" -> counts.critical++;
                    case "HIGH_SEVERITY" -> counts.high++;
                    case "MEDIUM_SEVERITY" -> counts.medium++;
                    case "LOW_SEVERITY" -> counts.low++;
                    default -> { /* unrecognized severity string — ignore for counting */ }
                }

                ComplianceSnapshot.Violation v = new ComplianceSnapshot.Violation();
                v.alertId = alert.path("id").asText("");
                v.policyId = alert.path("policy").path("id").asText("");
                v.namespace = alert.at("/commonEntityInfo/namespace").asText(
                        alert.at("/deployment/namespace").asText(""));
                v.lifecycleStage = alert.path("lifecycleStage").asText("");
                v.policyName = alert.path("policy").path("name").asText("Unknown policy");
                v.deploymentName = alert.at("/deployment/name").isMissingNode()
                        ? alert.at("/resource/name").asText("unknown")
                        : alert.at("/deployment/name").asText("unknown");
                v.cluster = alert.at("/commonEntityInfo/clusterName").asText(
                        alert.at("/deployment/clusterName").asText("unknown"));
                v.severity = severity;
                // The list API's "time" is the latest occurrence; firstOccurred only
                // exists on the single-alert endpoint (see fetchViolationDetail).
                v.lastOccurred = parseTimeMillis(alert.path("time").asText(null));
                violations.add(v);
            }

            violations.sort(Comparator
                    .comparingInt((ComplianceSnapshot.Violation v) -> severityRank(v.severity))
                    .thenComparingLong((ComplianceSnapshot.Violation v) -> -v.lastOccurred));

            snap.severity = counts;
            snap.violations = violations;
        } catch (Exception e) {
            // leave severity/violations at their zero-value defaults — best-effort,
            // same convention as ClusterPoller's per-field try/catch degrade.
        }
    }

    // RHACS 4.11 has no /v1/summary/counts (404) and its /v1/images list carries no
    // per-severity vulnerability counter — confirmed live. The per-entity *count
    // endpoints take the same search query as the list endpoints and return
    // {"count": N}. Each is fetched independently so one failure only blanks its
    // own tile.
    private void fetchCounts(ComplianceSnapshot snap) {
        snap.numDeployments = fetchCount("/v1/deploymentscount", NAMESPACE_QUERY);
        snap.numSecrets = fetchCount("/v1/secretscount", NAMESPACE_QUERY);
        snap.imagesScanned = fetchCount("/v1/imagescount", NAMESPACE_QUERY);
        snap.imagesWithCriticalVulns = fetchCount("/v1/imagescount",
                NAMESPACE_QUERY + "+Severity:CRITICAL_VULNERABILITY_SEVERITY");
    }

    private long fetchCount(String path, String query) {
        try {
            JsonNode root = mapper.readTree(httpGet(centralUrl + path + "?query=" + urlEncode(query)));
            return root.path("count").asLong(-1);
        } catch (Exception e) {
            return -1; // unknown
        }
    }

    // alertId must already be validated by the caller (it goes straight into the URL).
    ComplianceSnapshot.ViolationDetail fetchViolationDetail(String alertId) throws Exception {
        if (apiToken.isEmpty() || apiToken.get().isBlank()) {
            throw new IllegalStateException("RHACS_API_TOKEN not configured");
        }
        JsonNode alert = mapper.readTree(httpGet(centralUrl + "/v1/alerts/" + alertId));
        // The alert embeds a policy summary; rationale/remediation/MITRE are only
        // guaranteed on the policy itself, so prefer that and fall back to the embed.
        JsonNode policy = alert.path("policy");
        String policyId = policy.path("id").asText("");
        if (!policyId.isEmpty()) {
            try {
                JsonNode full = policyCache.get(policyId);
                if (full == null) {
                    full = mapper.readTree(httpGet(centralUrl + "/v1/policies/" + policyId));
                    policyCache.put(policyId, full);
                }
                policy = full;
            } catch (Exception e) {
                // keep the embedded summary
            }
        }

        ComplianceSnapshot.ViolationDetail d = new ComplianceSnapshot.ViolationDetail();
        d.alertId = alertId;
        d.policyName = policy.path("name").asText("Unknown policy");
        d.severity = policy.path("severity").asText("UNKNOWN");
        d.description = policy.path("description").asText("");
        d.rationale = policy.path("rationale").asText("");
        d.remediation = policy.path("remediation").asText("");
        d.categories = textList(policy.path("categories"));
        d.lifecycleStages = textList(policy.path("lifecycleStages"));
        d.enforcementActions = textList(policy.path("enforcementActions"));
        for (JsonNode m : policy.path("mitreAttackVectors")) {
            ComplianceSnapshot.MitreVector mv = new ComplianceSnapshot.MitreVector();
            mv.tactic = m.path("tactic").asText("");
            mv.techniques = textList(m.path("techniques"));
            d.mitre.add(mv);
        }

        d.deploymentName = alert.at("/deployment/name").isMissingNode()
                ? alert.at("/resource/name").asText("unknown")
                : alert.at("/deployment/name").asText("unknown");
        d.namespace = alert.path("namespace").asText(alert.at("/deployment/namespace").asText(""));
        d.cluster = alert.path("clusterName").asText(alert.at("/deployment/clusterName").asText("unknown"));
        d.firstOccurred = parseTimeMillis(alert.path("firstOccurred").asText(null));
        d.lastOccurred = parseTimeMillis(alert.path("time").asText(null));

        JsonNode violations = alert.path("violations");
        d.totalMessages = violations.size();
        for (JsonNode v : violations) {
            if (d.messages.size() >= MAX_DETAIL_MESSAGES) break;
            d.messages.add(new ComplianceSnapshot.ViolationMessage(
                    v.path("message").asText(""), parseTimeMillis(v.path("time").asText(null))));
        }
        return d;
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
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
                .header("Authorization", "Bearer " + apiToken.orElse(""))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("RHACS Central returned HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }
}
