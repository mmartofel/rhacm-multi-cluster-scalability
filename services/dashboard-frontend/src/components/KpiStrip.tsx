import React from 'react';
import { MetricsPayload } from '../types/metrics';

interface Props { payload: MetricsPayload | null; }

interface Tile {
  label: string;
  value: string;
  sub: string;
  accent: string;
}

export default function KpiStrip({ payload }: Props) {
  const onprem = payload?.clusters.find(c => c.cluster === 'onprem');
  const cloud = payload?.clusters.find(c => c.cluster === 'cloud');

  const onpremTpm = (onprem?.committedTps ?? 0) * 60;
  const cloudTpm = (cloud?.committedTps ?? 0) * 60;
  const totalTpm = onpremTpm + cloudTpm;
  const genTps = onprem?.generatorTps ?? 0;
  const totalCommitTps = (onprem?.committedTps ?? 0) + (cloud?.committedTps ?? 0);
  const efficiency = genTps > 0 ? Math.min(100, Math.round((totalCommitTps / genTps) * 100)) : null;
  const healthyCount = payload ? payload.clusters.filter(c => c.healthy).length : 0;
  const totalCount = payload?.clusters.length ?? 0;

  const tiles: Tile[] = [
    {
      label: 'Total Throughput',
      value: payload ? totalTpm.toLocaleString('en', { maximumFractionDigits: 0 }) : '—',
      sub: 'transactions / min',
      accent: '#06c',
    },
    {
      label: 'Generator Rate',
      value: payload ? genTps.toLocaleString('en', { maximumFractionDigits: 0 }) : '—',
      sub: 'transactions / sec injected',
      accent: '#f4c145',
    },
    {
      label: 'Processing Efficiency',
      value: efficiency !== null ? `${efficiency}%` : payload ? '—' : '—',
      sub: efficiency !== null && efficiency < 80 ? 'backlog building ↑' : 'commit rate vs generation',
      accent: efficiency !== null && efficiency < 80 ? '#c9190b' : '#4cb140',
    },
    {
      label: 'Cluster Health',
      value: payload ? `${healthyCount}/${totalCount}` : '—',
      sub: payload ? (healthyCount === totalCount ? 'all clusters healthy' : 'degraded') : 'waiting for data',
      accent: payload && healthyCount === totalCount ? '#92d400' : '#c9190b',
    },
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
      {tiles.map(tile => (
        <div key={tile.label} style={{
          background: '#1b1d21',
          border: '1px solid #2a2d32',
          borderTop: `3px solid ${tile.accent}`,
          borderRadius: 8,
          padding: '16px 20px',
        }}>
          <div style={{ fontSize: 12, color: '#8a8d90', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 6 }}>
            {tile.label}
          </div>
          <div style={{ fontSize: 32, fontWeight: 700, color: '#f0f0f0', lineHeight: 1, marginBottom: 6, fontVariantNumeric: 'tabular-nums' }}>
            {tile.value}
          </div>
          <div style={{ fontSize: 12, color: '#6a6e73' }}>{tile.sub}</div>
        </div>
      ))}
    </div>
  );
}
