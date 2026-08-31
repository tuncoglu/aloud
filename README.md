# WearBite

A no-frills, open-source audiobook player for Wear OS, built for the Pixel Watch 5.
Watch-native: plays your own DRM-free MP3/M4B files from the watch itself (Bluetooth
audio), remembers your position, navigates M4B chapters, and never needs a phone.

Built because nothing else in the niche exists: the good open-source players are
phone-only (Voice), and the watch-capable ones are closed-source, MP3-only, or
require a self-hosted server.

## Features (v1)

- Local MP3 + M4B playback, standalone on the watch (no phone needed)
- Resume exactly where you left off, per book (survives app kills and restarts)
- M4B chapter navigation (Nero `chpl` and QuickTime chapter-track formats, parsed
  directly from the file)
- Playback speed 0.75–2.0×, persisted
- Sleep timer (15/30/60/120 min), persisted and re-armed after restarts
- Finished books restart from the beginning instead of hanging at EOF
- Foreground media service: keeps playing with the screen off/locked, system media
  controls + Bluetooth buttons work
- Tiny HTTP upload server on the watch: add books from any PC browser on the same
  WiFi — no cables, no companion app. PIN-protected: a 6-digit code shown on the
  watch is required for every upload, listing and delete

## Install (sideload)

```bash
# on the watch: Settings → System → Developer options → Wireless debugging → ON
adb pair 192.168.x.y:<pair-port>      # 6-digit code shown on watch
adb connect 192.168.x.y:<connect-port>  # ports rotate after each watch reboot
./gradlew :app:installDebug
```

`adb mdns services` discovers the current pair/connect ports.

## Adding books

1. On the watch: open WearBite → **Uploader** → **Start** (grant local-network
   permission on first use). The watch shows its URL and a 6-digit PIN.
2. On any PC on the same WiFi: open `http://<watch-ip>:8080`, type the PIN, drag
   in `.mp3` / `.m4b` files. Uploads go up in 1 MiB chunks, each retried up to
   three times; a chunk that still fails aborts that file and cleans up its
   partial upload, so a broken transfer never lands in the library. The server
   auto-stops after 2 minutes idle (and after 20 wrong PINs).

Dev-only alternatives: `adb push` to `/data/local/tmp` then
`adb shell "run-as com.emre.wearbook sh -c 'cp <src> files/books/'"`,
or launch with `--es autoplay <bookId>` to skip the UI.

## Build

```bash
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleDebug      # dev (42 MB, debug logging + hooks)
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleRelease    # signed release (~5 MB, R8-minified)
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:testDebugUnitTest  # 33 JVM tests
JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:lintDebug          # gate: 0 errors, no baseline
```

Unit tests cover `Mp4ChapterParser` against synthetic Nero/QuickTime files built
in `app/src/test/.../Mp4Builder.kt`, the uploader's full endpoint contract
(`UploadServerTest`), and the name rules — no device or real audiobook needed.

The release keystore lives in `~/.gradle/wearbook-release.jks` with its password in
`~/.gradle/gradle.properties` (`wearbookReleaseStorePassword`) — neither is in the
repo. CI (GitHub Actions) builds and tests on every push; tagged releases build a
signed APK from repository secrets `WEARBOOK_STORE_B64` + `WEARBOOK_STORE_PASSWORD`.

Stack: AGP 9.3.2 (built-in Kotlin), Compose for Wear OS 1.6.2, Media3 1.11.0,
Ktor 3.5.2 (CIO), DataStore 1.2.1, coroutines 1.11.0. minSdk 30 / targetSdk 37.

Known quirk: Media3's MP4 chapter extraction does not fire on Wear OS 7, so
chapters are parsed by `books/Mp4ChapterParser.kt` directly from the file.

## Next

See [ROADMAP.md](ROADMAP.md) for known gaps and planned improvements.

## License

GPL-3.0. See [LICENSE](LICENSE).
