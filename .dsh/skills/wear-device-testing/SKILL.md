---
name: wear-device-testing
description: Drive and verify the Aloud audiobook app on a Wear OS watch over adb — launch screens, tap controls, capture screenshots, read logs, and upload audiobooks through the app's HTTP uploader. Use this Skill for any on-device work on the watch, including UI verification, chapter checks, installing debug or release builds, and getting books onto the device.
---

# Testing Aloud on the watch

On-device testing is not optional for this project. Two shipped bugs (chapters
vanishing on every resume, a 1.3 GB book taking >4 minutes to parse) and one
feature-hiding layout bug were invisible to unit tests and to reading the code.
Anything touching playback, chapters, or layout must be confirmed on hardware.

`scripts/watch.py` in this skill wraps the parts that are easy to get wrong.

## Trust screenshots over uiautomator

`uiautomator dump` returns stale or mixed content when the watch is dozing or on
its charger — including watch-face nodes and rows with empty text. A phantom
"blank row" bug was investigated for several turns before a screenshot revealed
the app was not even in the foreground.

**Read a screenshot before believing a dump.** `watch.py shot` writes a PNG;
view it with the `read_image` tool. Use dumps for tapping by label, not for
concluding that something is missing.

## Standard loop

```bash
python3 scripts/watch.py doctor            # one device? awake? which build?
python3 scripts/watch.py launch            # cold start the app
python3 scripts/watch.py shot /tmp/s.png   # then read_image it
python3 scripts/watch.py text              # labels currently on screen
python3 scripts/watch.py tap Chapters      # tap by visible label
python3 scripts/watch.py scroll            # only works on NowPlaying, see below
python3 scripts/watch.py log 30            # Aloud log lines from the last 30s
```

`doctor` clears the duplicate adb entry that mdns leaves behind, which otherwise
breaks every `adb shell` with "more than one device".

## Scrolling

Synthetic input scrolls a `verticalScroll` but **not** a lazy list. `scroll`
moves `NowPlayingScreen`; it does nothing to the library or chapter list
(`TransformingLazyColumn`). That is an adb limitation — fingers and the crown
work on both. If a list item is out of reach, ask the user to scroll rather than
concluding the list is broken.

## Installing

| Situation | Command |
| --- | --- |
| Watch runs release (normal) | `./gradlew :app:assembleRelease` then `adb install -r <apk>` |
| Watch runs debug | `./gradlew :app:installDebug` |

Signatures differ between debug and release, so switching requires an uninstall
that **wipes the 8.5 GB library**. Same-key release→release upgrades keep data.

**Never install while an upload is running** — it restarts the app and kills the
upload service mid-transfer. Finish the transfer first.

After installing, confirm the device is running the build you think it is:

```bash
python3 scripts/watch.py verify-apk app/build/outputs/apk/release/app-release.apk
```

It pulls the installed APK and compares hashes. A CI artifact from an older tag
was once installed and reported as current; this catches that.

## Getting books onto the watch

`run-as` only works on debuggable builds, so on a release build `adb push` is not
available. Books go through the app's own uploader:

1. On the watch: **Uploader → Start**, note the URL and 6-digit PIN.
2. `python3 scripts/watch.py upload ~/Audiobooks --pin 123456`

It skips books already present, resumes after interruption, and verifies sizes at
the end. Expect ~2.8 MB/s, so a full 8.5 GB library takes roughly 50 minutes —
run it as a background job and do not install anything until it finishes.

## Verifying chapters

`ffprobe -show_chapters` is the reference. The app must match it exactly:

```bash
python3 scripts/watch.py chapters ~/Audiobooks   # expected counts per file
```

Then play a book and confirm the counter reads `ch 1/17` for a 17-chapter file.
Check both an M4B **and** an MP3 with ID3 `CHAP` frames — they take different code
paths. Check a resumed book as well as a fresh one, because the resume path is
where chapter extraction has broken before. Some files genuinely have zero
chapters; an empty list is correct, not a bug.

## Playback stopping on its own

A paired phone sends `KEYCODE_MEDIA_PAUSE` (`callingPackage:
com.google.android.bluetooth`) shortly after playback starts. Check
`watch.py log` for it before suspecting the app. `adb shell svc bluetooth
disable` isolates it — remember to re-enable afterwards.
