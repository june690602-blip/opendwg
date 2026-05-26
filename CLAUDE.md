# CleanCAD Viewer

Ad-free Android DWG viewer for construction-site users. Open source, GPL v3.
(GitHub repo name is `opendwg`; the app's display name is "CleanCAD Viewer".)

## Stack
- Kotlin, Android Views (XML layouts). minSdk 24, compile/targetSdk 36.
- DWG parsing: **LibreDWG 0.13.4** (GPL v3), vendored as a git submodule under
  `app/src/main/cpp/libredwg`, built via CMake/NDK, called over JNI.
- Rendering pipeline (planned): DWG → DXF → parse entities → custom `Canvas` (`DrawingView`).
- Package / applicationId: `io.github.june690602_blip.cleancad`

## Key docs (read these first to get oriented)
- Design spec — all decisions: `docs/superpowers/specs/2026-05-25-cleancad-viewer-design.md`
- Phase 2 plan: `docs/superpowers/plans/2026-05-25-phase2-libredwg-ndk.md`

## Status (2026-05-26)
- Phase 0/1 done (skeleton builds).
- **Phase 2 done**: LibreDWG cross-compiled for arm64-v8a / armeabi-v7a / x86_64,
  JNI wrapper (`libdwgjni.so`) links LibreDWG and returns its version; tests pass.
- **Next = Phase 3**: `dwgToDxf(inPath, outPath)` JNI + Kotlin DXF parser + Drawing model.
  The design is already in the spec — go straight to writing a Phase 3 plan.

## Build & test
- After clone, init the submodule: `git submodule update --init --recursive`
- Build: `./gradlew :app:assembleDebug`
- Instrumented tests (need a running x86_64 emulator, e.g. AVD `Medium_Phone_API_36.1`):
  `./gradlew :app:connectedDebugAndroidTest`
- Toolchain: NDK `30.0.14904198`, CMake `4.1.2` (pinned in `app/build.gradle.kts`).

## Gotchas
- **Windows CRLF**: Git's system `core.autocrlf=true` checks out LibreDWG's
  `configure.ac` with CRLF, which corrupts the generated `config.h` and breaks the
  whole native compile. `app/src/main/cpp/CMakeLists.txt` strips CRs from
  `configure.ac` at configure time to work around this. (This leaves the submodule
  working tree dirty — that's expected; don't commit the submodule content change.)

## Conventions
- Immutable Kotlin data classes for the drawing model; many small, focused files.
- License: GPL v3 — linking LibreDWG makes the whole app GPL v3, so source stays public.
