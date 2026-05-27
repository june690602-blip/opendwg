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

## Status (2026-05-27)
- Phase 0–7 전부 완료. 단위 테스트 59개 통과. 실제 DWG 2종 렌더링 확인.
- **Phase 7 완료**: 렌더링 품질 대폭 개선 (ZWCAD Mobile 벤치마크).
  - DXF 인코딩 자동 감지 (`$DWGCODEPAGE` → MS949/UTF-8 등) — 한글 깨짐 해결
  - AciColor 256색 표준 팔레트 — 레이어별 색상 렌더링
  - POLYLINE (구형식) 파서 + 렌더링 — VERTEX/SEQEND 시퀀스
  - 3DFACE, SOLID 파서 + 렌더링 (SOLID는 FILL)
  - HATCH polyline + line-edge boundary 파싱 + 솔리드 채우기
  - MAX_ENTITIES 50K → 100K
  플랜: `docs/superpowers/plans/2026-05-27-phase7-rendering-quality.md`
- **Phase 6 완료**: 렌더링 품질 개선 + Play Store 출시 준비.
  - `%%D/C/P/U/O` 이스케이프 코드 → 유니코드 변환 (°, ⌀, ±)
  - Fit-to-Screen 아웃라이어 필터링 (`displayExtents`, trimRatio=5%)
  - 뷰포트 컬링 — 화면 밖 엔티티 스킵으로 렌더 성능 개선
  - About 화면 — GPL v3 고지, LibreDWG 저작권 표시
  - 릴리즈 서명 설정 (환경변수 기반, keystore 미커밋)
  스펙: `docs/superpowers/specs/2026-05-27-phase6-rendering-and-release.md`
- **Phase 5 완료**: 다크모드, 설정, 최근파일, Share/View Intent, Toolbar 버그 수정.
- **핫픽스 완료** (`edb41ca`): DxfReader 스트리밍(OOM 방지) + MAX_ENTITIES=50,000(ANR 방지)
  + INSERT 블록 확장(`expandEntities`) — 13MB DWG 파일 정상 렌더링.
- **Next = 수동 검증 + Play Store 출시**: 04_참고도면.dwg / 워킹타워.dwg 비교 스크린샷 후
  ZWCAD 수준 도달 확인 → Play Store 업로드. 미흡 시 Phase 8 (LibreDWG 바이너리 API).

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
