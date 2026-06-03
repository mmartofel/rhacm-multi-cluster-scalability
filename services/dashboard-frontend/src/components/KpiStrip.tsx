import React from 'react';
import { MetricsPayload, ONPREM_CAPACITY_TPS } from '../types/metrics';
import { ProcessingMode } from '../App';
import { AWS_COLOR, GCP_COLOR, CAPACITY_COLOR, HEALTHY_COLOR } from '../colors';

interface Props {
  payload: MetricsPayload | null;
  processingMode: ProcessingMode;
}

interface Tile {
  label: string;
  value: string;
  sub: string;
  accent: string;
}

const MODE_CONFIG: Record<ProcessingMode, { label: string; sub: string; accent: string }> = {
  'auto-burst':  { label: 'Auto Burst',        sub: '≤100 TPS onprem · cloud scales on overflow', accent: HEALTHY_COLOR },
  'onprem-only': { label: 'Route 100% → AWS',  sub: 'all traffic routed to onprem cluster',       accent: AWS_COLOR    },
  'split':       { label: 'Split 50 / 50',     sub: 'traffic split equally across clusters',       accent: '#8476d1'   },
  'cloud-only':  { label: 'Route 100% → GCP',  sub: 'all traffic routed to cloud cluster',        accent: GCP_COLOR    },
};

export default function KpiStrip({ payload, processingMode }: Props) {
  const onprem = payload?.clusters.find(c => c.cluster === 'onprem');
  const cloud = payload?.clusters.find(c => c.cluster === 'cloud');

  const onpremTpm = (onprem?.committedTps ?? 0) * 60;
  const cloudTpm = (cloud?.committedTps ?? 0) * 60;
  const totalTpm = onpremTpm + cloudTpm;
  const genTps = onprem?.generatorTps ?? 0;
  const healthyCount = payload ? payload.clusters.filter(c => c.healthy).length : 0;
  const totalCount = payload?.clusters.length ?? 0;
  const totalRejected = (onprem?.rejectedTotal ?? 0) + (cloud?.rejectedTotal ?? 0);

  const modeConf = MODE_CONFIG[processingMode];
  const modeLabel = processingMode === 'split' && onprem
    ? `Split ${onprem.trafficWeight} / ${100 - onprem.trafficWeight}`
    : modeConf.label;
  const modeSub = processingMode === 'split' && onprem
    ? `${onprem.trafficWeight}% onprem · ${100 - onprem.trafficWeight}% cloud`
    : modeConf.sub;

  const tiles: Tile[] = [
    {
      label: 'Total Throughput',
      value: payload ? totalTpm.toLocaleString('en', { maximumFractionDigits: 0 }) : '—',
      sub: 'transactions / min',
      accent: AWS_COLOR,
    },
    {
      label: 'Generator Rate',
      value: payload ? genTps.toLocaleString('en', { maximumFractionDigits: 0 }) : '—',
      sub: 'transactions / sec injected',
      accent: '#f4c145',
    },
    {
      label: 'Processing Mode',
      value: payload ? modeLabel : '—',
      sub: payload ? modeSub : 'waiting for data',
      accent: payload ? modeConf.accent : '#6a6e73',
    },
    {
      label: 'Cluster Health',
      value: payload ? `${healthyCount}/${totalCount}` : '—',
      sub: payload ? (healthyCount === totalCount ? 'all clusters healthy' : 'degraded') : 'waiting for data',
      accent: payload && healthyCount === totalCount ? HEALTHY_COLOR : CAPACITY_COLOR,
    },
    {
      label: 'Total Rejected',
      value: payload ? totalRejected.toLocaleString('en') : '—',
      sub: 'transactions sent to DLQ',
      accent: totalRejected > 0 ? CAPACITY_COLOR : '#6a6e73',
    },
  ];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 12 }}>
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
