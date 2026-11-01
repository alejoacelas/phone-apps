"""Find secondary material about a book from the trusted sources in trusted-sources.toml.

Candidates come from channel-restricted YouTube searches, podcast feeds, The Public
Domain Review's essay index and Gutenberg's catalog. Gemini then keeps the ones that
really concern the book or its author, best first.
"""

import csv
import json
import re
import time
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

import httpx

from .llm import _env, ask_json

ROOT = Path(__file__).resolve().parent.parent
TRUSTED = Path(__file__).resolve().parent / "trusted-sources.toml"
CACHE = ROOT / "cache" / "secondary"
HTTP = httpx.Client(timeout=60, follow_redirects=True, headers={"User-Agent": "morning-reader/0.1"})
YOUTUBE = "https://www.googleapis.com/youtube/v3"
WPM = 238
MAX_PICKS = 4
PER_SOURCE = 12  # candidates per source sent to Gemini


class QuotaError(RuntimeError):
    pass


class Usage:
    youtube_units = 0


def _cached(name: str, url: str, max_age_days: float) -> Path:
    """Download url into cache/secondary/name unless a fresh copy is there."""
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists() or time.time() - path.stat().st_mtime > max_age_days * 86400:
        resp = HTTP.get(url)
        resp.raise_for_status()
        path.write_bytes(resp.content)
    return path


def _matcher(names: list[str], titles: list[str]):
    """Score text: 2 if it names the author or the book in a title, 1 in a description."""
    patterns = [re.compile(rf"\b{re.escape(n)}\b") for n in names if n] + \
               [re.compile(rf"\b{re.escape(t)}\b", re.I) for t in titles if t]

    def score(title: str, description: str) -> int:
        if any(p.search(title) for p in patterns):
            return 2
        return 1 if any(p.search(description) for p in patterns) else 0
    return score


def _best(items: list[tuple[int, dict]]) -> list[dict]:
    return [c for _, c in sorted(items, key=lambda x: -x[0])[:PER_SOURCE]]


def podcast(name: str, feed: str, score) -> list[dict]:
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower())
    root = ET.parse(_cached(f"podcast-{slug}.xml", feed, 7)).getroot()
    itunes = "{http://www.itunes.com/dtds/podcast-1.0.dtd}"
    found = []
    for item in root.iter("item"):
        title = item.findtext("title") or ""
        description = re.sub(r"<[^>]+>", " ", item.findtext("description") or "")
        s = score(title, description)
        if not s:
            continue
        enclosure = item.find("enclosure")
        seconds = item.findtext(f"{itunes}duration") or "0"
        seconds = int(seconds) if seconds.isdigit() else 0
        audio = enclosure.get("url") if enclosure is not None else None
        found.append((s, {"kind": "audio", "title": title, "creator": name,
                          "url": item.findtext("link") or audio, "audio_url": audio,
                          "minutes": round(seconds / 60) or None, "description": description[:300]}))
    return _best(found)


def public_domain_review(score) -> list[dict]:
    path = _cached("pdr-essays.json", "https://publicdomainreview.org/page-data/essays/2/page-data.json", 7)
    edges = json.loads(path.read_text())["result"]["data"]["essays"]["edges"]
    found = []
    for edge in edges:
        data = edge["node"]["data"]
        title, intro = data.get("Title") or "", re.sub(r"<[^>]+>", " ", data.get("Intro") or "")
        s = score(title, intro)
        if s:
            authors = ", ".join(c["data"]["Name"] for c in data.get("Contributors") or [] if c.get("data"))
            found.append((s, {"kind": "essay", "title": title,
                              "creator": "The Public Domain Review" + (f" · {authors}" if authors else ""),
                              "url": f"https://publicdomainreview.org/essay/{data['Slug']}/",
                              "minutes": None, "description": intro[:300]}))
    return _best(found)


def _catalog() -> list[dict]:
    path = _cached("pg_catalog.csv", "https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv", 30)
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def gutenberg_biographies(gutenberg_id: int, language: str) -> list[dict]:
    """Books whose subject headings name the author: lives, letters, critical studies."""
    rows = _catalog()
    own = next((r for r in rows if r["Text#"] == str(gutenberg_id)), None)
    if not own or not own["Authors"]:
        return []
    # "Shelley, Mary Wollstonecraft, 1797-1851" -> "Shelley, Mary Wollstonecraft"
    author = re.sub(r",\s*[\d?-].*$", "", own["Authors"].split(";")[0]).strip()
    codes = {"English": "en", "Spanish": "es", "French": "fr", "German": "de"}
    languages = {"en", codes.get(language, "en")}
    found = []
    for r in rows:
        if r["Text#"] == str(gutenberg_id) or r["Type"] != "Text" or r["Language"] not in languages:
            continue
        subjects = [s.strip() for s in r["Subjects"].split(";")]
        if not any(s.startswith(author) for s in subjects):
            continue
        if r["Authors"].startswith(author):  # the author's own works
            continue
        s = 2 if any(re.search(r"Biography|Correspondence|Criticism", x) for x in subjects if x.startswith(author)) else 1
        found.append((s, {"kind": "book", "title": r["Title"].replace("\n", " "),
                          "creator": "Project Gutenberg · " + re.sub(r",\s*\d.*$", "", r["Authors"].split(";")[0]),
                          "url": f"https://www.gutenberg.org/ebooks/{r['Text#']}", "minutes": None,
                          "gutenberg_id": int(r["Text#"]), "description": "; ".join(subjects)[:300]}))
    return _best(found)


def youtube(channels: list[dict], author: str, title: str) -> list[dict]:
    key = _env("YOUTUBE_API_KEY")
    query = "|".join(f'"{t}"' for t in (author, title) if t)
    ids: dict[str, str] = {}
    for channel in channels:
        resp = HTTP.get(f"{YOUTUBE}/search", params={
            "part": "id", "q": query, "type": "video", "channelId": channel["channel_id"],
            "maxResults": 8, "key": key})
        Usage.youtube_units += 100
        if resp.status_code in (403, 429):
            raise QuotaError(f"YouTube search refused ({resp.status_code}); the daily quota is likely used up")
        resp.raise_for_status()
        for item in resp.json().get("items", []):
            ids.setdefault(item["id"]["videoId"], channel["name"])
    found = []
    id_list = list(ids)
    for i in range(0, len(id_list), 50):
        resp = HTTP.get(f"{YOUTUBE}/videos", params={
            "part": "snippet,contentDetails", "id": ",".join(id_list[i:i + 50]), "key": key})
        Usage.youtube_units += 1
        resp.raise_for_status()
        for item in resp.json().get("items", []):
            seconds = _seconds(item["contentDetails"].get("duration"))
            if seconds < 90:  # Shorts and trailers
                continue
            found.append({"kind": "video", "title": item["snippet"]["title"], "creator": ids[item["id"]],
                          "url": f"https://www.youtube.com/watch?v={item['id']}", "youtube_id": item["id"],
                          "minutes": max(1, round(seconds / 60)),
                          "description": item["snippet"].get("description", "")[:300].replace("\n", " ")})
    return found


def _seconds(iso: str) -> int:
    parts = re.fullmatch(r"P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", iso or "")
    if not parts:
        return 0
    d, h, m, s = (int(x or 0) for x in parts.groups())
    return ((d * 24 + h) * 60 + m) * 60 + s


SCREEN_PROMPT = """A reader is dipping into "{title}" by {author}. {summary}

After each reading block we offer one or two pieces of secondary material from trusted
sources. The reader most enjoys biographical accounts of the author and context on the
period as it bears on the book. Below are candidates found by keyword. Many will be
false matches: another person with the same name, a different work with a similar
title, or a general piece that only mentions the book in passing.

Keep only candidates that really concern this book or this author (their life, the
writing of the book, its reception, or the world it came from in a way the piece ties
to the author or book). Rank the kept ones best first, at most {max_picks}, preferring
biography and context over plot summaries. It is fine to keep none.

For each pick give "id" and "why": one line in {language} saying what the reader will
get from it.

Candidates:
{candidates}

Reply with JSON only: {{"picks": [{{"id": "", "why": ""}}]}}
"""


def find(pack: dict, log=print) -> list[dict]:
    """Candidates from every trusted source, screened by Gemini. Returns picks, best first."""
    trusted = tomllib.loads(TRUSTED.read_text())
    author, language = pack.get("author") or "", pack.get("language") or "English"
    title = re.split(r"[:;]", pack["title"])[0].strip()
    surname = author.split()[-1] if author else ""
    names = [author, f"{surname}'s" if surname else ""]
    # A distinctive surname alone ("Kafka", "Tolstoy") is enough; a common one ("Mill") needs the full name.
    if surname and len(surname) > 4:
        names.append(surname)
    score = _matcher(names, [title])
    gutenberg_id = int(pack["id"].split("-")[-1]) if pack["id"].startswith("gutenberg-") else None

    candidates: list[dict] = []
    for p in trusted.get("podcast", []):
        candidates += podcast(p["name"], p["feed"], score)
    if trusted.get("public_domain_review", {}).get("enabled"):
        candidates += public_domain_review(score)
    if gutenberg_id and trusted.get("gutenberg_biographies", {}).get("enabled"):
        candidates += gutenberg_biographies(gutenberg_id, language)
    channels = [c for c in trusted.get("youtube", []) if language in c.get("languages", [language])]
    try:
        candidates += youtube(channels, author, title)
    except QuotaError as err:
        log(f"  YouTube skipped: {err}. Run ./pack sources {gutenberg_id} tomorrow for videos.")
    if not candidates:
        return []
    for n, c in enumerate(candidates):
        c["id"] = f"c{n}"
    lines = "\n".join(
        f"{c['id']} | {c['kind']} | {c['creator']} | {c['title']} | "
        f"{str(c['minutes']) + ' min' if c.get('minutes') else ''} | {c.get('description', '')}" for c in candidates)
    reply = ask_json(SCREEN_PROMPT.format(title=pack["title"], author=author, summary=pack.get("summary") or "",
                                          max_picks=MAX_PICKS, language=language, candidates=lines), max_tokens=4000, fast=True)
    by_id = {c["id"]: c for c in candidates}
    picks, seen = [], set()
    for pick in reply.get("picks", []):
        c = by_id.get(pick.get("id"))
        if c and c["id"] not in seen and len(picks) < MAX_PICKS:
            seen.add(c["id"])
            c = {k: v for k, v in c.items() if k not in ("id", "description") and v is not None}
            picks.append({**c, "why": pick.get("why", "")})
    log(f"  {len(candidates)} candidates, kept {len(picks)}")
    return picks
