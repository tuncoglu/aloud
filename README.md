# Aloud

A no-frills, open-source audiobook player for Wear OS, built for the Pixel Watch 5.

Aloud plays your own DRM-free MP3 and M4B files from the watch itself over
Bluetooth, remembers where you stopped, follows chapters, and never needs a
phone. Books get on the watch through a tiny upload page it serves over your own
WiFi — no cable, no companion app, no account, no cloud.

Built because nothing else in the niche exists: the good open-source players are
phone-only (Voice), and the watch-capable ones are closed-source, MP3-only, or
require a self-hosted server.

## Features

- Local MP3 + M4B playback, standalone on the watch (no phone needed)
- Resume exactly where you left off, per book — survives app kills and restarts
- Chapter navigation: Nero `chpl` and QuickTime chapter tracks in M4B, ID3 `CHAP`
  frames in MP3
- Playback speed 0.75–2.0×, persisted
- Sleep timer (15/30/60/120 min), persisted and re-armed after a restart
- Finished books restart from the beginning instead of hanging at the end
- Foreground media service: keeps playing with the screen off or locked, and the
  system media controls and Bluetooth buttons work
- Built-in upload page, PIN-protected: a 6-digit code shown on the watch is
  required for every upload, listing and delete

## Install (sideload)

```bash
# on the watch: Settings → System → Developer options → Wireless debugging → ON
adb pair 192.168.x.y:<pair-port>        # 6-digit code shown on watch
adb connect 192.168.x.y:<connect-port>  # ports rotate after each watch reboot
./gradlew :app:installDebug
```

`adb mdns services` discovers the current pair/connect ports.

## Adding books

1. On the watch: open Aloud → **Uploader** → **Start** (grant local-network
   permission on first use). The watch shows its URL and a 6-digit PIN.
2. On any PC on the same WiFi: open `http://<watch-ip>:8080`, type the PIN, and
   drag in `.mp3` / `.m4b` files.

Uploads go up in 1 MiB chunks, each retried up to three times. A chunk that still
fails aborts that file and cleans up its partial upload, so a broken transfer
never lands in the library. The server auto-stops after 2 minutes idle, and after
20 wrong PINs.

Dev-only alternatives: `adb push` to `/data/local/tmp` then
`adb shell "run-as com.emre.aloud sh -c 'cp <src> files/books/'"`, or launch with
`--es autoplay <bookId>` to skip the UI.

## Build

```bash
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleDebug      # dev (~42 MB, debug logging + hooks)
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleRelease    # signed release (~5 MB, R8-minified)
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:testDebugUnitTest  # 39 JVM tests
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:lintDebug          # gate: 0 errors, no baseline
```

Unit tests cover `Mp4ChapterParser` against synthetic Nero/QuickTime files, the
uploader's full endpoint contract (`UploadServerTest`: PIN enforcement, offset and
size validation, chunked writes, `.part` reaping) and the library's name and
media-id rules — no device or real audiobook needed.

The release keystore lives in `~/.gradle/aloud-release.jks` with its password in
`~/.gradle/gradle.properties` (`aloudReleaseStorePassword`) — neither is in the
repo. CI (GitHub Actions) builds and tests on every push; tagged releases build a
signed APK from repository secrets `ALOUD_STORE_B64` + `ALOUD_STORE_PASSWORD`.

Stack: AGP 9.3.2 (built-in Kotlin), Compose for Wear OS 1.6.2, Media3 1.11.0,
Ktor 3.5.2 (CIO), DataStore 1.2.1, coroutines 1.11.0. minSdk 30 / targetSdk 37.

## Notes

**Chapters** are read from the file by `books/ChapterReader.kt` on a background
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
samples), and 0.3 s for that same 1.3 GB book. MP3 has no sample table, so
Media3's `Mp3Extractor` reads ID3 `CHAP` frames directly in well under a second.

Both paths are checked against `ffprobe`: chapter counts match on all 20 books in
the reference library, and the parser has 21 unit tests against synthetic
Nero/QuickTime files built in `app/src/test/.../Mp4Builder.kt`.

**The app was renamed** from WearBook/WearBite to Aloud, and its application id
from `com.emre.wearbook` to `com.emre.aloud`. Android treats that as a different
app: the old install will not upgrade in place. To keep your library, stage it
through `/data/local/tmp` (the same hop the sideload path above uses) before
uninstalling the old version:

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
