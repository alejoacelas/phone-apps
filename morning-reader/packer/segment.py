"""Split a book's paragraphs into reading blocks with hooks, recaps and playlist prompts."""

from .llm import ask_json

WPM = 238
MIN_WORDS, TARGET_WORDS, MAX_WORDS = 1200, 2400, 3600  # about 5, 10 and 15 minutes
WINDOW_WORDS = 140_000  # one Gemini call per window keeps output within limits

BOOK_PROMPT = """You are preparing "{title}" by {author} for a reader who dips into books in
10-minute sittings instead of reading cover to cover. The book is in {language}: write every
title, hook, recap, summary and playlist prompt in {language}.

Below is the {part}text, one paragraph per line. Each line starts with
[paragraph index @ word offset]. Headings start with "##".

Divide the text into consecutive reading blocks. Each block starts at a paragraph
index and runs until the next block starts. Rules:
- Aim for {target} words per block. A block must never exceed {max} words or fall under
  {min}: compute each block's length by subtracting word offsets, and if a chapter is
  longer than {max} words, break it into two or more blocks at a scene change. Break at
  natural pauses: chapter or section starts, scene changes, the end of an argument.
- Short stories and poems are never split. In a story collection a block is one or more
  whole stories; in poetry a block is three or four whole poems. Mark such blocks
  "whole_work": true; they may run shorter than {min} words, or longer than {max} if one
  story is long.
- Cover every paragraph. Mark front matter, contents lists, indexes, notes, appendices
  of tables and licence text with "skip": true.
- "hook": one line, at most 20 words, saying concretely what happens or is argued in
  the block. Use plain words and the book's own terms; don't dress it in modern jargon
  or hype.
- "entry_point": true for the 15-25% of blocks most rewarding to read cold: famous
  passages, vivid episodes, self-contained arguments or stories.
- "recap": for entry points only, 1-3 sentences giving what a reader starting here needs
  (who is who, what just happened, where we are). null for the book's opening block and
  for non-entry blocks.
- "playlist_prompt": a Spotify AI Playlist prompt (under 25 words) matched to the block's
  mood, subject and the place and era it evokes.
- "title": a short block title, e.g. "Chapter IV: the gauchos' bolas".
{book_fields}
Reply with JSON only:
{{{book_schema}"blocks": [{{"start": 0, "title": "", "hook": "", "entry_point": false,
"recap": null, "playlist_prompt": "", "whole_work": false, "skip": false}}]}}

TEXT:
{text}
"""

BOOK_FIELDS = """
Also give, for the whole book:
- "summary": two sentences on what the book is and why it is worth dipping into.
- "year": first publication year (negative for BCE), "origin": the place it is about or
  was written in.
- "playlist_prompts": three Spotify AI Playlist prompts under 25 words each, keyed
  "thematic", "geographic" and "mood".
"""
BOOK_SCHEMA = ('"summary": "", "year": 0, "origin": "", "playlist_prompts": {"thematic": "", '
               '"geographic": "", "mood": ""}, ')


def _words(text: str) -> int:
    return len(text.split())


def _windows(paragraphs: list[str]) -> list[tuple[int, int]]:
    """Split into windows of about WINDOW_WORDS, breaking at headings where possible."""
    windows, start, count = [], 0, 0
    for i, para in enumerate(paragraphs):
        if count >= WINDOW_WORDS and para.startswith("## "):
            windows.append((start, i))
            start, count = i, 0
        count += _words(para)
    windows.append((start, len(paragraphs)))
    return windows


def _split_long(block: dict, paragraphs: list[str]) -> list[dict]:
    """Split a block longer than MAX_WORDS into near-equal parts at paragraph boundaries."""
    words = sum(_words(p) for p in paragraphs[block["start"]:block["end"]])
    parts = max(1, -(-words // TARGET_WORDS)) if words > MAX_WORDS else 1
    if parts == 1:
        return [block]
    out, start, acc, goal = [], block["start"], 0, words / parts
    for i in range(block["start"], block["end"]):
        acc += _words(paragraphs[i])
        if acc >= goal * (len(out) + 1) and len(out) < parts - 1 and i + 1 < block["end"]:
            out.append((start, i + 1))
            start = i + 1
    out.append((start, block["end"]))
    result = []
    for n, (s, e) in enumerate(out):
        part = dict(block, start=s, end=e)
        if n:
            part.update(entry_point=False, recap=None, split=True)
        result.append(part)
    return result


HOOK_PROMPT = """These passages come from "{title}" by {author}. Writing in {language}, give each a short
"title" and a "hook": one line, at most 20 words, saying concretely what happens or is
argued. Use plain words and the book's own terms, no hype.

Reply with JSON only: {{"passages": [{{"n": 0, "title": "", "hook": ""}}]}}

{passages}
"""


def _fill_split_hooks(title: str, author: str, language: str, blocks: list[dict], paragraphs: list[str]) -> None:
    """Give blocks created by splitting their own titles and hooks."""
    todo = [b for b in blocks if b.get("split")]
    if not todo:
        return
    passages = "\n\n".join(
        f"PASSAGE {n}:\n" + "\n".join(paragraphs[b["start"]:b["end"]]) for n, b in enumerate(todo))
    reply = ask_json(HOOK_PROMPT.format(title=title, author=author, language=language, passages=passages),
                     max_tokens=8000)
    for item in reply.get("passages", []):
        n = item.get("n")
        if isinstance(n, int) and 0 <= n < len(todo):
            todo[n]["title"] = item.get("title") or todo[n]["title"]
            todo[n]["hook"] = item.get("hook") or todo[n]["hook"]


def _absorb_scraps(blocks: list[dict], paragraphs: list[str], limit: int = 400) -> list[dict]:
    """Merge any remaining sub-`limit`-word block into a contiguous neighbour."""
    out: list[dict] = []
    for block in blocks:
        words = sum(_words(p) for p in paragraphs[block["start"]:block["end"]])
        if words < limit and not block.get("whole_work") and out and out[-1]["end"] == block["start"]:
            out[-1]["end"] = block["end"]
        else:
            out.append(block)
    if len(out) > 1:  # a scrap at the very start joins the block after it
        first = out[0]
        if sum(_words(p) for p in paragraphs[first["start"]:first["end"]]) < limit and out[1]["start"] == first["end"]:
            out[1] = dict(out[1], start=first["start"], entry_point=True)
            out = out[1:]
    return out


def segment(title: str, author: str, paragraphs: list[str], language: str = "English", log=print) -> dict:
    """Return book-level fields plus 'blocks' with start/end paragraph indices."""
    offsets, total = [], 0
    for para in paragraphs:
        offsets.append(total)
        total += _words(para)
    windows = _windows(paragraphs)
    book: dict = {}
    raw_blocks: list[dict] = []
    for w, (lo, hi) in enumerate(windows):
        lines = "\n".join(f"[{i} @{offsets[i]}] {paragraphs[i].replace(chr(10), ' / ')}"
                          for i in range(lo, hi))
        part = f"part {w + 1} of {len(windows)} of the " if len(windows) > 1 else ""
        first = w == 0
        log(f"  segmenting window {w + 1}/{len(windows)} (paragraphs {lo}-{hi - 1})")
        reply = ask_json(BOOK_PROMPT.format(
            title=title, author=author, language=language, part=part, target=TARGET_WORDS, min=MIN_WORDS,
            max=MAX_WORDS, book_fields=BOOK_FIELDS if first else "",
            book_schema=BOOK_SCHEMA if first else "", text=lines))
        if isinstance(reply, list):  # later windows sometimes drop the {"blocks": ...} wrapper
            reply = {"blocks": reply}
        if first:
            book = {k: reply.get(k) for k in ("summary", "year", "origin", "playlist_prompts")}
        starts = sorted({b["start"] for b in reply.get("blocks", []) if lo <= int(b["start"]) < hi})
        by_start = {int(b["start"]): b for b in reply.get("blocks", [])}
        if not starts or starts[0] != lo:
            starts.insert(0, lo)
            by_start.setdefault(lo, {"title": "Opening", "hook": "", "skip": False})
        for s, e in zip(starts, starts[1:] + [hi]):
            raw_blocks.append(dict(by_start[s], start=s, end=e))

    blocks: list[dict] = []
    carry: dict | None = None  # a heading-only scrap waiting to join the block after it
    for block in raw_blocks:
        if block.get("skip"):
            carry = None
            continue
        if carry is not None and carry["end"] == block["start"]:
            block = dict(block, start=carry["start"])
        carry = None
        text = paragraphs[block["start"]:block["end"]]
        words = sum(_words(p) for p in text)
        # Trust "whole story/poem" only when the block opens on a heading (the work's title).
        block["whole_work"] = bool(block.get("whole_work") and text and text[0].startswith("## "))
        if words < MIN_WORDS / 2 and not block.get("whole_work"):
            if text and text[-1].startswith("## "):
                carry = block  # it ends on a heading: it belongs with what follows
                continue
            if blocks and blocks[-1]["end"] == block["start"]:
                blocks[-1]["end"] = block["end"]  # fold a scrap into the block before it
                continue
        blocks.extend([block] if block.get("whole_work") else _split_long(block, paragraphs))
    if carry is not None and blocks and blocks[-1]["end"] == carry["start"]:
        blocks[-1]["end"] = carry["end"]
    blocks = _absorb_scraps(blocks, paragraphs)
    for block in blocks:
        block["words"] = sum(_words(p) for p in paragraphs[block["start"]:block["end"]])
    if blocks:
        blocks[0]["entry_point"] = True
    _fill_split_hooks(title, author, language, blocks, paragraphs)
    book["blocks"] = blocks
    return book
