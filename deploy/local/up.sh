#!/usr/bin/env bash
# 로컬 클러스터에 전체를 올린다. 저장소 맨 위에서 실행한다.
# 필요한 것: kind, kubectl, helm, 그리고 값이 채워진 .env
set -euo pipefail

CTX=kind-zero-trust
NS=zero-trust

kubectl --context "$CTX" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CTX" apply -f -

# 비밀값은 Git에 두지 않는다. 내 컴퓨터의 .env에서 읽어 클러스터에 직접 넣는다.
kubectl --context "$CTX" -n "$NS" create secret generic app-secrets --from-env-file=.env \
  --dry-run=client -o yaml | kubectl --context "$CTX" apply -f -

kubectl --context "$CTX" -n "$NS" apply -f deploy/local/deps.yaml

for SERVICE in auth-service gateway; do
  helm --kube-context "$CTX" -n "$NS" upgrade --install "$SERVICE" deploy/charts/service \
    -f "deploy/values/$SERVICE.yaml" --wait --timeout 5m
done

kubectl --context "$CTX" -n "$NS" get pods
