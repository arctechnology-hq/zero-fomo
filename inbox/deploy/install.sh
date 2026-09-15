#!/usr/bin/env bash
# Install / update the 0 FOMO inbox on fie-worker-1. Idempotent. Run as a
# sudoer from the repo's inbox/ directory copied to the node:
#   tar --exclude=inbox/data -czf - inbox | ssh fie-worker 'rm -rf ~/zerofomo-inbox; mkdir -p ~/zerofomo-inbox; tar -xzf - -C ~/zerofomo-inbox --strip-components=1; sudo bash ~/zerofomo-inbox/deploy/install.sh'
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)"
HOST=inbox.0fomo.app

id -u zerofomo >/dev/null 2>&1 || useradd --system --home /var/lib/zerofomo-inbox --shell /usr/sbin/nologin zerofomo
install -d -o zerofomo -g zerofomo -m 0750 /var/lib/zerofomo-inbox
install -d -m 0755 /opt/zerofomo-inbox
install -m 0644 "$SRC/server.py" /opt/zerofomo-inbox/server.py
[ -f /etc/zerofomo-inbox.env ] || { echo "INBOX_TOKEN=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' )" > /etc/zerofomo-inbox.env; chmod 0600 /etc/zerofomo-inbox.env; }
install -m 0644 "$SRC/deploy/zerofomo-inbox.service" /etc/systemd/system/zerofomo-inbox.service
systemctl daemon-reload
systemctl enable --now zerofomo-inbox
systemctl restart zerofomo-inbox
sleep 1
curl -fsS http://127.0.0.1:8787/health && echo

# Apache: the port-80 vhost (ACME + redirect) is always safe to enable; the
# TLS vhost only once the certificate exists. Never reload on a failed test.
a2enmod -q proxy proxy_http headers ssl >/dev/null
install -d -m 0755 /var/www/acme/.well-known/acme-challenge
install -m 0644 "$SRC/deploy/$HOST-http.conf" "/etc/apache2/sites-available/$HOST-http.conf"
install -m 0644 "$SRC/deploy/$HOST.conf" "/etc/apache2/sites-available/$HOST.conf"
a2ensite -q "$HOST-http.conf" >/dev/null
if [ -f "/etc/letsencrypt/live/$HOST/fullchain.pem" ]; then
  a2ensite -q "$HOST.conf" >/dev/null
else
  a2dissite -q "$HOST.conf" >/dev/null 2>&1 || true
  echo "TLS cert missing. After the DNS record resolves here, run:"
  echo "  certbot certonly --webroot -w /var/www/acme -d $HOST --non-interactive --agree-tos --register-unsafely-without-email"
  echo "  a2ensite $HOST.conf && apache2ctl configtest && systemctl reload apache2"
fi
if apache2ctl configtest >/dev/null 2>&1; then
  systemctl reload apache2
  echo "apache reloaded"
else
  apache2ctl configtest || true
  echo "apache config test FAILED - not reloaded" >&2
  exit 1
fi
echo "token: $(grep INBOX_TOKEN /etc/zerofomo-inbox.env | cut -d= -f2)"
