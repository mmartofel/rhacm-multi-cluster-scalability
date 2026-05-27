import React, { useState } from 'react';
import { Card, CardTitle, CardBody, Button, Alert, AlertVariant } from '@patternfly/react-core';

export default function ChaosPanel() {
  const [status, setStatus] = useState<{ msg: string; variant: AlertVariant } | null>(null);

  const callGateway = async (path: string, method = 'GET', body?: object) => {
    try {
      const res = await fetch(`/api/gateway${path}`, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : {},
        body: body ? JSON.stringify(body) : undefined
      });
      const json = await res.json();
      setStatus({ msg: JSON.stringify(json, null, 2), variant: AlertVariant.success });
    } catch (e: any) {
      setStatus({ msg: e.message, variant: AlertVariant.danger });
    }
  };

  return (
    <Card>
      <CardTitle>Chaos & Traffic Control</CardTitle>
      <CardBody>
        {status && (
          <Alert variant={status.variant} title="Gateway response" style={{ marginBottom: 12 }}>
            <pre style={{ fontSize: 12, overflow: 'auto', maxHeight: 100 }}>{status.msg}</pre>
          </Alert>
        )}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Button variant="primary"
                  onClick={() => callGateway('/traffic-weight', 'PUT', { trafficWeight: 100 })}>
            Route 100% → AWS
          </Button>
          <Button variant="secondary"
                  onClick={() => callGateway('/traffic-weight', 'PUT', { trafficWeight: 50 })}>
            Split 50/50
          </Button>
          <Button variant="warning"
                  onClick={() => callGateway('/traffic-weight', 'PUT', { trafficWeight: 0 })}>
            Route 100% → GCP
          </Button>
          <Button variant="plain"
                  onClick={() => callGateway('/health')}>
            Check Health
          </Button>
        </div>
        <p style={{ fontSize: 12, color: '#6a6e73', marginTop: 12 }}>
          To simulate RHSI link partition, delete the <code>skupper-link</code> Secret on GCP from the CLI.
        </p>
      </CardBody>
    </Card>
  );
}
