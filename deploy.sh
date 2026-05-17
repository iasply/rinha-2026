#!/bin/bash
set -e

REGISTRY="${REGISTRY:-iuryasilva/rinha-2026}"

build_local() {
  docker compose down --remove-orphans
  docker rmi -f "${REGISTRY}:latest" || true
  docker buildx build --platform linux/amd64 --load -t "${REGISTRY}:latest" .
  docker compose up -d
  docker compose logs -f
}

build_push() {
  docker buildx build \
    --platform linux/amd64 \
    --push \
    -t "${REGISTRY}:latest" .
}

case "${1:-local}" in
  push)  build_push ;;
  *)     build_local ;;
esac
