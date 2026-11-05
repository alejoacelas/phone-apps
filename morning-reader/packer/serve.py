"""Book requests from the app: a queue, a worker that builds packs, and an HTTP API.

Runs on the agent server behind Tailscale Serve at /books (tailnet only). The app posts a
request (any description of a book), the worker finds it on Gutenberg or Standard Ebooks,
else as an EPUB on LibGen, builds a pack, and every device downloads it from /packs.

  POST /requests        {"id", "query", "device"} -> the request (idempotent on id)
  GET  /requests        every request, newest first
  GET  /packs           [{"id", "title", "author", "bytes", "updated"}]
  GET  /packs/<id>.json the pack

Requests are kept in cache/requests.json; packs are written to packs/.
"""

import json
import os
import re
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import libgen, sources, wishlist
from .llm import Usage, ask_json

ROOT = Path(__file__).resolve().parent.parent
PACKS = ROOT / "packs"
STATE = ROOT / "cache" / "requests.json"
DOWNLOADS = ROOT / "cache" / "libgen"
PORT = int(os.environ.get("PORT", "8775"))
STATUSES = {"queued", "building", "done", "not_found", "failed"}

lock = threading.Lock()
wake = threading.Event()


def now() -> int:
    return int(time.time() * 1000)


def load() -> list[dict]:
    return json.loads(STATE.read_text()) if STATE.exists() else []


def save(requests: list[dict]) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(requests, ensure_ascii=False, indent=1))
    tmp.replace(STATE)


def update(request_id: str, **fields) -> None:
    with lock:
        requests = load()
        for r in requests:
            if r["id"] == request_id:
                r.update(fields, updated=now())
        save(requests)


def submit(request_id: str, query: str, device: str) -> dict:
    with lock:
        requests = load()
        existing = next((r for r in requests if r["id"] == request_id), None)
        if existing:
            return existing
        request = {"id": request_id, "query": query, "device": device, "status": "queued", "title": None,
                   "author": None, "source": None, "pack_id": None, "message": None,
                   "created": now(), "updated": now()}
        requests.append(request)
        save(requests)
    wake.set()
    return request


INTERPRET_PROMPT = """A reader typed this request for a book they want to read:

{query}

Work out which book they mean. Return JSON:
{{"title": "the book's usual title in the language they want to read it in",
  "author": "the author's full name, or empty if unknown",
  "language": "ISO 639-1 code of the language they want to read it in: the language the request is written in, unless it names another",
  "search": "a short LibGen search query: key title words and the author's surname",
  "is_book": true or false (false if the request isn't a book at all)}}"""

CHOOSE_PROMPT = """A reader asked for: {query}
We take this to mean "{title}" by {author}, in language "{language}".

These EPUB files are on LibGen. Pick the files that are this book, complete, in that
language (prefer a published edition over a sample, summary, study guide or abridgement;
prefer a mid-sized file over a tiny one). Return JSON:
{{"picks": [indexes of matching files, best first, at most 3; empty if none match]}}

{lines}"""


def build(request: dict) -> None:
    from .__main__ import make_pack  # the CLI module holds the pack builder

    wanted = ask_json(INTERPRET_PROMPT.format(query=request["query"]), max_tokens=1000, fast=True)
    if not wanted.get("is_book", True):
        update(request["id"], status="not_found", message="That doesn't look like a book.")
        return
    title, author = wanted.get("title") or request["query"], wanted.get("author") or ""
    update(request["id"], title=title, author=author or None)

    gutenberg_id = wishlist._on_gutenberg(title, author)
    if gutenberg_id:
        path = PACKS / f"gutenberg-{gutenberg_id}.json"
        if not path.exists():
            epub, edition = sources.download(sources.by_id(gutenberg_id))
            make_pack(sources.extract(epub), path.stem, "Standard Ebooks" if edition == "se" else "Project Gutenberg",
                      f"https://www.gutenberg.org/ebooks/{gutenberg_id}")
        update(request["id"], status="done", pack_id=path.stem, source=json.loads(path.read_text())["edition"])
        return

    files = libgen.search(wanted.get("search") or f"{title} {author}")[:40]
    if not files and wanted.get("search"):
        files = libgen.search(f"{title} {author}".strip())[:40]
    if not files:
        update(request["id"], status="not_found",
               message="Not on Project Gutenberg, and LibGen has no EPUB of it.")
        return
    lines = "\n".join(f"{n}: {f['title']} | {f['author']} | {f['year']} | {f['language']} | "
                      f"{f['bytes'] // 1024} KB | {f['filename']}" for n, f in enumerate(files))
    picks = ask_json(CHOOSE_PROMPT.format(query=request["query"], title=title, author=author or "unknown",
                                          language=wanted.get("language") or "en", lines=lines),
                     max_tokens=1000, fast=True).get("picks") or []
    picks = [files[i] for i in picks if isinstance(i, int) and 0 <= i < len(files)]
    if not picks:
        update(request["id"], status="not_found", message="LibGen has EPUBs, but none matched this book.")
        return
    errors = []
    for pick in picks:
        pack_id = f"libgen-{pick['md5'][:12]}"
        if (PACKS / f"{pack_id}.json").exists():
            update(request["id"], status="done", pack_id=pack_id, source="LibGen")
            return
        try:
            epub = libgen.download(pick["md5"], DOWNLOADS / f"{pick['md5']}.epub")
            text = sources.extract(epub)
        except Exception as err:  # a bad file or a failed download: try the next pick
            errors.append(str(err))
            continue
        if sum(len(p.split()) for p in text["paragraphs"]) < 1000:
            errors.append(f"{pick['md5']} has almost no text")
            continue
        text["title"] = text["title"] or title
        text["author"] = text["author"] or author
        make_pack(text, pack_id, "EPUB from LibGen", "")
        update(request["id"], status="done", pack_id=pack_id, source="LibGen")
        return
    update(request["id"], status="failed", message="Couldn't download a usable EPUB: " + "; ".join(errors)[:300])


def worker() -> None:
    with lock:  # a build interrupted by a restart starts again
        requests = load()
        for r in requests:
            if r["status"] == "building":
                r["status"] = "queued"
        save(requests)
    while True:
        queued = [r for r in load() if r["status"] == "queued"]
        if not queued:
            wake.wait(60)
            wake.clear()
            continue
        request = min(queued, key=lambda r: r["created"])
        update(request["id"], status="building")
        cost_before = Usage.cost
        print(f"Request {request['id']}: {request['query']!r}", flush=True)
        try:
            build(request)
        except Exception as err:
            traceback.print_exc()
            update(request["id"], status="failed", message=f"{type(err).__name__}: {err}"[:300])
        print(f"  Claude cost ${Usage.cost - cost_before:.3f}", flush=True)


def packs_index() -> list[dict]:
    out = []
    for path in sorted(PACKS.glob("*.json")):
        try:
            pack = json.loads(path.read_text())
        except (OSError, ValueError):
            continue
        out.append({"id": path.stem, "title": pack.get("title"), "author": pack.get("author"),
                    "bytes": path.stat().st_size, "updated": int(path.stat().st_mtime * 1000)})
    return out


class Handler(BaseHTTPRequestHandler):
    def _path(self) -> str:
        path = self.path.split("?", 1)[0]
        return path.removeprefix("/books") or "/"

    def do_GET(self):
        path = self._path()
        if path in ("/", "/health"):
            return self._send(200, b"ok\n", "text/plain; charset=utf-8")
        if path == "/requests":
            return self._json(sorted(load(), key=lambda r: r["created"], reverse=True))
        if path == "/packs":
            return self._json(packs_index())
        match = re.fullmatch(r"/packs/([A-Za-z0-9._-]+)\.json", path)
        if match and (PACKS / f"{match[1]}.json").is_file():
            return self._send(200, (PACKS / f"{match[1]}.json").read_bytes(), "application/json")
        self._send(404, b"not found\n", "text/plain; charset=utf-8")

    def do_POST(self):
        if self._path() != "/requests":
            return self._send(404, b"not found\n", "text/plain; charset=utf-8")
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
            request_id, query = str(body["id"]).strip(), str(body["query"]).strip()
        except (ValueError, KeyError, TypeError):
            return self._send(400, b"expected JSON with id and query\n", "text/plain; charset=utf-8")
        if not request_id or not query or len(query) > 500 or len(request_id) > 100:
            return self._send(400, b"id and query must be non-empty and short\n", "text/plain; charset=utf-8")
        self._json(submit(request_id, query, str(body.get("device") or "")[:20]))

    def _json(self, value) -> None:
        self._send(200, json.dumps(value, ensure_ascii=False).encode(), "application/json")

    def _send(self, code: int, data: bytes, kind: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def serve() -> None:
    threading.Thread(target=worker, daemon=True).start()
    print(f"Serving book requests on 127.0.0.1:{PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
