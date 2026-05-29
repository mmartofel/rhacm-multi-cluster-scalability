import React, { useState } from 'react';
import { MetricsPayload } from '../types/metrics';
import { ProcessingMode } from '../App';

interface Props {
  payload: MetricsPayload | null;
  processingMode: ProcessingMode;
  onModeChange: (mode: ProcessingMode) => void;
}

interface ModeAction {
  mode: ProcessingMode;
  label: string;
  description: string;
  weight: number;
  activeColor: string;
  borderColor: string;
}

const MODES: ModeAction[] = [
  {
    mode: 'auto-burst',
    label: 'Auto Burst',
    description: '≤100 TPS onprem · cloud scales on overflow',
    weight: 100,
    activeColor: '#92d40022',
    borderColor: '#92d400',
  },
  {
    mode: 'onprem-only',
    label: 'Route 100% → AWS',
    description: 'all traffic routed to onprem cluster',
    weight: 100,
    activeColor: '#0066cc22',
    borderColor: '#06c',
  },
  {
    mode: 'split',
    label: 'Split 50 / 50',
    description: 'traffic split equally across clusters',
    weight: 50,
    activeColor: '#8476d122',
    borderColor: '#8476d1',
  },
  {
    mode: 'cloud-only',
    label: 'Route 100% → GCP',
    description: 'all traffic routed to cloud cluster',
    weight: 0,
    activeColor: '#f4c14522',
    borderColor: '#f4c145',
  },
];

export default function ChaosPanel({ payload, processingMode, onModeChange }: Props) {
  const [status, setStatus] = useState<{ msg: string; ok: boolean } | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  const onpremWeight = payload?.clusters.find(c => c.cluster === 'onprem')?.trafficWeight ?? null;
  const cloudWeight  = payload?.clusters.find(c => c.cluster === 'cloud')?.trafficWeight  ?? null;

  const selectMode = async (action: ModeAction) => {
    if (pending) return;
    setPending(action.mode);
    setStatus(null);
    try {
      const res = await fetch('/api/backend/traffic-weight', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ trafficWeight: action.weight }),
      });
      const json = await res.json();
      const aws = json.onprem ?? action.weight;
      const gcp = json.cloud  ?? (100 - action.weight);
      setStatus({ msg: `Traffic updated — AWS ${aws}% · GCP ${gcp}%`, ok: true });
      onModeChange(action.mode);
    } catch (e: any) {
      setStatus({ msg: `Error: ${e.message}`, ok: false });
    } finally {
      setPending(null);
    }
  };

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16, height: '100%' }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0', marginBottom: 14 }}>Traffic & Chaos Control</div>

      {/* Current split indicator */}
      <div style={{
        background: '#151515', border: '1px solid #2a2d32', borderRadius: 6,
        padding: '10px 12px', marginBottom: 14,
      }}>
        <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 6 }}>Current split</div>
        {onpremWeight !== null ? (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span style={{ fontSize: 12, color: '#06c',    fontWeight: 700 }}>AWS {onpremWeight}%</span>
            <span style={{ fontSize: 11, color: '#6a6e73' }}>·</span>
            <span style={{ fontSize: 12, color: '#4cb140', fontWeight: 700 }}>GCP {cloudWeight ?? 100 - onpremWeight}%</span>
          </div>
        ) : (
          <span style={{ fontSize: 12, color: '#6a6e73' }}>waiting for data…</span>
        )}
      </div>

      {status && (
        <div style={{
          padding: '8px 12px', borderRadius: 6, fontSize: 12, marginBottom: 12,
          background: status.ok ? '#4cb14022' : '#c9190b22',
          border: `1px solid ${status.ok ? '#4cb14066' : '#c9190b66'}`,
          color: status.ok ? '#92d400' : '#e57979',
        }}>
          {status.msg}
        </div>
      )}

      {/* Processing Mode buttons */}
      <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
        Processing Mode
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 16 }}>
        {MODES.map(a => {
          const isActive = processingMode === a.mode;
          return (
            <button
              key={a.mode}
              onClick={() => selectMode(a)}
              disabled={pending !== null}
              style={{
                background: isActive ? a.activeColor : 'transparent',
                border: `1px solid ${isActive ? a.borderColor : '#3c3f42'}`,
                color: isActive ? '#f0f0f0' : '#8a8d90',
                padding: '10px 14px',
                borderRadius: 6,
                fontSize: 13,
                fontWeight: isActive ? 700 : 400,
                cursor: pending ? 'wait' : 'pointer',
                opacity: pending && pending !== a.mode ? 0.5 : 1,
                transition: 'all 0.15s',
                textAlign: 'left',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <span>{a.label}</span>
              {isActive && <span style={{ fontSize: 11, color: a.borderColor }}>● active</span>}
            </button>
          );
        })}

        <button
          onClick={async () => {
            setPending('health');
            setStatus(null);
            try {
              const res = await fetch('/api/gateway/health');
              const json = await res.json();
              setStatus({ msg: `${json.cluster ?? ''}: ${json.status ?? 'ok'}`, ok: res.ok });
            } catch (e: any) {
              setStatus({ msg: `Error: ${e.message}`, ok: false });
            } finally {
              setPending(null);
            }
          }}
          disabled={pending !== null}
          style={{
            background: 'transparent', border: '1px solid #3c3f42', color: '#8a8d90',
            padding: '7px 14px', borderRadius: 6, fontSize: 12, cursor: pending ? 'wait' : 'pointer',
            marginTop: 4,
          }}
        >
          Check Gateway Health
        </button>
      </div>

      <div style={{ borderTop: '1px solid #2a2d32', paddingTop: 12 }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: '#8a8d90', marginBottom: 6 }}>Simulate link failure</div>
        <div style={{ fontSize: 11, color: '#6a6e73', lineHeight: 1.7 }}>
          Delete the <code style={{ background: '#2a2d32', padding: '1px 4px', borderRadius: 3 }}>skupper-link</code> Secret
          on GCP to sever the RHSI tunnel. MM2 pauses, GCP processor circuit-breaker opens.
          AWS continues unaffected. Re-apply the link token to recover.
        </div>
      </div>
    </div>
  );
}
