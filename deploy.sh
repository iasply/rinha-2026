#!/bin/bash
set -e

build_local() {
  docker compose down --remove-orphans
  docker image rm -f rinha-hello:latest nginx:alpine 2>/dev/null || true
  docker buildx build --platform "${PLATFORM:-linux/amd64}" --load -t rinha-hello:latest .
  docker buildx build --platform "${PLATFORM:-linux/amd64}" --load -t nginx:alpine -f Dockerfile.nginx .
  docker compose up -d
  docker compose logs -f
}

build_push() {
  docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    -t "${REGISTRY}/rinha-hello:latest" .
  docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    -f Dockerfile.nginx \
    -t "${REGISTRY}/nginx:alpine" .
}

case "${1:-local}" in
  push)  build_push ;;
  *)     build_local ;;
esac
