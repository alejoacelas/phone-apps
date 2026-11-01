"""A wish list of books read from a Chrome bookmarks folder, checked whenever the packer asks.

Chrome keeps bookmarks in ~/Library/Application Support/Google/Chrome/<profile>/Bookmarks.
The folder comes from cache/settings.json ("bookmarks_folder"); without that setting, a
single folder with a plainly bookish name ("Books", "To read", "Libros"...) is used.
Everything derived from the bookmarks stays in the ignored cache/.
"""

import json
import re
from pathlib import Path

from . import sources
from .llm import ask_json

ROOT = Path(__file__).resolve().parent.parent
CHROME = Path.home() / "Library/Application Support/Google/Chrome"
SETTINGS = ROOT / "cache" / "settings.json"
RESOLVED = ROOT / "cache" / "wishlist.json"
BOOKISH = {"books", "libros", "to read", "to-read", "por leer", "reading list", "wish list", "wishlist",
           "books to read", "libros por leer", "book wishlist"}


def _folders() -> list[tuple[str, dict]]:
    """Every bookmark folder as ("Profile 1: Bookmarks bar/Books", node)."""
    found = []
    for path in sorted(CHROME.glob("*/Bookmarks")):
        roots = json.loads(path.read_text())["roots"]

        def walk(node: dict, trail: list[str]) -> None:
            if node.get("type") != "folder":
                return
            here = trail + [node["name"]]
            found.append((f"{path.parent.name}: {'/'.join(here)}", node))
            for child in node.get("children", []):
                walk(child, here)
        for root in roots.values():
            if isinstance(root, dict):
                walk(root, [])
    return found


def _count(node: dict) -> int:
    return sum(1 if c.get("type") == "url" else _count(c) for c in node.get("children", []))


def candidates() -> list[str]:
    """Folders that look meant for books, with how many bookmarks each holds."""
    words = re.compile(r"\b(books?|libros?|read|leer|lectura|reading)\b", re.I)
    return [f"{name} ({_count(node)})" for name, node in _folders()
            if words.search(name.split(": ", 1)[1].rsplit("/", 1)[-1])]


def setting() -> str | None:
    return json.loads(SETTINGS.read_text()).get("bookmarks_folder") if SETTINGS.exists() else None


def choose(folder: str) -> None:
    SETTINGS.parent.mkdir(exist_ok=True)
    data = json.loads(SETTINGS.read_text()) if SETTINGS.exists() else {}
    data["bookmarks_folder"] = folder
    SETTINGS.write_text(json.dumps(data, indent=1))


def folder() -> tuple[str, dict] | None:
    """The wish-list folder: the setting (a folder name or the end of its path), else the one bookish folder."""
    folders = _folders()
    wanted = setting()
    if wanted:
        w = wanted.lower().strip("/")
        hits = [f for f in folders if f[0].lower().endswith("/" + w) or f[0].lower().endswith(": " + w)]
    else:
        hits = [f for f in folders if f[0].rsplit("/", 1)[-1].lower() in BOOKISH]
    return hits[0] if len(hits) == 1 else None


def _bookmarks(node: dict) -> list[dict]:
    out = []
    for child in node.get("children", []):
        if child.get("type") == "url":
            out.append({"url": child["url"], "name": child.get("name", "")})
        else:
            out += _bookmarks(child)
    return out


RESOLVE_PROMPT = """These are bookmarks from a folder of books someone wants to read. For each,
say whether it points at one specific book (a store page, Goodreads, Wikipedia article on
a book, publisher page) rather than a list of books, an article or anything else. For a
book give its usual English title (or original title if it is usually known by that) and
its author's name as commonly written.

Reply with JSON only:
{{"books": [{{"n": 0, "is_book": true, "title": "", "author": ""}}]}}

{lines}
"""


def _words(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+", text.lower())) - sources.STOPWORDS


def _on_gutenberg(title: str, author: str) -> int | None:
    """The most downloaded Gutenberg book matching this title and author, if any."""
    surname = author.split()[-1].lower() if author else ""
    short = re.split(r"[:;(,]", title)[0]
    for book in sources.search(f"{short} {surname}".strip(), limit=5):
        if surname and surname not in book.author.lower() and surname not in book.title.lower():
            continue
        wanted = _words(short)
        if wanted and len(wanted & _words(book.title)) >= 0.8 * len(wanted):
            return book.gutenberg_id
    return None


def entries(log=print) -> list[dict]:
    """Read the folder now and resolve new bookmarks (cached by URL in cache/wishlist.json)."""
    chosen = folder()
    if chosen is None:
        return []
    resolved = json.loads(RESOLVED.read_text()) if RESOLVED.exists() else {}
    marks = _bookmarks(chosen[1])
    new = [m for m in marks if m["url"] not in resolved]
    if new:
        log(f"  checking {len(new)} new bookmarks")
        for i in range(0, len(new), 60):
            batch = new[i:i + 60]
            lines = "\n".join(f"{n}: {m['name']} | {m['url'][:150]}" for n, m in enumerate(batch))
            reply = ask_json(RESOLVE_PROMPT.format(lines=lines), max_tokens=8000, fast=True)
            by_n = {b.get("n"): b for b in reply.get("books", []) if isinstance(b, dict)}
            for n, m in enumerate(batch):
                b = by_n.get(n, {})
                direct = re.search(r"gutenberg\.org/(?:ebooks|cache/epub)/(\d+)", m["url"])
                entry = {"name": m["name"], "is_book": bool(b.get("is_book")) or bool(direct),
                         "title": b.get("title") or m["name"], "author": b.get("author") or ""}
                entry["gutenberg_id"] = int(direct[1]) if direct else (
                    _on_gutenberg(entry["title"], entry["author"]) if entry["is_book"] else None)
                resolved[m["url"]] = entry
        RESOLVED.write_text(json.dumps(resolved, ensure_ascii=False, indent=1))
    return [{"url": m["url"], **resolved[m["url"]]} for m in marks]
