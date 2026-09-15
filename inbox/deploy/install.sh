#!/usr/bin/env bash
# Install / update the 0 FOMO inbox on fie-worker-1. Idempotent. Run as a
# sudoer from the repo's inbox/ directory copied to the node:
#   scp -r inbox fie-worker:~/zerofomo-inbox && ssh fie-worker 'sudo bash ~/zerofomo-inbox/deploy/install.sh'
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)"

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

# Apache reverse proxy (TLS vhost is only enabled once the certificate exists).
a2enmod -q proxy proxy_http headers ssl >/dev/null
install -m 0644 "$SRC/deploy/inbox.0fomo.app.conf" /etc/apache2/sites-available/inbox.0fomo.app.conf
if [ -f /etc/letsencrypt/live/inbox.0fomo.app/fullchain.pem ]; then
  a2ensite -q inbox.0fomo.app.conf >/dev/null
  apache2ctl configtest && systemctl reload apache2
  echo "inbox.0fomo.app vhost enabled"
else
  echo "TLS cert missing: create the DNS record, then run: certbot --apache -d inbox.0fomo.app && a2ensite inbox.0fomo.app.conf && systemctl reload apache2"
fi
echo "token: $(grep INBOX_TOKEN /etc/zerofomo-inbox.env | cut -d= -f2)"
