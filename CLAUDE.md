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
| `transaction-processor` | Consumes Kafka, validates balance, writes to PostgreSQL, emits `TransactionCommitted`; failed transactions emitted to DLQ | JVM mode + KEDA; GCP instance writes to AWS PostgreSQL via RHSI; `OWNED_PARTITIONS` env var restricts consumption to cluster-specific partitions (onprem: 0,1,2 · cloud: 3,4,5) |
| `account-service` | Balance reads via Quarkus `@CacheResult` in-process cache; reads PostgreSQL directly | AWS: HPA CPU 60%; GCP: 0–5 replicas |
| `ledger-service` | Authoritative running balance; serves REST to dashboard-backend | GCP instance reads from onprem PostgreSQL via RHSI (`postgresql-primary`) |
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

1. `transaction-generator` (onprem) → Kafka AWS partitions 0–2; `transaction-generator` (cloud) → Kafka GCP partitions 3–5
2. MirrorMaker 2 replicates `transactions-raw` onprem → cloud (DR copy; cloud processor ignores MM2-replicated partitions 0–2)
3. `transaction-processor` (onprem) consumes partitions 0–2 from onprem Kafka; cloud processor consumes partitions 3–5 from cloud Kafka — **no message is processed by both clusters**
4. Both processors validate balance via `account-service` (Quarkus `@CacheResult` + `@CacheInvalidate` → PostgreSQL, optimistic version locking)
5. **GCP processor writes committed transactions to AWS PostgreSQL primary via RHSI** (tunnelled JDBC)
6. Failed transactions (insufficient funds, version conflict, service error) → `transactions-dlq` (Avro `TransactionFailed`); in-memory counters surfaced via `/api/processor/stats`
7. `ledger-service` consumes `TransactionCommitted` and updates running balance
8. `dashboard-backend` polls both ledger services + processor stats every 1 s → WebSocket push to frontend

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
| `onprem` | AWS (EC2) | `https://api.zenek.sandbox5552.opentlc.com:6443` |
| `cloud` | GCP (GCE) | `https://api.zenek.tcw5b.gcp.redhatworkshops.io:6443` |

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
| `scripts/bootstrap-phase1.sh` | Full Phase 1 orchestration: register cloud cluster with Argo CD → apply Argo CD RBAC for `banking-infra` → apply ApplicationSets → wait for Kafka/PostgreSQL → deploy Skupper sites → exchange AccessGrant/AccessToken → apply Connectors+Listeners → wait for MirrorMaker 2 → Phase 1 checkpoint. No extra env vars required. |
| `scripts/bootstrap-phase2.sh` | Full Phase 2 orchestration: build all 7 service images via Tekton → apply Skupper app-layer extensions → init PostgreSQL schema → propagate DB credentials → register Avro schemas with Apicurio → apply Argo CD RBAC for `banking-demo` → apply banking-demo ApplicationSet → wait for all pods → Phase 2 checkpoint. Requires `QUAY_ORG`, `QUAY_USER`, `QUAY_TOKEN` env vars (or `source quay.sh`). |
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

## Phase 2 Operational Notes

Issues discovered during Phase 2 deployment that must be kept in mind for future work:

**Apicurio serde package name — singular `serde`, not plural `serdes`:**
The BOM-managed `apicurio-registry-serdes-avro-serde-2.5.9.Final.jar` (pulled via `quarkus-apicurio-registry-avro`) places its classes under the package `io.apicurio.registry.serde.avro` (singular). Any `application.properties` referencing `io.apicurio.registry.serdes.avro` (plural) causes `ClassNotFoundException` at startup. All three services — `transaction-generator`, `transaction-processor`, `ledger-service` — must use the singular form for both serializer and deserializer class names.

**`@Blocking @Transactional` incompatible with SmallRye reactive messaging in Quarkus 3.9.5:**
Combining `@Blocking` and `@Transactional` on a `@Incoming` message handler causes Agroal's `LocalXAResource.commit()` to find `enlisted=false`, throwing `RollbackException: Enlisted connection used without active transaction`. The channel enters permanent `fail-stop` state. Fix: remove `@Transactional` from the handler and wrap the database work in `QuarkusTransaction.requiringNew().call(() -> { ... })`. Add `quarkus-narayana-jta` as an explicit compile dependency in `pom.xml` (it is pulled transitively but the `QuarkusTransaction` API requires it on the compile classpath).

**Hibernate 6 sequence naming — `ledger_entries_seq` must be lowercase, unquoted:**
Hibernate 6 `SequenceStyleGenerator` (Quarkus Panache) generates `select nextval('ledger_entries_SEQ')`. PostgreSQL casts that string to `regclass` by folding it to lowercase → looks for `ledger_entries_seq`. The `bootstrap-phase2.sh` schema SQL block must create the sequence **without double quotes**: `CREATE SEQUENCE IF NOT EXISTS ledger_entries_seq START 1 INCREMENT BY 50;`. Using `"ledger_entries_SEQ"` (double-quoted) creates a case-sensitive object that `nextval()` cannot find, crashing ledger-service on first persist. Also, the `ledger_entries` table primary key column must be named `id` (not `entry_id`) to match the `PanacheEntity.id` field mapping.

**Kafka PVC disk retention — always set both `retention.ms` AND `retention.bytes`:**
At 100 TPS with 6 partitions and replication factor 3, a 20 Gi broker PVC fills in under an hour with only a time-based retention limit. When MirrorMaker 2 reconnects after a link outage it replays backlog at full speed, accelerating the fill. Always configure both `retention.bytes` (per partition) and `segment.bytes` (smaller segments let the log cleaner enforce retention more frequently) in the `KafkaTopic` Strimzi CRs. Current values: `transactions-raw` — 2 h / 500 MB / 128 MB segments; `transactions-committed` — 2 h / 250 MB / 64 MB segments. Dynamic configs applied via `kafka-configs.sh` are lost when broker PVCs are wiped; the `KafkaTopic` CR is the only persistent source of truth.

**Cloud `transaction-processor` must use the local cloud Kafka, not the Skupper Listener:**
Both `KAFKA_BOOTSTRAP_SERVERS` (Deployment env var) and `bootstrapServers` (KEDA ScaledObject trigger) in the cloud overlay must be set to `banking-kafka-kafka-bootstrap.banking-infra.svc.cluster.local:9092` — the Strimzi bootstrap service of the cloud-local Kafka cluster. Setting them to `kafka-bootstrap.banking-infra.svc.cluster.local:9092` (the Skupper Listener that tunnels to the onprem Kafka) causes KEDA to fail with "client has run out of available brokers" and breaks the chaos-resilience property: when the RHSI link is severed the cloud processor must continue consuming from its local Kafka replica, not fall over with the link.

**`grep -P` (Perl regex) is not available on macOS:**
Bootstrap script checkpoint sections must use `grep -qE` instead of `grep -qP`. BSD grep (macOS default) does not support the `-P` flag; every check silently fails and the checkpoint reports all deployments as down even when they are fully healthy. `grep -qE '[1-9]'` is POSIX-compatible and works on both Linux and macOS.

**Cloud app services all connect to onprem PG via RHSI — never the local cloud PGO:**
All cloud application services (`account-service`, `transaction-processor`, `ledger-service`) connect to the onprem PostgreSQL primary via the RHSI Skupper listener at `postgresql-primary.banking-infra.svc.cluster.local:5432`. The cloud PGO instance (`postgres-primary`) runs independently with no application schema and a separate PGO-managed password. Do not point any cloud service DATASOURCE_URL at `postgres-primary`; use `postgresql-primary` in every cloud overlay.

**`postgresql-credentials` on cloud must carry the onprem PGO password:**
The `postgresql-credentials` secret in `banking-demo` on cloud must be populated with the onprem PGO password (from `postgres-pguser-postgres` in `banking-infra` on onprem), because all cloud services authenticate against the onprem PostgreSQL via RHSI. Using the cloud-local PGO password causes `FATAL: password authentication failed` on every cloud service. `bootstrap-phase2.sh` applies the same onprem password to both clusters.

**`transaction_id` Avro String must be cast to `java.util.UUID` before native INSERT:**
The `TransactionEvent` Avro schema declares `transactionId` as Avro `string`, so `getTransactionId()` returns a Java `String`. PostgreSQL's `UUID` column type rejects a bound `character varying` parameter without an explicit cast. The native INSERT in `TransactionProcessor.java` must pass `UUID.fromString(event.getTransactionId())` as parameter 1. Binding the raw `String` causes `ERROR: column "transaction_id" is of type uuid but expression is of type character varying` and puts the pod in CrashLoopBackOff on both clusters.

**RESTEasy Reactive dispatches JAX-RS methods on the event loop — add `@Blocking` for blocking I/O:**
In Quarkus 3.x with RESTEasy Reactive, resource methods that return plain (non-reactive) types are still dispatched on the Vert.x event loop by default. Any method that performs blocking I/O (e.g. `java.net.http.HttpClient.send()`, synchronous REST calls) must be annotated with `@Blocking` (`io.smallrye.common.annotation.Blocking`) so the call is dispatched to a worker thread. Without it the event loop stalls and Vert.x's blocked-thread-checker fires repeated WARNs.

**Partition ownership prevents silent balance double-apply caused by MM2 replication:**
MM2 replicates every message from onprem Kafka → cloud Kafka. With two independent consumer groups each reading from their local Kafka, every onprem-originated transaction was processed by BOTH clusters — `ON CONFLICT DO NOTHING` only prevents a duplicate DB row; it does NOT prevent `account-service.apply()` being called twice, silently corrupting account balances. Fix: split the 6 `transactions-raw` partitions between clusters (onprem owns 0,1,2; cloud owns 3,4,5). Both `transaction-generator` and `transaction-processor` read `OWNED_PARTITIONS` from an env var (default `"0,1,2,3,4,5"` — safe for local dev; overlays pin cluster-specific values). Generator uses `OutgoingKafkaRecordMetadata.withPartition(computePartition(accountId))` to restrict output to owned partitions. Processor handler signature changed from `void process(TransactionEvent)` to `CompletionStage<Void> process(Message<TransactionEvent>)` with `@Acknowledgment(Acknowledgment.Strategy.MANUAL)`; non-owned partitions are fast-ACKed via `return message.ack()` without processing. KEDA scaling remains correct because fast-ACKed partitions contribute ~0 to total consumer group lag.

**Quarkus `@CacheInvalidate` key must match `@CacheResult` key — use `@CacheKey` to select the right parameter:**
When `@CacheResult` is on a method with one parameter, the cache key is that parameter directly. When `@CacheInvalidate` is on a method with multiple parameters, the default key includes ALL parameters. If the write method has a different signature than the read method (e.g., `applyDelta` takes `accountId` + `body` Map while `getBalance` takes only `accountId`), add `@CacheKey` to the matching parameter on the write method to restrict the invalidation key to `accountId` alone. Without `@CacheKey`, the composed key `(accountId, body)` never matches the `@CacheResult` key `(accountId)` and the cache is never invalidated.

**Optimistic locking on balance update — version column, request body type, and idempotency pre-check:**
The `accounts` table carries a `version BIGINT NOT NULL DEFAULT 0` column. Every successful `UPDATE` atomically increments it (`version = version + 1`) and includes it in the `WHERE` clause (`AND version = :version`) when the caller supplies one. Account-service returns the current version in every `apply` response (success, insufficient funds, and version conflict). On version conflict (0 rows updated and DB version ≠ submitted version), the response carries `reason: "version conflict"` and the current DB version so the caller can retry immediately without an extra GET.

The `AccountServiceClient` body type must be `Map<String, Number>` (not `Map<String, Double>`) because the map carries both a `Double` delta and a `Long` version. Using `Map<String, Double>` forces a lossy cast on the version. Build the map with `new HashMap<>()` and `put` both values rather than `Map.of()` when the version entry is conditional — `Map.of` with mixed `Double`/`Long` values infers an intersection type that does not satisfy `Map<String, Number>` without explicit casts.

`ON CONFLICT DO NOTHING` on the `transactions` INSERT only prevents a duplicate row — it does NOT prevent `account-service.apply()` being called again on Kafka redelivery, which would corrupt the balance. The correct guard is a transaction-id pre-check BEFORE calling account-service: query `SELECT COUNT(*) FROM transactions WHERE transaction_id = ?1` inside `QuarkusTransaction.requiringNew()` and fast-ACK if the row already exists. The version cache (`ConcurrentHashMap<String, Long>`) in the processor stores the last known version per accountId, eliminating the extra GET call on every apply; it is populated from the `version` field in the `ApplyResponse`.

**DLQ Avro schema — use `string` for shared enum fields to avoid cross-schema type conflicts:**
When a new Avro schema (e.g., `TransactionFailed`) needs a field whose type is an enum defined in another schema (`TransactionType` in `TransactionEvent.avsc`), do NOT redefine the enum inline (avro-maven-plugin duplicate type error) and do NOT reference it by fully-qualified name (fragile cross-schema resolution at runtime). Use `"type": "string"` for the field and call `.name()` on the enum value at the call site (e.g., `event.getType().name()`). Simpler, avoids all cross-schema dependency, and the string value is human-readable in the DLQ topic.

**`bootstrap-phase2.sh` schema registration loop must include every Avro schema in `services/avro-schemas/`:**
Step 7 registers schemas explicitly with Apicurio so backward-compatibility enforcement is active before any pod starts. Any new `.avsc` file must be added to the `for schema in TransactionEvent TransactionCommitted TransactionFailed; do` loop. Without it the schema is only registered via `auto-register=true` on first emit — which works but bypasses compatibility checks until the first real message is produced.

**DLQ counter pattern — in-memory `AtomicLong` + `ConcurrentHashMap`, exposed via REST, proxied through cluster-gateway:**
To surface per-cluster rejection counts on the dashboard without a separate consumer service: track counts in the processor with `AtomicLong rejectedCount` and `ConcurrentHashMap<String, AtomicLong> rejectedByReason`; expose via `GET /api/processor/stats` (`ProcessorStatsResource`). The cluster-gateway proxies this as `GET /api/gateway/processor/stats` using the same `java.net.http.HttpClient` pattern as the existing `scaling/summary` proxy. `ClusterPoller` in dashboard-backend polls the gateway endpoint and adds `rejectedTotal` to `ClusterMetrics`/`MetricsPayload`. This avoids adding a Kafka dependency to dashboard-backend.

**Maven `target/` directories must never be committed — add `**/target/` to `.gitignore`:**
All Quarkus service Dockerfiles use multi-stage builds that copy only `pom.xml` and `src/`; Maven runs entirely inside the Docker build stage. The local `target/` directory is never read from the repository. Remove any committed `target/` content with `git rm -r --cached services/*/target` and add `**/target/` to `.gitignore`. Committing build artifacts wastes space, pollutes diffs, and gives a false impression that pre-compiled classes are required.

**Pod restart ≠ image rebuild — only `bootstrap-phase2.sh` (Tekton) rebuilds images:**
In OpenShift, restarting a pod (`oc rollout restart` or deleting a pod) causes the node to re-pull the existing image tag from the registry — it does NOT trigger a new build. Source code changes only take effect after a new Docker image is built and pushed. Run `bootstrap-phase2.sh` to trigger Tekton PipelineRuns for all 7 services. After builds complete, Argo CD detects the new image digest and automatically rolls out updated pods. If you restart a pod expecting to see code changes and nothing changes, the image has not been rebuilt.

**Cloud-local PostgreSQL replica for read-only queries — named Agroal datasource pattern:**
The cloud PGO streaming standby (`postgres-ha.banking-infra.svc.cluster.local:5432`) replicates the full onprem database including `pg_authid`, so the same `DATASOURCE_USER`/`DATASOURCE_PASSWORD` credentials (onprem PGO password) work directly on the cloud replica without crossing the Skupper tunnel. Verify the replica has data before relying on it: `oc exec -n banking-infra --context cloud <pg-pod> -- psql -U postgres postgres -c 'SELECT count(*) FROM accounts;'` should return 100.

**IMPORTANT — standby mode must be enabled before `DATASOURCE_READ_URL` is set in cloud overlays:**
The cloud `PostgresCluster` CR requires `spec.standby.enabled: true` before the read-replica path works. Without standby mode the cloud PGO is an independent cluster with its own PGO-managed password — different from the onprem password in `postgresql-credentials`. Setting `DATASOURCE_READ_URL=postgres-ha…` while standby is inactive causes `FATAL: password authentication failed` every few seconds, the Quarkus health check for the "read" datasource reports DOWN, and pods never become ready. To guard against this, `application.properties` must always include `quarkus.datasource."read".health-enabled=false` so a failed read replica cannot block pod readiness. Until `spec.standby` is configured and replication has caught up, do not add `DATASOURCE_READ_URL` to the cloud overlay — the fallback to `${DATASOURCE_URL}` (RHSI to onprem primary) keeps the service functional.

**PGO v5 standby mode requires a shared pgBackRest repository (S3, GCS, or Azure):**
The standby `PostgresCluster` bootstraps itself by running `pgbackrest restore` from the same repository as the primary. Local PVC-backed repos (`repo1.volume`) cannot be shared across clusters — each cluster's volume is inaccessible to the other. The only supported shared repo types in PGO v5 are `s3`, `gcs`, and `azure`. To enable standby: add a second repo (e.g., `repo2`) of type `s3` or `gcs` to the onprem `PostgresCluster`, let PGO back up to it, then configure the cloud `PostgresCluster` with the same repo and `spec.standby.enabled: true` / `spec.standby.repoName: repo2`. Once replication is streaming and `pg_authid` is replicated, the onprem PGO password works on `postgres-ha` and `DATASOURCE_READ_URL` can be restored in the cloud overlays.

To split reads from writes in Quarkus without a second Hibernate ORM persistence unit, add a named Agroal datasource in `application.properties`:
```
quarkus.datasource."read".db-kind=postgresql
quarkus.datasource."read".jdbc.url=${DATASOURCE_READ_URL:${DATASOURCE_URL:jdbc:postgresql://localhost:5432/postgres}}
quarkus.datasource."read".username=${DATASOURCE_USER:postgres}
quarkus.datasource."read".password=${DATASOURCE_PASSWORD:postgres}
```
Inject it with `@Inject @DataSource("read") AgroalDataSource readDataSource` and use raw JDBC (`Connection`/`PreparedStatement`/`ResultSet`) for SELECT queries. Panache static methods (`Entity.findById`, `Entity.count`) only work with the default datasource — replace them with raw JDBC on the read path. Add `@Blocking` to any JAX-RS method that opens a JDBC connection; without it RESTEasy Reactive dispatches on the event loop and JDBC stalls it. `DATASOURCE_READ_URL` defaults to `${DATASOURCE_URL}` so onprem services and local dev use a single datasource with zero code impact.

`@Scheduled` methods that previously used `@Transactional` + Panache for a count query can drop both annotations when replaced with raw JDBC — plain JDBC does not need a JTA transaction context.

The PGO service name for the cloud standby is `postgres-ha` (PgBouncer HA service, recommended entry point). Confirm with `oc get svc -n banking-infra --context cloud | grep postgres` before deploying — use `postgres-replicas` as a fallback if `postgres-ha` is absent. Do NOT use `postgres-primary` on cloud; that name is reserved for the Skupper Listener that tunnels to the onprem primary (`postgresql-primary` is the Skupper service; `postgres-primary` is the PGO local service — they have different names but the CLAUDE.md warning was about credential mismatch, not the hostname itself).

Chaos resilience benefit: routing reads to the local replica means balance lookups and ledger counts survive a Skupper link failure — only write operations (`POST /apply`, ledger entry inserts) circuit-break when the tunnel is severed.

**Quarkus Agroal named datasource health check — use `health-enabled` (kebab-case), not `health.enabled`:**
The Agroal extension config key for disabling a named datasource health check is `quarkus.datasource."<name>".health-enabled=false` — hyphen, not dot. Using `health.enabled` (dot notation) is silently ignored with a startup WARN `Unrecognized configuration key`, leaving the health check enabled. Verify with `grep health /application.properties` and ensure the hyphen form is present.

**WebSocket broadcasting in Quarkus WebSockets Next 3.9.5 — use explicit connection registry, not BroadcastProcessor:**
`BroadcastProcessor.create()` (Mutiny) retains subscriptions for disconnected WebSocket clients indefinitely. When Quarkus tries to send to a closed connection, it logs `ERROR: Unable to send text message from Multi: WebSocket is closed` every second per stale client, forever. `OpenConnections` (the idiomatic fix in newer Quarkus) does not exist in 3.9.5. The correct pattern for this version:
- Broadcaster holds `ConcurrentHashMap.newKeySet()` of `WebSocketConnection` instances
- `@WebSocket` endpoint declares `WebSocketConnection connection` as a **method parameter** on `@OnOpen` and `@OnClose` — **never via `@Inject` field**
- `publish()` calls `connections.removeIf(WebSocketConnection::isClosed)` then `conn.sendText(json)` for each remaining connection
This completely eliminates the Multi/BroadcastProcessor and the orphaned-subscription problem.

**CRITICAL — `@WebSocket` endpoint must receive `WebSocketConnection` as a method parameter, not via `@Inject`:**
`@WebSocket` endpoints default to `@Singleton` scope in Quarkus WebSockets Next when no scope annotation is present. `WebSocketConnection` is a `@ConnectionScoped` normal CDI scope, so `@Inject WebSocketConnection connection` gives a CDI client proxy — not the actual connection object. Every call to `broadcaster.register(connection)` adds the same proxy to the set. When `MetricsBroadcaster.publish()` calls `isClosed()` on the proxy from the scheduler thread (no active connection scope), it throws `ContextNotActiveException`. This exception propagates out of `removeIf` and is silently swallowed by `ClusterPoller.poll()` — no data ever reaches the frontend, even though the WebSocket shows "Live". The fix: declare `WebSocketConnection connection` as a method parameter on `@OnOpen` and `@OnClose`. The Quarkus WebSockets Next runtime passes the actual backing instance via method parameters, bypassing CDI proxy mechanics. The stored object is then safe to call from any thread (including `@Scheduled` methods).

**Avro schemas must be placed in each service's `src/main/avro/` — the shared `services/avro-schemas/` directory is not used by Maven:**
The avro-maven-plugin in each Quarkus service reads `.avsc` files from `src/main/avro/` inside that service's directory. The Dockerfile copies only `pom.xml` and `src/` into the Docker build stage — the top-level `services/avro-schemas/` directory is never copied and is invisible to Maven. Adding a new schema only to `services/avro-schemas/` causes a `cannot find symbol` compilation failure in Tekton. The correct procedure for any new Avro type used by a service:
1. Add the `.avsc` file to `services/avro-schemas/` (for Apicurio bootstrap registration)
2. Copy the same file to `services/<service-name>/src/main/avro/` for every service that imports the generated class
`TransactionFailed.avsc` must exist in both `services/avro-schemas/` AND `services/transaction-processor/src/main/avro/` — the former for Apicurio, the latter for the Maven build.
