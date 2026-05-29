import React, { useState } from 'react';
import { MetricsPayload } from '../types/metrics';

interface Props { payload: MetricsPayload | null; }

const PRESETS = [
  { label: 'Quiet',  tps: 0,   color: '#6a6e73', description: 'System idle — all processors drain, KEDA scales cloud to 0 replicas.' },
  { label: 'Low',    tps: 50,  color: '#4cb140', description: '50 TPS — all onprem (under capacity). Cloud generator stays at 0 TPS, KEDA holds 0 replicas.' },
  { label: 'Medium', tps: 100, color: '#06c',    description: '100 TPS — onprem at full capacity. Cloud TPS = 0, no burst needed.' },
  { label: 'High',   tps: 200, color: '#f4c145', description: '200 TPS — 100 TPS onprem + 100 TPS cloud burst. Watch KEDA ramp to ~7 GCP pods over ~90 s.' },
  { label: 'Burst',  tps: 300, color: '#c9190b', description: '300 TPS — 100 TPS onprem + 200 TPS cloud burst. KEDA scales to ~14 GCP pods at full throughput.' },
];

export default function LoadControlPanel({ payload }: Props) {
  const [status, setStatus] = useState<{ msg: string; ok: boolean } | null>(null);
  const [pending, setPending] = useState<number | null>(null);

  const setTps = async (tps: number) => {
    setPending(tps);
    setStatus(null);
    try {
      // Fan-out to both clusters: backend splits ≤100 TPS onprem, remainder cloud.
      const res = await fetch(`/api/backend/generator/tps/${tps}`, { method: 'PUT' });
      if (res.ok) {
        setStatus({ msg: `Generator rate set to ${tps} TPS`, ok: true });
      } else {
        setStatus({ msg: `Failed: HTTP ${res.status}`, ok: false });
      }
    } catch (e: any) {
      setStatus({ msg: `Error: ${e.message}`, ok: false });
    } finally {
      setPending(null);
    }
  };

  const currentTps = payload?.clusters.find(c => c.cluster === 'onprem')?.generatorTps ?? null;
  const currentPreset = PRESETS.find(p => p.tps === currentTps);

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 24, maxWidth: 720 }}>
      <div style={{ marginBottom: 20 }}>
        <div style={{ fontSize: 18, fontWeight: 700, color: '#f0f0f0', marginBottom: 4 }}>Load Control</div>
        <div style={{ fontSize: 13, color: '#8a8d90' }}>
          Controls the transaction-generator rate. Changes take effect within 1 second.
          Watch the TPM chart and Processing Throughput chart respond to load changes.
        </div>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
        <span style={{ fontSize: 13, color: '#8a8d90' }}>Current generator rate:</span>
        <span style={{ fontSize: 22, fontWeight: 700, color: '#f0f0f0', fontVariantNumeric: 'tabular-nums' }}>
          {currentTps !== null ? `${currentTps} TPS` : '—'}
        </span>
        {currentPreset && (
          <span style={{ fontSize: 12, color: currentPreset.color, fontWeight: 600, background: `${currentPreset.color}22`, padding: '2px 8px', borderRadius: 12 }}>
            {currentPreset.label}
          </span>
        )}
      </div>

      <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
        {PRESETS.map(preset => {
          const isActive = currentTps === preset.tps;
          const isLoading = pending === preset.tps;
          return (
            <button
              key={preset.label}
              onClick={() => setTps(preset.tps)}
              disabled={isLoading}
              style={{
                padding: '10px 24px',
                borderRadius: 6,
                border: `2px solid ${isActive ? preset.color : '#3c3f42'}`,
                background: isActive ? `${preset.color}22` : '#212427',
                color: isActive ? preset.color : '#c0c2c5',
                fontWeight: isActive ? 700 : 500,
                fontSize: 14,
                cursor: isLoading ? 'wait' : 'pointer',
                transition: 'all 0.15s',
                opacity: isLoading ? 0.7 : 1,
              }}
            >
              {isLoading ? '…' : preset.label}
              <span style={{ fontSize: 11, display: 'block', color: '#6a6e73', fontWeight: 400 }}>{preset.tps} TPS</span>
            </button>
          );
        })}
      </div>

      {status && (
        <div style={{
          padding: '10px 14px', borderRadius: 6, fontSize: 13,
          background: status.ok ? '#4cb14022' : '#c9190b22',
          border: `1px solid ${status.ok ? '#4cb14066' : '#c9190b66'}`,
          color: status.ok ? '#92d400' : '#c9190b',
        }}>
          {status.msg}
        </div>
      )}

      <div style={{ marginTop: 24, borderTop: '1px solid #2a2d32', paddingTop: 16 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: '#c0c2c5', marginBottom: 12 }}>What to observe</div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, fontSize: 12, color: '#8a8d90' }}>
          <div>■ TPM chart — red dashed line marks onprem capacity (6000 TPM)</div>
          <div>■ KPI strip — Processing Mode switches Onprem Only → Cloud Burst at 100 TPS</div>
          <div>■ Autoscale Watch — GCP pod count ramps gradually over ~90 s (lagThreshold=500)</div>
          <div>■ Cloud pods stay up 5 min after load drops (cooldownPeriod=300 s)</div>
        </div>
      </div>
    </div>
  );
}
