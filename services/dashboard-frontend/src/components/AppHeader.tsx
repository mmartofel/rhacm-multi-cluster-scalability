import React, { useState, useEffect } from 'react';

interface Props { connected: boolean; }

export default function AppHeader({ connected }: Props) {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16, color: 'white' }}>
      <span style={{ fontSize: 15, fontWeight: 600, letterSpacing: '-0.01em' }}>
        Transactions Processing Dashboard for Multicluster
      </span>
      <span style={{
        color: connected ? '#92d400' : '#f4c145',
        fontSize: 12,
        fontWeight: 500,
        display: 'flex',
        alignItems: 'center',
        gap: 4,
      }}>
        <span style={{
          display: 'inline-block', width: 7, height: 7, borderRadius: '50%',
          background: connected ? '#92d400' : '#f4c145',
        }} />
        {connected ? 'Live' : 'Reconnecting…'}
      </span>
      <span style={{ marginLeft: 8, color: '#6a6e73', fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>
        {now.toLocaleTimeString()}
      </span>
    </div>
  );
}
