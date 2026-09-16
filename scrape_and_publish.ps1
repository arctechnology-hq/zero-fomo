# =============================================================================
# scrape_and_publish.ps1 — daily residential-IP scrape for the 0 FOMO app
#
#   1. Nassau pipeline (workbook + New_Providence_Events.json) unless -MarketsOnly
#   2. Every market in markets/*.json -> feeds/<id>/events.json + feeds/markets.json
#   3. Local device-testing copy -> android-dev\feed\events.json
#   4. feeds/ pushed as a single-commit orphan branch `feed-data`; the Pages
#      workflow merges it per market (merge_feeds.py). This exists because
#      Eventbrite refuses GitHub's runner IPs (HTTP 405) but serves this
#      machine, so the residential run is where most non-API events come from.
#
# Scheduled task "ZeroFomoFeedScrape" (daily 06:00):  -Register creates it.
# Run manually any time:  pwsh -NoProfile -File scrape_and_publish.ps1 [-MarketsOnly] [-NoPublish]
# =============================================================================
param(
    [switch]$MarketsOnly,   # skip the Nassau workbook run; bs-nassau runs as a market
    [switch]$NoPublish,     # build feeds/ but do not push the feed-data branch
    [switch]$Register       # (re)create the daily scheduled task and exit
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if ($Register) {
    $pwsh = (Get-Command pwsh).Source
    $action = New-ScheduledTaskAction -Execute $pwsh `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $trigger = New-ScheduledTaskTrigger -Daily -At 06:00
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
        -ExecutionTimeLimit (New-TimeSpan -Hours 2) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName "ZeroFomoFeedScrape" -Action $action `
        -Trigger $trigger -Settings $settings -Force `
        -Description "0 FOMO: daily scrape + per-market feeds -> feed-data branch" | Out-Null
    Write-Host "Registered scheduled task ZeroFomoFeedScrape (daily 06:00) -> $PSCommandPath"
    return
}

$log = Join-Path $here "scrape_and_publish.log"
"=== Scrape started $(Get-Date -Format o) ===" | Add-Content $log

try {
    $feedsDir = Join-Path $here "feeds"

    # 1. Nassau: the strict, workbook-producing run (floor enforced).
    if (-not $MarketsOnly) {
        python "$here\comprehensive_bahamas_scraper.py" --delay 1.5 2>&1 | Add-Content $log
        if ($LASTEXITCODE -ne 0) {
            throw "Scraper exit $LASTEXITCODE (floor breach or hard failure) - see $log"
        }
        $feedSrc = Join-Path $here "New_Providence_Events.json"
        if (-not (Test-Path $feedSrc)) { throw "Feed JSON not produced" }

        New-Item -ItemType Directory -Force (Join-Path $feedsDir "bs-nassau") | Out-Null
        Copy-Item $feedSrc (Join-Path $feedsDir "bs-nassau\events.json") -Force

        $localFeed = "C:\Users\rhanrichardson\android-dev\feed"
        New-Item -ItemType Directory -Force $localFeed | Out-Null
        Copy-Item $feedSrc (Join-Path $localFeed "events.json") -Force
        "Published to $localFeed\events.json" | Add-Content $log
    }

    # 1b. Community inbox: pull new submissions from the node, extract with the
    #     FIE tiers, auto-approve the confident ones. Best effort — the node
    #     being unreachable must not block the scrape. Approved rows feed the
    #     per-market `community` source below.
    try {
        New-Item -ItemType Directory -Force (Join-Path $here "inbox\data") | Out-Null
        # scp -r copies the whole tree; existing extracted/review files on this side
        # are preserved because only submission folders live on the node.
        & scp -q -r -o BatchMode=yes "fie-worker:/var/lib/zerofomo-inbox/*" (Join-Path $here "inbox\data\") 2>&1 |
            Add-Content $log
        python "$here\inbox\extract.py" 2>&1 | Add-Content $log
        python "$here\inbox\review.py" auto 2>&1 | Add-Content $log
        # Retention (site/privacy.html promises <= 90 days): reviewed copies here,
        # and raw submission folders on the node once they are older than 90 days
        # (by then they have been pulled and reviewed on this side).
        python "$here\inbox\review.py" purge --days 90 2>&1 | Add-Content $log
        & ssh -o BatchMode=yes fie-worker 'sudo find /var/lib/zerofomo-inbox -mindepth 2 -maxdepth 2 -type d -mtime +90 -exec rm -rf {} +' 2>&1 |
            Add-Content $log
    } catch {
        "inbox step skipped: $_" | Add-Content $log
    }

    # 2. Every other market, best effort: one broken city never blocks the rest.
    $markets = @(python "$here\comprehensive_bahamas_scraper.py" --list-markets)
    $failed = @()
    foreach ($m in $markets) {
        if ($m -eq "bs-nassau" -and -not $MarketsOnly) { continue }
        "--- market $m ---" | Add-Content $log
        python "$here\comprehensive_bahamas_scraper.py" --market $m --delay 1.5 --no-details 2>&1 |
            Add-Content $log
        if ($LASTEXITCODE -ne 0) { $failed += $m }
    }
    python "$here\build_markets_manifest.py" 2>&1 | Add-Content $log
    if ($failed.Count) { "Markets below floor / failed: $($failed -join ', ')" | Add-Content $log }

    # 3. Publish feeds/ as the single-commit orphan branch `feed-data`.
    #    Plumbing only: the working tree and main's index are never touched.
    if (-not $NoPublish) {
        $tmpIndex = Join-Path $env:TEMP "zerofomo-feed-data.index"
        Remove-Item $tmpIndex -ErrorAction SilentlyContinue
        $env:GIT_INDEX_FILE = $tmpIndex
        try {
            git add -f feeds
            $tree = (git write-tree).Trim()
        } finally {
            Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
            Remove-Item $tmpIndex -ErrorAction SilentlyContinue
        }
        $msg = "feeds: $(Get-Date -Format s) from $env:COMPUTERNAME"
        $commit = (git commit-tree $tree -m $msg).Trim()
        git push -q -f origin "${commit}:refs/heads/feed-data"
        if ($LASTEXITCODE -ne 0) { throw "git push feed-data failed ($LASTEXITCODE)" }
        "Pushed feed-data $commit" | Add-Content $log
    }

    "=== Scrape finished OK $(Get-Date -Format o) ===" | Add-Content $log
} catch {
    "=== Scrape FAILED: $_ ===" | Add-Content $log
    # Push a phone alert when FIE notify infra is present (status text only).
    $notify = "C:\Users\rhanrichardson\OneDrive - National Health Insurance Authority\Documents\AI_Workspaces\_FIE\Notify.ps1"
    if (Test-Path $notify) {
        try { & $notify -Title "0 FOMO scrape FAILED" -Message "$_" -Priority high } catch {}
    }
    throw
}
