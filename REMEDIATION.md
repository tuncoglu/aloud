# Remediation plan

Everything the 2026-08-31 review left open, plus the pre-existing ROADMAP items
that block a release, ordered into phases that can be picked up one session at a
time.

`ROADMAP.md` stays the inventory of ideas; **this file is the ordered plan with
acceptance criteria**. Tick items here first and mirror them into ROADMAP when a
phase closes, so the two never drift silently.

Status: ⬜ todo · 🔄 in progress · ✅ done · ⏸ deferred

## How anything here gets verified

| Gate | Command | State today |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest` | ✅ 20 passing |
| Static analysis | `./gradlew :app:lintDebug` | ❌ **19 errors, 9 warnings** |
| Build | `./gradlew :app:assembleDebug` | ✅ ~42 MB debug APK |
| Device | sideload + `logcat -s WearBite` + `uiautomator dump` | manual |

Prefix every command with `JAVA_HOME=/home/emre/.jdks/temurin-21.0.12.1`.

An item marked **[device]** cannot be closed from the dev machine alone — say so
in the commit rather than claiming it works.

---

## Phase 0 — make the build honest  ·  ~2 h  ·  no device

Lint currently *fails*, so it cannot gate anything and real problems hide behind
the noise. Everything here is mechanical.

- ✅ **R1 — Fix the Media3 opt-in annotation.** All 19 lint errors are one root
  cause: `@OptIn(UnstableApi::class)` on `PlayerManager` does nothing (Media3's
  marker is enforced by `androidx.annotation.experimental`, not Kotlin opt-in) —
  the compiler already says so. Annotate the class with
  `@androidx.media3.common.util.UnstableApi` instead.
  *Verify:* lint errors 19 → 0, and the only compiler warning disappears.
- ✅ **R2 — `android:allowBackup="false"`** (or data-extraction rules excluding
  `files/books`): the default is `true` with gigabytes of audiobooks in
  `filesDir`.
- ✅ **R3 — `android:taskAffinity=""` on `MainActivity`** so the app appears
  correctly in Wear recents (lint `WearRecents`).
- ✅ **R4 — Add a `<monochrome>` layer** to `ic_launcher.xml` (themed icons).
- ✅ **R5 — Merge `res/mipmap-anydpi-v26` into `res/mipmap-anydpi`** — the `-v26`
  qualifier is dead weight at minSdk 30.
- ✅ **R6 — Document the exported `PlaybackService`** with a comment plus
  `tools:ignore="ExportedService"`: it is exported on purpose, because that is
  how `MediaSessionService` is discovered.
- ✅ **R7 — Suppress `StaticFieldLeak` on `PlayerManager.instance` with a
  pointer to R20**, which removes the singleton for real.
- ✅ **R8 — `mutableLongStateOf`** for the sleep-countdown clock in
  `NowPlayingScreen` (lint `AutoboxingStateCreation`).
- ✅ **R9 — Free-space check:** lint suggests `StorageManager#getAllocatableBytes`
  over `File.usableSpace` (it accounts for clearable cache). Either switch or
  suppress with a one-line rationale.
- ✅ **R10 — Version warnings:** Ktor 3.3.3 → 3.5.2 is real (defer if D1 drops
  Ktor). The "newer Kotlin compose plugin" warning is a **false positive** —
  AGP 9.3.2's built-in Kotlin pins 2.2.10 — so suppress it with a comment.

**Acceptance:** `./gradlew :app:lintDebug` exits 0 with **no baseline file**, and
`assembleDebug` + tests stay green.

✅ **Closed 2026-08-31: 0 errors, 3 warnings (all version-notes), 20 tests green,
APK builds.** R1 is the *androidx* `OptIn`, not Kotlin's — `kotlin.OptIn` is
inert against Media3's marker, and `@UnstableApi` on the class only moves the
error onto every caller. `androidx.annotation.experimental` is now an explicit
dependency. R2 needed `dataExtractionRules` + `fullBackupContent` as well, or
lint flags the deprecated `allowBackup` on API 31+. The annotated
`StaticFieldLeak` suppression (R7) carries the R20 pointer.

---


## Phase 1 — correctness the user can feel  ·  ~1 day  ·  device smoke at the end

Small, independent fixes. Each one is a commit.

- ⬜ **R11 — Seek-while-paused shows a stale position.** `positionMs` only
  advances from the ticker, which runs only while playing, so a chapter tap on a
  paused book keeps the old time and highlights the old chapter. Add
  `onPositionDiscontinuity` → `positionMs.value = player.currentPosition`.
  **[device]** to confirm the highlight follows.
- ⬜ **R12 — A late metadata event can wipe the chapter list.** `onMetadata` and
  `onTracksChanged` both assign `chapters.value` unconditionally, empty results
  included, which can clobber what `Mp4ChapterParser` produced. Guard both with
  `isNotEmpty()`.
- ⬜ **R13 — `MediaController` leak in `MainActivity`.** If the activity is
  destroyed before `buildAsync` completes, the listener assigns a live controller
  nothing ever releases. Use `MediaController.releaseFuture(controllerFuture)`.
- ⬜ **R14 — Position is persisted every 5 s** — a full DataStore file rewrite
  each time, thousands per book. Move to ~20 s plus an explicit save on
  pause/seek/stop.
- ⬜ **R15 — Request `POST_NOTIFICATIONS` before the first playback**, not only
  from the Uploader screen; today the media notification can be suppressed until
  the user happens to open the uploader. **[device]**
- ⬜ **R16 — Expired sleep-timer keys are never removed** from DataStore — they
  are filtered on read and linger forever. Clear them during the startup restore.
- ⬜ **R17 — `PlayerPrefs.getLastBook` is never read.** The last book is written
  on every play but never restored. Either implement "open the last book on
  launch" (the intended feature) or delete the pref and its writer. Recommend
  implementing it — the write is already paid for.
- ⬜ **R18 — Parser hardening** (all JVM-testable, add a test per fix):
  - QuickTime titles are decoded as UTF-8 unconditionally; handle a UTF-16 BOM.
  - `chpl` version is read but never validated — only the Nero v1 layout is
    actually parsed.
  - `parseChpl` can read past the box end; clamp reads to `end`.
  - Derive `endMs` for `chpl` chapters from the next chapter's start (they are
    all `-1` today), which also unblocks a chapter-progress UI.
- ⬜ **R19 — Uploader endpoint tests** with Ktor's `testApplication`: PIN
  required on every mutating route, wrong PIN does not refresh the idle timer,
  oversized chunk refused, offset outside the received range refused, name rules
  (Unicode kept, separators/dot-segments/quotes rejected), failed-chunk abort
  leaves no library entry. This closes the gap where today's uploader changes are
  only verified by reading them.

**Acceptance:** tests green (JVM items), one device pass covering R11/R13/R15
plus the still-unverified PIN upload flow.

---

## Phase 2 — the architectural fix  ·  1–2 days  ·  **[device]** heavy

The single change that retires a whole class of bugs: **the UI drives ExoPlayer
directly through a process-wide singleton instead of the `MediaController` the
activity already builds.**

- ⬜ **R20 — Route transport through `MediaController`.** UI → controller →
  session → player, the shape Media3 is designed for. Survives service death,
  and `MainActivity`'s controller stops being a keep-alive stub.
- ⬜ **R21 — Move chapter/sleep/error state out of the player object** into an
  app-scoped observable repository the service writes and the UI reads (they
  share a process, so this stays cheap). Alternative: session extras + custom
  commands — heavier, only needed if the UI ever moves out of process.
- ⬜ **R22 — Delete the `PlayerManager` singleton and its `clear()` workaround.**
  A service teardown while the UI is alive currently leaves the composition
  holding a released player; lint's `StaticFieldLeak` (R7) also disappears here.

**Acceptance [device]:** kill the service while the UI is open → no crash;
lock-screen playback, Bluetooth media keys and the media notification all still
work; resume position still survives a process kill.

**Risk:** the project memory records that lock-screen playback *requires* the
MediaController binding to promote the FGS. This is the change most likely to
regress it — do it on its own branch and keep the working commit reachable.

**Decided 2026-08-31:** accepted — proceed with the refactor and triage any
regression as it appears, rather than designing around it.

---

## Phase 3 — a real release build  ·  ~1 day  ·  starts with a decision

> **Decision D1 — keep Ktor, or replace it?**
> Ktor is why the watch APK ships `jansi.dll` (Windows!) and macOS dylibs, plus
> slf4j, websockets and serialization the app never uses; ~41 MB of the 42 MB
> debug APK is dex. Only `ktor-utils` bundles R8 rules, and `ktor-server-core`
> loads `io.ktor.server.config.ConfigLoader` through a `ServiceLoader`, so R8
> needs hand-written keeps. The uploader uses five endpoints and no Ktor feature
> beyond routing.
> **Recommendation:** timebox R8-with-Ktor to ~2 h; if the release build misbehaves,
> replace the server with ~150 lines over `ServerSocket`, which deletes the
> dependency, the natives and the keep rules at once.
>
> **Decided 2026-08-31:** the recommendation stands — try R8-with-Ktor first
> (timeboxed), fall back to the `ServerSocket` implementation.

- ⬜ **R23 — `buildTypes { release { ... } }`** with `isMinifyEnabled`,
  `isShrinkResources`, and a signing config whose keystore path/passwords come
  from `~/.gradle/gradle.properties` (never the repo).
- ⬜ **R24 — R8 keep rules** for whatever survives D1, then **[device]** verify a
  *release* build: playback, chapters, and the full upload flow.
- ⬜ **R25 — Packaging excludes:** `org.fusesource.jansi` (and its natives),
  redundant `META-INF` licence files; drop unused ktor modules if Ktor stays.
- ⬜ **R26 — Gate the debug intent extras** (`--es autoplay`, `--es screen`,
  `--es uploader_start`) behind `BuildConfig.DEBUG`.
- ⬜ **R27 — Gate `Log.d`** behind `BuildConfig.DEBUG` (a two-line wrapper), so
  release builds stop compiling in the string concatenation.

**Acceptance:** signed release APK installs on the watch, plays, uploads; APK
size recorded in the README. Target under 10 MB.

---

## Phase 4 — make it Wear-native  ·  1–2 days  ·  **[device]**

Confirmed available in the *already pinned* wear-compose 1.6.2 — no version bump:
`TransformingLazyColumn` and `ScalingLazyColumn` (foundation.lazy),
`Modifier.rotaryScrollable` (foundation.rotary), `ScreenScaffold`/`AppScaffold`/
`TimeText` (material3).

- ⬜ **R28 — Replace the plain `LazyColumn`s** in Library and Chapters with
  `TransformingLazyColumn` inside a `ScreenScaffold` (curved-edge scaling, scroll
  indicator).
- ⬜ **R29 — Rotary input.** The crown/bezel does not scroll anything today. Wire
  `Modifier.rotaryScrollable` (or the list's built-in rotary support) to both
  lists. This is the most-felt gap on a real watch.
- ⬜ **R30 — `contentDescription` on the ⏮/⏸/▶/⏭ buttons** — they are bare emoji
  and unreadable to TalkBack.
- ⬜ **R31 — Auto-scroll the chapter list to the current chapter** on open.
- ⬜ **R32 — Move UI strings into `strings.xml`** (the file exists and holds only
  the app name).
- ⬜ **R33 — Show progress % per book** in the library and the **current chapter
  title** on the now-playing screen (both are data the app already has).
- ⬜ **R34 — Delete a book from the watch UI** (only possible over HTTP today).
- ⬜ **R35 — `TimeText`** on the main screens.

---

## Phase 5 — distribution  ·  ~0.5 day + waiting

- ⬜ **R36 — CI** (GitHub Actions or Woodpecker on Codeberg) running
  `assembleDebug`, `testDebugUnitTest` and `lintDebug` in an Android SDK
  container. The lint gate only becomes possible after Phase 0.
- ⬜ **R37 — IzzyOnDroid** submission once Phase 3 produces a signed APK from a
  tagged release (no account fee; the middle step before F-Droid proper).
- ⏸ Play Store — $25 + Wear review, only if sideloading ever stops being enough.

---

## Deferred — not remediation, just wishes

⏸ ALAC-only M4B detection · library search/filter · bookmarks · skip-silence ·
volume boost · edit-list (`elst`) offsets (no affected file seen yet) · parser
fixtures from other muxers (needs sample files) · on-watch battery measurement.

---

## If there is only an hour

1. **[device]** Re-verify the PIN upload flow shipped on 2026-08-31 — it is the
   only way books get onto the watch and it has never been run there. (~10 min)
2. Phase 0 (lint goes green). (~2 h, no device)
3. R11 + R12 + R13 — three small, self-contained correctness fixes. (~1 h)
