# Morning reader

An Android app that replaces a morning feed of Twitter and blogs with a finite
session of reading blocks. It explores whether books can compete with feeds when
they are served as short, self-contained passages under a daily time budget.

## Working on it

Set up Android as in [`../AGENTS.md`](../AGENTS.md), run `./gradlew assembleDebug`,
then `~/best/areas/dotfiles/bin/adb-phone install -r app/build/outputs/apk/debug/app-debug.apk`.
The app also runs on Alejo's Boox Go7 as its home screen, so changes to shared screens
show up there too; install there with the Boox's serial and follow the README's Boox
section.

Telemetry from both devices, including every model prompt and reply, is on the agent
server in `~/.local/share/reader-telemetry/` (README, "Telemetry"). Check it first when
debugging what the app did.

`packer/serve.py` runs on the agent server as `book-requests.service` (checkout
`~/work/phone-apps`). After changing it, pull on the server and restart the service.

When Alejo points you to books or files to read on the Boox, use `./pack file --boox`
for EPUBs and text PDFs and `./pack send` for anything else.

`cache/` and `packs/` are ignored and stay that way: `cache/` holds the user's private
feeds, reading lists and anything derived from Chrome bookmarks, and packs of bought
books hold their full text.

## Secrets

`.env` (ignored) holds the keys. Keys are in the personal 1Password account
(`my.1password.com`), field `credential`. Unless listed otherwise, use vault `Personal`.

| Variable | 1Password item (ID) |
| --- | --- |
| `ANTHROPIC_API_KEY` | `ANTHROPIC_API_KEY — personal-apps-2026-10` (`mj6dyf42xml2ughfxqzpxey3zi`), vault `mac-agents`, field `credential` |
| `YOUTUBE_API_KEY` | `YOUTUBE_API_KEY — 2026-09-morning-reader` (`xwm7xqceh6je7wnnghhfqmpfue`; Google Cloud project `morning-reader-27191`, `alejoacelas@gmail.com`) |
| `GITHUB_ISSUES_TOKEN` | `GITHUB_ISSUES_TOKEN — 2026-09-morning-reader` (`n2bc52jki6ebzrstlw2hg25ure`; fine-grained token for this repository only, Issues read and write, expires 2027-10-09) |

Load the Anthropic key with `~/best/areas/dotfiles/bin/op-agent` from `mac-agents`.
Use its item ID with `op-agent read` or `op-agent inject` to rebuild the ignored `.env`.
The YouTube and GitHub credentials remain in the locations above.

The agent server's `.env` (in `~/work/phone-apps/morning-reader`) holds `ANTHROPIC_API_KEY`
and `YOUTUBE_API_KEY` from the `server-agents` vault, field `credential`, items
`3la5ndmxz37k757hrfspqlot5m` and `omlliirbzpru77z6evbmaswgke`; rebuild it there with
`~/.local/bin/op-agent inject`.

The Anthropic key and GitHub token are compiled into the private APK; never publish
an APK. The app's Explain and Ask call Anthropic directly with the monthly API credits
linked to Alejandro's Projects. The packer calls Claude through `claude -p` on the Claude
subscription, never the API key. Jobs with more than 20 parallel model calls must pass
`parallel_calls` to `ask_json`, which selects Haiku. Building packs and rerunning model
calls is approved.
