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

    public static class Violation {
        public String alertId;        // RHACS alert id — key for the drill-down endpoint
        public String policyId;
        public String policyName;
        public String deploymentName;
        public String namespace;
        public String cluster;
        public String severity;
        public String lifecycleStage; // DEPLOY | RUNTIME | BUILD
        public long lastOccurred;     // epoch millis, 0 if unknown
    }

    public static class ViolationMessage {
        public String message;
        public long time; // epoch millis, 0 if unknown (deploy-time violations carry no timestamp)

        public ViolationMessage() {}
        public ViolationMessage(String message, long time) {
            this.message = message;
            this.time = time;
        }
    }

    public static class MitreVector {
        public String tactic;
        public List<String> techniques = new ArrayList<>();
    }

    // On-demand drill-down for one violation (not part of the polled snapshot):
    // the policy's own explanation plus the concrete evidence for this alert.
    // Served by /api/backend/compliance/violations/{alertId}.
    public static class ViolationDetail {
        public String alertId;
        public String policyName;
        public String severity;
        public String description;
        public String rationale;
        public String remediation;
        public List<String> categories = new ArrayList<>();
        public List<String> lifecycleStages = new ArrayList<>();
        public List<String> enforcementActions = new ArrayList<>();
        public List<MitreVector> mitre = new ArrayList<>();

        public String deploymentName;
        public String namespace;
        public String cluster;
        public long firstOccurred; // epoch millis, 0 if unknown
        public long lastOccurred;
        public int totalMessages;  // before the cap applied to `messages`
        public List<ViolationMessage> messages = new ArrayList<>();
    }

    // false until the first successful poll; flips back to false (without clearing
    // the rest of the snapshot) whenever Central is unreachable, so the UI can show
    // "last known data as of <lastUpdated>" instead of going blank.
    public boolean available = false;
    public String error;
    public long lastUpdated = 0;

    public List<ClusterSecurityHealth> clusters = new ArrayList<>();
    public SeverityCounts severity = new SeverityCounts();
    public List<Violation> violations = new ArrayList<>();

    // Counts scoped to our namespaces on both clusters (RHACS /v1/*count endpoints),
    // except numNodes which is cluster-wide (nodes have no namespace). -1 = not yet
    // known (never fetched successfully), distinct from a genuine 0.
    public long numDeployments = -1;
    public long numNodes = -1;
    public long numSecrets = -1;
    public long imagesScanned = -1;
    public long imagesWithCriticalVulns = -1;
}
