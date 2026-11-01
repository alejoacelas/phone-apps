"""Add spoiler-safe character notes and a "standalone" flag to every block of a pack.

Runs over an existing pack without re-segmenting it, so block ids (and the reader's
progress) stay the same. The book is read in order, in windows of about WINDOW_WORDS,
with a running list of characters met so far carried from one window to the next.
"""

from .llm import ask_json

WINDOW_WORDS = 25_000

PROMPT = """You are annotating "{title}" by {author} for a reader who dips into it block by
block. Write in {language}.

Characters met before this part of the book:
{known}

Below are consecutive blocks. For each block give:
- "characters": at most 4 people who matter in that block and whom a reader starting
  there could not place from the block itself, usually people introduced in earlier
  blocks. Each has "name" and a "note" of at most 8 words saying who they are. Leave out
  the narrator, incidental people, and anyone the block introduces or explains itself.
  The note may use only what the book has revealed up to the end of that block, never
  anything from later blocks: no deaths, betrayals, identities or twists not yet shown.
  An empty list is fine, and usual for essays and poems with no recurring people.
- "standalone": true if the block can be enjoyed cold as a complete piece (a whole
  story, poem, essay or self-contained episode), false if it mainly continues or sets
  up something else.

Reply with JSON only: {{"blocks": [{{"id": "", "characters": [{{"name": "", "note": ""}}], "standalone": false}}]}}

{blocks}
"""


def _windows(blocks: list[dict]) -> list[list[dict]]:
    windows, current, words = [], [], 0
    for block in blocks:
        current.append(block)
        words += block["words"]
        if words >= WINDOW_WORDS:
            windows.append(current)
            current, words = [], 0
    if current:
        windows.append(current)
    return windows


def annotate(pack: dict, log=print) -> None:
    known: dict[str, str] = {}
    windows = _windows(pack["blocks"])
    for n, window in enumerate(windows):
        log(f"  characters: window {n + 1}/{len(windows)}")
        text = "\n\n".join(
            f"=== BLOCK {b['id']}: {b['title']} ===\n" + "\n".join(b["paragraphs"]) for b in window)
        known_text = "\n".join(f"- {name}: {note}" for name, note in known.items()) or "(none yet)"
        reply = ask_json(PROMPT.format(title=pack["title"], author=pack["author"],
                                       language=pack.get("language") or "English",
                                       known=known_text, blocks=text), max_tokens=12000)
        by_id = {b.get("id"): b for b in reply.get("blocks", []) if isinstance(b, dict)}
        for block in window:
            got = by_id.get(block["id"], {})
            chars = [{"name": str(c["name"]).strip(), "note": str(c["note"]).strip()}
                     for c in got.get("characters") or [] if c.get("name") and c.get("note")]
            block["characters"] = chars[:4]
            block["standalone"] = bool(got.get("standalone"))
            for c in block["characters"]:
                known[c["name"]] = c["note"]
