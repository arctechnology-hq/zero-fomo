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
install -m 0644 "$SRC/telegram_bridge.py" /opt/zerofomo-inbox/telegram_bridge.py
install -m 0644 "$SRC/deploy/zerofomo-telegram.service" /etc/systemd/system/zerofomo-telegram.service
install -m 0644 "$SRC/discord_bridge.py" /opt/zerofomo-inbox/discord_bridge.py
install -m 0644 "$SRC/deploy/zerofomo-discord.service" /etc/systemd/system/zerofomo-discord.service
install -m 0644 "$SRC/instagram_bridge.py" /opt/zerofomo-inbox/instagram_bridge.py
install -m 0644 "$SRC/deploy/zerofomo-instagram.service" /etc/systemd/system/zerofomo-instagram.service
install -m 0644 "$SRC/reddit_bridge.py" /opt/zerofomo-inbox/reddit_bridge.py
install -m 0644 "$SRC/deploy/zerofomo-reddit.service" /etc/systemd/system/zerofomo-reddit.service
systemctl daemon-reload
# Each bridge only starts once its credentials are in /etc/zerofomo-inbox.env.
stage() {  # $1 unit, $2 required env var, $3 hint
  if grep -q "^$2=.\+" /etc/zerofomo-inbox.env 2>/dev/null; then
    systemctl enable --now "$1" >/dev/null 2>&1; systemctl restart "$1"; echo "$1: running"
  else
    systemctl disable --now "$1" >/dev/null 2>&1 || true
    echo "$1 staged; add $2=... ($3) to /etc/zerofomo-inbox.env, then: systemctl enable --now $1"
  fi
}
stage zerofomo-telegram  TELEGRAM_BOT_TOKEN "BotFather token"
stage zerofomo-discord   DISCORD_BOT_TOKEN  "Discord bot token + DISCORD_CHANNEL_MARKETS=channel_id=market,..."
stage zerofomo-instagram IG_ACCESS_TOKEN    "long-lived token + IG_USER_ID + IG_HASHTAGS=tag=market,..."
stage zerofomo-reddit    REDDIT_SUBREDDIT_MARKETS "subreddit=market,... (RSS mode, no creds; REDDIT_CLIENT_ID/SECRET optional for OAuth JSON)"
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
  # Port 80 is closed on fie-worker-1 (host firewall + OCI security list only
  # pass 443), so HTTP-01 through Cloudflare returns 522. Validate over DNS with
  # a token scoped to the 0fomo.app zone (/root/.secrets/cloudflare-0fomo.ini,
  # "dns_cloudflare_api_token = ..."). Let's Encrypt caches NXDOMAIN for the
  # zone's negative TTL, so keep the propagation wait generous.
  echo "TLS cert missing. With the 0fomo.app token in /root/.secrets/cloudflare-0fomo.ini, run:"
  echo "  certbot certonly --dns-cloudflare --dns-cloudflare-credentials /root/.secrets/cloudflare-0fomo.ini \\"
  echo "    --dns-cloudflare-propagation-seconds 120 -d $HOST --non-interactive --agree-tos --register-unsafely-without-email"
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
