# Roadmap — improvements for future sessions

Status markers: ✅ done · 🔶 needs user hands-on test · ⬜ todo

Inventory of ideas lives here; the **ordered remediation plan with acceptance
criteria** lives in [REMEDIATION.md](REMEDIATION.md). Tick items there first.

## User-facing features (v1+)

- ✅ Browser drag-and-drop upload with a real book — verified 2026-08-30 (4 real books, ~1.2GB, byte-exact; chunked upload, complete-rename, delete, 2-min auto-stop)
- 🔶 Re-verify that drag-and-drop path after the 2026-08-31 uploader changes (PIN field, per-chunk retry, streamed writes) — not yet run against the watch
- ✅ On-watch tap-through: chapter list seek, speed chip cycling (full cycle incl. 2.0× and wrap), sleep-timer chip — verified 2026-08-30
- ⬜ Compute proper chapter end times: `chpl` chapters get `endMs = -1`; derive from next chapter start / media duration
- ⬜ Show current chapter title on the now-playing screen (have the data, not the UI)
- ⬜ Library list: show progress % per book (DataStore positions already available)
- ✅ Sleep-timer chip: now cycles by the armed option (15→30→60→120→off, mid-countdown safe; armed minutes persisted like the deadline)
- ⬜ Delete book from the watch UI (only possible via HTTP DELETE today)
- ✅ Uploader PIN for untrusted WiFi — 6-digit code per server start, shown on the watch and in the notification, required for upload/list/delete; 20 wrong guesses stop the server, and a wrong PIN does not count as activity (so it cannot hold the idle stop open)
- ⬜ Detect ALAC-only M4Bs and warn (ExoPlayer has no ALAC decoder; advise AAC transcode)
- ⬜ Deferred from v1 scope: bookmarks, skip-silence, volume boost
- ⬜ Library search/filter once book count grows past a screenful

## Engineering robustness

- ⬜ **Release build + signing** (debug-only today)
- ⬜ Gate debug-only hooks behind `BuildConfig.DEBUG`: the `--es autoplay` / `--es screen` / `--es uploader_start` intent extras
- ⬜ Reduce `Log.d` spam or gate behind `BuildConfig.DEBUG` (currently always compiled)
- ✅ **Unit tests for `Mp4ChapterParser`** (20 tests, `:app:testDebugUnitTest`) — synthetic `chpl`/QuickTime files from `Mp4Builder.kt`. They immediately found a real bug: `stsz` read its sample count from +12 (the first *size* entry) instead of +8, which capped the chapter count at "however many bytes the first sample happens to be" and shifted every size one entry down, truncating titles whenever the next sample was shorter. Fixed, plus fixed-size `stsz` tables are now handled
- ⬜ Test chapter parsing against other real M4Bs (different muxers: iTunes, m4b-tool, audiobook binder) — fix parser for any variants found
- 🔶 Verify MP3 ID3 chapters arrive via the `onMetadata` path — classic Mp3Extractor is now used (ID3 metadata on by default); no CHAP-tagged MP3 on hand to verify with
- 🔶 Verify long uploads survive doze (dataSync FGS + wakelock present; unproven for 30+ min transfers)
- ✅ MediaParser extractor path retired 2026-08-30 — classic extractors for both formats (its MP3 backward seeks were broken on-device); chapters come solely from `Mp4ChapterParser`
- ⬜ Handle edit lists (`elst`): chapter times are taken straight from the media timeline with no period/edit offset, so a file with a non-trivial edit list can be shifted (no such file seen yet)
- ⬜ Battery measurement on the watch during playback (wakelock mode already `LOCAL`)

## From the 2026-08-31 review (not yet fixed)

All of it — plus the release/CI/Wear-UI work above — is sequenced with acceptance
criteria in [REMEDIATION.md](REMEDIATION.md) (items R1–R37, five phases). Highlights:
lint currently fails with 19 errors that share one root cause (R1), the crown does
not scroll any list (R29), seeking while paused shows a stale position (R11), and
the UI still drives ExoPlayer through a process-wide singleton instead of the
MediaController it already builds (R20).

## Distribution / infra

- ⬜ CI build: GitHub Actions (or Codeberg/Woodpecker) — needs an Android SDK container
- ⬜ Play Store: $25 account + Wear OS app review, if ever wanted (sideload works indefinitely)
- ⬜ Publish on IzzyOnDroid F-Droid repo as a middle step (no account fee; APK from GitHub releases)

## Session-handoff notes for Claude (non-code)

- Build: `JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1 ./gradlew :app:assembleDebug`
- Device: `adb mdns services` to find pair/connect ports (rotate on watch reboot); watch re-locks fast — debug via `--es autoplay` behind the PIN lock
- Watch quirks (2026-08-30): reboot turns WiFi debugging OFF and WiFi can linger off — re-enable both, then reconnect (mdns or `adb connect <ip:port>` from the wireless-debugging screen); after mdns connect the device may appear under two names — use `adb -s <ip:port>` explicitly; the watch kills the app process aggressively when idle (expect pid churn between commands; the media FGS keeps it alive while playing)
- Screenshots on this watch aren't readable by Claude — use `uiautomator dump` + `logcat | grep WearBite`
- Full project memory: `~/.claude/projects/-home-emre-Projects/memory/wearbook-project.md` (session log: `-home-emre-Projects-wearbook/memory/wearbook-review-findings.md`)
