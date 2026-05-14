#!/bin/sh

API1="http://api-1:8080"
API2="http://api-2:8080"

nginx -g "daemon off;" &
NGINX_PID=$!
trap 'nginx -s quit; wait $NGINX_PID; exit 0' TERM INT

wait_up() {
  local name=$1 url=$2
  printf "waiting %s " "$name"
  until curl -sf "$url/ready" > /dev/null; do
    printf "."
    sleep 0.3
  done
  echo " up"
}

wait_up api-1 "$API1"
wait_up api-2 "$API2"
echo "all instances ready"

wait $NGINX_PID
