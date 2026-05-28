import React from 'react';

export default function AppFooter() {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', color: '#6a6e73', fontSize: 12 }}>
      <span>© 2026 Red Hat — Banking Demo · Multi-Cluster Scalability Showcase</span>
      <span style={{ display: 'flex', gap: 20 }}>
        <span>AWS (on-prem sim) + GCP (cloud burst)</span>
        <span style={{ color: '#3c3f42' }}>|</span>
        <span>RHACM 2.16 · Argo CD · KEDA · RHSI</span>
        <span style={{ color: '#3c3f42' }}>|</span>
        <span>v2.0</span>
      </span>
    </div>
  );
}
