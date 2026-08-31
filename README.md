# rhacm-multi-cluster-scalability

A multi-cluster banking transaction demo platform showing high availability, elasticity, data consistency, and zero-downtime upgrades across two OpenShift 4.21+ clusters, managed with **RHACM** and **OpenShift GitOps (Argo CD)**:

- **`onprem`** — AWS (EC2), self-managed OCP. Static baseline capacity. Record-of-truth cluster: PostgreSQL primary, Kafka source, RHACM Hub, Argo CD.
- **`cloud`** — GCP (GCE), self-managed OCP. Elastic capacity, scales to zero when idle via KEDA-driven autoscaling on Kafka consumer lag.

Cross-cluster connectivity runs over **Red Hat Service Interconnect (RHSI)**; both clusters are managed by **RHACM 2.16+**.

See [`CLAUDE.md`](CLAUDE.md) for the full architecture reference (services, data flow, chaos scenarios, and operational gotchas), and [`docs/architecture-diagrams.md`](docs/architecture-diagrams.md) for diagrams.

## Prerequisites

- Two OpenShift 4.21+ clusters already provisioned and reachable:
  - `onprem` (AWS/EC2) — will act as the RHACM hub
  - `cloud` (GCP/GCE) — will act as the managed spoke
- `oc` CLI (this repo uses `oc` exclusively — not `kubectl`)
- A Quay.io (or other registry) account/robot token to push built images
- Cluster-admin access on both clusters
- DNS resolving `*.apps.<cluster-domain>` for both clusters

## 1. Configure cluster access

Each cluster has its own kubeconfig file; `oc` merges them at runtime via a colon-separated `KUBECONFIG`. **Never merge them into one file.**

```bash
# Log in to onprem, then save its kubeconfig
oc login https://api.<onprem-cluster-domain>:6443
./get-kubeconfig.sh onprem

# Log in to cloud, then save its kubeconfig
oc login https://api.<cloud-cluster-domain>:6443
./get-kubeconfig.sh cloud

# Point oc at both
export KUBECONFIG="$(pwd)/kubeconfig-onprem:$(pwd)/kubeconfig-cloud"
oc config get-contexts   # should list both 'onprem' and 'cloud'
```

`kubeconfig-onprem` and `kubeconfig-cloud` are git-ignored — never commit them. All bootstrap scripts auto-configure `KUBECONFIG` to these two files if you don't export it yourself.

## 2. Configure registry credentials

Create `quay.sh` (git-ignored) with your registry robot account:

```bash
cat > quay.sh <<'EOF'
export QUAY_USER=<your-quay-robot-user>
export QUAY_TOKEN=<your-quay-robot-token>
export QUAY_ORG=<your-quay-org>        # images pushed to quay.io/$QUAY_ORG/banking-demo-*
EOF
source quay.sh
```

## 3. Install operators

Install required OLM operators on both clusters — all 9 on the hub (`onprem`), the 7 shared ones on the spoke (`cloud`):

```bash
./scripts/install-operators.sh --role hub   --context onprem
./scripts/install-operators.sh --role spoke --context cloud

# Verify every CSV reached Succeeded on both clusters
./scripts/operator-check.sh
```

## 4. Phase 0 — RHACM hub, cluster import, namespaces

```bash
export QUAY_USER=<your-quay-robot-user>
export QUAY_TOKEN=<your-quay-robot-token>
./scripts/bootstrap-phase0.sh
```

This runs the operator check, installs the RHACM `MultiClusterHub`, imports `cloud` as a `ManagedCluster`, waits for GitOps readiness, creates the app/infra/monitoring namespaces, propagates pull secrets, and applies the cert-manager `ClusterIssuer`.

## 5. Phase 1 — Kafka, PostgreSQL, Apicurio, cross-cluster mesh

```bash
./scripts/bootstrap-phase1.sh
```

This registers `cloud` with Argo CD, grants the Argo CD RBAC needed to sync `banking-infra`, applies the infra `ApplicationSet`s, waits for Kafka/PostgreSQL to come up, deploys the Skupper (RHSI) sites, exchanges the `AccessGrant`/`AccessToken`, wires up Connectors/Listeners, waits for MirrorMaker 2, and ends with a Phase 1 checkpoint.

No extra env vars are required beyond `KUBECONFIG` (auto-configured).

## 6. Phase 2 — application services

```bash
source quay.sh   # ensures QUAY_ORG, QUAY_USER, QUAY_TOKEN are set
./scripts/bootstrap-phase2.sh
```

This builds all 7 service images via Tekton, applies the Skupper application-layer extensions, initializes the PostgreSQL schema, propagates DB credentials to both clusters, registers Avro schemas with Apicurio, grants Argo CD RBAC for `banking-demo`, applies the app `ApplicationSet`, and ends with a Phase 2 checkpoint once all pods are healthy.

> `scripts/build-push-images-local.sh` is a fallback that builds images locally with podman/docker instead of Tekton — prefer `bootstrap-phase2.sh` unless Tekton is unavailable.

## Verifying the install

```bash
# Re-run any phase's checkpoint independently
./scripts/operator-check.sh

# Watch pods on both clusters
oc --context onprem get pods -n banking-demo -n banking-infra
oc --context cloud   get pods -n banking-demo -n banking-infra
```

## Refreshing an expired token

```bash
oc login https://api.<cluster-domain>:6443   # re-authenticate to onprem or cloud
./get-kubeconfig.sh onprem   # or: ./get-kubeconfig.sh cloud
```

## Applying config/code changes after install

Restarting a pod does **not** rebuild its image — it just re-pulls the existing tag. To pick up source changes, re-run `./scripts/bootstrap-phase2.sh` (triggers new Tekton builds; Argo CD rolls out the new image automatically). Use `./rollout.sh` to force a rollout restart of all `banking-demo` deployments on both clusters after a sync.

## Scripts reference

| Script | Purpose |
|---|---|
| `scripts/install-operators.sh --role hub\|spoke [--context <name>]` | Install OLM operators. Hub installs all 9; spoke installs the 7 shared ones. |
| `scripts/operator-check.sh` | Verify all required CSVs are `Succeeded` on both contexts. |
| `scripts/bootstrap-phase0.sh` | Phase 0: operator check → MCH → ManagedCluster import → GitOps readiness → namespaces → pull secrets → ClusterIssuer. Requires `QUAY_USER`/`QUAY_TOKEN`. |
| `scripts/bootstrap-phase1.sh` | Phase 1: Argo CD registration/RBAC → Kafka/PostgreSQL → Skupper (RHSI) → MirrorMaker 2 → checkpoint. |
| `scripts/bootstrap-phase2.sh` | Phase 2: Tekton image builds → Skupper app-layer → DB schema/credentials → Avro schema registration → Argo CD RBAC → app deploy → checkpoint. Requires `QUAY_ORG`/`QUAY_USER`/`QUAY_TOKEN`. |
| `scripts/build-push-images-local.sh` | Fallback local image build (podman/docker) instead of Tekton. |
| `get-kubeconfig.sh onprem\|cloud` | Save the current `oc login` session to the per-cluster kubeconfig file. |
| `rollout.sh` | Force a rollout restart of all `banking-demo` deployments on both clusters. |

## Further reading

- [`CLAUDE.md`](CLAUDE.md) — full architecture, data flow, chaos scenarios, and hard-won operational lessons from each phase
- [`docs/architecture-diagrams.md`](docs/architecture-diagrams.md) — Mermaid source for the C4 and sequence diagrams
