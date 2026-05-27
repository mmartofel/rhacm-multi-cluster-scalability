package com.redhat.banking.dashboard;

import java.util.List;

public class MetricsPayload {
    public List<ClusterMetrics> clusters;
    public long snapshotAt;

    public MetricsPayload(List<ClusterMetrics> clusters, long snapshotAt) {
        this.clusters = clusters;
        this.snapshotAt = snapshotAt;
    }
}
