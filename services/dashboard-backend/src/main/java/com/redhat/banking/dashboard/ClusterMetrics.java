package com.redhat.banking.dashboard;

public class ClusterMetrics {
    public String cluster;
    public double tps;
    public int trafficWeight;
    public long totalLedgerEntries;
    public long processedSinceStart;
    public boolean healthy;
    public long timestamp;
    public double committedTps;   // derived: processedSinceStart delta / poll interval
    public double generatorTps;   // from transaction-generator (onprem only)
}
