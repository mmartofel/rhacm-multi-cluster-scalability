import React from 'react';
import { MetricsPayload, ClusterMetrics } from '../types/metrics';

interface Props { payload: MetricsPayload | null; }

function ClusterCard({ m }: { m: ClusterMetrics }) {
  const isOnprem = m.cluster === 'onprem';
  const label = isOnprem ? 'AWS (on-prem)' : 'GCP (cloud burst)';
  const accent = isOnprem ? '#06c' : '#4cb140';
  const healthColor = m.healthy ? '#92d400' : '#c9190b';
  const tpm = (m.committedTps ?? 0) * 60;

  return (
    <div style={{
      background: '#212427',
      border: `1px solid ${accent}33`,
      borderTop: `3px solid ${accent}`,
      borderRadius: 8,
      padding: 16,
      flex: 1,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
        <div>
          <div style={{ fontWeight: 700, color: '#f0f0f0', fontSize: 14 }}>{label}</div>
          <div style={{ fontSize: 11, color: '#8a8d90', marginTop: 2 }}>{isOnprem ? 'Record-of-truth · Primary DB · Kafka source' : 'Cloud burst · KEDA 0–20 replicas'}</div>
        </div>
        <span style={{ fontSize: 11, color: healthColor, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: healthColor, display: 'inline-block' }} />
          {m.healthy ? 'Healthy' : 'Degraded'}
        </span>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px 16px' }}>
        <Metric label="Throughput" value={`${tpm.toLocaleString('en', { maximumFractionDigits: 0 })} TPM`} accent={accent} />
        <Metric label="Traffic weight" value={`${m.trafficWeight}%`} accent={accent} />
        <Metric label="Committed TPS" value={(m.committedTps ?? 0).toFixed(1)} accent={accent} />
        <Metric label="Ledger entries" value={m.totalLedgerEntries.toLocaleString('en')} accent={accent} />
      </div>

      <div style={{ marginTop: 12 }}>
        <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 4 }}>Traffic weight</div>
        <div style={{ height: 6, background: '#2a2d32', borderRadius: 3, overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${m.trafficWeight}%`, background: accent, borderRadius: 3, transition: 'width 0.4s ease' }} />
        </div>
      </div>
    </div>
  );
}

function Metric({ label, value, accent }: { label: string; value: string; accent: string }) {
  return (
    <div>
      <div style={{ fontSize: 11, color: '#6a6e73' }}>{label}</div>
      <div style={{ fontSize: 15, fontWeight: 600, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>{value}</div>
    </div>
  );
}

export default function ClusterCards({ payload }: Props) {
  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16, height: '100%' }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0', marginBottom: 14 }}>Cluster Status</div>
      {payload ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {payload.clusters.map(m => <ClusterCard key={m.cluster} m={m} />)}
        </div>
      ) : (
        <div style={{ color: '#6a6e73', fontSize: 13, textAlign: 'center', paddingTop: 40 }}>
          Waiting for data…
        </div>
      )}
    </div>
  );
}
