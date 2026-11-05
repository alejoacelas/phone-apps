# Morning reader

An Android app that replaces a morning feed of Twitter and blogs with a finite
session of reading blocks. It explores whether books can compete with feeds when
they are served as short, self-contained passages under a daily time budget.

## How it works

- A **block** is a passage of about 5–15 minutes from a book, a blog post of 5
  minutes or more, or a video of 10 minutes or less. Short stories and poems stay
  whole, so a block is one or more whole stories or a few whole poems.
- The home screen is today's session: blocks from several books and blogs, each
  with a one-line hook and an estimated time. A block can start only if time used
  today plus its estimate fits the daily budget (45 minutes by default). The block
  in progress can always be finished.
- Estimates use the reader's measured words per minute.
- Selecting text offers **Explain** (the meaning of a word or sentence, briefly),
  **Ask** (a typed question) and **Note**. Explain and Ask send the selection's
  paragraph and its neighbours as context and answer in the language of the text.
  The Notes tab lists Notes and Asks with their book, block and selection; Explain
  lookups are kept in state but not shown. Notes are stored in
  `files/exports/notes.jsonl` and Asks are exported to `files/exports/asks.json`.
- Each block can list a few main characters, described only with what the book has
  revealed by then.
- Swiping a card right to left archives its whole book (or post) at the next reload.
- After a block, the reader can mark its book **keep going** (Today lists it first),
  **maybe later** (Today lists it last) or **done with this** (archived at once). The
  choice is optional; a book with none keeps its place in the daily shuffle. The
  book page shows and changes the same choice.
- The end of a block offers the best one or two pieces of **secondary material** not
  yet opened: videos, radio episodes, essays and biographies about the book or its
  author, found only in the trusted sources listed in `packer/trusted-sources.toml`
  and screened by Claude. Videos of 10 minutes or less are also blocks on Today; longer
  material opens outside the session and doesn't count toward the budget.
- Feature requests come from the bottom of Settings or from the reader's top bar,
  which opens the same box over the text without leaving the block. Each is filed as a GitHub issue on this
  repository when sent; if that fails it stays on the phone and a background job
  retries once there's network.
- The Library's **Request a book** box sends a title, author or description to the agent
  server, which builds the pack. Every device checks for new packs on opening and every two
  hours, downloads them and lists each request's status under the box.
- Each block and book has Spotify AI Playlist prompts. The app copies one and opens
  Spotify, since Spotify has no API for AI Playlists.

## Layout

- `app/` — the Android app (Kotlin, Jetpack Compose). It fetches blog feeds and
  calls the model itself, so mornings don't need the Mac.
- `packer/` and `./pack` — Mac-side book preparation: Gutenberg search, Standard
  Ebooks edition when one exists, Claude segmentation, secondary material from
  trusted sources, push to phone.
- `packer/wishlist.py` reads a Chrome bookmarks folder as a wish list
  (`./pack wishlist`). The folder name is a setting in `cache/settings.json`; the
  bookmarks are personal, so everything derived from them stays in `cache/`.
- `packer/serve.py` (`./pack serve`) runs on the agent server as `book-requests.service`
  (`server/`, checkout `~/work/phone-apps`), published at `/books` on the tailnet. It
  queues the app's book requests and builds each from Gutenberg or Standard Ebooks, else
  from a LibGen EPUB (`packer/libgen.py`), into the server's `packs/`. After changing it,
  pull on the server and restart the service.
- `docs/book-pack.md` — the book pack format both sides share.
- `cache/` and `packs/` are ignored. `cache/` holds downloads and the user's
  private feed and reading lists; never commit it.

## Build and install

Set up Android as in [`../AGENTS.md`](../AGENTS.md), run `./gradlew assembleDebug`,
then `~/best/stash/dotfiles/bin/adb-phone install -r app/build/outputs/apk/debug/app-debug.apk`.
The README covers adding books and feeds.

The app also runs on Alejo's Boox Go7 as its home screen; changes to shared screens
show up there too, and `Eink.on` gates what the Boox leaves out. Install there by
running the same commands with the Boox's serial; the README's Boox section
covers enabling it and switching the home screen.

When Alejo points you to books or files to read on the Boox, use `./pack file --boox`
for EPUBs and text PDFs (they become packs on Today) and `./pack send` for anything
else; the README's "Your own books and files" section has details. Packs of bought books hold their full
text, so they stay in the ignored `packs/`.

## Secrets

`.env` (ignored) holds the keys. Keys are in the personal 1Password account
(`my.1password.com`), field `credential`. Unless listed otherwise, use vault `Personal`.

| Variable | 1Password item (ID) |
| --- | --- |
| `ANTHROPIC_API_KEY` | `ANTHROPIC_API_KEY — personal-apps-2026-10` (`mj6dyf42xml2ughfxqzpxey3zi`), vault `mac-agents`, field `credential` |
| `YOUTUBE_API_KEY` | `YOUTUBE_API_KEY — 2026-09-morning-reader` (`xwm7xqceh6je7wnnghhfqmpfue`; Google Cloud project `morning-reader-27191`, `alejoacelas@gmail.com`) |
| `GITHUB_ISSUES_TOKEN` | `GITHUB_ISSUES_TOKEN — 2026-09-morning-reader` (`n2bc52jki6ebzrstlw2hg25ure`; fine-grained token for this repository only, Issues read and write, expires 2027-10-09) |

Load the Anthropic key with `~/best/stash/dotfiles/bin/op-agent` from `mac-agents`.
Use its item ID with `op-agent read` or `op-agent inject` to rebuild the ignored `.env`.
The YouTube and GitHub credentials remain in the locations above.

The agent server's `.env` (in `~/work/phone-apps/morning-reader`) holds `ANTHROPIC_API_KEY`
and `YOUTUBE_API_KEY` from the `server-agents` vault, field `credential`, items
`3la5ndmxz37k757hrfspqlot5m` and `omlliirbzpru77z6evbmaswgke`; rebuild it there with
`~/.local/bin/op-agent inject`.

The Anthropic key and GitHub token are compiled into the private APK; never publish
an APK. Direct Anthropic calls use the monthly API credits linked to Alejandro's
Projects. Opus 5.5 is the default for book preparation and Ask. Haiku 5.5 handles
Explain, feed hooks, wish-list matching and secondary-source filtering. Jobs with
more than 20 parallel model calls must pass `parallel_calls` to `ask_json`, which
selects Haiku. Displayed pack costs estimate list price before credits.
API spending is approved for building packs and rerunning model calls.
