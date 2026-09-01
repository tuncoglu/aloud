# Roadmap

Status: ✅ done · 🔶 needs a device check · ⬜ todo · ⏸ deferred

The old `REMEDIATION.md` phased plan (R1–R37) is finished and has been removed;
its history is in git. This file is the whole inventory now.

## Verify before shipping

| Gate | Command | State |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest` | ✅ 18 passing |
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
- 🔶 **Confirm chapters still appear on the watch.** The hand-written
  `Mp4ChapterParser` was deleted in favour of Media3's own extractors, which
  produced byte-identical chapters for all 10 real audiobooks on the dev machine
  (same count, titles and start times). This is the one change a JVM test cannot
  fully close: play one M4B and one chaptered MP3 on the watch and check the
  chapter list. If it ever regresses, the parser is one `git revert` away.
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
