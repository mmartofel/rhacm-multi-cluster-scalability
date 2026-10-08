# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.2] - 2026-10-08

### Fixed

- Kafka Load showed `transactions-dlq` as holding every message ever written: for a topic
  without a consumer the processor reported the end offset. It now reports what the topic
  currently holds (end offset minus earliest offset), so a purge or retention shows up.

## [1.2.1] - 2026-10-08

Zero-DLQ release: under two-cluster load and through an interconnect outage nothing is
rejected to the DLQ any more. The acceptance test passes all five stages.

### Fixed

- Transactions rejected to the DLQ under two-cluster load (#25): `transaction-processor`
  no longer sends an account version with `apply` (no more `version conflict`), and a
  failed call to `account-service` is retried with backoff instead of being rejected as
  `service error`. Only insufficient funds or an unknown account reach the DLQ.
- A committed transaction could be left without a ledger entry when its first delivery
  stopped between the apply and the emit: a redelivered, already-committed message now
  re-emits `TransactionCommitted`.

### Changed

- During an interconnect outage cloud processors wait and commit after restore; cloud
  `transactions-raw` lag grows instead of the DLQ.
- `transactions-raw` and `transactions-committed` are retained for 6 h (was 2 h) so a
  backlog built during an outage or overload is not deleted before it is processed;
  `transactions-committed` size cap raised to 512 MB per partition.
- `smoke-test.sh` and `acceptance-test.sh` require zero DLQ messages; `sent to DLQ` is a
  `log-scan` signature.
- README and the dashboard's "Simulate Link Failure" text describe the new outage
  behaviour (and no longer claim MirrorMaker 2 pauses).

## [1.2.0] - 2026-10-08

Data-consistency release: repeated deliveries can no longer change a balance or the ledger
twice. The acceptance test passes all five stages.

### Fixed

- Balance updates applied twice for one transaction: `account-service` now writes the
  `transactions` row in the same statement as the balance update, idempotent on the
  transaction id (#20).
- Ledger batch written twice across an interconnect break: `ledger_entries` has a unique
  `transaction_id` and inserts use `ON CONFLICT DO NOTHING` (#21). **Requires the
  `scripts/schema.sql` reset.**

### Changed

- `acceptance-test.sh` checks one ledger entry per committed transaction; all five
  stages now pass.
- `transaction-processor` no longer inserts into `transactions`; it sends the transaction
  details with `apply`, retries a failed `apply` once and re-emits `TransactionCommitted`
  for a duplicate delivery.

## [1.1.0] - 2026-10-08

Hardening release: resource limits, probes, test suite and the fixes found while running
the platform under load on several cluster pairs (Azure, bare metal + AWS).

### Added

- `ResourceQuota` and `LimitRange` for `banking-demo` and `banking-infra` on both
  clusters (`infra/namespaces/`), applied by `bootstrap-phase0.sh`; `smoke-test.sh`
  fails if a quota is missing or is refusing pods (#23).
- Explicit requests/limits for Kafka brokers, controllers and operators, MirrorMaker 2,
  PostgreSQL, pgBackRest and PgBouncer (#23).
- `KafkaConsumerPollMonitor` in `ledger-service` and `transaction-processor`: liveness
  now detects a Kafka consumer that was closed by a fatal channel failure nothing else
  reported, and the pod is restarted (#24).
- Startup probe for `apicurio-registry` (#23).
- Test suite: `smoke-test.sh`, `log-scan.sh` and `acceptance-test.sh` (smoke, autoscale,
  interconnect chaos, data consistency, log gate), with reports in `test-reports/`.
- Dashboard Compliance pane backed by live RHACS data: posture counts, full violation
  list with cluster/severity filters, policy drill-down (#8, #22).

### Changed

- Startup probes of the JVM services use `/health/started` instead of `/health/ready`,
  so a pod created while the database is unreachable is no longer killed in a loop (#23).
- Every readiness and liveness probe has an explicit 3 s timeout (#23).
- `transaction-generator` liveness no longer depends on Kafka channel health; readiness
  uses a dedicated `kafka-topic` check (#23).
- `ledger-service` consumes `transactions-committed` in batches (one DB transaction per
  poll) and `account-service` applies a balance in one statement — both removed a
  cloud-side consumer lag caused by per-record round trips over the interconnect.
- PostgreSQL `max_connections` raised to 300; `transaction-processor` DB pool set to 2
  and the `account-service` HPA capped at 4 replicas to stay inside that budget.

### Fixed

- Kafka consumers of `ledger-service` and `transaction-processor` died 60 s into an
  interconnect outage while retrying a DB write (`throttled.unprocessed-record-max-age.ms`).
- `cluster-gateway` OOMKills caused by creating an `HttpClient` per proxied request.
- `dashboard-backend` crashing at boot when the RHACS API token secret did not exist yet,
  and failing TLS hostname verification against RHACS Central.
- `dashboard-frontend` returning 502 for every proxied call on clusters whose DNS service
  IP is not `172.30.0.10` (resolver now read from the pod's `resolv.conf`).
- RHACM search running without a PVC (`SearchPVCNotPresentCritical`).

### Known issues

- Balance updates can be applied twice for one transaction (#20) and a ledger batch can
  be written twice across an interconnect break (#21); the acceptance test's consistency
  stage fails until these are fixed.

## [1.0.0] - 2026-09-07

First stable baseline of the multi-cluster banking transaction demo platform, spanning
Phase 0 through Phase 2 bootstrap, the application services, and dashboard UI.

### Added

- **Phase 0 bootstrap**: OLM operator installation (hub/spoke), RHACM `ManagedCluster`
  import, OpenShift GitOps readiness, namespace/pull-secret/ClusterIssuer setup.
- **Phase 1 infrastructure**: AMQ Streams Kafka in KRaft mode (`KafkaNodePool`-based,
  no ZooKeeper), Crunchy PostgreSQL HA with PgBouncer, Apicurio Registry (kafkasql),
  Red Hat Service Interconnect (RHSI/Skupper) cross-cluster links, and MirrorMaker 2
  DR replication of `transactions-raw` from onprem to cloud.
- **Phase 2 application services**: `transaction-generator`, `transaction-processor`,
  `account-service`, `ledger-service`, `cluster-gateway`, and `dashboard-backend`/
  `dashboard-frontend`, built via Tekton and deployed via Argo CD ApplicationSets.
- **RHACS Central + SecuredCluster** on both clusters (onprem self-monitoring, cloud
  remote) and the RHSI Network Observer console (#2, #3).
- **Live dashboard**: Overview, Traffic & Chaos, Autoscale Watch, and real-time Kafka
  Load views covering all three topics (#12).
- **Chaos scenario**: real "Simulate Link Failure" control that breaks/restores the
  Skupper Listeners exposing onprem's Kafka/PostgreSQL/Apicurio to cloud, exercising
  an actual RHSI outage end-to-end from the dashboard.
- **KEDA-driven autoscaling** of `transaction-processor` on Kafka consumer lag
  (1-20 replicas), and partition-ownership-based load splitting between clusters.

### Fixed

- MirrorMaker 2 self-replication caused by colliding in-cluster broker hostnames,
  fixed with a dedicated advertised-listener tunnel.
- MM2 `topicsPattern` wildcard double-mirroring `transactions-committed` into cloud's
  ledger; narrowed to `transactions-raw` only.
- KEDA's Kafka scaler silently capping replicas at the topic's partition count;
  `transactions-raw` raised to 24 partitions to unblock real scaling headroom.
- Kafka consumer permanent fail-stop on deserialization failures and unguarded DB
  exceptions inside `@Incoming` handlers during RHSI outages, replaced with
  retry-with-backoff and a narrow liveness check tied to genuine exhaustion.
- JDBC connection pools left holding stale connections after an RHSI Listener
  recreation; fixed with `socketTimeout`/`connectTimeout` so Agroal self-heals.
- Intermittent JVM startup errors and restarts across `banking-demo` pods
  root-caused to CPU throttling (200-300m limits), not Hibernate/Quarkus
  configuration — raised CPU limits on all 6 JVM services (#17).
- Dashboard full-height layout issues: `ResizeObserver` feedback loop on chart
  sizing, PatternFly fill-section not shrinking to available height, and
  card content overflow on the Autoscale Watch and Overview panes.
- "Simulate Link Failure" panel's data-travel particle animation moving too fast;
  slowed 3x while preserving relative stagger spacing (#13).
- Optimistic locking and idempotency pre-checks on balance updates to prevent
  silent double-application of transactions on Kafka redelivery.

[Unreleased]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/compare/v1.2.2...HEAD
[1.2.2]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/releases/tag/v1.0.0
