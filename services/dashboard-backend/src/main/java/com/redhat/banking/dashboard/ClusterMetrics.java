package com.redhat.banking.dashboard;

import java.util.ArrayList;
import java.util.List;

public class ClusterMetrics {

    public static class PartitionStat {
        public int partition;
        public long lag;
        public boolean owned;

        public PartitionStat() {}
        public PartitionStat(int partition, long lag, boolean owned) {
            this.partition = partition;
            this.lag = lag;
            this.owned = owned;
        }
    }
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
    public long   rejectedTotal     = 0;    // cumulative rejected transactions since processor start
    public List<PartitionStat> partitions = new ArrayList<>();
}
