# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **multi-cluster banking transaction demo platform** designed to demonstrate high availability, elasticity, data consistency, and zero-downtime upgrades across two OpenShift 4.21+ clusters:

- **Cluster 1 — AWS (on-prem sim):** Self-managed OCP on EC2. Static baseline capacity. Record-of-truth cluster (PostgreSQL primary, Kafka source, RHACM Hub, Argo CD).
- **Cluster 2 — GCP (cloud burst):** Self-managed OCP on GCE. Elastic capacity — scales to zero when idle. KEDA-driven autoscaling (0–20 replicas on Kafka consumer lag).

Cross-cluster connectivity is provided by **Red Hat Service Interconnect (RHSI)** over mTLS. GitOps is managed by **OpenShift GitOps (Argo CD)** with Kustomize overlays per cluster. Both clusters are managed by **RHACM 2.12+**.

## Architecture

### Application Services (Quarkus 3)

| Service | Role | Notes |
|---|---|---|
| `transaction-generator` | Emits synthetic DEBIT/CREDIT `TransactionEvent`s to Kafka at configurable TPS | JVM mode; ConfigMap-driven TPS |
| `transaction-processor` | Consumes Kafka, validates balance, writes to PostgreSQL, emits `TransactionCommitted` | Native mode + KEDA; GCP instance writes to AWS PostgreSQL via RHSI |
| `account-service` | Balance reads via Quarkus `@CacheResult` in-process cache; reads PostgreSQL directly | AWS: HPA CPU 60%; GCP: 0–5 replicas |
| `ledger-service` | Authoritative running balance; serves REST to dashboard-backend | GCP instance reads from PostgreSQL standby (read-only) |
| `cluster-gateway` | Traffic weight control; aggregated `/health` and `/metrics` | Manages Istio VirtualService weights |
| `dashboard-backend` | Polls both clusters every 500ms, aggregates, streams `MetricsPayload` via WebSocket | Quarkus WebSocket |
| `dashboard-frontend` | Live dashboard: cluster map, TPS gauges, chaos panel, compliance widget | React 18 + Patternfly 5 |

### Infrastructure Components

- **Streams for Apache Kafka (Kafka 3):** Topics: `transactions-raw`, `transactions-committed`, `transactions-dlq`. AWS is source; GCP receives via MirrorMaker 2.
- **MirrorMaker 2:** Replicates `transactions-raw` AWS → GCP via RHSI virtual service.
- **Apicurio Registry:** Avro schema registry on AWS. Enforces backward compatibility.
- **PostgreSQL (Crunchy Postgres for Kubernetes v5):** AWS = 3-node HA primary with PgBouncer. GCP = streaming standby (read-only). **Both clusters use the default storage class** — no storage class names are pinned in manifests.
- **RHSI Router:** AWS issues the link token and exposes `kafka-bootstrap`, `postgresql-primary` as virtual services to GCP.
- **OpenShift Service Mesh 2 (Istio):** mTLS, traffic splitting, circuit breaker per cluster.
- **Custom Metrics Autoscaler (KEDA):** Scales `transaction-processor` on Kafka consumer group lag (threshold: 100 messages). Operator namespace: `openshift-keda`.

### Namespaces

- `banking-demo` — Application workloads (generators, processors, account/ledger services, dashboard).
- `banking-infra` — Infrastructure (Kafka, PostgreSQL, RHSI, Apicurio).
- `open-cluster-management` — RHACM Hub (AWS only).
- `openshift-gitops` — Argo CD (AWS only).
- `istio-system` — OSSM Control Plane (both clusters).
- `openshift-keda` — Custom Metrics Autoscaler (KEDA) ScaledObjects (both clusters).
- `stackrox` — RHACS Central (AWS) + Sensor (GCP).
- `banking-monitoring` — Grafana + Jaeger + Prometheus (AWS); Jaeger + Prometheus federated to AWS via RHACM Observability (GCP).

### Critical Data Flow

1. `transaction-generator` → Kafka AWS (`transactions-raw`, Avro, `acks=all`, `min.insync.replicas=2`)
2. MirrorMaker 2 replicates `transactions-raw` to Kafka GCP via RHSI
3. `transaction-processor` (AWS) consumes locally; GCP processor consumes from GCP Kafka replica
4. Both processors validate balance via `account-service` (Quarkus `@CacheResult` → PostgreSQL)
5. **GCP processor writes committed transactions to AWS PostgreSQL primary via RHSI** (tunnelled JDBC)
6. `ledger-service` consumes `TransactionCommitted` and updates running balance
7. `dashboard-backend` polls both ledger services every 500ms → WebSocket push to frontend

### Chaos Scenario: RHSI Link Partition

When the cross-cluster link is severed (delete `skupper-link` Secret on GCP):
- MM2 pauses replication; events buffer in AWS Kafka (no data loss)
- GCP processor circuit-breaker opens on JDBC failure; events remain in GCP Kafka
- AWS continues processing 100% of committed transactions unaffected
- On recovery (re-apply link token): MM2 drains lag, GCP processor reconnects and commits backlog

## Diagram Rendering

Source: `docs/architecture-diagrams.md` (Mermaid v10+, C4 diagrams require v10.3+).

```bash
# Render all diagrams to PNG
mmdc -i docs/architecture-diagrams.md -o docs/architecture/ --theme neutral
```

Expected output files:
```
docs/architecture/
├── c4-context.png
├── c4-container.png
├── c4-deployment.png
├── sequence-transaction-flow.png
└── sequence-chaos-partition.png
```

## Cluster Access

Two separate kubeconfig files — one per cluster. `oc` merges them natively at runtime via a colon-separated `KUBECONFIG` variable. **Never merge them into a single file.**

| Context | Cloud | Cluster endpoint |
|---|---|---|
| `onprem` | AWS (EC2) | `https://api.zenek.sandbox3454.opentlc.com:6443` |
| `cloud` | GCP (GCE) | `https://api.zenek.ln6np.gcp.redhatworkshops.io:6443` |

**Interactive setup (one-liner):**
```bash
export KUBECONFIG="$(pwd)/kubeconfig-onprem:$(pwd)/kubeconfig-cloud"
oc config get-contexts   # should show both onprem and cloud
```

**All scripts auto-configure KUBECONFIG** — no export needed before running them. If you pre-export `KUBECONFIG`, your value is respected.

**Refreshing an expired token:**
```bash
# 1. oc login to the cluster with --context onprem or cloud
oc login https://api.zenek.sandbox3454.opentlc.com:6443   # onprem
# 2. Export the new credentials to the right file
./get-kubeconfig.sh onprem   # or: ./get-kubeconfig.sh cloud
```

`kubeconfig-onprem` and `kubeconfig-cloud` are in `.gitignore` — never commit them.

## Scripts Reference

| Script | Purpose |
|---|---|
| `scripts/install-operators.sh --role hub\|spoke [--context <name>]` | Install OLM operators. Hub installs all 9 (RHACM + GitOps + 7 shared); Spoke installs 7 shared only. Default context: `onprem` for hub, `cloud` for spoke. |
| `scripts/operator-check.sh` | Verify all required CSVs are `Succeeded` on both contexts. Exits 1 if any are missing or degraded. Run before Phase 0 bootstrap. |
| `scripts/bootstrap-phase0.sh` | Full Phase 0 orchestration: operator check → MCH → ManagedCluster import → GitOps readiness → namespaces → pull secrets → ClusterIssuer. Requires `QUAY_USER` and `QUAY_TOKEN` env vars. |
| `get-kubeconfig.sh onprem\|cloud` | Write the current `oc login` session credentials to `kubeconfig-onprem` or `kubeconfig-cloud`. Use after token expiry. |

## Infrastructure Notes

- **Neither cluster uses a managed service** — no ROSA, no OSD. Both are self-managed OCP 4.21+ on EC2 (AWS) and GCE (GCP).
- Do not use kubectl, use `oc` CLI for all cluster interactions. Fix existing `kubectl` references in scripts and documentation.
- Bootstrap scripts must handle full OCP install prerequisites: pull-secret configuration and DNS (`*.apps.<cluster-domain>`).
- Storage classes are not pinned in manifests; PVCs use the cluster's default SC at deploy time (EBS gp2/gp3 on AWS, GCP PD standard/ssd on GCP).
- RHACS Central runs on AWS; GCP runs the Sensor only, reporting back to Central via gRPC mTLS.
- Observability: GCP metrics federate to AWS Grafana via RHACM Observability Add-on.
