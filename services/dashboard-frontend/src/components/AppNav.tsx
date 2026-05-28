import React from 'react';
import { View } from '../App';

interface Props { active: View; onSelect: (v: View) => void; }

const ITEMS: { id: View; label: string; icon: string }[] = [
  { id: 'overview',      label: 'Overview',         icon: '⬡' },
  { id: 'load-control',  label: 'Load Control',      icon: '⚡' },
  { id: 'chaos',         label: 'Traffic & Chaos',   icon: '↔' },
  { id: 'autoscale',     label: 'Autoscale Watch',   icon: '↕' },
  { id: 'compliance',    label: 'Compliance',        icon: '✓' },
  { id: 'about',         label: 'About',             icon: 'ℹ' },
];

export default function AppNav({ active, onSelect }: Props) {
  return (
    <nav style={{ paddingTop: 8 }}>
      <div style={{ padding: '8px 16px 4px', fontSize: 11, fontWeight: 600, color: '#6a6e73', textTransform: 'uppercase', letterSpacing: '0.08em' }}>
        Dashboard
      </div>
      {ITEMS.map(item => (
        <button
          key={item.id}
          onClick={() => onSelect(item.id)}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            width: '100%',
            padding: '9px 20px',
            background: active === item.id ? '#2a2d32' : 'transparent',
            border: 'none',
            borderLeft: active === item.id ? '3px solid #06c' : '3px solid transparent',
            color: active === item.id ? '#f0f0f0' : '#8a8d90',
            fontSize: 14,
            fontWeight: active === item.id ? 600 : 400,
            cursor: 'pointer',
            textAlign: 'left',
            transition: 'background 0.15s, color 0.15s',
          }}
          onMouseEnter={e => {
            if (active !== item.id) {
              (e.currentTarget as HTMLButtonElement).style.background = '#22252a';
              (e.currentTarget as HTMLButtonElement).style.color = '#c0c2c5';
            }
          }}
          onMouseLeave={e => {
            if (active !== item.id) {
              (e.currentTarget as HTMLButtonElement).style.background = 'transparent';
              (e.currentTarget as HTMLButtonElement).style.color = '#8a8d90';
            }
          }}
        >
          <span style={{ fontSize: 13, opacity: 0.7, width: 16, textAlign: 'center' }}>{item.icon}</span>
          {item.label}
        </button>
      ))}
    </nav>
  );
}
