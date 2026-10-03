#!/usr/bin/env bash
# Runs the whole app as pods on a local kind cluster (Codespaces, or any machine with Docker,
# kind and kubectl), then forwards the frontend to http://localhost:4200.
#
#   k8s/up.sh                     build images, deploy, forward port 4200 (Ctrl+C stops the forward)
#   k8s/up.sh --no-build          reuse houwiya-*:local images already built
#   k8s/up.sh --no-port-forward   deploy and return (used by CI)
#
# Re-running it redeploys your latest code. k8s/down.sh deletes the cluster.
set -euo pipefail

CLUSTER="${CLUSTER:-houwiya}"
NAMESPACE=houwiya
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGES=(backend ai-service frontend)
BUILD=1
FORWARD=1
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-port-forward) FORWARD=0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

for tool in docker kind kubectl; do
  command -v "$tool" >/dev/null || { echo "missing '$tool' (rebuild the Codespace to get it)" >&2; exit 1; }
done

step() { printf '\n==> %s\n' "$*"; }

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  step "Creating kind cluster '$CLUSTER'"
  kind create cluster --name "$CLUSTER" --config "$ROOT/k8s/kind-config.yaml" --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

if [ "$BUILD" = 1 ]; then
  for image in "${IMAGES[@]}"; do
    step "Building houwiya-$image:local"
    docker build -t "houwiya-$image:local" "$ROOT/$image"
  done
fi

for image in "${IMAGES[@]}"; do
  step "Loading houwiya-$image:local into the cluster"
  kind load docker-image "houwiya-$image:local" --name "$CLUSTER"
done

step "Deploying (k8s/overlays/local)"
redeploy=0
kubectl -n "$NAMESPACE" get deployment backend >/dev/null 2>&1 && redeploy=1
kubectl apply -k "$ROOT/k8s/overlays/local"
if [ "$redeploy" = 1 ]; then
  # Same tag, new image: restart so the running pods pick up what was just loaded.
  kubectl -n "$NAMESPACE" rollout restart deployment
fi

step "Waiting for the pods"
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=300s
for deployment in ai-service backend frontend; do
  kubectl -n "$NAMESPACE" rollout status "deployment/$deployment" --timeout=600s
done
kubectl -n "$NAMESPACE" get pods -o wide

if [ "$FORWARD" = 1 ]; then
  step "App: http://localhost:4200 (in Codespaces: Ports tab -> 4200). Ctrl+C stops the forward; the pods keep running."
  echo "    Admin: admin@houwiya.local / admin-dev-password (development values, k8s/base/kustomization.yaml)"
  exec kubectl -n "$NAMESPACE" port-forward svc/frontend 4200:80
fi
