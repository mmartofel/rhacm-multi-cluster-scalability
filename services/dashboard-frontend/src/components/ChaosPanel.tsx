import React, { useState, useEffect, useRef } from 'react';
import { MetricsPayload } from '../types/metrics';
import { ProcessingMode } from '../App';
import { AWS_COLOR, GCP_COLOR } from '../colors';

interface Props {
  payload: MetricsPayload | null;
  processingMode?: ProcessingMode;
  onModeChange?: (mode: ProcessingMode) => void;
}

// 7 steps aligned to Kafka partition boundaries (6 partitions total)
// onprem owns [0..n-1], cloud owns [n..5] where n = round(6 * onpremWeight / 100)
const STEPS = [
  { onpremWeight: 0,   awsParts: '—',   gcpParts: '0–5' },
  { onpremWeight: 17,  awsParts: '0',   gcpParts: '1–5' },
  { onpremWeight: 33,  awsParts: '0–1', gcpParts: '2–5' },
  { onpremWeight: 50,  awsParts: '0–2', gcpParts: '3–5' },
  { onpremWeight: 67,  awsParts: '0–3', gcpParts: '4–5' },
  { onpremWeight: 83,  awsParts: '0–4', gcpParts: '5'   },
  { onpremWeight: 100, awsParts: '0–5', gcpParts: '—'   },
];

function nearestStep(onpremWeight: number): number {
  return STEPS.reduce((best, s, i) =>
    Math.abs(s.onpremWeight - onpremWeight) < Math.abs(STEPS[best].onpremWeight - onpremWeight) ? i : best, 3);
}

function stepToMode(s: number): ProcessingMode {
  if (s === 6) return 'auto-burst';
  if (s === 0) return 'cloud-only';
  return 'split';
}

export default function ChaosPanel({ payload, onModeChange }: Props) {
  const [step, setStep] = useState(3); // default 50/50
  const [status, setStatus] = useState<{ msg: string; ok: boolean } | null>(null);
  const [pending, setPending] = useState(false);
  const initialized = useRef(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const onpremWeight = payload?.clusters.find(c => c.cluster === 'onprem')?.trafficWeight ?? null;
  const cloudWeight  = payload?.clusters.find(c => c.cluster === 'cloud')?.trafficWeight  ?? null;

  // Sync slider to live cluster state on first payload arrival
  useEffect(() => {
    if (!initialized.current && onpremWeight !== null) {
      setStep(nearestStep(onpremWeight));
      initialized.current = true;
    }
  }, [onpremWeight]);

  const applyStep = async (s: number) => {
    if (pending) return;
    setPending(true);
    setStatus(null);
    try {
      const res = await fetch('/api/backend/traffic-weight', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ trafficWeight: STEPS[s].onpremWeight }),
      });
      const json = await res.json();
      const aws = json.onprem ?? STEPS[s].onpremWeight;
      const gcp = json.cloud  ?? (100 - STEPS[s].onpremWeight);
      setStatus({ msg: `Traffic updated — AWS ${aws}% · GCP ${gcp}%`, ok: true });
      onModeChange?.(stepToMode(s));
    } catch (e: any) {
      setStatus({ msg: `Error: ${e.message}`, ok: false });
    } finally {
      setPending(false);
    }
  };

  const handleSliderChange = (newStep: number) => {
    setStep(newStep);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => applyStep(newStep), 400);
  };

  const current = STEPS[step];
  const awsPct  = current.onpremWeight;
  const gcpPct  = 100 - current.onpremWeight;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16, height: '100%' }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0', marginBottom: 14 }}>Traffic & Chaos Control</div>

      {/* Current split indicator — live from cluster */}
      <div style={{ background: '#151515', border: '1px solid #2a2d32', borderRadius: 6, padding: '10px 12px', marginBottom: 14 }}>
        <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 6 }}>Current split (live)</div>
        {onpremWeight !== null ? (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span style={{ fontSize: 12, color: AWS_COLOR, fontWeight: 700 }}>AWS {onpremWeight}%</span>
            <span style={{ fontSize: 11, color: '#6a6e73' }}>·</span>
            <span style={{ fontSize: 12, color: GCP_COLOR, fontWeight: 700 }}>GCP {cloudWeight ?? 100 - onpremWeight}%</span>
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

      {/* Traffic split slider */}
      <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
        Traffic Split
      </div>

      <div style={{ marginBottom: 18 }}>
        {/* Endpoint labels */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
          <span style={{ fontSize: 13, color: AWS_COLOR, fontWeight: 700 }}>AWS {awsPct}%</span>
          <span style={{ fontSize: 11, color: '#8a8d90' }}>
            AWS [{current.awsParts}] · GCP [{current.gcpParts}]
          </span>
          <span style={{ fontSize: 13, color: GCP_COLOR, fontWeight: 700 }}>GCP {gcpPct}%</span>
        </div>

        {/* Range input */}
        <input
          type="range"
          min={0}
          max={6}
          step={1}
          value={step}
          disabled={pending}
          onChange={e => handleSliderChange(Number(e.target.value))}
          style={{
            width: '100%',
            accentColor: AWS_COLOR,
            cursor: pending ? 'wait' : 'pointer',
            margin: 0,
          }}
        />

        {/* Step tick marks */}
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4 }}>
          {STEPS.map((s, i) => (
            <span
              key={i}
              style={{
                fontSize: 10,
                color: i === step ? '#f0f0f0' : '#4a4d52',
                fontWeight: i === step ? 700 : 400,
                minWidth: 24,
                textAlign: 'center',
              }}
            >
              {s.onpremWeight}
            </span>
          ))}
        </div>
      </div>

      {/* Gateway health check */}
      <div style={{ marginBottom: 16 }}>
        <button
          onClick={async () => {
            setPending(true);
            setStatus(null);
            try {
              const res = await fetch('/api/gateway/health');
              const json = await res.json();
              setStatus({ msg: `${json.cluster ?? ''}: ${json.status ?? 'ok'}`, ok: res.ok });
            } catch (e: any) {
              setStatus({ msg: `Error: ${e.message}`, ok: false });
            } finally {
              setPending(false);
            }
          }}
          disabled={pending}
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
