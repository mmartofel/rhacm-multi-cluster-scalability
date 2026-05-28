export const ONPREM_CAPACITY_TPS = 100;

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
}

export interface MetricsPayload {
  clusters: ClusterMetrics[];
  snapshotAt: number;
}
