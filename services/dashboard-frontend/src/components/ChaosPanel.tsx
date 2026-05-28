import React, { useState } from 'react';

const ACTIONS = [
  { label: 'Route 100% → AWS',  body: { trafficWeight: 100 }, variant: 'primary'   as const },
  { label: 'Split 50 / 50',     body: { trafficWeight: 50  }, variant: 'secondary' as const },
  { label: 'Route 100% → GCP',  body: { trafficWeight: 0   }, variant: 'warning'   as const },
];

const VARIANT_STYLE: Record<string, React.CSSProperties> = {
  primary:   { background: '#06c',    border: '1px solid #06c',    color: 'white' },
  secondary: { background: 'transparent', border: '1px solid #06c', color: '#06c' },
  warning:   { background: '#f4c145', border: '1px solid #f4c145', color: '#151515' },
};

export default function ChaosPanel() {
  const [status, setStatus] = useState<{ msg: string; ok: boolean } | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  const call = async (path: string, method = 'GET', body?: object) => {
    const key = path + method;
    setPending(key);
    setStatus(null);
    try {
      const res = await fetch(`/api/gateway${path}`, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : {},
        body: body ? JSON.stringify(body) : undefined,
      });
      const json = await res.json();
      const weight = json.trafficWeight ?? json.weight;
      const cluster = json.cluster ?? '';
      if (typeof weight === 'number') {
        const aws = weight;
        const gcp = 100 - weight;
        setStatus({ msg: `Traffic updated — AWS ${aws}% · GCP ${gcp}%`, ok: true });
      } else {
        setStatus({ msg: `${cluster ? cluster + ': ' : ''}${json.status ?? 'OK'}`, ok: res.ok });
      }
    } catch (e: any) {
      setStatus({ msg: `Error: ${e.message}`, ok: false });
    } finally {
      setPending(null);
    }
  };

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16, height: '100%' }}>
      <div style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0', marginBottom: 14 }}>Traffic & Chaos Control</div>

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
        {ACTIONS.map(a => (
          <button
            key={a.label}
            onClick={() => call('/traffic-weight', 'PUT', a.body)}
            disabled={pending !== null}
            style={{
              ...VARIANT_STYLE[a.variant],
              padding: '9px 14px',
              borderRadius: 6,
              fontSize: 13,
              fontWeight: 600,
              cursor: pending ? 'wait' : 'pointer',
              opacity: pending ? 0.7 : 1,
              transition: 'opacity 0.15s',
            }}
          >
            {a.label}
          </button>
        ))}
        <button
          onClick={() => call('/health')}
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
