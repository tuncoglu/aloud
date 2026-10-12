# Aloud — notes for coding agents

A standalone audiobook player for Wear OS (Pixel Watch 5). ~2,000 lines of Kotlin.
`ROADMAP.md` is the human-facing plan; this file is the machine-facing memory of
things that are expensive to rediscover.

## Build and verify

```bash
export JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1
./gradlew :app:testDebugUnitTest   # 39 JVM tests
./gradlew :app:lintDebug           # gate: 0 errors, no baseline
./gradlew :app:assembleRelease     # signed, ~5 MB, R8-minified
```

Release signing uses `~/.gradle/aloud-release.jks` (alias `aloud`, password in
`~/.gradle/gradle.properties` as `aloudReleaseStorePassword`). CI decodes the same
keystore from the `ALOUD_STORE_B64` secret, so locally built and CI-built release
APKs are interchangeable for install purposes.

## Traps that have already cost real time

**Do not "simplify away" `books/Mp4ChapterParser.kt`.** It looks redundant with
Media3, which parses the same chapter formats. It is not. Media3's `Mp4Extractor`
parses the entire audio sample table before publishing chapters — millions of
entries for a 23 h book. Measured on the watch: **>4 minutes** for a 1.3 GB M4B
versus **0.3 s** for the parser, which reads only `chpl` and the chapter track.
It is O(chapters), Media3 is O(audio samples). This was deleted once and had to be
restored. A PC's CPU and page cache hide the difference completely.

**Chapters must be read off the playback path.** ExoPlayer only drives the
extractor past the file header *while actually playing*, and `Mp4Extractor` drops
chapters on the first seek. A book opened paused at its saved position — every
resume, and the default startup screen — gets nothing from `Player.Listener`.
`books/ChapterReader.kt` reads the file directly on an IO thread. The listener
path is kept only as a bonus for playback from byte zero.

**`readChapters()` must start *after* `player.setMediaItem()`.** `onMediaItemTransition`
cancels `chapterJob`, so a read started earlier is cancelled by the very item it
was reading for.

**Any JVM test that reads a file from byte zero cannot see either bug above.**
Both were found only on device.

**`onPlayWhenReadyChanged` reports a becoming-noisy pause even for a book that
was ALREADY paused.** Auto-resume must therefore be gated on `wasPlaying`, not
on the reason alone — otherwise a Bluetooth blip arms auto-resume on a book the
listener deliberately stopped, and reconnecting a headset later starts it
playing. Two cases must both be checked after any change here: playing when the
route drops (must resume) and user-paused when it drops (must stay paused).

**The watch's Wi-Fi radio is off unless an app asks for it.** A foreground
service that binds port 8080 is not enough: the uploader screen showed a URL and
PIN while nothing on the LAN could reach the watch. `UploadServerService` now holds
a `requestNetwork(TRANSPORT_WIFI)` request for as long as the server runs (the
documented Wear OS way to make the watch join a saved network and stay on it),
and the address shown comes from that network's `LinkProperties`, not from
whichever interface is up first. Anything that serves inbound traffic needs the
same. A JVM test cannot see this at all.

## Device workflow

Use the `wear-device-testing` skill. The essentials:

- **`run-as` only works on a debuggable build.** The watch normally runs the
  *release* build, so `adb push` + `run-as cp` is unavailable and books must go in
  through the app's own uploader over HTTP (~2.8 MB/s).
- **Installing the app kills the upload service.** Never `adb install` while a
  transfer is running; finish the upload first. This has interrupted transfers twice.
- **debug and release signatures differ.** Switching between them requires an
  uninstall, which **wipes the library** (8.5 GB). Same-key release→release
  upgrades keep app data.
- **`uiautomator dump` is unreliable** when the watch is dozing or on its charger:
  it returns stale content, mixes in watch-face nodes, or reports rows with empty
  text. **Take a screenshot and look at it** before believing a dump. A phantom
  "blank row" bug was chased for several turns before a screenshot showed the app
  was not even foregrounded.
- **Synthetic input scrolls a `verticalScroll` but not a lazy list.** `adb shell
  input swipe` moves `NowPlayingScreen`; it does nothing to `TransformingLazyColumn`
  in the library or chapter list. That is an adb limitation, not an app bug —
  fingers and the crown work on both.
- **Wireless debugging does not keep the watch reachable.** An idle watch with
  nothing holding a Wi-Fi request drops off the LAN even with wireless debugging
  on (ARP `FAILED`, `adb mdns services` empty), and `adb devices` keeps listing it
  from cache. When it returns, the connect port has changed: re-read it with
  `adb mdns services` and `adb connect` again. Pairing survives.
- **mdns leaves duplicate adb entries** (`192.168.0.15:39023` and
  `adb-…_tcp`), after which plain `adb shell` fails with "more than one device".
  `adb disconnect <ip:port>` clears it.
- **A paired phone sends `KEYCODE_MEDIA_PAUSE`** (`callingPackage:
  com.google.android.bluetooth`) shortly after playback starts, which pauses the
  book. Suspect this before suspecting the app when playback stops on its own.

## Release and distribution

- **`versionCode` must increase for every release.** `v0.1.0` and `v0.1.1` both
  shipped `versionCode = 1`; F-Droid and IzzyOnDroid key updates off that field and
  would not have seen the second as newer.
- **Verify the artifact matches the commit before claiming a build is current.**
  A CI artifact from an older tag was once installed and described as up to date.
  Pull the APK back off the device and compare hashes:
  `adb pull $(adb shell pm path com.emre.aloud | sed 's/package://')`.
- Fastlane metadata for IzzyOnDroid lives in `fastlane/metadata/android/en-US/`.

## Library conventions

Audiobooks live **flat** in `~/Audiobooks` — one file per book, no subdirectories.
`BooksRepository.list()` scans with `listFiles { it.isFile }`, so a directory is
invisible to the player however it is named; a multi-part download has to be
merged into a single file before it can be played at all.

Names are **`<Author> - <Full Title>.<ext>`** — author first, then the full title
including any subtitle. The file name *is* what the player shows, so it carries
the reading order too:

- **Separate a subtitle with an em dash (`—`), never a colon.** The file name is
  what the player displays, and `:` cannot survive a trip to Android: MTP
  silently rewrites it to `_`, so a phone-side copy shows a mangled title. An em
  dash transfers intact (verified over MTP). The rest of the FAT-forbidden set —
  `\ / * ? " < > |` — is off limits for the same reason.
- **Numbered series** put the number between the author and the title:
  `<Author> - <Series> <NN> - <Full Title>.<ext>`. The number is the *listening*
  position, which is not always publication order.
- **Core entries take integers; interstitial works take decimals** — a novella or
  connected standalone is slotted as `<NN>.<D>` between the two integers it falls
  between. This keeps the whole series in play order while still showing at a
  glance which works are core novels and which are not.
- **Works outside the sequence stay unnumbered.** They sort clear of the numbered
  run rather than inside it.
- **Zero-pad integers** (`03`, not `3`) and sort the app's way: `list()` sorts on
  the lowercased file name, so lexicographic order has to agree with play order.
- Leave gaps in the decimal positions — `02.5` then `02.7` costs nothing and
  avoids renumbering a shelf when a later title needs a slot.

Tags follow `artist` = author and `title` = `album` = full title including
subtitle; cover art embedded. Retag losslessly and confirm the audio is untouched:

```bash
# Do NOT use `-map 0`. Any m4b carrying a bin_data stream (most Audible rips)
# fails with "Tag text incompatible with output codec id '98314'", and ffmpeg
# leaves a 0-byte out.m4b behind — so a blind `mv out.m4b in.m4b` destroys the
# source. That has already cost one audiobook. Map audio + cover only; the
# bin_data stream is redundant (chapter titles live in the chapter track).
ffmpeg -i in.m4b -map 0:a -map 0:v -c copy \
  -metadata title="..." -metadata album="..." \
  -disposition:v:0 attached_pic out.m4b
# Never replace the original until the output is non-empty and verified:
ffprobe -v error -count_packets -select_streams a:0 \
  -show_entries stream=nb_read_packets -of csv=p=0 out.m4b   # must equal the source
ffmpeg -v error -i FILE -map 0:a -c copy -f streamhash -hash md5 -   # must not change
```

`ffprobe -show_chapters` is the reference for expected chapter counts; the app
should match it exactly. Some files legitimately have zero chapters — an empty
list is correct, not a bug. Never fabricate chapter marks.
