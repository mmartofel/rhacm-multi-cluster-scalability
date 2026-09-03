// Fallback used only before the first WebSocket payload arrives. Once live, the
// backend-authoritative value is MetricsPayload.onpremCapacityTps (dashboard-backend's
// `onprem.capacity.tps` config property) — see App.tsx's `capacityTps` derivation.
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
  onpremCapacityTps: number;
}
