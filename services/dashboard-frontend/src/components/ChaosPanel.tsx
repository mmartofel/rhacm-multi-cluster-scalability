import React, { useState, useEffect, useRef } from 'react';
import { MetricsPayload, PartitionStat } from '../types/metrics';
import { ProcessingMode } from '../App';
import { ONPREM_COLOR, CLOUD_COLOR } from '../colors';

interface Props {
  payload: MetricsPayload | null;
  processingMode?: ProcessingMode;
  onModeChange?: (mode: ProcessingMode) => void;
}

// 7 discrete traffic-split steps — pure generation-rate split (% of TPS routed to each
// cluster). Kafka partitions are statically assigned per cluster (see PartitionMap below)
// and no longer move with this slider.
const STEPS = [
  { onpremWeight: 0 },
  { onpremWeight: 17 },
  { onpremWeight: 33 },
  { onpremWeight: 50 },
  { onpremWeight: 67 },
  { onpremWeight: 83 },
  { onpremWeight: 100 },
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
      const onprem = json.onprem ?? STEPS[s].onpremWeight;
      const cloud  = json.cloud  ?? (100 - STEPS[s].onpremWeight);
      setStatus({ msg: `Traffic updated — On-Prem ${onprem}% · Cloud ${cloud}%`, ok: true });
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

  const current   = STEPS[step];
  const onpremPct = current.onpremWeight;
  const cloudPct  = 100 - current.onpremWeight;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16, height: '100%' }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0', marginBottom: 14 }}>Traffic & Chaos Control</div>

      {/* Current split indicator — live from cluster */}
      <div style={{ background: '#151515', border: '1px solid #2a2d32', borderRadius: 6, padding: '10px 12px', marginBottom: 14 }}>
        <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 6 }}>Current split (live)</div>
        {onpremWeight !== null ? (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span style={{ fontSize: 12, color: ONPREM_COLOR, fontWeight: 700 }}>On-Prem {onpremWeight}%</span>
            <span style={{ fontSize: 11, color: '#6a6e73' }}>·</span>
            <span style={{ fontSize: 12, color: CLOUD_COLOR, fontWeight: 700 }}>Cloud {cloudWeight ?? 100 - onpremWeight}%</span>
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
          <span style={{ fontSize: 13, color: ONPREM_COLOR, fontWeight: 700 }}>On-Prem {onpremPct}%</span>
          <span style={{ fontSize: 11, color: '#8a8d90' }}>generation-rate split</span>
          <span style={{ fontSize: 13, color: CLOUD_COLOR, fontWeight: 700 }}>Cloud {cloudPct}%</span>
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
            accentColor: ONPREM_COLOR,
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

      {/* Partition map */}
      <PartitionMap onpremWeight={onpremWeight ?? current.onpremWeight} payload={payload} />

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
          on Cloud to sever the RHSI tunnel. MM2 pauses, Cloud processor circuit-breaker opens.
          On-Prem continues unaffected. Re-apply the link token to recover.
        </div>
      </div>
    </div>
  );
}

function formatLag(lag: number): string {
  if (lag >= 1000000) return `${(lag / 1000000).toFixed(1)}M`;
  if (lag >= 1000) return `${(lag / 1000).toFixed(1)}k`;
  return String(lag);
}

interface PartitionMapProps {
  onpremWeight: number;
  payload: MetricsPayload | null;
}

function PartitionMap({ onpremWeight, payload }: PartitionMapProps) {
  const onpremCount = Math.round(6 * onpremWeight / 100);

  // Build maps from actual runtime partition data
  const lagMap   = new Map<number, number>();
  const ownerMap = new Map<number, 'onprem' | 'cloud'>();
  if (payload) {
    for (const cluster of payload.clusters) {
      for (const ps of (cluster.partitions ?? [])) {
        if (ps.owned) {
          lagMap.set(ps.partition, ps.lag);
          ownerMap.set(ps.partition, cluster.cluster as 'onprem' | 'cloud');
        }
      }
    }
  }
  const hasLag = lagMap.size > 0;
  const maxLag = hasLag ? Math.max(1, ...Array.from(lagMap.values())) : 1;

  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ fontSize: 11, color: '#6a6e73', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
        Kafka Partitions — transactions-raw
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        {Array.from({ length: 6 }, (_, p) => {
          // Colour: use actual runtime ownership when available, fall back to slider estimate
          const isOnpremEstimate = p < onpremCount;
          const color = ownerMap.size > 0
            ? (ownerMap.get(p) === 'onprem' ? ONPREM_COLOR : ownerMap.has(p) ? CLOUD_COLOR : '#4a4d52')
            : (isOnpremEstimate ? ONPREM_COLOR : CLOUD_COLOR);
          const clusterLabel = ownerMap.size > 0
            ? (ownerMap.get(p) === 'onprem' ? 'On-Prem' : ownerMap.has(p) ? 'Cloud' : '—')
            : (isOnpremEstimate ? 'On-Prem' : 'Cloud');
          const lag     = lagMap.get(p);
          const fillPct = hasLag ? Math.max(4, Math.round((lag ?? 0) / maxLag * 100)) : 30;
          return (
            <div key={p} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
              {/* Lag label above bar */}
              <span style={{ fontSize: 10, color: hasLag ? color : '#4a4d52', fontWeight: 600, minHeight: 14 }}>
                {hasLag ? formatLag(lag ?? 0) : '—'}
              </span>
              {/* Bar */}
              <div style={{ width: '100%', height: 48, background: '#2a2d32', borderRadius: 4, overflow: 'hidden', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                <div style={{ width: '100%', height: `${fillPct}%`, background: color, borderRadius: 4, opacity: hasLag ? 1 : 0.35, transition: 'height 0.4s ease, background 0.3s ease' }} />
              </div>
              {/* Partition number */}
              <span style={{ fontSize: 10, color: color, fontWeight: 700 }}>{p}</span>
              {/* Cluster label */}
              <span style={{ fontSize: 9, color: '#6a6e73' }}>{clusterLabel}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
