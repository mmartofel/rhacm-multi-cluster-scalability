import React, { useState } from 'react';
import { MetricsPayload } from '../types/metrics';

interface Props { payload: MetricsPayload | null; }

const ACTIONS = [
  { label: 'Route 100% → AWS',  weight: 100, variant: 'primary'   as const },
  { label: 'Split 50 / 50',     weight: 50,  variant: 'secondary' as const },
  { label: 'Route 100% → GCP',  weight: 0,   variant: 'warning'   as const },
];

const VARIANT_STYLE: Record<string, React.CSSProperties> = {
  primary:   { background: '#06c',    border: '1px solid #06c',    color: 'white' },
  secondary: { background: 'transparent', border: '1px solid #06c', color: '#06c' },
  warning:   { background: '#f4c145', border: '1px solid #f4c145', color: '#151515' },
};

const ACTIVE_RING: React.CSSProperties = { outline: '2px solid #92d400', outlineOffset: 2 };

export default function ChaosPanel({ payload }: Props) {
  const [status, setStatus] = useState<{ msg: string; ok: boolean } | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  const onpremWeight = payload?.clusters.find(c => c.cluster === 'onprem')?.trafficWeight ?? null;
  const cloudWeight  = payload?.clusters.find(c => c.cluster === 'cloud')?.trafficWeight  ?? null;

  const activeWeight = onpremWeight;

  const call = async (weight: number) => {
    setPending(String(weight));
    setStatus(null);
    try {
      const res = await fetch('/api/backend/traffic-weight', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ trafficWeight: weight }),
      });
      const json = await res.json();
      const aws = json.onprem ?? weight;
      const gcp = json.cloud  ?? (100 - weight);
      setStatus({ msg: `Traffic updated — AWS ${aws}% · GCP ${gcp}%`, ok: true });
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

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 16 }}>
        {ACTIONS.map(a => {
          const isActive = activeWeight === a.weight;
          return (
            <button
              key={a.label}
              onClick={() => call(a.weight)}
              disabled={pending !== null}
              style={{
                ...VARIANT_STYLE[a.variant],
                ...(isActive ? ACTIVE_RING : {}),
                padding: '9px 14px',
                borderRadius: 6,
                fontSize: 13,
                fontWeight: 600,
                cursor: pending ? 'wait' : 'pointer',
                opacity: pending ? 0.7 : 1,
                transition: 'opacity 0.15s',
              }}
            >
              {a.label}{isActive ? ' ✓' : ''}
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
