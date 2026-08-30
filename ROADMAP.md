# Roadmap — improvements for future sessions

Status markers: ✅ done · 🔶 needs user hands-on test · ⬜ todo

## User-facing features (v1+)

- 🔶 Browser drag-and-drop upload with a real book — endpoints proven via curl, HTML page untested in a real browser
- 🔶 On-watch tap-through: chapter list seek, speed chip cycling, sleep-timer chip — built, never tapped on the unlocked screen
- ⬜ Compute proper chapter end times: `chpl` chapters get `endMs = -1`; derive from next chapter start / media duration
- ⬜ Show current chapter title on the now-playing screen (have the data, not the UI)
- ⬜ Library list: show progress % per book (DataStore positions already available)
- ⬜ Sleep-timer chip: cycle logic gets weird mid-countdown (indexOf on remaining minutes); track the armed option properly
- ⬜ Delete book from the watch UI (only possible via HTTP DELETE today)
- ⬜ Uploader PIN/password for untrusted WiFi (v1 trusts same-WiFi)
- ⬜ Detect ALAC-only M4Bs and warn (ExoPlayer has no ALAC decoder; advise AAC transcode)
- ⬜ Deferred from v1 scope: bookmarks, skip-silence, volume boost
- ⬜ Library search/filter once book count grows past a screenful

## Engineering robustness

- ⬜ **Release build + signing** (debug-only today)
- ⬜ Gate debug-only hooks behind `BuildConfig.DEBUG`: the `--es autoplay` / `--es screen` / `--es uploader_start` intent extras
- ⬜ Reduce `Log.d` spam or gate behind `BuildConfig.DEBUG` (currently always compiled)
- ⬜ **Unit tests for `Mp4ChapterParser`** — feed synthetic `chpl`/QuickTime atoms; chapter parsing is the least battle-tested code
- ⬜ Test chapter parsing against other real M4Bs (different muxers: iTunes, m4b-tool, audiobook binder) — fix parser for any variants found
- ⬜ Verify MP3 ID3 chapters arrive via the `onMetadata` path (code exists, untested)
- ⬜ Verify long uploads survive doze (dataSync FGS + wakelock present; unproven for 30+ min transfers)
- ⬜ Watch for playback regressions from the MediaParser extractor path (used for MP4); fallback = revert to default `Mp4Extractor` — chapter parsing is independent of it
- ⬜ Handle multi-period M4Bs: `chapterOffset` is assumed 0 (single period); add period-offset math if a file with edit lists misaligns
- ⬜ Battery measurement on the watch during playback (wakelock mode already `LOCAL`)

## Distribution / infra

- ⬜ CI build: GitHub Actions (or Codeberg/Woodpecker) — needs an Android SDK container
- ⬜ Play Store: $25 account + Wear OS app review, if ever wanted (sideload works indefinitely)
- ⬜ Publish on IzzyOnDroid F-Droid repo as a middle step (no account fee; APK from GitHub releases)

## Session-handoff notes for Claude (non-code)

- Build: `JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleDebug`
- Device: `adb mdns services` to find pair/connect ports (rotate on watch reboot); watch re-locks fast — debug via `--es autoplay` behind the PIN lock
- Screenshots on this watch aren't readable by Claude — use `uiautomator dump` + `logcat | grep WearBite`
- Full project memory: `~/.claude/projects/-home-emre-Projects/memory/wearbook-project.md`
