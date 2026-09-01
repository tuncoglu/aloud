# Roadmap

Status: ✅ done · 🔶 needs a device check · ⬜ todo · ⏸ deferred

The old `REMEDIATION.md` phased plan (R1–R37) is finished and has been removed;
its history is in git. This file is the whole inventory now.

## Verify before shipping

| Gate | Command | State |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest` | ✅ 39 passing |
| Static analysis | `./gradlew :app:lintDebug` | ✅ 0 errors, no baseline |
| Release build | `./gradlew :app:assembleRelease` | ✅ signed, R8-minified |

Prefix with `JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1`.

## Open

- 🔶 **Reinstall under the new application id.** The app is now `com.emre.aloud`
  (was `com.emre.wearbook`), so the watch will not upgrade the old install in
  place. Migrate the library or re-upload — see the note at the end of the
  [README](README.md) — then uninstall the old package.
- 🔶 **Prove the release workflow on a real tag.** It had never worked: the job
  wrote the keystore password but never put a keystore on the runner, and no
  repository secrets existed at all. `ALOUD_STORE_B64` / `ALOUD_STORE_PASSWORD`
  are now set and the job decodes the keystore itself, but the first `v*` tag is
  what actually proves it end to end.
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
  it is fast). Measured on all 20 books: every count matches `ffprobe`, worst
  case 0.5 s.

  Reference: `James C. Scott - Weapons of the Weak….m4b` genuinely has no
  chapters — an empty list there is correct, not a bug. Files that tag every
  chapter with the book's own name (e.g. 21× "Caffeine") are renumbered
  "Chapter N" rather than shown as-is.
- 🔶 Re-verify browser drag-and-drop upload after the uploader changes (a declared
  `total` is now mandatory, and `.part` files are reaped at server start).
- 🔶 Crown/rotary scrolling, `TimeText`, chapter-list auto-follow — compile-verified
  only; they need the physical crown.
- ⬜ Rate-limit wrong PINs per source address. Today any device on the LAN can
  stop the uploader with 20 wrong guesses — an unauthenticated denial of service.
  Also consider a `Host`-header check to defeat DNS rebinding.
- ⬜ Serialise concurrent uploads to the same name. Two parallel POSTs to one
  `.part` interleave their writes; the web client is sequential, so it takes a
  second client to hit, but it can corrupt a book.
- ⬜ Detect ALAC-only M4Bs and warn (ExoPlayer has no ALAC decoder; advise an AAC
  transcode).
- ⬜ Library search/filter once the book count passes a screenful.
- ⬜ Battery measurement on the watch during playback (wake mode is already `LOCAL`).
- ⬜ IzzyOnDroid submission (no account fee; the step before F-Droid proper).

## Deferred

⏸ Bookmarks · skip-silence · volume boost · edit-list (`elst`) offsets ·
Play Store ($25 + Wear review; sideloading works indefinitely).

## Session-handoff notes

- Build: `JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleDebug`
- Device: `adb mdns services` finds the pair/connect ports (they rotate on watch
  reboot). A reboot also turns WiFi debugging off, and the watch kills the app
  process aggressively when idle — expect pid churn; the media FGS keeps it alive
  while playing.
- Screenshots aren't readable over adb — use `uiautomator dump` and
  `logcat -s Aloud`.
