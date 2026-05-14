#!/bin/bash
set -e

RINHA_REPO="${RINHA_REPO:-/d/rinha-de-backend-2026}"
PLATFORM="${PLATFORM:-linux/amd64}"

docker compose down --remove-orphans
#docker image rm -f rinha-hello:latest nginx:alpine 2>/dev/null || true
#docker buildx build --platform "$PLATFORM" --load -t rinha-hello:latest .
#docker buildx build --platform "$PLATFORM" --load -t nginx:alpine -f Dockerfile.nginx .
docker compose up -d

echo "waiting for stack to be ready..."
until curl -sf http://localhost:9999/ready > /dev/null 2>&1; do
  sleep 1
done
echo "ready — starting k6"

cd "$RINHA_REPO"

./run.sh
