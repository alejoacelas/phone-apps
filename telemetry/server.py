"""Receives the phone apps' telemetry and appends it to one JSON Lines file per day.

Runs on the agent server behind Tailscale Serve (tailnet only). POST a body of JSON lines to
any path; GET /health answers "ok". Files go to $TELEMETRY_DIR (default
~/.local/share/reader-telemetry/YYYY-MM-DD.jsonl).
"""

import json
import os
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock

DIR = Path(os.environ.get("TELEMETRY_DIR", Path.home() / ".local/share/reader-telemetry"))
PORT = int(os.environ.get("PORT", "8774"))
MAX_BODY = 20 * 1024 * 1024
lock = Lock()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self._reply(200, "ok\n")

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not 0 < length <= MAX_BODY:
            return self._reply(413 if length else 400, "bad length\n")
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        received = int(time.time() * 1000)
        lines, bad = [], 0
        for raw in body.splitlines():
            if not raw.strip():
                continue
            try:
                event = json.loads(raw)
            except json.JSONDecodeError:
                bad += 1
                event = {"type": "unparsable", "raw": raw[:2000]}
            event["received"] = received
            lines.append(json.dumps(event, ensure_ascii=False))
        DIR.mkdir(parents=True, exist_ok=True)
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        with lock, open(DIR / f"{day}.jsonl", "a", encoding="utf-8") as f:
            f.write("".join(line + "\n" for line in lines))
        self._reply(200, json.dumps({"stored": len(lines), "unparsable": bad}) + "\n")

    def _reply(self, code, text):
        data = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
