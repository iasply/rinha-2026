#!/bin/bash
set -e

RINHA_REPO="${RINHA_REPO:-/d/rinha-de-backend-2026}"

docker compose down --remove-orphans

docker rmi -f iuryasilva/rinha-2026:graal || true

docker build -t iuryasilva/rinha-2026:graal .

docker compose up -d

echo "waiting for stack to be ready..."
until curl -sf http://localhost:9999/ready > /dev/null 2>&1; do
  sleep 1
done
echo "ready — starting k6"

cd "$RINHA_REPO"
./run.sh
