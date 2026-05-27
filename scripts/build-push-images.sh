#!/usr/bin/env bash
# build-push-images.sh — Build and push all 7 banking-demo service images to quay.io
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load Quay credentials
if [[ -f "$REPO_ROOT/quay.sh" ]]; then
  # shellcheck source=/dev/null
  source "$REPO_ROOT/quay.sh"
fi

: "${QUAY_ORG:?QUAY_ORG must be set (e.g. export QUAY_ORG=mmartofe)}"
: "${QUAY_USER:?QUAY_USER must be set}"
: "${QUAY_TOKEN:?QUAY_TOKEN must be set}"

REGISTRY="quay.io/${QUAY_ORG}"

# Prefer podman; fall back to docker
if command -v podman &>/dev/null; then
  CONTAINER_CLI=podman
elif command -v docker &>/dev/null; then
  CONTAINER_CLI=docker
else
  echo "ERROR: neither podman nor docker found on PATH" >&2
  exit 1
fi

echo "=== Using $CONTAINER_CLI ==="
echo "$QUAY_TOKEN" | $CONTAINER_CLI login quay.io --username "$QUAY_USER" --password-stdin

SERVICES=(
  transaction-generator
  transaction-processor
  account-service
  ledger-service
  cluster-gateway
  dashboard-backend
  dashboard-frontend
)

BUILD_ERRORS=()

for svc in "${SERVICES[@]}"; do
  SVC_DIR="$REPO_ROOT/services/$svc"
  IMAGE="$REGISTRY/banking-demo-$svc:latest"

  if [[ ! -d "$SVC_DIR" ]]; then
    echo "WARN: $SVC_DIR not found, skipping $svc"
    BUILD_ERRORS+=("$svc: source directory missing")
    continue
  fi

  echo ""
  echo "=== Building $svc → $IMAGE ==="
  if $CONTAINER_CLI build --platform linux/amd64 -t "$IMAGE" -f "$SVC_DIR/Dockerfile" "$SVC_DIR"; then
    echo "=== Pushing $IMAGE ==="
    $CONTAINER_CLI push "$IMAGE"
    echo "OK: $svc pushed"
  else
    echo "ERROR: build failed for $svc"
    BUILD_ERRORS+=("$svc: build failed")
  fi
done

echo ""
if [[ ${#BUILD_ERRORS[@]} -eq 0 ]]; then
  echo "=== All images built and pushed successfully ==="
else
  echo "=== Completed with errors ==="
  for e in "${BUILD_ERRORS[@]}"; do echo "  - $e"; done
  exit 1
fi
