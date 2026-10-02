#!/bin/sh
# Start command of the production nginx (docker-compose.prod.yml).
#   1. Wait until a certificate and key exist in /etc/nginx/tls (mounted host directory, or the volume the
#      certbot sidecar fills; the sidecar writes a temporary self-signed pair first).
#   2. Reload nginx every EDGE_RELOAD_INTERVAL seconds [21600 = 6 h] so renewed certificates are picked up.
#   3. Run nginx in the foreground as PID 1's child process (uid 101).
set -eu

cert=/etc/nginx/tls/fullchain.pem
key=/etc/nginx/tls/privkey.pem
wait_seconds="${EDGE_CERT_WAIT_SECONDS:-300}"
interval="${EDGE_RELOAD_INTERVAL:-21600}"

waited=0
while [ ! -s "$cert" ] || [ ! -s "$key" ]; do
  if [ "$waited" -ge "$wait_seconds" ]; then
    echo "edge: no certificate in /etc/nginx/tls after ${wait_seconds}s (expected fullchain.pem and privkey.pem; see docs/ops/TLS.md)" >&2
    exit 1
  fi
  if [ "$waited" -eq 0 ]; then
    echo "edge: waiting for /etc/nginx/tls/fullchain.pem and privkey.pem"
  fi
  sleep 2
  waited=$((waited + 2))
done

nginx -t

(
  while :; do
    sleep "$interval"
    nginx -s reload && echo "edge: reloaded (certificate refresh)"
  done
) &

exec nginx -g 'daemon off;'
