# CleanCAD Viewer

Ad-free Android DWG viewer for construction-site users. Open source, GPL v3.
(GitHub repo name is `opendwg`; the app's display name is "CleanCAD Viewer".)

## Stack
- Kotlin, Android Views (XML layouts). minSdk 24, compile/targetSdk 36.
- DWG parsing: **LibreDWG 0.13.4** (GPL v3), vendored as a git submodule under
  `app/src/main/cpp/libredwg`, built via CMake/NDK, called over JNI.
- Rendering pipeline: **DWG → native serializer (Dwg_Data 직접 순회) → binary buffer
  → Kotlin NativeDecoder → Drawing model → custom `Canvas` (`DrawingView`)**.
  DXF 텍스트 중간단계는 Phase 8에서 제거됨 (현재 `nativeDwgToDxf` 함수 잔존하나
  사용 안 함; Task 11에서 삭제 예정).
- Package / applicationId: `io.github.june690602_blip.cleancad`

## Key docs (read these first to get oriented)
- Design spec — all decisions: `docs/superpowers/specs/2026-05-25-cleancad-viewer-design.md`
- Phase 8 plan (현재 진행 중): `docs/superpowers/plans/2026-05-27-phase8-libredwg-native.md`
- Phase 7 plan (DXF 시도, 부분 성공): `docs/superpowers/plans/2026-05-27-phase7-rendering-quality.md`

## Status (2026-05-28) — Phase 8.7 완료

**현재 HEAD: 커밋 예정** — SheetClusterer P5-P95 percentile 기반 그리드 클러스터링 + TabLayout 시트 내비게이션.

**다음 phase 핸드오프:** `docs/superpowers/handoff/2026-05-28-phase8.7-resume.md`

### 작동 중 ✅
- LibreDWG 바이너리 API로 직접 DWG 파싱 (DXF 중간단계 없음)
- 한글/일본어/중국어 인코딩 정상 (`bit_TV_to_utf8` 사용)
- 엔티티별 색상 (BYLAYER/BYBLOCK/ACI/RGB)
- 13MB DWG 파일 1초 내 파싱 (110K objects → 100K entities)
- 단위 테스트 86개 통과 (SheetClusterer 6 + Kotlin native 디코더 17 + EntityColor 3 + 기존)
- LINE/CIRCLE/ARC/POLYLINE/3DFACE/SOLID/ELLIPSE/SPLINE/HATCH/DIMENSION/LEADER/TEXT/MTEXT 디코딩
- INSERT 블록 재귀 전개 (depth 5, affine transform)
- **DIMENSION anonymous block 전개 (Phase 8.5)** — `clone_ins_pt` 변환으로 화살표/연장선/측정값
  텍스트가 제 위치에 렌더. 사용자 확인됨 ("수치들은 다 제자리로 가있네").
- model-space만 순회 (paper-space 무시 — 검증: PSPACE num_owned=0, layout block 모두 비어 있음)
- Layer name → index 캐시 (O(N²) → O(N))
- 화면상 1px 미만 엔티티 + 4px 미만 텍스트 컬링 → 100K 엔티티에서도 ANR 안 남
- HATCH 솔리드 채우기 25% 반투명 + 경계 항상 stroke (검정 덩어리 방지)
- `fit-to-screen`이 `extents`/`displayExtents` 둘 다 null 처리 — Kotlin에서 entity bounds로 계산
- **시트 클러스터링 + TabLayout (Phase 8.7)** — P5-P95 percentile 기반 그리드 BFS 클러스터링.
  ref.dwg 기준 9개 시트 자동 감지 (171ms), 탭별 fit-to-screen 동작. 디바이스 검증 완료 (2026-05-28).
  SheetClusterer: `model/SheetClusterer.kt`, UI: `ui/ViewerActivity.kt` + `render/DrawingView.kt`

### 진행 중 ⏳ — 다음 세션에서 이어갈 작업

수동 검증 결과 (`ref.dwg` 13MB, 110K objects, 디바이스 직접 검증 2026-05-28):
1. **HATCH 패턴 미지원** — ANSI31/AR-CONC/벽돌무늬 등 모두 반투명 회색 솔리드로 fallback.
   다른 캐드앱 대비 시각적 차이 큼. → Phase 8.6 (우선순위 낮음): 자주 쓰이는 10여개 패턴 라인 생성.
2. **구 DXF 파이프라인 잔존** — `DxfParser`, `DxfReader`, `DxfCharsetDetector`,
   `nativeDwgToDxf` JNI 함수 모두 미사용 상태로 유지 (회귀 대비). 다음 단계에서
   삭제 (원 플랜의 Task 11).

### 디버그/로깅
- `adb logcat | grep CleanCAD/` 로 ViewModel(파일 카피/파싱 타이밍, entity/layer 개수,
  extents bounds) + dwgjni(dwg_read_file rc, num_objects, serialized bytes) 추적 가능.
- 진단 워크플로우: `ref.dwg` 데이터 — `extents=BoundingBox(-2M..2M, -2.7M..1.5M)`,
  `mspace top-level=7,947`, `entities=100,000` (MAX_ENTITIES 도달), `serialized=6.2MB`, parse 1.2초.
- 디바이스 자동화 명령은 Phase 8.7 핸드오프 문서 참조 (`ADB shell input tap` 시퀀스).

### 이전 phase 요약
- **Phase 8.5**: DIMENSION block 전개 + `clone_ins_pt` 변환. 수치/화살표 정상 위치 렌더.
- **Phase 8**: LibreDWG 바이너리 API 직접 호출, native serializer, INSERT 재귀 전개.
- **Phase 7**: DXF 텍스트 중간단계 기반 시도. 인코딩/색상 부분만 해결, 누락 엔티티 미해결.
  Phase 8 채택 배경.
- **Phase 6**: 텍스트 이스케이프, 컬링, 릴리즈 서명, About.
- **Phase 5**: 다크모드, 설정, 최근파일.
- **Phase 0–4**: LibreDWG NDK 빌드 + DXF 파서 + Renderer 골격.

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
