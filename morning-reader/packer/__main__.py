"""Command line for building and pushing book packs.

  ./pack add "voyage of the beagle"   search Gutenberg, build the top match, push it
  ./pack add 944                      same, by Gutenberg id
  ./pack file BOOK.epub [BOOK.pdf ...] build packs from your own EPUBs or text PDFs and push them
  ./pack send FILE [FILE ...]         copy any file into the Boox library for its own reader
  ./pack criticism gutenberg-1342 F.json  build a criticism pack linked to a built book and push it
  ./pack search "wealth of nations"   list matches without building
  ./pack starter                      build and push the starter shelf
  ./pack push                         push every built pack to the phone
  ./pack annotate [ID ...]            add character notes and standalone flags to built packs
  ./pack sources [ID ...]             find secondary material from trusted sources for built packs
  ./pack wishlist                     list the wish list from a Chrome bookmarks folder
  ./pack wishlist build [--limit 3]   build and push wish-list books found on Gutenberg
  ./pack wishlist folders             show bookmark folders that look meant for books
  ./pack wishlist folder NAME         use this bookmarks folder for the wish list
  ./pack opml FILE                    push a feed list (OPML) for the app to import
  ./pack pull                         copy notes, Ask answers and shortfalls from the phone
  ./pack serve                        take book requests from the app and build them (agent server)

Add --boox to any command that pushes or pulls to use the Boox instead of the phone.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from . import annotate, pdf, secondary, segment, sources, wishlist
from .llm import Usage

ROOT = Path(__file__).resolve().parent.parent
PACKS = ROOT / "packs"
PACKAGE = "com.alejoacelas.morningreader"
PHONE_DIR = f"/sdcard/Android/data/{PACKAGE}/files"
ADB_PHONE = Path.home() / "best/stash/dotfiles/bin/adb-phone"
BOOX_SERIAL = "ec27fd9"
BOOX_LIBRARY = "/sdcard/Books"

# The first shelf, hand-picked from the user's to-read list. How to suggest further
# books is an open question (see DECISIONS.md).
STARTER = {
    145: "Middlemarch",
    10378: "Autobiography of John Stuart Mill",
    13507: "Cuentos de amor de locura y de muerte",
    72665: "Romancero gitano",
    84: "Frankenstein",
    1399: "Anna Karenina",
    7849: "The Trial",
    863: "The Mysterious Affair at Styles",
}
LANGUAGE_CODES = {}  # filled below: "Spanish" -> "es"
LANGUAGES = {"en": "English", "es": "Spanish", "fr": "French", "de": "German", "it": "Italian", "pt": "Portuguese"}
LANGUAGE_CODES.update({name: code for code, name in LANGUAGES.items()})
LANGUAGES.update({"eng": "English", "spa": "Spanish", "fra": "French", "deu": "German", "ita": "Italian",
                  "por": "Portuguese"})


def build(query: str) -> Path:
    matches = sources.search(query)
    if not matches:
        sys.exit(f"No Gutenberg match for {query!r}")
    book = matches[0]
    epub, edition = sources.download(book)
    return make_pack(sources.extract(epub), f"gutenberg-{book.gutenberg_id}",
                     "Standard Ebooks" if edition == "se" else "Project Gutenberg",
                     f"https://www.gutenberg.org/ebooks/{book.gutenberg_id}")


def build_file(path: Path) -> Path:
    """Build a pack from the reader's own EPUB or text PDF."""
    kind = path.suffix.lower()
    if kind not in (".epub", ".pdf"):
        sys.exit(f"{path.name}: only EPUB and PDF books can become packs; use `./pack send` for other files")
    text = sources.extract(path) if kind == ".epub" else pdf.extract(path)
    if sum(len(p.split()) for p in text["paragraphs"]) < 1000:
        sys.exit(f"{path.name}: almost no text found; a scanned PDF needs OCR first, or use `./pack send`")
    slug = re.sub(r"[^a-z0-9]+", "-", text["title"].lower()).strip("-")[:60]
    return make_pack(text, f"file-{slug}", kind[1:].upper(), "")


def build_criticism(primary_id: str, path: Path) -> Path:
    """Build a pack of criticism from {'title', 'author', 'language', 'paragraphs'}, linked to its book."""
    if not (PACKS / f"{primary_id}.json").exists():
        sys.exit(f"No built pack {primary_id}; companion_to must name one in packs/")
    return make_pack(json.loads(path.read_text()), f"criticism-{primary_id}", "Public-domain criticism", "",
                     companion_to=primary_id)


def make_pack(text: dict, book_id: str, edition: str, source_url: str, companion_to: str | None = None) -> Path:
    paragraphs = text["paragraphs"]
    words = sum(len(p.split()) for p in paragraphs)
    print(f"{text['title']} by {text['author']} ({edition}): {words:,} words")
    cost_before = Usage.cost
    language = LANGUAGES.get(text["language"].split("-")[0].lower(), text["language"])
    seg = segment.segment(text["title"], text["author"], paragraphs, language=language)
    blocks = []
    for n, b in enumerate(seg["blocks"]):
        blocks.append({
            "id": f"{book_id}-b{n:03d}", "order": n, "title": b.get("title") or f"Part {n + 1}",
            "hook": b.get("hook") or "", "recap": b.get("recap") if b.get("entry_point") else None,
            "entry_point": bool(b.get("entry_point")), "words": b["words"],
            "paragraphs": paragraphs[b["start"]:b["end"]],
            "playlist_prompt": b.get("playlist_prompt") or "", "videos": [],
        })
    print(f"  {len(blocks)} blocks, {sum(b['entry_point'] for b in blocks)} entry points")
    pack = {
        "version": 1, "id": book_id, "title": text["title"], "author": text["author"], "language": language,
        "year": seg.get("year"), "origin": seg.get("origin"),
        "edition": edition, "source_url": source_url,
        "summary": seg.get("summary") or "", "playlist_prompts": seg.get("playlist_prompts") or {},
        "secondary": [], "blocks": blocks,
    }
    if companion_to:
        pack["companion_to"] = companion_to
    annotate.annotate(pack)
    add_secondary(pack)
    PACKS.mkdir(exist_ok=True)
    path = PACKS / f"{book_id}.json"
    path.write_text(json.dumps(pack, ensure_ascii=False, indent=1))
    print(f"  wrote {path.relative_to(ROOT)} (Claude cost ${Usage.cost - cost_before:.3f})")
    return path


def add_secondary(pack: dict) -> None:
    """Attach up to four screened pieces of secondary material from the trusted sources."""
    picks = secondary.find(pack)
    pack["secondary"] = picks
    # Older packs carried videos from a broad YouTube search; trusted sources replace them.
    pack.pop("video_queries", None)
    pack["videos"] = []
    for block in pack["blocks"]:
        block["videos"] = []
    for pick in picks:
        print(f"    {pick['kind']}: {pick['creator']} · {pick['title']}")


def push(paths: list[Path], subdir: str = "packs") -> None:
    adb = [str(ADB_PHONE)]
    # The app must create its own folder: one made by adb belongs to the shell user and
    # the app can't read it. Opening the app once creates it.
    if subprocess.run(adb + ["shell", "test", "-d", f"{PHONE_DIR}/{subdir}"]).returncode != 0:
        subprocess.run(adb + ["shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity"], check=True,
                       stdout=subprocess.DEVNULL)
        time.sleep(4)
    for path in paths:
        subprocess.run(adb + ["push", str(path), f"{PHONE_DIR}/{subdir}/{path.name}"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"  pushed {path.name}")
    print("Open the app (or pull to refresh) to import.")


def send(paths: list[Path]) -> None:
    """Copy files into the Boox library, where its own reader opens them."""
    adb = [str(ADB_PHONE)]
    subprocess.run(adb + ["shell", "mkdir", "-p", BOOX_LIBRARY], check=True)
    for path in paths:
        target = f"{BOOX_LIBRARY}/{path.name}"
        subprocess.run(adb + ["push", str(path), target], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(adb + ["shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                              "-d", f"file://{target}"], stdout=subprocess.DEVNULL)
        print(f"  sent {path.name} to {BOOX_LIBRARY}")


def run_wishlist(args) -> None:
    if args.action == "folder":
        wishlist.choose(" ".join(args.name))
        print(f"Wish list folder set to {' '.join(args.name)!r} (saved in cache/settings.json).")
        return
    chosen = wishlist.folder()
    if args.action == "folders" or chosen is None:
        if chosen is None:
            print(f"No single bookmarks folder matches {wishlist.setting() or 'a books name'!r}.")
        else:
            print(f"Using {chosen[0]}")
        print("Folders that look meant for books:")
        for line in wishlist.candidates():
            print(f"  {line}")
        print("Choose one with ./pack wishlist folder NAME (its name or the end of its path).")
        return
    print(f"Wish list: {chosen[0]}")
    entries = wishlist.entries()
    books = [e for e in entries if e["is_book"]]
    to_build = []
    for e in books:
        gid = e["gutenberg_id"]
        status = ("built" if (PACKS / f"gutenberg-{gid}.json").exists() else f"Gutenberg #{gid}") if gid \
            else "not on Gutenberg"
        if gid and status != "built":
            to_build.append(gid)
        if args.action == "list":
            print(f"  {status:<18} {e['title']}" + (f" — {e['author']}" if e["author"] else ""))
    print(f"{len(books)} books ({len(entries) - len(books)} other bookmarks skipped), "
          f"{sum(1 for e in books if e['gutenberg_id'])} on Gutenberg, {len(to_build)} not built yet.")
    if args.action == "build":
        paths = [build(str(gid)) for gid in list(dict.fromkeys(to_build))[:args.limit]]
        print(f"Total Claude cost this run: ${Usage.cost:.3f}")
        if paths and not args.no_push:
            push(paths)
    elif Usage.cost:
        print(f"Claude cost: ${Usage.cost:.3f}")


def main() -> None:
    parser = argparse.ArgumentParser(prog="pack", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    device = argparse.ArgumentParser(add_help=False)
    device.add_argument("--boox", action="store_true", help="push to or pull from the Boox instead of the phone")
    add = sub.add_parser("add", help="build a pack and push it", parents=[device])
    add.add_argument("query", nargs="+")
    add.add_argument("--no-push", action="store_true")
    sub.add_parser("search", help="list Gutenberg matches").add_argument("query", nargs="+")
    local = sub.add_parser("file", help="build packs from your own EPUBs or text PDFs and push them",
                           parents=[device])
    local.add_argument("paths", nargs="+", type=Path)
    local.add_argument("--no-push", action="store_true")
    crit = sub.add_parser("criticism", help="build a criticism pack linked to a built book and push it",
                          parents=[device])
    crit.add_argument("primary", help="pack id of the book, e.g. gutenberg-1342")
    crit.add_argument("file", type=Path, help="JSON with title, author, language and paragraphs ('## ' headings)")
    crit.add_argument("--no-push", action="store_true")
    sub.add_parser("send", help="copy files into the Boox library").add_argument("paths", nargs="+", type=Path)
    starter = sub.add_parser("starter", help="build and push the starter shelf", parents=[device])
    starter.add_argument("--no-push", action="store_true")
    sub.add_parser("push", help="push all built packs", parents=[device])
    ann = sub.add_parser("annotate", help="add character notes and standalone flags to built packs")
    ann.add_argument("ids", nargs="*", help="Gutenberg ids (default: every built pack)")
    sub.add_parser("tidy", help="re-apply text cleanup to built packs without rebuilding them")
    sec = sub.add_parser("sources", help="find secondary material from trusted sources for built packs")
    sec.add_argument("ids", nargs="*", help="Gutenberg ids (default: every pack without secondary material)")
    wish = sub.add_parser("wishlist", help="list or build books from the Chrome bookmarks wish list",
                          parents=[device])
    wish.add_argument("action", nargs="?", default="list", choices=["list", "build", "folders", "folder"])
    wish.add_argument("name", nargs="*", help="folder name, for `folder`")
    wish.add_argument("--limit", type=int, default=3, help="most books to build at once (default 3)")
    wish.add_argument("--no-push", action="store_true")
    sub.add_parser("opml", help="push an OPML feed list", parents=[device]).add_argument("file", type=Path)
    sub.add_parser("pull", help="copy notes, Ask answers and shortfalls from the phone into cache/phone/",
                   parents=[device])
    sub.add_parser("serve", help="take book requests from the app and build them (runs on the agent server)")
    args = parser.parse_args()
    if getattr(args, "boox", False) or args.command == "send":
        os.environ["PHONE_SERIAL"] = BOOX_SERIAL  # read by adb-phone

    if args.command == "serve":
        from . import serve
        serve.serve()
    elif args.command == "search":
        for b in sources.search(" ".join(args.query), limit=10):
            print(f"{b.gutenberg_id:>6}  {b.title}  —  {b.author}")
    elif args.command == "add":
        path = build(" ".join(args.query))
        if not args.no_push:
            push([path])
    elif args.command == "file":
        paths = [build_file(path.expanduser()) for path in args.paths]
        print(f"Total Claude cost this run: ${Usage.cost:.3f}")
        if not args.no_push:
            push(paths)
    elif args.command == "criticism":
        path = build_criticism(args.primary, args.file.expanduser())
        if not args.no_push:
            push([path])
    elif args.command == "send":
        send([path.expanduser() for path in args.paths])
    elif args.command == "starter":
        paths = []
        for gid in STARTER:
            path = PACKS / f"gutenberg-{gid}.json"
            paths.append(path if path.exists() else build(str(gid)))
        print(f"Total Claude cost this run: ${Usage.cost:.3f}")
        if not args.no_push:
            push(paths)
    elif args.command == "sources":
        paths = [PACKS / f"gutenberg-{i}.json" for i in args.ids] or sorted(PACKS.glob("*.json"))
        covered = 0
        for path in paths:
            pack = json.loads(path.read_text())
            if args.ids or "secondary" not in pack:
                print(pack["title"])
                add_secondary(pack)
                path.write_text(json.dumps(pack, ensure_ascii=False, indent=1))
            covered += bool(pack.get("secondary"))
        print(f"{covered} of {len(paths)} books have secondary material. "
              f"Claude ${Usage.cost:.3f}, YouTube {secondary.Usage.youtube_units} quota units.")
    elif args.command == "annotate":
        from concurrent.futures import ThreadPoolExecutor
        paths = [PACKS / f"gutenberg-{i}.json" for i in args.ids] or sorted(PACKS.glob("*.json"))

        def run(path: Path) -> str:
            pack = json.loads(path.read_text())
            Usage.local.cost = 0.0
            annotate.annotate(pack, log=lambda msg: None)
            path.write_text(json.dumps(pack, ensure_ascii=False, indent=1))
            return f"  {pack['title']}: {sum(len(b['characters']) for b in pack['blocks'])} character notes, " \
                   f"{sum(b['standalone'] for b in pack['blocks'])} standalone blocks (${Usage.local.cost:.3f})"

        with ThreadPoolExecutor(max_workers=4) as pool:
            for line in pool.map(run, paths):
                print(line)
        print(f"Total Claude cost: ${Usage.cost:.3f}")
    elif args.command == "tidy":
        for path in sorted(PACKS.glob("*.json")):
            pack = json.loads(path.read_text())
            for block in pack["blocks"]:
                block["paragraphs"] = sources.dedupe_headings(block["paragraphs"])
                block["words"] = sum(len(p.split()) for p in block["paragraphs"])
            path.write_text(json.dumps(pack, ensure_ascii=False, indent=1))
            print(f"  tidied {path.name}")
    elif args.command == "wishlist":
        run_wishlist(args)
    elif args.command == "push":
        push(sorted(PACKS.glob("*.json")))
    elif args.command == "opml":
        push([args.file], subdir="import")
    elif args.command == "pull":
        out = ROOT / "cache" / "phone"
        out.mkdir(parents=True, exist_ok=True)
        names = ["notes.jsonl", "asks.json", "shortfalls.json"]
        for name in names:
            subprocess.run([str(ADB_PHONE), "pull", f"{PHONE_DIR}/exports/{name}", str(out)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        got = [n for n in names if (out / n).exists()]
        print(f"Saved to {out.relative_to(ROOT)}/: {', '.join(got) or 'nothing yet'}")


if __name__ == "__main__":
    main()
