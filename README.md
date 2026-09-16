# Aloud

**Your audiobooks. On your Wear OS watch. No phone required.**

Aloud is a free, open-source, standalone audiobook player for Wear OS. Copy your
own DRM-free **MP3 or M4B audiobooks** to a Pixel Watch, connect Bluetooth
headphones, and go for a run without carrying a phone.

It is designed for people looking for a **Wear OS audiobook player**, an
**offline audiobook app for Pixel Watch**, or a private way to listen to their
own audiobook library without Audible, a cloud account, or a companion app.

> **Status:** usable sideloaded alpha. Built and tested on Pixel Watch 5 running
> Wear OS 4+. There is not yet a Play Store listing or public APK release.

[![CI](https://github.com/tuncoglu/aloud/actions/workflows/ci.yml/badge.svg)](https://github.com/tuncoglu/aloud/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

## What it does

- Plays local **MP3 and M4B** audiobooks directly on the watch
- Remembers your position separately for every book, including after restarts
- Reads chapters from M4B Nero/QuickTime chapter tracks and MP3 ID3 `CHAP` frames
- Offers playback speed from 0.75–2.0× and a persistent 15/30/60/120-minute sleep timer
- Keeps playing with the screen off and supports system media controls and headset buttons
- Recovers cleanly when Bluetooth headphones temporarily disconnect
- Transfers books through a built-in browser uploader over your local Wi-Fi
- Requires no phone, companion app, account, cloud service, analytics, or tracking

## The simple workflow

```
Computer ──(local Wi-Fi upload)──> Wear OS watch ──(Bluetooth)──> headphones
```

On the watch, open **Aloud → Uploader → Start**. Aloud displays a local address
and a six-digit PIN. Open that address on a computer on the same Wi-Fi, enter the
PIN, and drag in your `.m4b` or `.mp3` files. Stop the uploader, choose a book,
connect headphones, and listen.

The uploader is intentionally local and temporary: it starts only when you ask
it to, stops after two minutes idle, and removes incomplete files. Aloud does not
need internet access to play books already stored on the watch.

## Install

Aloud is currently installed by sideloading. Enable wireless debugging on the
watch:

**Settings → System → Developer options → Wireless debugging → On**

Then pair and connect with ADB:

```bash
adb pair 192.168.x.y:<pair-port>        # enter the code shown on the watch
adb connect 192.168.x.y:<connect-port>
./gradlew :app:installDebug
```

The debug APK is also produced by CI for every push. A public, signed APK download
will be added to GitHub Releases when the distribution workflow is ready.

## Build and test

Requirements: JDK 21 and an Android SDK with API 37 installed.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The test suite covers M4B chapter parsing, MP3 chapter handling, the uploader
contract (including PIN enforcement, chunk validation, retries and cleanup), and
library naming/media-ID rules. CI runs tests, lint and a debug build on every
push and pull request; tagged builds produce a signed release APK when the
repository signing secrets are configured.

## Why this project exists

Most Wear OS audiobook options are phone-first, closed, cloud-dependent, or
awkward for a personal DRM-free library. Aloud does one thing deliberately:
make the watch itself a reliable offline audiobook player for running, walking,
travel, and sleep.

## Engineering notes

The interesting implementation details are documented in the source and
[ROADMAP.md](ROADMAP.md). In particular:

- MP4/M4B chapters are parsed in O(chapters), avoiding Media3's slow full sample-table
  scan on large audiobooks.
- Chapter discovery happens off the playback path, so chapters remain available
  when resuming a paused book.
- Bluetooth route loss is handled as a recoverable interruption while deliberate
  pauses remain paused.
- The release build is R8-minified and signed outside the repository.

## Limitations

- Wear OS only; this is not an Android phone, iOS, or desktop player.
- Tested primarily on Pixel Watch 5. Other Wear OS watches may work but are not
  yet verified.
- Files must be DRM-free. ALAC-only M4B files are not currently supported.
- Sideloading is required for now.
- Long uninterrupted listening and battery impact still need broader real-world
  testing.

## Contributing

Bug reports and device compatibility reports are welcome. Please include the
watch model, Wear OS version, Aloud version/commit, file format, and whether the
problem is reproducible after restarting the app. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## Roadmap and licence

See [ROADMAP.md](ROADMAP.md) for current verification status and planned work.

Aloud is licensed under [GPL-3.0](LICENSE).
