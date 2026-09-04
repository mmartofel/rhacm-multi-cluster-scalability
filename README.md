# Multi cluster scalability for transaction processing application

A multi-cluster banking transaction demo platform showing high availability, elasticity, data consistency, and zero-downtime upgrades across two OpenShift 4.21+ clusters, managed with **RHACM** and **OpenShift GitOps (Argo CD)**:

- **`onprem`** — Self-managed OCP. Static baseline capacity. Record-of-truth cluster: PostgreSQL primary, Kafka source, RHACM Hub, Argo CD.
- **`cloud`** — Self-managed OCP. Elastic capacity, scales to zero when idle via KEDA-driven autoscaling on Kafka consumer lag.

`onprem` and `cloud` are logical roles, not specific infrastructure — either cluster can run on any provider (AWS, GCP, Azure, bare metal, etc.), and the two don't need to be on the same one. Nothing in this repo assumes a particular cloud; only the `onprem`/`cloud` context names and their roles matter.

Cross-cluster connectivity runs over **Red Hat Service Interconnect (RHSI)**; both clusters are managed by **RHACM 2.16+**.

See [`CLAUDE.md`](CLAUDE.md) for the full architecture reference (services, data flow, chaos scenarios, and operational gotchas), and [`docs/architecture-diagrams.md`](docs/architecture-diagrams.md) for diagrams.

## Prerequisites

- Two OpenShift 4.21+ clusters already provisioned and reachable, on any infrastructure (mixing providers is fine):
  - `onprem` — will act as the RHACM hub
  - `cloud` — will act as the managed spoke
- `oc` CLI (this repo uses `oc` exclusively — not `kubectl`)
- A Quay.io (or other registry) account/robot token to push built images
- Cluster-admin access on both clusters
- DNS resolving `*.apps.<cluster-domain>` for both clusters

## 1. Configure cluster access

Each cluster has its own kubeconfig file; `oc` merges them at runtime via a colon-separated `KUBECONFIG`. **Never merge them into one file.** The two clusters can be on different providers or infrastructure — only the `onprem`/`cloud` role matters, so log in to whichever cluster is playing each role for this run.

```bash
# Log in to the cluster that will act as 'onprem', then save its kubeconfig
oc login https://api.<onprem-cluster-domain>:6443
./get-kubeconfig.sh onprem

# Log in to the cluster that will act as 'cloud', then save its kubeconfig
oc login https://api.<cloud-cluster-domain>:6443
./get-kubeconfig.sh cloud

# Point oc at both
export KUBECONFIG="$(pwd)/kubeconfig-onprem:$(pwd)/kubeconfig-cloud"
oc config get-contexts   # should list both 'onprem' and 'cloud'
```

`get-kubeconfig.sh` renames the captured context to `onprem`/`cloud` regardless of the cluster's real name, so every other script can target `--context onprem|cloud` without caring where each cluster actually lives.

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

Install required OLM operators on both clusters — all 10 on the hub (`onprem`), the 6 shared ones on the spoke (`cloud`):

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

This registers `cloud` with Argo CD, grants the Argo CD RBAC needed to sync `banking-infra`, applies the infra `ApplicationSet`s, waits for Kafka/PostgreSQL to come up, deploys the Skupper (RHSI) sites, exchanges the `AccessGrant`/`AccessToken`, wires up Connectors/Listeners, waits for MirrorMaker 2, deploys the RHSI Network Observer (network console UI, onprem-only — see below), and ends with a Phase 1 checkpoint.

Once complete, the network console is reachable at the printed Route URL and shows live topology/traffic across both linked sites. Get the URL and log in with your OpenShift username/password (OAuth login — no separate credential to fetch):

```bash
oc --context onprem get route skupper-network-observer -n banking-infra -o jsonpath='https://{.status.ingress[0].host}{"\n"}'
```

No extra env vars are required beyond `KUBECONFIG` (auto-configured).

## 6. Phase 2 — application services

```bash
source quay.sh   # ensures QUAY_ORG, QUAY_USER, QUAY_TOKEN are set
./scripts/bootstrap-phase2.sh
```

This builds all 7 service images via Tekton, applies the Skupper application-layer extensions, initializes the PostgreSQL schema, propagates DB credentials to both clusters, registers Avro schemas with Apicurio, grants Argo CD RBAC for `banking-demo`, applies the app `ApplicationSet`, waits for all pods, deploys RHACS Central (onprem) and registers cloud as a Sensor/`SecuredCluster` via an automated cluster-init bundle exchange, and ends with a Phase 2 checkpoint once everything is healthy.

The RHACS console URL and a pointer to the auto-generated admin password (`central-htpasswd` secret) are printed at the end. Get the URL, username, and password:

```bash
oc --context onprem get route central -n stackrox -o jsonpath='https://{.spec.host}{"\n"}'
echo "user: admin"
echo "password: $(oc --context onprem get secret central-htpasswd -n stackrox -o jsonpath='{.data.password}' | base64 -d)"
```

This is local basic-auth login only (username `admin`) — RHACS is not configured with OpenShift OAuth as an auth provider. Note: `central-db`'s default resource requests can be significant — on a resource-constrained cluster it may stay `Pending` until capacity frees up; this doesn't indicate a broken deployment.

> `scripts/build-push-images-local.sh` is a fallback that builds images locally with podman/docker instead of Tekton — prefer `bootstrap-phase2.sh` unless Tekton is unavailable.

## 7. Access the live dashboard

```bash
oc --context onprem get route dashboard -n banking-demo -o jsonpath='https://{.spec.host}{"\n"}'
```

The dashboard streams live per-cluster metrics over WebSocket. Its **Traffic & Chaos** page has a Load Control panel (TPS / traffic-split) and a "Simulate Link Failure" control that performs a *real* RHSI chaos action — toggling it deletes or recreates the `kafka-bootstrap`/`postgresql-primary`/`apicurio-registry` Skupper Listeners on `cloud` (via `cluster-gateway`'s scoped Kubernetes RBAC), cutting off or restoring `cloud`'s access to onprem's Kafka/PostgreSQL/Apicurio so you can watch the cloud processor start rejecting transactions to the DLQ and `onprem` keep processing unaffected — all while the dashboard itself stays fully responsive, since it reaches `cloud` over a separate RHSI channel these Listeners don't touch. MirrorMaker 2 also stays unaffected and keeps mirroring `transactions-raw` throughout, since it runs over its own dedicated tunnel this toggle doesn't touch. See [`CLAUDE.md`](CLAUDE.md#chaos-scenario-rhsi-link-partition) for the mechanism.

## Verifying the install

```bash
# Re-run any phase's checkpoint independently
./scripts/operator-check.sh

# Watch pods on both clusters
oc --context onprem get pods -n banking-demo -n banking-infra
oc --context cloud   get pods -n banking-demo -n banking-infra

# Dashboard (Phase 2), RHSI network console (Phase 1), and RHACS console (Phase 2)
oc --context onprem get route dashboard -n banking-demo
oc --context onprem get route skupper-network-observer -n banking-infra
oc --context onprem get route central -n stackrox
```

## Refreshing an expired token

```bash
oc login https://api.<cluster-domain>:6443   # re-authenticate to onprem or cloud
./get-kubeconfig.sh onprem   # or: ./get-kubeconfig.sh cloud
```

## Applying config/code changes after install

Restarting a pod does **not** rebuild its image — it just re-pulls the existing tag. To pick up source changes, re-run `./scripts/bootstrap-phase2.sh` (triggers new Tekton builds; Argo CD rolls out the new image automatically). Use `./rollout.sh` to force a rollout restart of all `banking-demo` deployments on both clusters after a sync.

Every infra/app manifest under `infra/` and `app-services/` is Argo CD-managed (`selfHeal: true`) once Phase 1/2 have registered the `ApplicationSet`s — always `git push` a manifest change before (or instead of) applying it directly with `oc apply`, or Argo will silently revert your live change back to whatever's still in git on its next reconcile. Argo's default git poll interval means a pushed fix can take a few minutes to land on its own; to apply it immediately (e.g. mid-incident), force a refresh instead of waiting:

```bash
oc --context onprem annotate application.argoproj.io <app-name> -n openshift-gitops \
  argocd.argoproj.io/refresh=hard --overwrite
# e.g. <app-name> = banking-kafka-onprem, banking-mirrormaker2, banking-demo-transaction-processor-cloud, ...
```

## Scripts reference

| Script | Purpose |
|---|---|
| `scripts/install-operators.sh --role hub\|spoke [--context <name>]` | Install OLM operators. Hub installs all 10; spoke installs the 6 shared ones. |
| `scripts/operator-check.sh` | Verify all required CSVs are `Succeeded` on both contexts. |
| `scripts/bootstrap-phase0.sh` | Phase 0: operator check → MCH → ManagedCluster import → GitOps readiness → namespaces → pull secrets → ClusterIssuer. Requires `QUAY_USER`/`QUAY_TOKEN`. |
| `scripts/bootstrap-phase1.sh` | Phase 1: Argo CD registration/RBAC → Kafka/PostgreSQL → Skupper (RHSI) → MirrorMaker 2 → RHSI Network Observer → checkpoint. |
| `scripts/bootstrap-phase2.sh` | Phase 2: Tekton image builds → Skupper app-layer → DB schema/credentials → Avro schema registration → Argo CD RBAC → app deploy → RHACS Central + SecuredCluster → checkpoint. Requires `QUAY_ORG`/`QUAY_USER`/`QUAY_TOKEN`. |
| `scripts/build-push-images-local.sh` | Fallback local image build (podman/docker) instead of Tekton. |
| `get-kubeconfig.sh onprem\|cloud` | Save the current `oc login` session to the per-cluster kubeconfig file. |
| `rollout.sh` | Force a rollout restart of all `banking-demo` deployments on both clusters. |

## Further reading

- [`CLAUDE.md`](CLAUDE.md) — full architecture, data flow, chaos scenarios, and hard-won operational lessons from each phase
- [`docs/architecture-diagrams.md`](docs/architecture-diagrams.md) — Mermaid source for the C4 and sequence diagrams
