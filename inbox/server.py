#!/usr/bin/env python3
"""0 FOMO inbox — the "Share to 0 FOMO" receiver (docs/GLOBAL_DESIGN.md §3).

Standard library only so it runs on the OCI Ampere node (or anywhere) with no
pip install. Stores each submission as a folder under DATA_DIR:

    <market>/<submission_id>/submission.json   what the app sent (text, url, hint)
    <market>/<submission_id>/image.<ext>       the shared image, if any

Extraction and review happen elsewhere (inbox/extract.py, inbox/review.py);
this process only receives, validates, rate-limits and stores.

    POST /submit      JSON body, see MAX_* limits below
    GET  /health      {"ok": true, "pending": n}

Env:  INBOX_DATA_DIR (default ./data)   INBOX_PORT (default 8787)
      INBOX_TOKEN (optional shared secret; app sends X-Inbox-Token)
"""
from __future__ import annotations

import base64
import json
import os
import re
import secrets
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA_DIR = os.environ.get("INBOX_DATA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
PORT = int(os.environ.get("INBOX_PORT", "8787"))
TOKEN = os.environ.get("INBOX_TOKEN", "").strip()

MAX_BODY = 6 * 1024 * 1024          # 6 MB: a phone photo re-encoded by the app
MAX_TEXT = 8000
MAX_PER_DEVICE_PER_HOUR = 30
MARKET_RX = re.compile(r"^[a-z]{2}-[a-z0-9-]{2,40}$")
KINDS = {"text", "url", "image"}
IMAGE_EXT = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}

_rate_lock = threading.Lock()
_rate: dict[str, list[float]] = {}


def _rate_ok(device: str) -> bool:
    now = time.time()
    with _rate_lock:
        hits = [t for t in _rate.get(device, []) if now - t < 3600]
        if len(hits) >= MAX_PER_DEVICE_PER_HOUR:
            _rate[device] = hits
            return False
        hits.append(now)
        _rate[device] = hits
        return True


def _pending_count() -> int:
    n = 0
    if not os.path.isdir(DATA_DIR):
        return 0
    for market in os.listdir(DATA_DIR):
        mdir = os.path.join(DATA_DIR, market)
        if not os.path.isdir(mdir):
            continue
        for sid in os.listdir(mdir):
            sdir = os.path.join(mdir, sid)
            if os.path.isdir(sdir) and not os.path.exists(os.path.join(sdir, "extracted.json")):
                n += 1
    return n


class Handler(BaseHTTPRequestHandler):
    server_version = "ZeroFomoInbox/1.0"

    def log_message(self, fmt, *args):  # quieter default log line
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("", "/health"):
            self._send(200, {"ok": True, "pending": _pending_count()})
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path.rstrip("/") != "/submit":
            self._send(404, {"ok": False, "error": "not found"})
            return
        if TOKEN and self.headers.get("X-Inbox-Token", "") != TOKEN:
            self._send(401, {"ok": False, "error": "bad token"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            self._send(413, {"ok": False, "error": "body size"})
            return
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._send(400, {"ok": False, "error": "invalid json"})
            return

        market = str(body.get("market") or "").lower()
        kind = str(body.get("kind") or "").lower()
        device = str(body.get("device") or "")[:64]
        text = str(body.get("text") or "")[:MAX_TEXT]
        url = str(body.get("url") or "")[:2000]
        hint = str(body.get("source_hint") or "")[:120]
        image_b64 = body.get("image_base64")
        image_type = str(body.get("image_type") or "")

        if not MARKET_RX.match(market) or kind not in KINDS or not device:
            self._send(400, {"ok": False, "error": "market, kind and device are required"})
            return
        if kind == "image" and (not image_b64 or image_type not in IMAGE_EXT):
            self._send(400, {"ok": False, "error": "image_base64 + image_type (jpeg/png/webp) required"})
            return
        if kind in ("text", "url") and not (text or url):
            self._send(400, {"ok": False, "error": "text or url required"})
            return
        if not _rate_ok(device):
            self._send(429, {"ok": False, "error": "slow down"})
            return

        sid = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4)
        sdir = os.path.join(DATA_DIR, market, sid)
        os.makedirs(sdir, exist_ok=True)
        record = {
            "id": sid, "market": market, "kind": kind, "device": device,
            "received_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "text": text, "url": url, "source_hint": hint,
            "app_version": str(body.get("app_version") or "")[:32],
        }
        if kind == "image":
            try:
                raw = base64.b64decode(image_b64, validate=True)
            except (ValueError, TypeError):
                self._send(400, {"ok": False, "error": "image_base64 not valid base64"})
                return
            fn = f"image.{IMAGE_EXT[image_type]}"
            with open(os.path.join(sdir, fn), "wb") as fh:
                fh.write(raw)
            record["image"] = fn
        with open(os.path.join(sdir, "submission.json"), "w", encoding="utf-8") as fh:
            json.dump(record, fh, ensure_ascii=False, indent=1)
        self._send(202, {"ok": True, "id": sid, "status": "queued"})


def main() -> int:
    os.makedirs(DATA_DIR, exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"0 FOMO inbox listening on :{PORT}, data at {DATA_DIR}, token {'set' if TOKEN else 'NOT set'}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
