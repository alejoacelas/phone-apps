"""Turn a text PDF (not a scan) into clean paragraphs, like `sources.extract` does for EPUBs."""

import re
from collections import Counter
from pathlib import Path

import pymupdf

LIGATURES = {"ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl", "ﬅ": "st", "ﬆ": "st"}
SENTENCE_END = re.compile(r"[.!?:;\"”’)\]]$")
DICTIONARY = Path("/usr/share/dict/words")
_words: set[str] | None = None


def _is_word(word: str) -> bool:
    global _words
    if _words is None:
        _words = set(DICTIONARY.read_text().lower().split()) if DICTIONARY.exists() else set()
    word = word.lower()
    return any(word.removesuffix(end) in _words for end in ("", "s", "es", "ed", "d", "ing", "ly"))


def _clean(text: str) -> str:
    # Typeset ligatures often come out as "inﬂ uence": expand them and drop the stray space.
    text = re.sub(f"([{''.join(LIGATURES)}]) (?=[a-z])", r"\1", text)
    for lig, plain in LIGATURES.items():
        text = text.replace(lig, plain)
    return re.sub(r"\s+", " ", text).strip()


def _heading(text: str) -> str:
    """Undo letter-spacing ("AC K N OW L E D G M E N T S") and shouting in a heading."""
    tokens = text.split()
    if sum(len(t) <= 2 and t.isalpha() for t in tokens) > len(tokens) / 2:
        text = re.sub(r"(?<=[A-Z])(?=\d)", " ", text.replace(" ", ""))
    if text.isupper():
        text = " ".join(w[:1] + w[1:].lower() for w in text.split())
    return text


def _join(a: str, b: str) -> str:
    """Join two lines, undoing end-of-line hyphenation when the joined word is a real word."""
    m = re.search(r"(\w+)-$", a)
    n = re.match(r"([a-z]\w*)", b)
    if m and n:
        # "occasion-ally" was split by the typesetter; "face-to-face" was hyphenated by the author.
        keep = not _is_word(m[1] + n[1]) and _is_word(m[1]) and _is_word(n[1])
        return a + b if keep else a[:-1] + b
    return f"{a} {b}"


def extract(path: Path) -> dict:
    """Return {'title', 'author', 'language', 'paragraphs'}; headings are prefixed '## '."""
    doc = pymupdf.open(path)
    pages = [page.get_text("dict") for page in doc]
    sizes: Counter = Counter()
    for page in pages:
        for block in page["blocks"]:
            for line in block.get("lines", []):
                for span in line["spans"]:
                    sizes[round(span["size"])] += len(span["text"].strip())
    body = sizes.most_common(1)[0][0]

    paragraphs: list[str] = []
    last_heading_page = -1
    for number, page in enumerate(pages):
        height = page["height"]
        for block in page["blocks"]:
            lines = [ln for ln in block.get("lines", []) if "".join(s["text"] for s in ln["spans"]).strip()]
            if not lines:
                continue
            pieces = [_clean("".join(s["text"] for s in line["spans"])) for line in lines]
            text = pieces[0]
            for piece in pieces[1:]:
                text = _join(text, piece)
            top, bottom = block["bbox"][1], block["bbox"][3]
            if (top > 0.88 * height or bottom < 0.07 * height) and len(text.split()) < 10:
                continue  # running header, footer or page number
            spans = [s for ln in lines for s in ln["spans"] if s["text"].strip()]
            if all(s["size"] >= 1.2 * body for s in spans) and len(text.split()) <= 15:
                text = " ".join(_heading(piece) for piece in pieces)
                prev = paragraphs[-1] if paragraphs else ""
                numbered = re.search(r"\d$", prev)  # "Chapter 1", then the chapter title
                if prev.startswith("## ") and (last_heading_page == number or numbered):
                    paragraphs[-1] += (": " if numbered else " ") + text
                else:
                    paragraphs.append("## " + text)
                last_heading_page = number
                continue
            prev = paragraphs[-1] if paragraphs else ""
            if prev and not prev.startswith("## ") and not SENTENCE_END.search(prev) and text[:1].islower():
                paragraphs[-1] = _join(prev, text)  # a paragraph running over a page break
            else:
                paragraphs.append(text)
    return {
        "title": doc.metadata.get("title") or path.stem,
        "author": doc.metadata.get("author") or "",
        "language": "en",
        "paragraphs": paragraphs,
    }
