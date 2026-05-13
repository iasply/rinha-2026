#!/bin/sh

ROUNDS=${WARMUP_ROUNDS:-30}
CONCURRENCY=${WARMUP_CONCURRENCY:-10}
READY_TIMEOUT=${WARMUP_READY_TIMEOUT:-30}
PAYLOADS=/payloads.json
TARGET="http://localhost/fraud-score"
API1="http://api1:8080"
API2="http://api2:8080"

signal_ready() {
  curl -sf -X POST "$API1/warmup-done" > /dev/null && echo "warmup: api1 ready"
  curl -sf -X POST "$API2/warmup-done" > /dev/null && echo "warmup: api2 ready"
}

nginx -g "daemon off;" &
NGINX_PID=$!
trap 'nginx -s quit; wait $NGINX_PID; exit 0' TERM INT

wait_up() {
  local name=$1 url=$2
  printf "warmup: waiting %s " "$name"
  until curl -s -o /dev/null "$url/ready"; do
    printf "."
    sleep 0.3
  done
  echo " up"
}

wait_up api1 "$API1"
wait_up api2 "$API2"

TMP=$(mktemp -d)
trap 'nginx -s quit; wait $NGINX_PID; rm -rf "$TMP"; exit 0' TERM INT
jq -c '.[]' "$PAYLOADS" | awk -v dir="$TMP" '{print > dir "/" NR ".json"}'
COUNT=$(find "$TMP" -name "*.json" | wc -l | tr -d ' ')

echo "warmup: $COUNT payloads x $ROUNDS rounds (concurrency=$CONCURRENCY) timeout=${READY_TIMEOUT}s"
START=$(date +%s%3N)

(sleep "$READY_TIMEOUT" && echo "warmup: timeout — signaling ready early" && signal_ready) &
TIMER_PID=$!

i=1
while [ "$i" -le "$ROUNDS" ]; do
  find "$TMP" -name "*.json" | xargs -P "$CONCURRENCY" -I{} \
    curl -sf -X POST "$TARGET" -H "Content-Type: application/json" -d "@{}" > /dev/null
  i=$((i + 1))
done

kill "$TIMER_PID" 2>/dev/null
rm -rf "$TMP"
ELAPSED=$(($(date +%s%3N) - START))
echo "warmup: ${COUNT} x ${ROUNDS} = $((COUNT * ROUNDS)) requests in ${ELAPSED}ms — signaling ready"
signal_ready

wait $NGINX_PID
