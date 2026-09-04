import React, { useState } from 'react';
import { MetricsPayload } from '../types/metrics';
import { ONPREM_COLOR, CLOUD_COLOR, HEALTHY_COLOR, CAPACITY_COLOR } from '../colors';

interface Props {
  payload: MetricsPayload | null;
}

const UNKNOWN_COLOR = '#6a6e73';

function statusColor(status: string): string {
  if (status === 'active') return HEALTHY_COLOR;
  if (status === 'broken') return CAPACITY_COLOR;
  return UNKNOWN_COLOR;
}

function statusLabel(status: string): string {
  if (status === 'active') return 'Interconnect Active';
  if (status === 'broken') return 'Interconnect Broken';
  return 'Interconnect Status Unknown';
}

// Real infrastructure toggle: deletes/recreates the Skupper Listeners (banking-infra,
// cloud) that expose onprem's Kafka/PostgreSQL/Apicurio to cloud, via dashboard-backend
// -> cloud cluster-gateway -> fabric8 KubernetesClient. Deliberately does NOT touch the
// Link/inter-router connection itself — that would also sever the separate channel this
// very control survives on (see CLAUDE.md's Chaos Scenario section). Never flips color
// locally on click — waits for the next WebSocket payload so every viewer (and page
// reload) reflects real cluster state, including changes made outside the UI.
export default function LinkFailurePanel({ payload }: Props) {
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null);

  const status = payload?.interconnectStatus ?? 'unknown';
  const color = statusColor(status);
  const active = status === 'active';

  const handleToggle = async () => {
    if (pending || status === 'unknown') return;
    setPending(true);
    setMessage(null);
    const path = active ? '/api/backend/link/break' : '/api/backend/link/restore';
    try {
      const res = await fetch(path, { method: 'PUT' });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) {
        setMessage({ text: json.message ?? json.error ?? `Request failed (${res.status})`, ok: false });
      } else if (json.alreadyBroken || json.alreadyActive) {
        setMessage({ text: `Link was already ${json.status}.`, ok: true });
      }
    } catch (e: any) {
      setMessage({ text: `Error: ${e.message}`, ok: false });
    } finally {
      setPending(false);
    }
  };

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 20, flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 18 }}>
        <div style={{ fontWeight: 600, fontSize: 16, color: '#f0f0f0' }}>Simulate Link Failure</div>
        <button
          onClick={handleToggle}
          disabled={pending || status === 'unknown'}
          style={{
            display: 'flex', alignItems: 'center', gap: 7,
            background: `${color}1a`, border: `1px solid ${color}66`, color,
            padding: '7px 14px', borderRadius: 6, fontSize: 12, fontWeight: 700,
            cursor: pending || status === 'unknown' ? 'wait' : 'pointer',
          }}
        >
          <span style={{ width: 7, height: 7, borderRadius: '50%', background: color, display: 'inline-block' }} />
          {pending ? 'Applying…' : statusLabel(status)}
        </button>
      </div>

      <div style={{ fontSize: 13, color: '#8a8d90', marginBottom: 18, lineHeight: 1.7 }}>
        Deletes (or recreates) the <code style={{ background: '#2a2d32', padding: '2px 5px', borderRadius: 3 }}>kafka-bootstrap</code>, <code style={{ background: '#2a2d32', padding: '2px 5px', borderRadius: 3 }}>postgresql-primary</code>, and <code style={{ background: '#2a2d32', padding: '2px 5px', borderRadius: 3 }}>apicurio-registry</code> Skupper Listeners
        on Cloud — a real RHSI outage for those services. MM2 pauses, Cloud's processor rejects transactions to the DLQ, On-Prem continues unaffected.
      </div>

      {message && (
        <div style={{
          padding: '8px 12px', borderRadius: 6, fontSize: 12, marginBottom: 14,
          background: message.ok ? '#4cb14022' : '#c9190b22',
          border: `1px solid ${message.ok ? '#4cb14066' : '#c9190b66'}`,
          color: message.ok ? '#92d400' : '#e57979',
        }}>
          {message.text}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'stretch', gap: 20, flex: 1, minHeight: 0 }}>
        <ClusterBox label="On-Prem" sub="Record-of-truth" accent={ONPREM_COLOR} healthy={status !== 'unknown'} />

        <div style={{ flex: 1, display: 'flex', alignItems: 'center', minWidth: 80 }}>
          <svg width="100%" height="100%" viewBox="0 0 200 80" preserveAspectRatio="none" style={{ overflow: 'visible' }}>
            <line x1="4" y1="40" x2="196" y2="40" stroke={color} strokeWidth={4} strokeLinecap="round" />
            {active && [0, 1, 2].map(i => (
              <circle key={i} r={6} fill={CLOUD_COLOR}>
                <animateMotion dur="2s" repeatCount="indefinite" begin={`${i * 0.66}s`} path="M4,40 L196,40" />
              </circle>
            ))}
            {active && [0, 1].map(i => (
              <circle key={`r${i}`} r={5} fill={ONPREM_COLOR}>
                <animateMotion dur="2.4s" repeatCount="indefinite" begin={`${i * 1.2}s`} path="M196,40 L4,40" />
              </circle>
            ))}
          </svg>
        </div>

        <ClusterBox label="Cloud" sub="Elastic burst" accent={CLOUD_COLOR} healthy={status === 'active'} />
      </div>
    </div>
  );
}

function ClusterBox({ label, sub, accent, healthy }: { label: string; sub: string; accent: string; healthy: boolean }) {
  const dotColor = healthy ? HEALTHY_COLOR : CAPACITY_COLOR;
  return (
    <div style={{
      background: '#212427',
      border: `1px solid ${accent}33`,
      borderTop: `3px solid ${accent}`,
      borderRadius: 8,
      padding: '20px 24px',
      minWidth: 160,
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'center',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ width: 8, height: 8, borderRadius: '50%', background: dotColor, display: 'inline-block' }} />
        <span style={{ fontWeight: 700, color: '#f0f0f0', fontSize: 16 }}>{label}</span>
      </div>
      <span style={{ fontSize: 12, color: '#8a8d90', marginTop: 4 }}>{sub}</span>
    </div>
  );
}
