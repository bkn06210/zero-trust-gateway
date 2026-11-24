#!/usr/bin/env bash
# 로컬 클러스터를 처음 준비한다. 저장소 맨 위에서 실행한다.
# 필요한 것: kind로 만든 클러스터, kubectl, 그리고 값이 채워진 .env
#
# 이 스크립트는 서비스를 직접 올리지 않는다. ArgoCD를 설치하고 "배포 설정 저장소를 읽어라"는 지시서만 넣는다.
# 서비스는 ArgoCD가 배포 설정 저장소에 적힌 대로 올린다.
set -euo pipefail

CTX=kind-zero-trust
NS=zero-trust
ARGOCD_VERSION=v3.6.2
DEPLOY_REPO_RAW=https://gitlab.com/bkn06210-lab/zero-trust-deploy/-/raw/deployed

kubectl --context "$CTX" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CTX" apply -f -

# 비밀값은 Git에 두지 않는다. 내 컴퓨터의 .env에서 읽어 클러스터에 직접 넣는다.
kubectl --context "$CTX" -n "$NS" create secret generic app-secrets --from-env-file=.env \
  --dry-run=client -o yaml | kubectl --context "$CTX" apply -f -

kubectl --context "$CTX" create namespace argocd --dry-run=client -o yaml | kubectl --context "$CTX" apply -f -
kubectl --context "$CTX" -n argocd apply --server-side \
  -f "https://raw.githubusercontent.com/argoproj/argo-cd/$ARGOCD_VERSION/manifests/install.yaml"
kubectl --context "$CTX" -n argocd wait --for=condition=Available deployment --all --timeout=300s

for APP in deps auth-service gateway; do
  kubectl --context "$CTX" apply -f "$DEPLOY_REPO_RAW/apps/$APP.yaml"
done

kubectl --context "$CTX" -n argocd get applications
