"""Proofread built packs in place: fixed text rules, then rounds of Haiku edits.

Edits change paragraphs inside existing blocks and never add, remove or rename blocks, so
reading progress on the devices survives. Every edit is logged to cache/proofread/<id>.jsonl.
"""

import difflib
import json
import re
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .llm import Usage, ask_json

ROOT = Path(__file__).resolve().parent.parent
LOG = ROOT / "cache" / "proofread"
CHUNK_CHARS = 60_000
WORKERS = 12

LIGATURES = {"ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl", "ﬅ": "st", "ﬆ": "st"}
RULES = [
    (re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f�]"), ""),     # control characters
    (re.compile(r"(?<![-\s])--(?!-)|(?<=\w) -- (?=\w)"), "—"),           # typed dash
    (re.compile(r"(^|[\s(—])([“‘]) +(?=\w)"), r"\1\2"),            # “ word
    (re.compile(r"(?<=[\w.,;:!?]) +(”)(?=[\s,.;:!?)—]|$)"), r"\1"),  # word ”
    (re.compile(r"(?<=\w)\^(?=[\s,.;:!?’”)]|$)|(?<=\s)\^(?=\w)"), ""),  # footnote carets
    (re.compile(r"(?<=\S)  +(?=\S)"), " "),                        # double spaces
]

PROMPT = """You are an editor fixing text for a personal e-reading app. Below is part of a book.

How the app shows text: paragraphs separated by a blank line. A paragraph starting "## " is a bold
heading (the "## " is hidden). Text between underscores, _like this_, is shown in italics without
the underscores. A newline inside a paragraph is a line break (use it for verse and addresses).
Nothing else is interpreted: asterisks, "#", ">", "|" and footnote symbols appear as typed.

Each paragraph is prefixed with its index like [12]; the prefix is not part of the text.

Propose exact edits that fix errors a careful editor would fix:
- typos and scanning errors: misread letters ("Eosamond" for "Rosamond"), words split by a stray
  space or hyphen ("resent ment", "Mans-field"), words run together ("What’snew"), stray marks
- footnote markers whose notes are not in the text (*, †, ‡, §, ¶, superscript digits): delete them
- emphasis written as *word*: change it to _word_
- leftovers of page layout: page numbers, running heads, figure or table labels that make no
  sense as prose (delete the paragraph), a sentence wrongly split across two paragraphs
- headings shown as ordinary paragraphs (a short title line, "CHAPTER 3", an ALL-CAPS section
  title): make them headings
- verse or a list whose lines were run together: restore the line breaks with \\n

Do not change: spelling that is old-fashioned, British or deliberate dialect; the author's
wording, style or punctuation habits; translations; anything you are not sure is an error.
Leave "[...]" gaps alone. Keep _underscored_ italics. Never rewrite a sentence.

Reply with JSON: {"edits": [ ... ]}, each edit one of
  {"block": "<id>", "para": <index>, "op": "replace", "find": "<exact text from that paragraph,
   as short as possible but unique in it>", "replace": "<new text>"}
  {"block": "<id>", "para": <index>, "op": "delete"}      remove the whole paragraph
  {"block": "<id>", "para": <index>, "op": "heading"}     make the paragraph a heading
  {"block": "<id>", "para": <index>, "op": "join"}        join this paragraph onto the previous one
List every instance separately. Return {"edits": []} if the text is clean.
"""


def _straight_quotes(text: str) -> str:
    """Drop the space inside straight double quotes: '" The end, " he said' → '"The end," he said'.
    Only when the quotes pair up, so a quote running on from the previous paragraph is left alone."""
    if text.count('"') % 2 or '"' not in text:
        return text
    out, opening = [], True
    for part in re.split(r'(")', text):
        if part == '"':
            out.append(part)
            opening = not opening
            continue
        if not opening and part.startswith(" ") and (not out or out[-1] == '"') \
                and re.match(r" +\w", part) and (len(out) < 2 or re.search(r"(^|[\s(—])$", out[-2])):
            part = part.lstrip(" ")  # just after an opening quote
        out.append(part)
    text = "".join(out)
    # A space before a closing quote: odd-numbered quotes close.
    pieces, n = [], 0
    for part in re.split(r'(")', text):
        if part == '"':
            n += 1
            if n % 2 == 0 and pieces and re.search(r"[\w.,;:!?] +$", pieces[-1]):
                pieces[-1] = pieces[-1].rstrip(" ")
        pieces.append(part)
    return "".join(pieces)


def apply_rules(text: str) -> str:
    for lig, plain in LIGATURES.items():
        text = text.replace(lig, plain)
    for pattern, repl in RULES:
        text = pattern.sub(repl, text)
    return _straight_quotes(text)


def chunks(pack: dict) -> list[list[dict]]:
    out, cur, size = [], [], 0
    for block in sorted(pack["blocks"], key=lambda b: b["order"]):
        n = sum(len(p) for p in block["paragraphs"])
        if cur and size + n > CHUNK_CHARS:
            out.append(cur)
            cur, size = [], 0
        cur.append(block)
        size += n
    return out + ([cur] if cur else [])


def render(pack: dict, blocks: list[dict]) -> str:
    head = f"BOOK: {pack['title']} by {pack.get('author', '')} ({pack.get('language', '')})\n\n"
    return head + "\n\n".join(
        f"===== BLOCK {b['id']}: {b['title']}\n" + "\n\n".join(f"[{i}] {p}" for i, p in enumerate(b["paragraphs"]))
        for b in blocks)


def _plausible(find: str, replace: str) -> bool:
    """Reject edits that rewrite rather than repair."""
    if not find or len(find) > 300:
        return False
    if abs(len(find) - len(replace)) <= 3 and len(find) < 12:
        return True  # a stray mark, a misread letter, a joined or split word
    return difflib.SequenceMatcher(None, find, replace).ratio() >= 0.6


def apply_edits(pack: dict, edits: list[dict]) -> list[dict]:
    """Apply edits that match the text exactly; return the ones applied."""
    blocks = {b["id"]: b for b in pack["blocks"]}
    applied, merged = [], set()
    # Work from the last paragraph back, so deletes and joins don't shift the indices of later edits.
    for e in sorted(edits, key=lambda e: (str(e.get("block")), -int(e.get("para", -1)))):
        block = blocks.get(e.get("block"))
        i = e.get("para")
        if block is None or not isinstance(i, int) or not 0 <= i < len(block["paragraphs"]):
            continue
        paras, op = block["paragraphs"], e.get("op")
        p = paras[i]
        if op == "replace":
            find, repl = e.get("find") or "", e.get("replace") or ""
            if p.count(find) != 1 or not _plausible(find, repl):
                continue
            paras[i] = p.replace(find, repl)
        elif op == "delete":
            if (block["id"], i) in merged or len(p) > 400 or p.startswith("## ") or len(paras) == 1:
                continue
            del paras[i]
        elif op == "heading":
            if p.startswith("## ") or len(p) > 120 or "\n" in p:
                continue
            paras[i] = "## " + p
        elif op == "join":
            if i == 0 or paras[i - 1].startswith("## ") or p.startswith("## "):
                continue
            if (block["id"], i) in merged:
                continue
            paras[i - 1] = paras[i - 1].rstrip() + " " + p.lstrip()
            del paras[i]
            merged.add((block["id"], i - 1))
        else:
            continue
        applied.append({**e, "was": p})
    return applied


def _ask(pack: dict, blocks: list[dict]) -> list[dict]:
    try:
        reply = ask_json(PROMPT + "\n----\n" + render(pack, blocks), fast=True)
    except RuntimeError as err:
        print(f"  {pack['id']}: {err}")
        return []
    return [e for e in reply.get("edits", []) if isinstance(e, dict)]


def proofread(paths: list[Path], rounds: int = 3) -> list[Path]:
    """Proofread packs in place; return the ones that changed."""
    LOG.mkdir(parents=True, exist_ok=True)
    packs = {path: json.loads(path.read_text()) for path in paths}
    changed: set[Path] = set()
    for path, pack in packs.items():
        n = 0
        for block in pack["blocks"]:
            new = [apply_rules(p) for p in block["paragraphs"]]
            n += sum(a != b for a, b in zip(new, block["paragraphs"]))
            block["paragraphs"] = new
        if n:
            changed.add(path)
        print(f"  {pack['id']}: rules changed {n} paragraphs")

    pending = {path: chunks(pack) for path, pack in packs.items()}
    for round_ in range(1, rounds + 1):
        jobs = [(path, blocks) for path, cs in pending.items() for blocks in cs]
        if not jobs:
            break
        print(f"Round {round_}: {len(jobs)} chunks")
        with ThreadPoolExecutor(WORKERS) as pool:
            replies = list(pool.map(lambda job: _ask(packs[job[0]], job[1]), jobs))
        next_pending: dict[Path, list] = {}
        for (path, blocks), edits in zip(jobs, replies):
            applied = apply_edits(packs[path], edits)
            with (LOG / f"{packs[path]['id']}.jsonl").open("a") as log:
                for e in applied:
                    log.write(json.dumps({"round": round_, **e}, ensure_ascii=False) + "\n")
            if applied:
                changed.add(path)
            # Read the chunk again next round only while it still yields a fair number of fixes.
            if len(applied) >= 3:
                next_pending.setdefault(path, []).append(blocks)
            print(f"  {packs[path]['id']} {blocks[0]['id'][-4:]}…: {len(applied)}/{len(edits)} edits applied")
        pending = next_pending

    for path in changed:
        for block in packs[path]["blocks"]:
            block["words"] = sum(len(p.split()) for p in block["paragraphs"])
        path.write_text(json.dumps(packs[path], ensure_ascii=False, indent=1))
    print(f"Changed {len(changed)} packs. Claude Code's list-price estimate: ${Usage.cost:.2f}")
    return sorted(changed)
