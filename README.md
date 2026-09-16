# Aloud

**Your audiobooks. On your Wear OS watch. No phone required.**

Aloud is a small, open-source audiobook player for Wear OS, built and tested on
the Pixel Watch 5. Put your own DRM-free M4B or MP3 books on the watch, connect
Bluetooth headphones, and leave your phone at home.

It remembers exactly where you stopped, understands audiobook chapters, supports
playback speed and sleep timers, and keeps playing with the screen off. Books get
onto the watch through a tiny PIN-protected upload page that Aloud serves over
your own WiFi — **no cable, companion app, account, cloud service, or self-hosted
server required.**

## Why Aloud?

I wanted a simple thing: go for a run with just my watch and headphones and keep
listening to my own audiobook library.

The good open-source audiobook players are largely phone-first. Watch-capable
alternatives tend to be closed-source, limited in format support, or dependent
on a phone or self-hosted server. Aloud deliberately does less: it turns the
watch itself into a standalone audiobook player.

The basic workflow is:

**M4B / MP3 on your computer → WiFi upload → watch → Bluetooth headphones**

Once the book is on the watch, Aloud does not need the phone or an internet
connection to play it.

## Features

- **Standalone playback** — local MP3 + M4B playback directly from the watch
- **Per-book resume** — returns exactly where you stopped, surviving app kills
  and restarts
- **Real chapter navigation** — Nero `chpl` and QuickTime chapter tracks in M4B,
  plus ID3 `CHAP` frames in MP3
- **Playback speed** — 0.75–2.0×, remembered between sessions
- **Sleep timer** — 15/30/60/120 minutes, persisted and re-armed after restart
- **Background playback** — foreground media service keeps playing with the
  screen off or locked
- **Bluetooth controls** — system media controls and headset buttons work
- **Bluetooth recovery** — pauses cleanly if your headset disappears and resumes
  when it returns; a pause you requested stays paused
- **WiFi book transfer** — built-in browser uploader with a 6-digit PIN; no
  companion app required
- **No account or cloud** — your audiobook files stay on your devices

## Adding books

1. On the watch, open Aloud → **Uploader** → **Start**. Grant local-network
   permission the first time. Aloud shows an address and a 6-digit PIN.
2. On a computer connected to the same WiFi, open the displayed address in a
   browser, enter the PIN, and drag in `.m4b` or `.mp3` files.
3. Stop the uploader, connect your Bluetooth headphones, choose the book, and go.

Uploads are sent in 1 MiB chunks and each failed chunk is retried up to three
times. If a transfer cannot complete, Aloud removes the partial file rather than
leaving a broken book in the library. The upload server automatically stops
after 2 minutes idle or after 20 incorrect PIN attempts.

## Install (sideload)

Aloud is currently installed by sideloading it onto the watch. Enable wireless
debugging on the watch first:

**Settings → System → Developer options → Wireless debugging → ON**

Then from a computer with ADB:

```bash
adb pair 192.168.x.y:<pair-port>        # enter the 6-digit code shown on watch
adb connect 192.168.x.y:<connect-port>  # ports rotate after each watch reboot
./gradlew :app:installDebug
```

`adb mdns services` can discover the current pair/connect ports.

Dev-only alternatives: `adb push` to `/data/local/tmp` then
`adb shell "run-as com.emre.aloud sh -c 'cp <src> files/books/'"`, or launch with
`--es autoplay <bookId>` to skip the UI.

## Build and test

```bash
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleDebug      # dev (~42 MB, debug logging + hooks)
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleRelease    # signed release (~5 MB, R8-minified)
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:testDebugUnitTest  # 39 JVM tests
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:lintDebug          # gate: 0 errors, no baseline
```

Unit tests cover `Mp4ChapterParser` against synthetic Nero/QuickTime files, the
uploader's full endpoint contract (`UploadServerTest`: PIN enforcement, offset
and size validation, chunked writes, `.part` reaping), and the library's name and
media-id rules — no device or real audiobook is needed for the test suite.

The release keystore lives in `~/.gradle/aloud-release.jks` with its password in
`~/.gradle/gradle.properties` (`aloudReleaseStorePassword`) — neither is in the
repo. CI (GitHub Actions) builds and tests on every push; tagged releases build a
signed APK from repository secrets `ALOUD_STORE_B64` + `ALOUD_STORE_PASSWORD`.

Stack: AGP 9.3.2 (built-in Kotlin), Compose for Wear OS 1.6.2, Media3 1.11.0,
Ktor 3.5.2 (CIO), DataStore 1.2.1, coroutines 1.11.0. minSdk 30 / targetSdk 37.

## Engineering notes

### Bluetooth interruptions

A phone notification seizing the headset, or the headset dropping and returning,
changes the audio route underneath the player. Left alone that surfaces as an
`AUDIO_TRACK_*` failure and ExoPlayer stops for good — a run ends with the book
silent and the position lost.

Aloud sets `handleAudioBecomingNoisy` so the route going away pauses cleanly,
watches for a usable output returning through an `AudioDeviceCallback`, and
resumes from the same position. Auto-resume is armed only by a becoming-noisy
pause, so a pause you asked for is never undone, and only headset-type outputs
count — a dropped headset will not restart the book out loud on your wrist.

### Fast M4B chapter parsing

Chapters are read from the file by `books/ChapterReader.kt` on a background
thread, never from playback. Two things forced that design, both found only by
testing on the watch:

1. The player *does* publish chapters through `Player.Listener`, but only while
   it is actually playing, and `Mp4Extractor` discards them on the first seek. A
   book opened paused at its saved position — the normal case, and the app's
   default startup screen — got nothing.
2. Media3's MP4 extractor parses the entire audio sample table before it
   publishes chapters. For a 1.3 GB / 23 h audiobook that is millions of entries:
   **over 4 minutes** on a Pixel Watch 5.

So MP4/M4B is parsed by `books/Mp4ChapterParser.kt`, which reads only the Nero
`chpl` atom and the QuickTime chapter track — O(chapters) instead of O(audio
samples), and **0.3 seconds** for that same 1.3 GB book. MP3 has no sample table,
so Media3's `Mp3Extractor` reads ID3 `CHAP` frames directly in well under a
second.

Both paths are checked against `ffprobe`: chapter counts match across the whole
reference library, and the parser has 21 unit tests against synthetic
Nero/QuickTime files built in `app/src/test/.../Mp4Builder.kt`.

### Migrating from WearBook / WearBite

The app was renamed from WearBook/WearBite to Aloud, and its application id from
`com.emre.wearbook` to `com.emre.aloud`. Android treats that as a different app:
the old install will not upgrade in place. To keep your library, stage it through
`/data/local/tmp` before uninstalling the old version:

```bash
# 1. copy the books out of the old app, then off the watch
adb shell "run-as com.emre.wearbook sh -c 'cp files/books/* /data/local/tmp/'"
adb pull /data/local/tmp ./books-backup

# 2. replace the app
adb uninstall com.emre.wearbook
./gradlew :app:installDebug

# 3. copy them back in
adb push ./books-backup/. /data/local/tmp/
adb shell "run-as com.emre.aloud sh -c 'mkdir -p files/books && cp /data/local/tmp/*.m4b /data/local/tmp/*.mp3 files/books/'"
adb shell "rm -f /data/local/tmp/*.m4b /data/local/tmp/*.mp3"
```

Resume positions live in DataStore under the old application id and are not
carried over — a moved book starts from the beginning. Re-uploading through the
Uploader page works just as well if you would rather start clean.

## Roadmap

See [ROADMAP.md](ROADMAP.md) for known gaps and planned improvements.

## License

GPL-3.0. See [LICENSE](LICENSE).
