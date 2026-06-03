export const ONPREM_CAPACITY_TPS = 100;

export interface PartitionStat {
  partition: number;
  lag: number;
  owned: boolean;
}

export interface ClusterMetrics {
  cluster: string;
  tps: number;
  trafficWeight: number;
  totalLedgerEntries: number;
  processedSinceStart: number;
  healthy: boolean;
  timestamp: number;
  committedTps: number;
  generatorTps: number;
  processorReplicas: number;
  accountReplicas: number;
  rejectedTotal: number;
  partitions?: PartitionStat[];
}

export interface MetricsPayload {
  clusters: ClusterMetrics[];
  snapshotAt: number;
}
