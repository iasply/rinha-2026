#!/bin/bash
set -e

RINHA_REPO="${RINHA_REPO:-/d/rinha-de-backend-2026}"

docker compose down --remove-orphans

docker rmi -f rinha-hello:latest || true
docker rmi -f nginx:alpine || true

docker build -t rinha-hello:latest .
docker build -t nginx:alpine -f Dockerfile.nginx .

docker compose up -d

echo "waiting for stack to be ready..."
until curl -sf http://localhost:9999/ready > /dev/null 2>&1; do
  sleep 1
done
echo "ready — starting k6"

cd "$RINHA_REPO"
./run.sh
