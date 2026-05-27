# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **multi-cluster banking transaction demo platform** designed to demonstrate high availability, elasticity, data consistency, and zero-downtime upgrades across two OpenShift 4.21+ clusters:

- **Cluster 1 — AWS (on-prem sim):** Self-managed OCP on EC2. Static baseline capacity. Record-of-truth cluster (PostgreSQL primary, Kafka source, RHACM Hub, Argo CD).
- **Cluster 2 — GCP (cloud burst):** Self-managed OCP on GCE. Elastic capacity — scales to zero when idle. KEDA-driven autoscaling (0–20 replicas on Kafka consumer lag).

Cross-cluster connectivity is provided by **Red Hat Service Interconnect (RHSI)** over mTLS. GitOps is managed by **OpenShift GitOps (Argo CD)** with Kustomize overlays per cluster. Both clusters are managed by **RHACM 2.16+**.

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

- **Streams for Apache Kafka (AMQ Streams 3.2 / Kafka 4.2.0):** KRaft mode (ZooKeeper removed). Topology: 3 controller nodes (5Gi PVC each) + 3 broker nodes (20Gi JBOD PVC each), managed via `KafkaNodePool` resources. Topics: `transactions-raw` (6 partitions), `transactions-committed` (3 partitions), `transactions-dlq` (3 partitions). AWS is source; GCP receives via MirrorMaker 2.
- **MirrorMaker 2:** Replicates `transactions-.*` AWS → GCP via Skupper Listener (`kafka-bootstrap:9092`). Uses `IdentityReplicationPolicy` so topic names are not prefixed. Deployed to cloud (GCP) only.
- **Apicurio Registry 2.5.11.Final (kafkasql):** Avro schema registry on AWS only. Enforces backward compatibility. Requires both `APICURIO_KAFKASQL_BOOTSTRAP_SERVERS` (kafkasql storage) and `KAFKA_BOOTSTRAP_SERVERS` (SmallRye AdminClient health check) env vars.
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
| `scripts/bootstrap-phase1.sh` | Full Phase 1 orchestration: register cloud cluster with Argo CD → apply ApplicationSets → wait for Kafka/PostgreSQL → deploy Skupper sites → exchange AccessGrant/AccessToken → apply Connectors+Listeners → wait for MirrorMaker 2 → Phase 1 checkpoint. No extra env vars required. |
| `get-kubeconfig.sh onprem\|cloud` | Write the current `oc login` session credentials to `kubeconfig-onprem` or `kubeconfig-cloud`. Use after token expiry. |

## Infrastructure Notes

- **Neither cluster uses a managed service** — no ROSA, no OSD. Both are self-managed OCP 4.21+ on EC2 (AWS) and GCE (GCP).
- Do not use kubectl, use `oc` CLI for all cluster interactions. Fix existing `kubectl` references in scripts and documentation.
- Bootstrap scripts must handle full OCP install prerequisites: pull-secret configuration and DNS (`*.apps.<cluster-domain>`).
- Storage classes are not pinned in manifests; PVCs use the cluster's default SC at deploy time (EBS gp2/gp3 on AWS, GCP PD standard/ssd on GCP).
- RHACS Central runs on AWS; GCP runs the Sensor only, reporting back to Central via gRPC mTLS.
- Observability: GCP metrics federate to AWS Grafana via RHACM Observability Add-on.

## Phase 1 Operational Notes

Issues discovered during Phase 1 deployment that must be kept in mind for future work:

**AMQ Streams 3.2 / Kafka 4.x only (no ZooKeeper):**
AMQ Streams 3.2 ships Strimzi 0.46, which dropped ZooKeeper entirely. Only Kafka 4.1.0 and 4.2.0 are supported. The `Kafka` CR must have `strimzi.io/node-pools: enabled` and `strimzi.io/kraft: enabled` annotations, no `spec.zookeeper`, no `spec.kafka.replicas`, no `spec.kafka.storage` — all node topology is defined via `KafkaNodePool` resources. Never use Kafka version 3.x in any manifest.

**Argo CD RBAC — admin RoleBinding required per managed namespace:**
The `openshift-gitops-argocd-application-controller` ServiceAccount (in `openshift-gitops`) has no default permissions in application namespaces. Before Argo CD can sync Kafka, PostgreSQL, or any other resource into `banking-infra`, a `RoleBinding` must exist that grants it the `admin` ClusterRole in that namespace. The `admin` ClusterRole aggregates Strimzi and Crunchy PGO rules automatically via operator-installed aggregation roles.

**Cloud cluster Argo CD registration — TLS insecure:**
The GCP cluster API server certificate covers `*.apps.*` SANs, not `api.*`. The `cloud-cluster-secret` in `openshift-gitops` must set `tlsClientConfig.insecure: true`; providing `caData` from the cluster config view will fail with an x509 mismatch. Do not attempt to fix this by pulling a different CA — just keep `insecure: true`.

**Skupper AccessToken — PEM CA must use YAML literal block scalar:**
PEM certificates contain `-----END CERTIFICATE-----` lines that YAML parses as document separators (`---`) when embedded in a heredoc. The `spec.ca` field in the `AccessToken` CR must be written as a YAML literal block scalar (`ca: |`) with the PEM content indented. `bootstrap-phase1.sh` writes the token to a temp file using `sed 's/^/    /'` to indent the PEM rather than using a heredoc inline.

**MirrorMaker 2 v1beta2 API (Strimzi 0.46+) required fields:**
The `KafkaMirrorMaker2` CR with the new spec structure requires `spec.target.alias`, `spec.target.groupId`, `spec.target.configStorageTopic`, `spec.target.offsetStorageTopic`, `spec.target.statusStorageTopic`, and `spec.mirrors[].source.alias`. Missing any of these causes a validation error and the resource is never created. `spec.mirrors[].source.bootstrapServers` points to the Skupper Listener hostname (`kafka-bootstrap:9092`).

**No routes on GCP cluster from Phase 1 is correct:**
The cloud Skupper site uses an empty spec (outbound link initiator — no `linkAccess`). Kafka has internal-only listeners. Apicurio is onprem-only. The absence of routes in `banking-infra` on cloud after Phase 1 is expected behaviour.
