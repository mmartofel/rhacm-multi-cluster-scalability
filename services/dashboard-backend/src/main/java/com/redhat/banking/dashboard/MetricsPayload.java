package com.redhat.banking.dashboard;

import java.util.List;

public class MetricsPayload {
    public List<ClusterMetrics> clusters;
    public long snapshotAt;
    public int onpremCapacityTps;
    // Ground truth for the RHSI cross-cluster link: "active" | "broken" | "unknown".
    // A cross-cluster concept, not per-cluster, so it lives at the payload top level.
    public String interconnectStatus;

    public MetricsPayload(List<ClusterMetrics> clusters, long snapshotAt, int onpremCapacityTps, String interconnectStatus) {
        this.clusters = clusters;
        this.snapshotAt = snapshotAt;
        this.onpremCapacityTps = onpremCapacityTps;
        this.interconnectStatus = interconnectStatus;
    }
}
