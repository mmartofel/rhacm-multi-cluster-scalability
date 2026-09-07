package com.redhat.banking.dashboard;

import java.util.ArrayList;
import java.util.List;

// Cached, best-effort snapshot of RHACS Central security/risk data scoped to the
// banking-demo/banking-infra namespaces. Populated by RhacsPoller; served as-is by
// DashboardResource's /api/backend/compliance endpoint. Public fields, no getters —
// same convention as ClusterMetrics/MetricsPayload.
public class ComplianceSnapshot {

    public static class ClusterSecurityHealth {
        public String cluster;             // "onprem" | "cloud"
        public String healthStatus;        // RHACS overall cluster health (e.g. HEALTHY, DEGRADED, UNHEALTHY, UNINITIALIZED)
        public String sensorHealthStatus;

        public ClusterSecurityHealth() {}
        public ClusterSecurityHealth(String cluster, String healthStatus, String sensorHealthStatus) {
            this.cluster = cluster;
            this.healthStatus = healthStatus;
            this.sensorHealthStatus = sensorHealthStatus;
        }
    }

    public static class SeverityCounts {
        public int critical;
        public int high;
        public int medium;
        public int low;
    }

    public static class TopViolation {
        public String policyName;
        public String deploymentName;
        public String cluster;
        public String severity;
        public long firstOccurred; // epoch millis, 0 if unknown
    }

    // false until the first successful poll; flips back to false (without clearing
    // the rest of the snapshot) whenever Central is unreachable, so the UI can show
    // "last known data as of <lastUpdated>" instead of going blank.
    public boolean available = false;
    public String error;
    public long lastUpdated = 0;

    public List<ClusterSecurityHealth> clusters = new ArrayList<>();
    public SeverityCounts severity = new SeverityCounts();
    public List<TopViolation> topViolations = new ArrayList<>();

    // Fleet-wide counts from /v1/summary/counts. -1 = not yet known (never fetched
    // successfully), distinct from a genuine 0.
    public long numAlerts = -1;
    public long numImages = -1;
    public long numDeployments = -1;
    public long numNodes = -1;
    public long numSecrets = -1;

    // Best-effort rollup of images (scoped to our namespaces) with at least one
    // fixable Critical CVE. -1 = unknown (RHACS API version didn't expose the
    // expected field — see RhacsPoller.fetchImageVulnSummary).
    public long imagesScanned = -1;
    public long imagesWithCriticalVulns = -1;
}
