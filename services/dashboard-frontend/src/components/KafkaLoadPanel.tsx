import React from 'react';
import { MetricsPayload, ClusterMetrics, TopicLag, PartitionDetail } from '../types/metrics';
import { ONPREM_COLOR, CLOUD_COLOR, CAPACITY_COLOR, HEALTHY_COLOR, GEN_COLOR } from '../colors';

interface Props {
  payload: MetricsPayload | null;
}

const UNOWNED_COLOR = '#4a4d52';

function formatLag(lag: number): string {
  if (lag >= 1000000) return `${(lag / 1000000).toFixed(1)}M`;
  if (lag >= 1000) return `${(lag / 1000).toFixed(1)}k`;
  return String(lag);
}

function formatRate(rate: number): string {
  if (rate >= 1000) return `${(rate / 1000).toFixed(1)}k/s`;
  return `${rate.toFixed(1)}/s`;
}

function formatBytes(bytes: number): string {
  if (bytes < 0) return '…';
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(2)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(1)} MB`;
  if (bytes >= 1e3) return `${(bytes / 1e3).toFixed(1)} KB`;
  return `${bytes} B`;
}

function findCluster(payload: MetricsPayload | null, cluster: 'onprem' | 'cloud'): ClusterMetrics | undefined {
  return payload?.clusters.find(c => c.cluster === cluster);
}

function findTopic(cm: ClusterMetrics | undefined, topic: string): TopicLag | undefined {
  return cm?.kafkaTopics?.find(t => t.topic === topic);
}

interface BarRow {
  partition: number;
  lag: number;
  hasLag: boolean;
  color: string;
  ownerLabel: string;
}

function Bars({ rows }: { rows: BarRow[] }) {
  const lagValues = rows.filter(r => r.hasLag).map(r => r.lag);
  const maxLag = lagValues.length > 0 ? Math.max(1, ...lagValues) : 1;
  return (
    <div style={{ display: 'flex', gap: 6, flex: 1, minHeight: 0 }}>
      {rows.map(r => {
        const fillPct = r.hasLag ? Math.max(4, Math.round((r.lag / maxLag) * 100)) : 30;
        return (
          <div key={r.partition} style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
            <span style={{ fontSize: 11, color: r.hasLag ? r.color : UNOWNED_COLOR, fontWeight: 600, minHeight: 14 }}>
              {r.hasLag ? formatLag(r.lag) : '—'}
            </span>
            <div style={{ width: '100%', flex: 1, minHeight: 0, background: '#2a2d32', borderRadius: 4, overflow: 'hidden', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
              <div style={{ width: '100%', height: `${fillPct}%`, background: r.color, borderRadius: 4, opacity: r.hasLag ? 1 : 0.35, transition: 'height 0.4s ease, background 0.3s ease' }} />
            </div>
            <span style={{ fontSize: 10, color: r.color, fontWeight: 700 }}>{r.partition}</span>
          </div>
        );
      })}
    </div>
  );
}

function TopicCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: '14px 16px', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: subtitle ? 2 : 10 }}>{title}</div>
      {subtitle && <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 10 }}>{subtitle}</div>}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>{children}</div>
    </div>
  );
}

function RawTopicPanel({ payload }: { payload: MetricsPayload | null }) {
  const lagMap = new Map<number, number>();
  const ownerMap = new Map<number, 'onprem' | 'cloud'>();
  for (const cluster of payload?.clusters ?? []) {
    const topic = findTopic(cluster, 'transactions-raw');
    for (const pd of topic?.partitions ?? []) {
      if (pd.owned) {
        lagMap.set(pd.partition, pd.lag);
        ownerMap.set(pd.partition, cluster.cluster as 'onprem' | 'cloud');
      }
    }
  }
  const rows: BarRow[] = Array.from({ length: 24 }, (_, p) => {
    const owner = ownerMap.get(p);
    const color = owner === 'onprem' ? ONPREM_COLOR : owner === 'cloud' ? CLOUD_COLOR : UNOWNED_COLOR;
    return {
      partition: p,
      lag: lagMap.get(p) ?? 0,
      hasLag: lagMap.has(p),
      color,
      ownerLabel: owner === 'onprem' ? 'On-Prem' : owner === 'cloud' ? 'Cloud' : '—',
    };
  });

  return (
    <TopicCard title="transactions-raw" subtitle="24 partitions — split 0-11 On-Prem / 12-23 Cloud">
      <Bars rows={rows} />
    </TopicCard>
  );
}

function localRows(cm: ClusterMetrics | undefined, topic: string, fallbackCount: number, color: string, dlq: boolean): BarRow[] {
  const t = findTopic(cm, topic);
  const count = t?.partitionCount ?? fallbackCount;
  const details = t?.partitions ?? [];
  const byPartition = new Map<number, PartitionDetail>(details.map(d => [d.partition, d]));
  return Array.from({ length: count }, (_, p) => {
    const d = byPartition.get(p);
    return {
      partition: p,
      lag: d?.lag ?? 0,
      hasLag: d !== undefined,
      color: dlq ? CAPACITY_COLOR : color,
      ownerLabel: '',
    };
  });
}

function LocalSplitTopicPanel({ payload, topic, title, subtitle, dlq }: {
  payload: MetricsPayload | null; topic: string; title: string; subtitle: string; dlq?: boolean;
}) {
  const onprem = findCluster(payload, 'onprem');
  const cloud = findCluster(payload, 'cloud');
  const onpremRows = localRows(onprem, topic, 3, ONPREM_COLOR, !!dlq);
  const cloudRows = localRows(cloud, topic, 3, CLOUD_COLOR, !!dlq);

  return (
    <TopicCard title={title} subtitle={subtitle}>
      <div style={{ display: 'flex', gap: 16, flex: 1, minHeight: 0 }}>
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <div style={{ fontSize: 11, color: ONPREM_COLOR, fontWeight: 700, marginBottom: 6 }}>On-Prem (local)</div>
          <Bars rows={onpremRows} />
        </div>
        <div style={{ width: 1, background: '#2a2d32' }} />
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <div style={{ fontSize: 11, color: CLOUD_COLOR, fontWeight: 700, marginBottom: 6 }}>Cloud (local)</div>
          <Bars rows={cloudRows} />
        </div>
      </div>
    </TopicCard>
  );
}

function groupAccent(state: string | undefined, memberCount: number): string {
  if (!state || state === 'UNKNOWN' || state === 'NO_CONSUMER') return '#6a6e73';
  if (state === 'EMPTY' || state === 'DEAD' || memberCount === 0) return CAPACITY_COLOR;
  if (state === 'STABLE') return HEALTHY_COLOR;
  return GEN_COLOR; // PREPARING_REBALANCE / COMPLETING_REBALANCE
}

function ConsumerGroupKpi({ clusterLabel, topicLabel, topicData }: {
  clusterLabel: string; topicLabel: string; topicData: TopicLag | undefined;
}) {
  const memberCount = topicData?.memberCount ?? 0;
  const state = topicData?.groupState;
  const accent = groupAccent(state, memberCount);
  return (
    <div style={{ background: '#212427', border: `1px solid ${accent}55`, borderTop: `3px solid ${accent}`, borderRadius: 8, padding: '10px 14px' }}>
      <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 2 }}>{clusterLabel}</div>
      <div style={{ fontSize: 11, color: '#8a8d90', marginBottom: 4 }}>{topicLabel}</div>
      <div style={{ fontSize: 26, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums', lineHeight: 1 }}>
        {topicData ? memberCount : '—'}
      </div>
      <div style={{ fontSize: 11, color: accent, marginTop: 4, fontWeight: 600 }}>{state ?? 'waiting…'}</div>
    </div>
  );
}

function TopicBreakdownRow({ label, color, topicData, isRateOnly }: {
  label: string; color: string; topicData: TopicLag | undefined; isRateOnly?: boolean;
}) {
  const underReplicated = topicData?.underReplicatedCount ?? 0;
  const diskBytes = (topicData?.partitions ?? []).reduce((sum, p) => sum + Math.max(0, p.logDirBytes), 0);
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 11, marginBottom: 4, gap: 8 }}>
      <span style={{ color: '#8a8d90', minWidth: 62 }}>{label}</span>
      <span style={{ color, fontWeight: 600 }}>
        {isRateOnly ? 'growth ' : 'lag '}
        {formatLag(topicData?.totalLag ?? 0)} · {formatRate(topicData?.msgsPerSec ?? 0)} · {formatBytes(diskBytes)}
      </span>
      {underReplicated > 0 && (
        <span style={{ fontSize: 10, fontWeight: 700, padding: '1px 6px', borderRadius: 8, background: `${CAPACITY_COLOR}22`, color: CAPACITY_COLOR, border: `1px solid ${CAPACITY_COLOR}55` }}>
          {underReplicated} under-replicated
        </span>
      )}
    </div>
  );
}

function StatsBreakdownCard({ payload }: { payload: MetricsPayload | null }) {
  const onprem = findCluster(payload, 'onprem');
  const cloud = findCluster(payload, 'cloud');

  const section = (title: string, topicName: string, isRateOnly?: boolean) => (
    <div style={{ marginBottom: 14 }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: '#f0f0f0', marginBottom: 6 }}>{title}</div>
      <div style={{ background: '#151515', borderRadius: 6, padding: '8px 10px' }}>
        <TopicBreakdownRow label="On-Prem" color={ONPREM_COLOR} topicData={findTopic(onprem, topicName)} isRateOnly={isRateOnly} />
        <TopicBreakdownRow label="Cloud" color={CLOUD_COLOR} topicData={findTopic(cloud, topicName)} isRateOnly={isRateOnly} />
      </div>
    </div>
  );

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16, flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
      <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 14 }}>Topic Health</div>
      {section('transactions-raw', 'transactions-raw')}
      {section('transactions-committed', 'transactions-committed')}
      {section('transactions-dlq (no consumer)', 'transactions-dlq', true)}
      <div style={{ fontSize: 10, color: '#6a6e73', lineHeight: 1.6, marginTop: 4 }}>
        Lag/growth · throughput · disk usage per cluster's local broker storage. DLQ has no active consumer —
        its number is cumulative backlog since topic creation, not real-time lag.
      </div>
    </div>
  );
}

export default function KafkaLoadPanel({ payload }: Props) {
  const onprem = findCluster(payload, 'onprem');
  const cloud = findCluster(payload, 'cloud');

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16, alignItems: 'stretch', height: '100%', minHeight: 0 }}>
      {/* Left: per-topic partition maps */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minHeight: 0 }}>
        <RawTopicPanel payload={payload} />
        <LocalSplitTopicPanel
          payload={payload}
          topic="transactions-committed"
          title="transactions-committed"
          subtitle="3 partitions per cluster — local, not mirrored"
        />
        <LocalSplitTopicPanel
          payload={payload}
          topic="transactions-dlq"
          title="transactions-dlq"
          subtitle="3 partitions per cluster — no consumer, cumulative backlog"
          dlq
        />
      </div>

      {/* Right: consumer group health + topic breakdown */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minHeight: 0 }}>
        <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16 }}>
          <div style={{ fontWeight: 600, fontSize: 13, color: '#f0f0f0', marginBottom: 12 }}>Consumer Group Health</div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <ConsumerGroupKpi clusterLabel="On-Prem" topicLabel="transaction-processors" topicData={findTopic(onprem, 'transactions-raw')} />
            <ConsumerGroupKpi clusterLabel="Cloud" topicLabel="transaction-processors" topicData={findTopic(cloud, 'transactions-raw')} />
            <ConsumerGroupKpi clusterLabel="On-Prem" topicLabel="ledger-updaters" topicData={findTopic(onprem, 'transactions-committed')} />
            <ConsumerGroupKpi clusterLabel="Cloud" topicLabel="ledger-updaters" topicData={findTopic(cloud, 'transactions-committed')} />
          </div>
        </div>
        <StatsBreakdownCard payload={payload} />
      </div>
    </div>
  );
}
