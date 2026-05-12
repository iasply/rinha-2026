#!/bin/sh
set -e

ROUNDS=${WARMUP_ROUNDS:-5}
CONCURRENCY=${WARMUP_CONCURRENCY:-10}
PAYLOADS=/payloads.json
TARGET="http://nginx/fraud-score"
READY_1="http://api1:8080/ready"
READY_2="http://api2:8080/ready"

wait_ready() {
  local url=$1
  printf "Waiting for %s " "$url"
  until curl -sf "$url" > /dev/null 2>&1; do
    printf "."
    sleep 0.5
  done
  echo " ok"
}

wait_ready "$READY_1"
wait_ready "$READY_2"

COUNT=$(jq 'length' "$PAYLOADS")
TOTAL=$((COUNT * ROUNDS))
echo "Warmup: $COUNT payloads x $ROUNDS rounds = $TOTAL requests (concurrency=$CONCURRENCY)"

send_round() {
  jq -c '.[]' "$PAYLOADS" | xargs -P "$CONCURRENCY" -I{} \
    curl -sf -X POST "$TARGET" \
         -H "Content-Type: application/json" \
         -d {} > /dev/null
}

START=$(date +%s%3N)
i=1
while [ "$i" -le "$ROUNDS" ]; do
  send_round
  echo "  round $i/$ROUNDS done"
  i=$((i + 1))
done
END=$(date +%s%3N)

echo "Warmup complete in $((END - START))ms"
