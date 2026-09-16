#Requires -Version 7
<#
.SYNOPSIS
  Land a bridge credential on fie-worker-1 and start its bridge.

.DESCRIPTION
  One command for the step that used to be five: upsert the bridge's variables
  in /etc/zerofomo-inbox.env on the node (sent over SSH stdin, never on a
  command line), enable + restart the systemd unit, print its status and the
  last journal lines, and mirror the token into this user's environment so the
  FIE Doctor's manifest check goes green.

  Bridge -> variables -> unit:
    discord    DISCORD_BOT_TOKEN + DISCORD_CHANNEL_MARKETS   zerofomo-discord
    telegram   TELEGRAM_BOT_TOKEN (+ TELEGRAM_DEFAULT_MARKET) zerofomo-telegram
    instagram  IG_ACCESS_TOKEN + IG_USER_ID + IG_HASHTAGS    zerofomo-instagram
    reddit     REDDIT_SUBREDDIT_MARKETS (RSS; + optional REDDIT_CLIENT_ID/SECRET) zerofomo-reddit

.EXAMPLE
  ./inbox/deploy/Set-BridgeSecret.ps1 -Bridge discord -Token $env:DISCORD_BOT_TOKEN_NEW `
      -Map '123456789012345678=us-miami,234567890123456789=bs-nassau'

.EXAMPLE
  # Only change the channel map (token already on the node).
  ./inbox/deploy/Set-BridgeSecret.ps1 -Bridge discord -Map '...=bs-nassau'

.PARAMETER Map
  Discord: "channel_id=market,..." (only listed channels are read).
  Instagram: "hashtag=market,..." (no #).
  Telegram: default market id (e.g. bs-nassau); groups set theirs with /market.
  Reddit: "subreddit=market,..." is enough (RSS mode, no credentials).
  -Token = REDDIT_CLIENT_SECRET with -ClientId = REDDIT_CLIENT_ID only if an
  approved Reddit app ever exists (app creation is approval-gated since 2026).
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][ValidateSet('discord', 'telegram', 'instagram', 'reddit')][string]$Bridge,
    [string]$Token,
    [string]$Map,
    [string]$IgUserId,
    [string]$ClientId,
    [string]$SshHost = 'fie-worker',
    [switch]$NoLocalEnv
)
$ErrorActionPreference = 'Stop'

$spec = @{
    discord   = @{ unit = 'zerofomo-discord';   token = 'DISCORD_BOT_TOKEN';     map = 'DISCORD_CHANNEL_MARKETS';  required = 'DISCORD_BOT_TOKEN' }
    telegram  = @{ unit = 'zerofomo-telegram';  token = 'TELEGRAM_BOT_TOKEN';    map = 'TELEGRAM_DEFAULT_MARKET';  required = 'TELEGRAM_BOT_TOKEN' }
    instagram = @{ unit = 'zerofomo-instagram'; token = 'IG_ACCESS_TOKEN';       map = 'IG_HASHTAGS';              required = 'IG_ACCESS_TOKEN' }
    reddit    = @{ unit = 'zerofomo-reddit';    token = 'REDDIT_CLIENT_SECRET';  map = 'REDDIT_SUBREDDIT_MARKETS'; required = 'REDDIT_SUBREDDIT_MARKETS' }
}[$Bridge]

$vars = [ordered]@{}
if ($Token) {
    $Token = $Token.Trim()
    if ($Token -match '\s') { throw "Token contains whitespace - paste it again." }
    $vars[$spec.token] = $Token
}
if ($Map) {
    $Map = $Map.Trim()
    if ($Bridge -ne 'telegram' -and $Map -notmatch '^[^=,\s]+=[a-z]{2}-[a-z0-9-]+(,[^=,\s]+=[a-z]{2}-[a-z0-9-]+)*$') {
        throw "Map must look like id=market,id=market (market ids like bs-nassau)."
    }
    $vars[$spec.map] = $Map
}
if ($IgUserId) { $vars['IG_USER_ID'] = $IgUserId.Trim() }
if ($ClientId) { $vars[@{ instagram = 'IG_USER_ID'; reddit = 'REDDIT_CLIENT_ID' }[$Bridge] ?? 'CLIENT_ID'] = $ClientId.Trim() }
if ($vars.Count -eq 0) { throw "Nothing to set: pass -Token and/or -Map." }

# Bash runs on the node from stdin; values travel inside the script body, so
# they never appear in argv or in the node's shell history.
$lines = foreach ($k in $vars.Keys) {
    $v = $vars[$k] -replace "'", "'\''"
    "upsert $k '$v'"
}
$remote = @"
set -euo pipefail
ENV=/etc/zerofomo-inbox.env
touch "`$ENV"; chmod 0600 "`$ENV"
upsert() {  # key value
  if grep -q "^`$1=" "`$ENV"; then
    tmp=`$(mktemp); grep -v "^`$1=" "`$ENV" > "`$tmp"; printf '%s=%s\n' "`$1" "`$2" >> "`$tmp"; cat "`$tmp" > "`$ENV"; rm -f "`$tmp"
  else
    printf '%s=%s\n' "`$1" "`$2" >> "`$ENV"
  fi
}
$($lines -join "`n")
if grep -q "^$($spec.required)=.\+" "`$ENV"; then
  systemctl enable --now $($spec.unit) >/dev/null 2>&1 || true
  systemctl restart $($spec.unit)
  sleep 4
  echo "unit: `$(systemctl is-active $($spec.unit))"
  journalctl -u $($spec.unit) -n 8 --no-pager -o cat || true
else
  echo "$($spec.required) still empty on the node; unit left staged"
fi
echo "keys now: `$(grep -o '^[A-Z_]*=' "`$ENV" | tr -d = | tr '\n' ' ')"
"@

$what = ($vars.Keys -join ', ')
if ($PSCmdlet.ShouldProcess("$SshHost /etc/zerofomo-inbox.env", "set $what and restart $($spec.unit)")) {
    $remote | & ssh -o BatchMode=yes $SshHost 'sudo bash -s'
    if ($LASTEXITCODE -ne 0) { throw "ssh/sudo bash exited $LASTEXITCODE" }
}

if ($Token -and -not $NoLocalEnv -and $PSCmdlet.ShouldProcess("user environment", "set $($spec.token)")) {
    [Environment]::SetEnvironmentVariable($spec.token, $Token, 'User')
    Set-Item -Path "Env:$($spec.token)" -Value $Token
    Write-Host "$($spec.token) set in the user environment (new shells see it)."
    Write-Host "Next: vault it - pwsh <AI_Workspaces>/_FIE/FIE_KeyVault.ps1 -Encrypt ; -SyncToCloud"
}
