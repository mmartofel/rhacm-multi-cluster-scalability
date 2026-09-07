# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/mmartofel/rhacm-multi-cluster-scalability/releases/tag/v1.0.0
