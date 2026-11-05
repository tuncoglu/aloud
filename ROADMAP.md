# Roadmap

Status: ✅ done · 🔶 needs a device check · ⬜ todo · ⏸ deferred

The old `REMEDIATION.md` phased plan (R1–R37) is finished and has been removed;
its history is in git. This file is the whole inventory now.

## Verify before shipping

| Gate | Command | State |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest` | ✅ 45 passing |
| Static analysis | `./gradlew :app:lintDebug` | ✅ 0 errors, no baseline |
| Release build | `./gradlew :app:assembleRelease` | ✅ signed, R8-minified |

Prefix with `JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1`.

## Open

- 🔶 **First real listening session, 2026-09-01 evening.** Three problems
  reported from an actual run with Pixel Buds Pro:

  | Reported | Status |
  | --- | --- |
  | A phone notification to the headset stopped playback entirely, no resume | ✅ fixed |
  | Playback overlapped watch/phone announcements | 🔶 partly — see below |
  | A watch health announcement did not pause the book | ⬜ may not be fixable |

  The cause of the first was a dead audio route: the only trace left behind was
  `state=ERROR(7) … error=Bluetooth audio disconnected` in `dumpsys
  media_session`. `handleAudioBecomingNoisy` was never set, `onPlayerError` only
  recorded the failure and never re-prepared, and release builds logged nothing
  at all. All three are fixed, and warnings now survive into release builds
  (`adb logcat -s Aloud:W`).

  Verified on the watch, both directions: playing when the route drops resumes by
  itself (1:06:37 → 1:06:51); a book the listener paused stays paused
  (1:06:57 → 1:06:57).

  **Not proven:** the exact reported case. Testing used a full Bluetooth
  disconnect, not a phone notification briefly seizing the headset — the same
  class of event, handled generically, but it cannot be driven from adb. The next
  run is the test.

  **Possibly not fixable:** a watch announcement that plays *without requesting
  audio focus* is invisible to the player. Audio focus itself is configured
  correctly — Media3 requests it and, because the content type is `SPEECH`,
  pauses rather than ducks.
- ⬜ **Nobody has yet listened for an hour uninterrupted.** Battery cost over a
  run is also unmeasured.
- ✅ **Running as `com.emre.aloud` on the watch** (2026-09-01): 21 books /
  8.80 GB verified byte-exact against the source library, old `com.emre.wearbook`
  package and its data removed.
- ✅ **Release workflow proven** by tag `v0.1.0`. It had never worked before: the
  job wrote the keystore password but never put a keystore on the runner, and no
  repository secrets existed at all. It now decodes `ALOUD_STORE_B64`, runs only
  after tests pass, and the attached APK was confirmed to carry the release
  certificate (`5A:75:82:00…`).
- ✅ **Uploader re-verified on the watch**: ten endpoint checks (PIN enforcement,
  mandatory `total`, overshoot → 413, traversal → 400), a 3 MiB chunked upload
  that came back sha256-identical, and DELETE cleanup.
- ✅ **Crown/rotary scrolling, `TimeText`, chapter-list auto-follow** — confirmed
  by hand on the Pixel Watch 5. Touch scrolling works too; only synthetic
  `adb input swipe` fails to move the list, which is an adb artefact.
- ✅ **Getting back to the player from the library.** The library used to be a
  one-way trip; it now shows a `▶ <title>` row while a book is loaded, and
  re-tapping the current book navigates instead of re-preparing it.
- ✅ **Chapters verified on the Pixel Watch 5** (2026-09-01), both formats and
  both entry paths: M4B QuickTime `ch 1/17`, MP3 ID3 `CHAP` `ch 1/21`, chapter
  list renders, and a cold restart resumes at 18.6 s with the chapter list
  intact.

  On-device testing earned its keep here. Deleting the hand-written parser in
  favour of the player's own chapter callbacks looked airtight off-device, but
  ExoPlayer only drives the extractor past the header **while playing**, and
  `Mp4Extractor` drops chapters on the first seek. So every resume — and the
  paused continue-listening startup, i.e. normal use — came up with no chapters.
  Every JVM test read from byte zero and never saw it. Fixed by
  `books/ChapterReader.kt`, which drives Media3's extractor over the file on an
  IO thread, independently of playback position.

  A second on-device finding reversed the parser deletion outright. Media3's
  MP4 extractor parses the whole audio sample table before publishing chapters:
  **over 4 minutes** for a 1.3 GB / 23 h book on the watch, versus 0.3 s for
  `Mp4ChapterParser`, which reads only `chpl` and the chapter track. The parser
  is back for MP4/M4B; Media3 still handles MP3 ID3 `CHAP` (no sample table, so
  it is fast). Measured across the reference library: every count matches
  `ffprobe`, worst case 0.5 s.

  Note: some files genuinely have no chapters — an empty list there is
  correct, not a bug. Files that tag every chapter with the book's own title are
  renumbered "Chapter N" rather than shown as-is.
- ⬜ The browser upload page itself (the JS in `UploadServer.kt`) is still only
  exercised by hand; the server contract underneath it is covered by tests and
  was re-verified on the watch.
- 🔶 **Uploader hardening** (JVM-tested, and the lockout and `Host` check were
  confirmed against the real CIO server over raw sockets; not yet re-run on the
  watch). Wrong PINs are counted per source address: the tenth locks that address
  out for five minutes (429), and neither the server nor other devices are
  affected — it used to stop the whole uploader after 20 guesses. Requests must
  name the watch by IP literal or `localhost`, which defeats DNS rebinding (421
  otherwise). Writes and deletes to one file name are serialised; a second
  request waits 2 s, then gets 409 and the web client's retry takes it.
- ⬜ Detect ALAC-only M4Bs and warn (ExoPlayer has no ALAC decoder; advise an AAC
  transcode).
- ⬜ Library search/filter once the book count passes a screenful.
- ⬜ Battery measurement on the watch during playback (wake mode is already `LOCAL`).
- ⬜ IzzyOnDroid submission (no account fee; the step before F-Droid proper).

## Deferred

⏸ Bookmarks · skip-silence · volume boost · edit-list (`elst`) offsets ·
Play Store ($25 + Wear review; sideloading works indefinitely). The release pipeline
exists since 2026-11-05 (`.github/workflows/play-release.yml`, see AGENTS.md). As of
2026-11-05 the app is **submitted for review** (11 changes, managed publishing off): Wear OS
production track `wear:production` holds 0.1.3 (versionCode 3), listing has 4 Wear screenshots,
178 countries, foreground-service declaration filed (DATA_SYNC: importing/exporting, MEDIA_PLAYBACK,
each with an unlisted YouTube demo video). A Wear app cannot use the standard production track
(promote with `tracks=wear`); the standard-track draft is unused. Next: wait for the review,
then check the release page shows 0.1.3 in production (the release was not listed as a change in
Publishing overview, so confirm it was included). No phone version (parked).

## Session-handoff notes

- Build: `JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleDebug`
- Device: `adb mdns services` finds the pair/connect ports (they rotate on watch
  reboot). A reboot also turns WiFi debugging off, and the watch kills the app
  process aggressively when idle — expect pid churn; the media FGS keeps it alive
  while playing.
- Screenshots aren't readable over adb — use `uiautomator dump` and
  `logcat -s Aloud`.
