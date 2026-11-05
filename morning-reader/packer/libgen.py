"""Find and download EPUBs from Library Genesis (libgen.li), for books not on Gutenberg.

LibGen has no search API: its HTML search page gives file ids, and json.php gives each
file's md5 and format. A file's ads page links a one-time get.php download URL.
"""

import html
import json
import re
import urllib.parse
from pathlib import Path

import httpx

BASE = "https://libgen.li"
# libgen.li answers plain clients with a placeholder page; a browser User-Agent gets results.
HTTP = httpx.Client(timeout=120, follow_redirects=True, headers={
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"})


def _text(fragment: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


def search(query: str, extension: str = "epub") -> list[dict]:
    """Files matching a free-text query, in LibGen's order, limited to one format."""
    page = HTTP.get(f"{BASE}/index.php", params={"req": query, "res": "100", "curtab": "f"}).text
    table = re.search(r'id="tablelibgen".*?<tbody>(.*?)</tbody>', page, re.S)
    if not table:
        if "Files" not in page:
            raise RuntimeError("LibGen search returned an unrecognized page")
        return []
    editions: dict[str, dict] = {}
    for row in re.findall(r"<tr\b[^>]*>(.*?)</tr>", table[1], re.S):
        fid = re.search(r"file\.php\?id=([0-9]+)", row)
        cells = re.findall(r"<td\b[^>]*>(.*?)</td>", row, re.S)
        if not fid or len(cells) < 8:
            continue
        titles = [_text(t) for t in re.findall(r'href="edition\.php\?id=[0-9]+"[^>]*>(.*?)</a>', cells[0], re.S)]
        title = next((t for t in titles if t and not re.fullmatch(r"[0-9X;,\s-]+", t)), "")
        editions.setdefault(fid[1], {"title": title, "author": _text(cells[1]), "year": _text(cells[3]),
                                     "language": _text(cells[4])})
    files: list[dict] = []
    ids = list(editions)
    for start in range(0, len(ids), 50):
        chunk = ids[start:start + 50]
        info = json.loads(HTTP.get(f"{BASE}/json.php", params={"object": "f", "ids": ",".join(chunk)}).text)
        for fid in chunk:
            record = info.get(fid) if isinstance(info, dict) else None
            if not isinstance(record, dict) or (record.get("extension") or "").lower() != extension:
                continue
            md5 = str(record.get("md5") or "").lower()
            if not re.fullmatch(r"[0-9a-f]{32}", md5):
                continue
            files.append({"md5": md5, "bytes": int(record.get("filesize") or 0),
                          "filename": str(record.get("locator") or "").replace("\\", "/").rsplit("/", 1)[-1],
                          **editions[fid]})
    return files


def download(md5: str, dest: Path) -> Path:
    """Download one file by md5 to dest; raises if LibGen doesn't hand over an EPUB."""
    page = HTTP.get(f"{BASE}/ads.php", params={"md5": md5}).text
    link = re.search(r'href="([^"]*get\.php\?[^"]*)"', page)
    if not link:
        raise RuntimeError(f"LibGen has no download link for {md5}")
    resp = HTTP.get(urllib.parse.urljoin(f"{BASE}/ads.php", html.unescape(link[1])))
    if resp.status_code != 200 or resp.content[:2] != b"PK":
        raise RuntimeError(f"LibGen download of {md5} was not an EPUB (HTTP {resp.status_code})")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(resp.content)
    return dest
