import React from 'react';
import { MetricsPayload, ClusterMetrics, ONPREM_CAPACITY_TPS } from '../types/metrics';
import { ONPREM_COLOR, CLOUD_COLOR } from '../colors';

interface Props { payload: MetricsPayload | null; capacityTps?: number; }

function fmt(n: number, decimals = 0) {
  return n.toLocaleString('en', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

function ClusterCard({ m, isBurst }: { m: ClusterMetrics; isBurst: boolean }) {
  const isOnprem = m.cluster === 'onprem';
  const label    = isOnprem ? 'On-Prem' : 'Cloud';
  const accent   = isOnprem ? ONPREM_COLOR : CLOUD_COLOR;
  const healthColor = m.healthy ? '#92d400' : '#c9190b';

  const roleBadge = isOnprem
    ? { text: 'Primary', color: ONPREM_COLOR }
    : isBurst
      ? { text: 'Burst Active', color: '#f4c145' }
      : { text: 'Standby', color: '#6a6e73' };

  const committedTps = m.committedTps ?? 0;
  const tpm          = committedTps * 60;
  const ledger       = m.totalLedgerEntries ?? 0;
  const sinceStat    = m.processedSinceStart ?? 0;
  const rejected     = m.rejectedTotal ?? 0;

  return (
    <div style={{
      background: '#212427',
      border: `1px solid ${accent}33`,
      borderTop: `3px solid ${accent}`,
      borderRadius: 8,
      padding: 20,
      flex: 1,
      minHeight: 0,
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'space-between',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 18 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 700, color: '#f0f0f0', fontSize: 16 }}>{label}</span>
            <span style={{
              fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 10,
              background: `${roleBadge.color}22`, color: roleBadge.color, border: `1px solid ${roleBadge.color}55`,
            }}>
              {roleBadge.text}
            </span>
          </div>
          <div style={{ fontSize: 12, color: '#8a8d90', marginTop: 4 }}>
            {isOnprem ? 'Record-of-truth · Primary DB · Kafka source' : 'Cloud burst · KEDA 1–20 replicas'}
          </div>
        </div>
        <span style={{ fontSize: 12, color: healthColor, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ width: 7, height: 7, borderRadius: '50%', background: healthColor, display: 'inline-block' }} />
          {m.healthy ? 'Healthy' : 'Degraded'}
        </span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '18px 20px' }}>
        <div>
          <div style={{ fontSize: 12, color: '#6a6e73', marginBottom: 4 }}>Throughput</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {fmt(tpm)} <span style={{ fontSize: 12, fontWeight: 400, color: '#8a8d90' }}>TPM</span>
          </div>
        </div>
        <div>
          <div style={{ fontSize: 12, color: '#6a6e73', marginBottom: 4 }}>Committed TPS</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {fmt(committedTps, 1)}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 12, color: '#6a6e73', marginBottom: 4 }}>Ledger entries</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {ledger > 0 ? fmt(ledger) : <span style={{ color: '#6a6e73', fontSize: 15, fontWeight: 400 }}>—</span>}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 12, color: '#6a6e73', marginBottom: 4 }}>Committed (session)</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {sinceStat > 0 ? fmt(sinceStat) : <span style={{ color: '#6a6e73', fontSize: 15, fontWeight: 400 }}>—</span>}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 18, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ fontSize: 12, color: '#6a6e73' }}>Rejected (DLQ)</div>
        <span style={{
          fontSize: 14, fontWeight: 700,
          color: rejected > 0 ? '#c9190b' : '#6a6e73',
          fontVariantNumeric: 'tabular-nums',
        }}>
          {fmt(rejected)}
        </span>
      </div>

      <div style={{ marginTop: 14 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
          <span style={{ fontSize: 12, color: '#6a6e73' }}>Traffic weight</span>
          <span style={{ fontSize: 12, color: accent, fontWeight: 600 }}>{m.trafficWeight}%</span>
        </div>
        <div style={{ height: 7, background: '#2a2d32', borderRadius: 4, overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${m.trafficWeight}%`, background: accent, borderRadius: 4, transition: 'width 0.4s ease' }} />
        </div>
      </div>
    </div>
  );
}

export default function ClusterCards({ payload, capacityTps = ONPREM_CAPACITY_TPS }: Props) {
  const genTps = payload?.clusters.find(c => c.cluster === 'onprem')?.generatorTps ?? 0;
  const isBurst = genTps > capacityTps;
  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16, flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0', marginBottom: 14 }}>Cluster Status</div>
      {payload ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minHeight: 0 }}>
          {payload.clusters.map(m => <ClusterCard key={m.cluster} m={m} isBurst={isBurst} />)}
        </div>
      ) : (
        <div style={{ color: '#6a6e73', fontSize: 13, textAlign: 'center', padding: '40px 0' }}>
          Waiting for data…
        </div>
      )}
    </div>
  );
}
