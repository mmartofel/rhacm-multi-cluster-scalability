import React from 'react';

const POLICIES = [
  { name: 'mTLS between all services',       category: 'Network' },
  { name: 'Network Policy isolation',         category: 'Network' },
  { name: 'Image signature verification',     category: 'Supply Chain' },
  { name: 'Secrets rotation (30 days)',       category: 'Secrets' },
  { name: 'Privileged containers prohibited', category: 'Runtime' },
  { name: 'Resource limits on all pods',      category: 'Resources' },
];

export default function ComplianceWidget() {
  const score = 100;

  return (
    <div style={{ background: '#1b1d21', border: '1px solid #2a2d32', borderRadius: 8, padding: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: '#f0f0f0' }}>RHACS Compliance</span>
        <span style={{
          fontSize: 13, fontWeight: 700, color: '#92d400',
          background: '#92d40022', padding: '2px 10px', borderRadius: 12,
          border: '1px solid #92d40044',
        }}>
          {score}% — {POLICIES.length}/{POLICIES.length} policies
        </span>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {POLICIES.map(p => (
          <div key={p.name} style={{
            display: 'flex', alignItems: 'center', gap: 6,
            background: '#4cb14018', border: '1px solid #4cb14044',
            borderRadius: 20, padding: '4px 12px', fontSize: 12, color: '#92d400',
          }}>
            <span style={{ fontSize: 10 }}>✓</span>
            {p.name}
          </div>
        ))}
      </div>
    </div>
  );
}
