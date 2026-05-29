package com.redhat.banking.dashboard;

public class ClusterMetrics {
    public String cluster;
    public double tps;
    public int trafficWeight;
    public long totalLedgerEntries;
    public long processedSinceStart;
    public boolean healthy;
    public long timestamp;
    public double committedTps;     // derived: processedSinceStart delta / poll interval
    public double generatorTps;     // from transaction-generator (onprem only)
    public int    processorReplicas = -1;   // readyReplicas of transaction-processor (-1 = unknown)
    public int    accountReplicas   = -1;   // readyReplicas of account-service (-1 = unknown)
}
