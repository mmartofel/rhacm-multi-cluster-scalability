import React from 'react';
import { MetricsPayload, ClusterMetrics } from '../types/metrics';

interface Props { payload: MetricsPayload | null; }

function fmt(n: number, decimals = 0) {
  return n.toLocaleString('en', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

function ClusterCard({ m }: { m: ClusterMetrics }) {
  const isOnprem = m.cluster === 'onprem';
  const label    = isOnprem ? 'AWS (on-prem)' : 'GCP (cloud burst)';
  const accent   = isOnprem ? '#06c' : '#4cb140';
  const healthColor = m.healthy ? '#92d400' : '#c9190b';

  const committedTps = m.committedTps ?? 0;
  const tpm          = committedTps * 60;
  const ledger       = m.totalLedgerEntries ?? 0;
  const sinceStat    = m.processedSinceStart ?? 0;

  return (
    <div style={{
      background: '#212427',
      border: `1px solid ${accent}33`,
      borderTop: `3px solid ${accent}`,
      borderRadius: 8,
      padding: 16,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
        <div>
          <div style={{ fontWeight: 700, color: '#f0f0f0', fontSize: 14 }}>{label}</div>
          <div style={{ fontSize: 11, color: '#8a8d90', marginTop: 2 }}>
            {isOnprem ? 'Record-of-truth · Primary DB · Kafka source' : 'Cloud burst · KEDA 0–20 replicas'}
          </div>
        </div>
        <span style={{ fontSize: 11, color: healthColor, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: healthColor, display: 'inline-block' }} />
          {m.healthy ? 'Healthy' : 'Degraded'}
        </span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px 16px' }}>
        <div>
          <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 2 }}>Throughput</div>
          <div style={{ fontSize: 17, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {fmt(tpm)} <span style={{ fontSize: 11, fontWeight: 400, color: '#8a8d90' }}>TPM</span>
          </div>
        </div>
        <div>
          <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 2 }}>Committed TPS</div>
          <div style={{ fontSize: 17, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {fmt(committedTps, 1)}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 2 }}>Ledger entries</div>
          <div style={{ fontSize: 17, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {ledger > 0 ? fmt(ledger) : <span style={{ color: '#6a6e73', fontSize: 13, fontWeight: 400 }}>—</span>}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 2 }}>Committed (session)</div>
          <div style={{ fontSize: 17, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
            {sinceStat > 0 ? fmt(sinceStat) : <span style={{ color: '#6a6e73', fontSize: 13, fontWeight: 400 }}>—</span>}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 14 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
          <span style={{ fontSize: 11, color: '#6a6e73' }}>Traffic weight</span>
          <span style={{ fontSize: 11, color: accent, fontWeight: 600 }}>{m.trafficWeight}%</span>
        </div>
        <div style={{ height: 5, background: '#2a2d32', borderRadius: 3, overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${m.trafficWeight}%`, background: accent, borderRadius: 3, transition: 'width 0.4s ease' }} />
        </div>
      </div>
    </div>
  );
}

export default function ClusterCards({ payload }: Props) {
  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16 }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0', marginBottom: 14 }}>Cluster Status</div>
      {payload ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {payload.clusters.map(m => <ClusterCard key={m.cluster} m={m} />)}
        </div>
      ) : (
        <div style={{ color: '#6a6e73', fontSize: 13, textAlign: 'center', padding: '40px 0' }}>
          Waiting for data…
        </div>
      )}
    </div>
  );
}
