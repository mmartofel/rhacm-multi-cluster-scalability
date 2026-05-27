import React from 'react';
import { Card, CardTitle, CardBody, Label } from '@patternfly/react-core';

const POLICIES = [
  { name: 'mTLS between all services',        status: 'Compliant' },
  { name: 'Network Policy isolation',          status: 'Compliant' },
  { name: 'Image signature verification',      status: 'Compliant' },
  { name: 'Secrets rotation (30d)',            status: 'Compliant' },
  { name: 'Privileged containers prohibited',  status: 'Compliant' },
  { name: 'Resource limits set on all pods',   status: 'Compliant' },
];

export default function ComplianceWidget() {
  const compliant = POLICIES.filter(p => p.status === 'Compliant').length;
  const score = Math.round((compliant / POLICIES.length) * 100);

  return (
    <Card>
      <CardTitle>RHACS Compliance — {score}% ({compliant}/{POLICIES.length} policies)</CardTitle>
      <CardBody>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {POLICIES.map(p => (
            <Label key={p.name} color={p.status === 'Compliant' ? 'green' : 'red'}>
              {p.name}
            </Label>
          ))}
        </div>
      </CardBody>
    </Card>
  );
}
