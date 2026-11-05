# Morning reader

A personal Android app for a 45-minute morning reading session. It serves books
as self-contained 5–15 minute blocks, mixed with long blog posts and short
videos. You approve one block at a time, and a block can start only if it fits in
the time left today. After a block you can mark the book keep going, maybe later or
done with this, and Today orders books by that choice.

## Build and install

Requires JDK 17 and the Android SDK (see `AGENTS.md` for paths) and the phone
reachable through `~/best/stash/dotfiles/bin/adb-phone`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug
~/best/stash/dotfiles/bin/adb-phone install -r app/build/outputs/apk/debug/app-debug.apk
```

## Boox e-reader

The same APK runs on the Onyx Boox Go7, where it is the home screen. On an Onyx
device the app turns black on white and leaves out videos, Spotify prompts
and secondary material. The grid icon on Today opens Boox's own
launcher, for its library, other apps and settings.

`./pack` commands reach the Boox with `--boox`, for example `./pack push --boox`;
`adb-phone` reaches it when `PHONE_SERIAL=ec27fd9` is set. Plugging the Boox into the
Mac by USB is the reliable connection: USB debugging is on and the Mac is authorised.
Wireless debugging also works, but the Boox turns Wi-Fi off while asleep, so wake it
first. Reading progress,
the budget and feeds are kept separately on each device.

After installing on the Boox, Boox may mark the app disabled; enable it and make it
the home screen with:

```bash
b() { PHONE_SERIAL=ec27fd9 ~/best/stash/dotfiles/bin/adb-phone "$@"; }
b shell pm enable com.alejoacelas.morningreader
b shell am start -n com.alejoacelas.morningreader/.MainActivity   # registers the home screen
b shell cmd package set-home-activity com.alejoacelas.morningreader/.Home
```

To go back to Boox's launcher, run
`b shell cmd package set-home-activity com.onyx/.StartupActivity`.

## Add books

Books come from Project Gutenberg, using the Standard Ebooks edition when there is
one. `./pack` builds a book pack on the Mac and pushes it to the phone. The app
loads new packs when it opens or when you tap refresh.

```bash
./pack search "tale of two cities"   # list Gutenberg matches with their ids
./pack add 98                        # build and push by Gutenberg id
./pack add "tale of two cities"      # or build the top search match
./pack starter                       # build and push the first shelf
./pack sources                       # find secondary material for packs that have none
./pack sources 84                    # or redo it for one book
./pack annotate                      # add character notes to packs built before they existed
./pack push                          # re-push every built pack
```

## Your own books and files

`./pack file` builds packs from EPUBs and text PDFs you already have, such as books
bought elsewhere. `./pack send` copies any file (a PDF with diagrams, a paper, a
comic) into the Boox's `Books` folder, where Boox's own reader opens it.

```bash
./pack file --boox ~/Downloads/book.epub   # build a pack and push it to the Boox
./pack file ~/Downloads/book.pdf           # or to the phone
./pack send ~/Downloads/paper.pdf          # into the Boox library, unchanged
```

A scanned PDF has no text layer, so `file` refuses it; send it instead. A PDF loses
its figures and tables in a pack, so send a heavily illustrated book as well.

## Wish list

A Chrome bookmarks folder doubles as a wish list. The packer reads Chrome's bookmark
files each time you ask, so a book bookmarked this morning shows up the next run.

```bash
./pack wishlist                      # list wished-for books and whether each is on Gutenberg
./pack wishlist build                # build and push up to 3 that aren't built yet (--limit N)
./pack wishlist folders              # show bookmark folders that look meant for books
./pack wishlist folder "To read"     # choose the folder (saved in cache/settings.json)
```

`starter` builds the fixed first shelf and `add` builds any one book; use `wishlist
build` to pick the next books from what you've bookmarked. Only public-domain books
are on Gutenberg, so most modern titles are listed as "not on Gutenberg". Wish-list matching uses Haiku 5.5; results are cached in `cache/wishlist.json`.

Book preparation, Ask and Explain on whole sentences use Opus 5.5 directly through
Anthropic. Explain on a word or short phrase, feed hooks and lightweight matching use
Haiku 5.5. The packer reports estimated
list-price cost before the linked monthly API credits.

## Secondary material

At the end of a block the app offers one or two things to watch, listen to or read
about the book or its author. They come only from the trusted sources in
`packer/trusted-sources.toml`: YouTube channels such as the National Theatre and
Gresham College, BBC *In Our Time* and *Great Lives*, The Public Domain Review, and
biographies on Project Gutenberg. Edit that file to add or drop a source. Claude keeps
the candidates that really concern the book or author.
Each YouTube channel searched costs 100 of the 10,000 free daily quota units, so with
six channels about 16 books a day can be searched; when the quota runs out the other
sources still work and `./pack sources <id>` adds videos the next day.

## Notes

Select text while reading to Explain it, Ask a question about it, or add a Note.
Explain and Ask see the whole block and the one before it, with your selection marked.
The Notes tab lists your notes and your questions with their answers, each with the
book, block and text you selected. Explain answers aren't listed. `./pack pull`
copies the notes, the questions and answers, and the log of times nothing fit the
minutes left into `cache/phone/`.

## Blog feeds

The phone checks feeds every 12 hours and keeps posts of 5 minutes or more. To
load an OPML export (for example from Feedly), push it with
`./pack opml path/to/feeds.opml`, or use Settings → Import OPML on the phone.
Settings also adds and removes single feeds.

## Feature requests

Type a request at the bottom of Settings, or tap the request icon at the top of the
reader, and tap Send, on the phone or the Boox. It becomes an issue on this
repository. Requests made offline wait on the device and go out once it's online.

## Telemetry

The app logs every screen, block, selection, model call (with its full prompt and
reply), request, error and crash. Events wait on the device and upload to
`https://agent-server.tail622085.ts.net/reader/events`, reachable only inside the
tailnet, so the device needs Tailscale on. The receiver is `../telemetry/server.py`,
run by `reader-telemetry.service` on the agent server; it writes one file per day:

```bash
ssh agent-server 'tail ~/.local/share/reader-telemetry/$(date -u +%F).jsonl'
```

## Secrets

The app and `./pack` read API keys from an ignored `.env`. `AGENTS.md` says where
each key lives in 1Password and how to rebuild the file.

The Literata font is under the SIL Open Font License (`licenses/literata-OFL.txt`).
