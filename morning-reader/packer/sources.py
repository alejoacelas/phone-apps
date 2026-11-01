"""Find public-domain books and turn their EPUBs into clean paragraphs."""

import re
import zipfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote_plus

import httpx
import warnings

from bs4 import BeautifulSoup, Tag, XMLParsedAsHTMLWarning

warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)

CACHE = Path(__file__).resolve().parent.parent / "cache"
HTTP = httpx.Client(timeout=60, follow_redirects=True, headers={"User-Agent": "morning-reader/0.1"})


@dataclass
class Book:
    gutenberg_id: int
    title: str
    author: str


def search(query: str, limit: int = 5) -> list[Book]:
    """Search Project Gutenberg, most downloaded first. Gutendex is too slow to rely on."""
    if query.isdigit():
        return [by_id(int(query))]
    url = f"https://www.gutenberg.org/ebooks/search/?query={quote_plus(query)}&sort_order=downloads"
    soup = BeautifulSoup(HTTP.get(url).text, "lxml")
    books = []
    for li in soup.select("li.booklink"):
        link = li.select_one("a.link")
        match = re.search(r"/ebooks/(\d+)", link["href"]) if link else None
        if not match:
            continue
        title = li.select_one("span.title")
        author = li.select_one("span.subtitle")
        books.append(Book(int(match[1]), title.get_text(strip=True) if title else "",
                          author.get_text(strip=True) if author else ""))
    return books[:limit]


def by_id(gutenberg_id: int) -> Book:
    soup = BeautifulSoup(HTTP.get(f"https://www.gutenberg.org/ebooks/{gutenberg_id}").text, "lxml")
    heading = soup.select_one("h1#book_title")
    creator = soup.select_one('[itemprop="creator"]')
    title = heading.get_text(strip=True) if heading else ""
    author = ""
    if creator:  # "Darwin, Charles, 1809-1882" -> "Charles Darwin"
        parts = [p.strip() for p in creator.get_text().split(",") if not re.search(r"\d", p)]
        author = " ".join(reversed(parts[:2]))
    if " by " in title:  # the heading is "<title> by <author as printed>"
        title = title.rsplit(" by ", 1)[0]
    return Book(gutenberg_id, title or f"Gutenberg #{gutenberg_id}", author)


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


STOPWORDS = {"the", "a", "an", "of", "and", "in", "on", "to"}


def standard_ebooks_url(book: Book) -> str | None:
    """Return the Standard Ebooks EPUB URL for this book, if they publish it."""
    if not book.title:
        return None
    query = quote_plus(re.split(r"[:;]", book.title)[0])
    html = HTTP.get(f"https://standardebooks.org/ebooks?query={query}").text
    surname = _slug(book.author.split(",")[0].split()[-1]) if book.author else ""
    title_slug = _slug(re.split(r"[:;]", book.title)[0])
    author_words = set(_slug(book.author).split("-")) if book.author else set()
    pattern = r'href="/ebooks/([a-z0-9-]+)/([a-z0-9-]+)(?:/([a-z0-9-]+))?"'
    for author_slug, work_slug, translator in dict.fromkeys(re.findall(pattern, html)):
        if surname and surname not in author_slug:
            continue
        work_words = set(work_slug.split("-")) - STOPWORDS
        known = set(title_slug.split("-")) | author_words
        if not work_words or len(work_words & known) < 0.7 * len(work_words):
            continue
        segments = [author_slug, work_slug] + ([translator] if translator else [])
        return f"https://standardebooks.org/ebooks/{'/'.join(segments)}/downloads/{'_'.join(segments)}.epub?source=download"
    return None


def download(book: Book) -> tuple[Path, str]:
    """Download the best EPUB for a book. Returns (path, source) where source is 'se' or 'pg'."""
    CACHE.mkdir(exist_ok=True)
    se_url = standard_ebooks_url(book)
    if se_url:
        path = CACHE / f"se-{book.gutenberg_id}.epub"
        if not path.exists():
            resp = HTTP.get(se_url)
            if resp.status_code == 200 and resp.content[:2] == b"PK":
                path.write_bytes(resp.content)
        if path.exists():
            return path, "se"
    path = CACHE / f"pg-{book.gutenberg_id}.epub"
    if not path.exists():
        for name in (f"pg{book.gutenberg_id}-images-3.epub", f"pg{book.gutenberg_id}-images.epub",
                     f"pg{book.gutenberg_id}.epub"):
            resp = HTTP.get(f"https://www.gutenberg.org/cache/epub/{book.gutenberg_id}/{name}")
            if resp.status_code == 200 and resp.content[:2] == b"PK":
                path.write_bytes(resp.content)
                break
        else:
            raise RuntimeError(f"No EPUB found for Gutenberg #{book.gutenberg_id}")
    return path, "pg"


SKIP_FILES = re.compile(r"(titlepage|imprint|colophon|uncopyright|halftitle|toc|loi|endnotes|"
                        r"frontispiece|wrap0000|cover|copyright|nav)\.x?html", re.I)
INVISIBLE = re.compile("[﻿⁠​­]")
LINE_BREAK = "\ue000"  # private-use sentinel; not whitespace, so it survives collapsing


def _text(el: Tag) -> str:
    """Collapse source line wrapping; keep only real line breaks (br, verse lines)."""
    for br in el.find_all("br"):
        br.replace_with(LINE_BREAK)
    for line in el.select("span.line, span[class*=i1], span[class*=i2]"):
        line.insert_after(LINE_BREAK)
    text = re.sub(r"\s+", " ", INVISIBLE.sub("", el.get_text()))
    text = re.sub(r"#([^#]+)#", r"\1", text)  # Gutenberg's plain-text italics markers
    lines = [ln.strip() for ln in text.split(LINE_BREAK)]
    return "\n".join(ln for ln in lines if ln)


def extract(path: Path) -> dict:
    """Return {'title', 'author', 'language', 'paragraphs'}; headings are prefixed '## '."""
    with zipfile.ZipFile(path) as z:
        container = BeautifulSoup(z.read("META-INF/container.xml"), "xml")
        opf_path = container.find("rootfile")["full-path"]
        opf = BeautifulSoup(z.read(opf_path), "xml")
        base = opf_path.rsplit("/", 1)[0] + "/" if "/" in opf_path else ""
        manifest = {item["id"]: item["href"] for item in opf.find_all("item")}
        spine = [manifest[ref["idref"]] for ref in opf.find_all("itemref") if ref["idref"] in manifest]
        meta = {
            "title": (opf.find("dc:title") or opf.find("title")).get_text(strip=True),
            "author": ", ".join(c.get_text(strip=True) for c in opf.find_all("dc:creator")[:2]),
            "language": (opf.find("dc:language").get_text(strip=True) if opf.find("dc:language") else "en"),
        }
        targets = _toc_targets(z, opf, base)
        paragraphs: list[str] = []
        for href in spine:
            if SKIP_FILES.search(href):
                continue
            soup = BeautifulSoup(z.read(base + href), "lxml")
            body = soup.body
            if body is None:
                continue
            for junk in body.select('[id^="pg-"], #project-gutenberg-license, nav, table, figure, '
                                    'sup, a[epub\\:type="noteref"], .pagenum, [class*="pagenum"]'):
                junk.decompose()
            for stanza in body.select('div[class*="stanza"], div[class*="estrofa"], div[class*="verse"], div.lg'):
                lines = [_text(p) for p in stanza.find_all(["p", "div", "span"], recursive=False)]
                lines = [ln for ln in lines if ln]
                if lines:
                    joined = soup.new_tag("p")
                    joined.string = LINE_BREAK.join(lines)
                    stanza.replace_with(joined)
            header = body.find("header")
            if header is not None:
                head_title = soup.title.get_text(strip=True) if soup.title else ""
                if head_title:
                    paragraphs.append("## " + INVISIBLE.sub("", head_title))
                header.decompose()
            for el in body.find_all(["h1", "h2", "h3", "h4", "p", "blockquote"]):
                if el.find_parent(["p", "blockquote"]) or (el.name == "blockquote" and el.find("p")):
                    continue
                text = _text(el)
                if not text:
                    continue
                anchors = targets.get(href, set())
                in_toc = bool(anchors) and len(text.split()) <= 20 and (
                    el.get("id") in anchors or el.find(id=lambda i: i in anchors) is not None)
                paragraphs.append(("## " + text.replace("\n", " ")) if el.name[0] == "h" or in_toc else text)
        meta["paragraphs"] = dedupe_headings(paragraphs)
        return meta


def _toc_targets(z: zipfile.ZipFile, opf, base: str) -> dict[str, set[str]]:
    """Anchors the table of contents points at, by file, so books that style chapter titles
    as plain paragraphs still get headings."""
    targets: dict[str, set[str]] = {}
    for item in opf.find_all("item"):
        href = item["href"]
        is_ncx = item.get("media-type") == "application/x-dtbncx+xml"
        if not (is_ncx or "nav" in (item.get("properties") or "").split()):
            continue
        toc = BeautifulSoup(z.read(base + href), "xml")
        links = [c["src"] for c in toc.find_all("content")] if is_ncx else [a.get("href", "") for a in toc.find_all("a")]
        folder = href.rsplit("/", 1)[0] + "/" if "/" in href else ""
        for link in links:
            if "#" in link:
                file, anchor = link.split("#", 1)
                targets.setdefault(folder + file, set()).add(anchor)
    return targets


def dedupe_headings(paragraphs: list[str]) -> list[str]:
    """Drop the pieces of a chapter title repeated right after it ("## VI: The Inquest", "VI", "The Inquest")."""
    out: list[str] = []
    pieces: set[str] = set()
    for para in paragraphs:
        bare = para.removeprefix("## ").strip()
        if pieces and bare in pieces:
            pieces.discard(bare)
            continue
        pieces = set()
        if para.startswith("## ") and ": " in bare:
            pieces = {bare, *(x.strip() for x in bare.split(": ", 1))}
        out.append(para)
    return out
