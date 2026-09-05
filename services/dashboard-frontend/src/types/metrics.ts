// Fallback used only before the first WebSocket payload arrives. Once live, the
// backend-authoritative value is MetricsPayload.onpremCapacityTps (dashboard-backend's
// `onprem.capacity.tps` config property) — see App.tsx's `capacityTps` derivation.
export const ONPREM_CAPACITY_TPS = 100;

export interface PartitionStat {
  partition: number;
  lag: number;
  owned: boolean;
}

export interface PartitionDetail {
  partition: number;
  endOffset: number;
  committedOffset: number;
  lag: number;
  owned: boolean;
  isrCount: number;
  replicaCount: number;
  underReplicated: boolean;
  logDirBytes: number; // -1 = not yet computed
}

export interface TopicLag {
  topic: string;
  partitionCount: number;
  consumerGroup: string | null;
  hasConsumer: boolean;
  groupState: string;
  memberCount: number;
  totalLag: number;
  msgsPerSec: number;
  underReplicatedCount: number;
  partitions: PartitionDetail[];
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
  kafkaTopics?: TopicLag[];
}

export interface MetricsPayload {
  clusters: ClusterMetrics[];
  snapshotAt: number;
  onpremCapacityTps: number;
  interconnectStatus: 'active' | 'broken' | 'unknown';
}
