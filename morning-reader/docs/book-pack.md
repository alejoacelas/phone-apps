# Book pack format

A book pack is one JSON file, `<book-id>.json`, pushed to the phone at
`/sdcard/Android/data/<app package>/files/packs/`. The app imports every pack
it finds there on launch.

```json
{
  "version": 1,
  "id": "gutenberg-2680",
  "title": "Meditations",
  "author": "Marcus Aurelius",
  "language": "English",
  "edition": "Standard Ebooks",
  "year": 180,
  "origin": "Rome",
  "source_url": "https://www.gutenberg.org/ebooks/2680",
  "summary": "Two sentences on what the book is and why it's worth dipping into.",
  "playlist_prompts": {"thematic": "…", "geographic": "…", "mood": "…"},
  "secondary": [
    {"kind": "audio", "title": "Meditations", "creator": "In Our Time", "url": "https://www.bbc.co.uk/programmes/…",
     "audio_url": "http://…mp3", "minutes": 50, "why": "One line."},
    {"kind": "video", "title": "…", "creator": "Gresham College", "url": "https://www.youtube.com/watch?v=abc123",
     "youtube_id": "abc123", "minutes": 8, "why": "One line."}
  ],
  "blocks": [
    {
      "id": "gutenberg-2680-b07",
      "order": 7,
      "title": "Book IV, 1–18",
      "hook": "One line on why this passage is worth ten minutes.",
      "recap": "Context a reader needs when starting here, or null.",
      "entry_point": true,
      "words": 1850,
      "paragraphs": ["Plain text of each paragraph, in order."],
      "playlist_prompt": "A Spotify AI Playlist prompt matched to this passage's mood, theme and place.",
      "standalone": false,
      "characters": [{"name": "Dorcas", "note": "Longtime parlourmaid at Styles Court."}]
    }
  ]
}
```

- `blocks` covers the whole book in reading order; `order` is its position.
- `entry_point` marks blocks worth starting on cold. Those get a `recap`.
- Paragraph text is plain Unicode. Emphasis and footnotes are dropped. Headings
  start with `## `; verse keeps its line breaks as `\n`.
- Hooks, recaps, summaries and playlist prompts are in the book's `language`.
- `characters` names up to four people a reader starting at this block couldn't place,
  described only with what the book has revealed by the end of the block.
  `standalone` marks blocks that read well cold (a whole story, poem or episode).
- `secondary` lists up to four pieces of material from the trusted sources in
  `packer/trusted-sources.toml`, best first. `kind` is `video`, `audio` (with
  `audio_url`), `essay` or `book`; `minutes` is missing for books. Videos of 10 minutes
  or less double as video blocks on Today.
- Packs built before trusted sources have `videos` (book level and per block) from a
  broad YouTube search instead. The app still shows them; `./pack sources` replaces them.
