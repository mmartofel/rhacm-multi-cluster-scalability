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

// One ResourceQuota entry: CPU in cores, memory/storage in bytes, counts as-is.
export interface QuotaItem {
  resource: string;
  used: number;
  hard: number;
}

// Per namespace: quota used vs hard, plus live usage from the metrics API (-1 = unknown).
export interface NamespaceResources {
  namespace: string;
  quota: string | null;
  items: QuotaItem[];
  usageCpuCores: number;
  usageMemoryBytes: number;
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
  resources?: NamespaceResources[];
}

export interface MetricsPayload {
  clusters: ClusterMetrics[];
  snapshotAt: number;
  onpremCapacityTps: number;
  interconnectStatus: 'active' | 'broken' | 'unknown';
}

// REST-pulled (not WebSocket-pushed) — see ComplianceWidget.tsx. Mirrors
// dashboard-backend's ComplianceSnapshot.java field-for-field.
export interface ClusterSecurityHealth {
  cluster: string;
  healthStatus: string;
  sensorHealthStatus: string;
}

export interface SeverityCounts {
  critical: number;
  high: number;
  medium: number;
  low: number;
}

export interface Violation {
  alertId: string;
  policyId: string;
  policyName: string;
  deploymentName: string;
  namespace: string;
  cluster: string;
  severity: string;
  lifecycleStage: string;
  lastOccurred: number;
}

export interface ViolationMessage {
  message: string;
  time: number;
}

export interface MitreVector {
  tactic: string;
  techniques: string[];
}

// On-demand drill-down — /api/backend/compliance/violations/{alertId}.
export interface ViolationDetail {
  alertId: string;
  policyName: string;
  severity: string;
  description: string;
  rationale: string;
  remediation: string;
  categories: string[];
  lifecycleStages: string[];
  enforcementActions: string[];
  mitre: MitreVector[];
  deploymentName: string;
  namespace: string;
  cluster: string;
  firstOccurred: number;
  lastOccurred: number;
  totalMessages: number;
  messages: ViolationMessage[];
}

export interface ComplianceSnapshot {
  available: boolean;
  error: string | null;
  lastUpdated: number;
  clusters: ClusterSecurityHealth[];
  severity: SeverityCounts;
  violations: Violation[];
  numDeployments: number;
  numNodes: number;
  numSecrets: number;
  imagesScanned: number;
  imagesWithCriticalVulns: number;
}
