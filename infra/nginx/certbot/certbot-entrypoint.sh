#!/bin/sh
# Certbot sidecar (docker-compose.prod.yml, profile "certbot"): issues and renews a Let's Encrypt certificate
# with the HTTP-01 webroot challenge and installs it for nginx.
#
# Environment:
#   EDUCORE_TLS_DOMAINS   comma-separated host names, the first one names the certificate (required)
#   EDUCORE_ACME_EMAIL    account e-mail for expiry notices (required)
#   EDUCORE_ACME_STAGING  true = Let's Encrypt staging CA (untrusted test certificates) [false]
#   CERTBOT_RENEW_INTERVAL seconds between renewal checks [43200 = 12 h]
#
# Volumes: /etc/letsencrypt (certbot state), /var/www/acme (webroot shared with nginx), /tls (the files nginx
# reads: fullchain.pem and privkey.pem, owned by nginx's uid 101).
set -eu
umask 077

: "${EDUCORE_TLS_DOMAINS:?EDUCORE_TLS_DOMAINS is required for the certbot profile}"
: "${EDUCORE_ACME_EMAIL:?EDUCORE_ACME_EMAIL is required for the certbot profile}"
interval="${CERTBOT_RENEW_INTERVAL:-43200}"
primary="${EDUCORE_TLS_DOMAINS%%,*}"
live="/etc/letsencrypt/live/$primary"

install_cert() {
  cp "$live/fullchain.pem" /tls/fullchain.pem.new
  cp "$live/privkey.pem" /tls/privkey.pem.new
  chown 101:101 /tls/fullchain.pem.new /tls/privkey.pem.new
  chmod 0644 /tls/fullchain.pem.new
  chmod 0600 /tls/privkey.pem.new
  mv -f /tls/fullchain.pem.new /tls/fullchain.pem
  mv -f /tls/privkey.pem.new /tls/privkey.pem
  echo "certbot: installed certificate for $EDUCORE_TLS_DOMAINS (nginx picks it up on its next reload)"
}

# Bootstrap: nginx needs some certificate to start serving port 80 for the challenge.
if [ ! -s /tls/fullchain.pem ] || [ ! -s /tls/privkey.pem ]; then
  openssl req -x509 -nodes -newkey rsa:2048 -days 2 -subj "/CN=$primary" \
    -keyout /tls/privkey.pem -out /tls/fullchain.pem 2>/dev/null
  chown 101:101 /tls/fullchain.pem /tls/privkey.pem
  chmod 0644 /tls/fullchain.pem
  echo "certbot: wrote a temporary self-signed certificate until the real one is issued"
fi

mkdir -p /var/www/acme/.well-known/acme-challenge
chmod 0755 /var/www/acme /var/www/acme/.well-known /var/www/acme/.well-known/acme-challenge

staging=""
if [ "${EDUCORE_ACME_STAGING:-false}" = "true" ]; then
  staging="--staging"
fi

domain_args=""
old_ifs="$IFS"
IFS=','
for d in $EDUCORE_TLS_DOMAINS; do
  domain_args="$domain_args -d $d"
done
IFS="$old_ifs"

while :; do
  if [ -s "$live/fullchain.pem" ]; then
    # shellcheck disable=SC2086
    certbot renew --webroot -w /var/www/acme --non-interactive $staging --quiet || echo "certbot: renewal attempt failed" >&2
  else
    # shellcheck disable=SC2086
    certbot certonly --webroot -w /var/www/acme --non-interactive --agree-tos --email "$EDUCORE_ACME_EMAIL" \
      --cert-name "$primary" $domain_args $staging || echo "certbot: issuance failed; retrying later" >&2
  fi
  if [ -s "$live/fullchain.pem" ] && ! cmp -s "$live/fullchain.pem" /tls/fullchain.pem; then
    install_cert
  fi
  if [ -s "$live/fullchain.pem" ]; then
    sleep "$interval"
  else
    # Not issued yet (DNS not pointing here, port 80 closed, rate limit): retry after 10 minutes.
    sleep 600
  fi
done
