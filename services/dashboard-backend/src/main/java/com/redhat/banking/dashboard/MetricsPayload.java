package com.redhat.banking.dashboard;

import java.util.List;

public class MetricsPayload {
    public List<ClusterMetrics> clusters;
    public long snapshotAt;
    public int onpremCapacityTps;

    public MetricsPayload(List<ClusterMetrics> clusters, long snapshotAt, int onpremCapacityTps) {
        this.clusters = clusters;
        this.snapshotAt = snapshotAt;
        this.onpremCapacityTps = onpremCapacityTps;
    }
}
