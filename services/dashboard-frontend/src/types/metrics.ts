export interface ClusterMetrics {
  cluster: string;
  tps: number;
  trafficWeight: number;
  totalLedgerEntries: number;
  processedSinceStart: number;
  healthy: boolean;
  timestamp: number;
}

export interface MetricsPayload {
  clusters: ClusterMetrics[];
  snapshotAt: number;
}
