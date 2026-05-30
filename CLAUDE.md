# CleanCAD Viewer

Ad-free Android DWG viewer for construction-site users. Open source, GPL v3.
(GitHub repo name is `opendwg`; the app's display name is "CleanCAD Viewer".)

## Stack
- Kotlin, Android Views (XML layouts). minSdk 24, compile/targetSdk 36.
- DWG parsing: **LibreDWG 0.13.4** (GPL v3), vendored as a git submodule under
  `app/src/main/cpp/libredwg`, built via CMake/NDK, called over JNI.
- Rendering pipeline: **DWG → native serializer (Dwg_Data 직접 순회) → binary buffer
  → Kotlin NativeDecoder → Drawing model → custom `Canvas` (`DrawingView`)**.
  DXF 텍스트 중간단계는 Phase 8에서 제거됨. Task 11(2026-05-29)에서
  `nativeDwgToDxf` 함수 및 DxfParser/Reader/CharsetDetector 코드 완전 삭제 완료.
- Package / applicationId: `io.github.june690602_blip.cleancad`

## Key docs (read these first to get oriented)
- Design spec — all decisions: `docs/superpowers/specs/2026-05-25-cleancad-viewer-design.md`
- Phase 8 plan (현재 진행 중): `docs/superpowers/plans/2026-05-27-phase8-libredwg-native.md`
- Phase 7 plan (DXF 시도, 부분 성공): `docs/superpowers/plans/2026-05-27-phase7-rendering-quality.md`

## Status (2026-05-30) — Phase 10 렌더링 성능 최적화 완료

**현재 HEAD: `0478a52`** (Phase 9.4 XCLIP). Phase 10 변경은 **working tree 미커밋**.

> **Phase 10 (2026-05-30) 렌더링 성능 최적화 완료 — 팬/줌 렉 제거.**
> 근본 원인: `EntityRenderer.drawAll`이 매 프레임 188K 엔티티 전체를 2회 스캔 + `worldBounds()`
> 매 프레임 재계산(polyline/hatch `minOf` 람다 → iterator/박싱 할당 → GC 폭증). 공간 인덱스 없음.
> 수정: **`render/SpatialIndex.kt`** 신규 — 로드 시 1회 ① 엔티티별 bounds 캐시 + ② 균일 그리드
> (긴 축 128분할) 구축. `drawAll`은 viewport와 겹치는 셀의 후보만 조회(187K→가시영역 수~수천),
> 캐시 bounds로 컬링. 텍스트도 인덱싱(넉넉한 근사 bounds)해 viewport 컬링 추가.
> **측정(`04_chamgo.dwg` 187,907 엔티티, 에뮬 gfxinfo): fit 팬 1600ms→97~150ms(~10–16×),
> 중간 줌(9x) 18ms, 고배율(81x) 17ms — 작업 줌은 모두 ~60fps.** 단위테스트 SpatialIndex 6개 추가.
> 시각 검증: 줌인 시 한글/치수/표제란 텍스트 정상(누락 없음). 비트맵 캐시(#3)는 fit 팬이라는
> 드문 케이스만 남아 **불필요 판단**(회귀 위험·메모리 대비 효용 낮음).
> 상세: `docs/superpowers/handoff/2026-05-30-phase10-render-performance.md` (완료 노트 추가됨).
>
> **Phase 9.4 (2026-05-30) XCLIP 완료 — 떡덩어리/겹침 해결, 사용자 만족.**
> 근본 원인: XCLIP(SPATIAL_FILTER) 미지원 — crop/xref 블록이 클립 무시하고 블록 전체를 렌더해 겹침.
> 수정: `dwg_serialize.c`가 INSERT의 SPATIAL_FILTER(xdic→ACAD_FILTER→SPATIAL)로 자식 엔티티를
> **기하 클리핑**(선/폴리라인 Liang-Barsky 분할, 해치 overflow 컬링, 중첩 crop 클립 교집합).
> 좌표 모델 실측 확정(`clip_world = childTransform(invXform2D(clip_vert))`). seg_clipped=5,115,
> emitted 435,609→187,907. **시트 탭 제거**(무의미 판단, SheetClusterer는 displayExtents용 내부 유지),
> displayExtents=시트합집합, INSERT base_pt 보정 포함. **모두 working tree 미커밋.**
> ⚠️ 에뮬레이터 함정: `adb install -r`이 native .so 갱신 안 함 → native 변경 시 uninstall+install 필수.
>
> **다음(Phase 10): 렌더링 성능 최적화** — 팬/줌 렉. 근본원인=매 프레임 188K 엔티티 전체 순회+
> worldBounds 재계산, 공간 인덱스/캐싱 없음. plan: worldBounds 캐싱 → 공간 그리드 인덱스 → 비트맵 캐시.
> 상세는 Phase 10 핸드오프 참조.

### 작동 중 ✅
- LibreDWG 바이너리 API로 직접 DWG 파싱 (DXF 중간단계 없음)
- 한글/일본어/중국어 인코딩 정상 (`bit_TV_to_utf8` 사용)
- 엔티티별 색상 (BYLAYER/BYBLOCK/ACI/RGB)
- 13MB DWG 파일 1초 내 파싱 (110K objects → 100K entities)
- 단위 테스트 63개 통과 (NativeDecoder 18 + ColorInvert 13 + AciColor 10 + SheetClusterer 6 +
  CoordTransform 6 + SpatialIndex 6 + EntityColorDecoding 3 + ExampleUnit 1)
- LINE/CIRCLE/ARC/POLYLINE/3DFACE/SOLID/ELLIPSE/SPLINE/HATCH/DIMENSION/LEADER/TEXT/MTEXT 디코딩
- **MULTILEADER(MLEADER) 지원 (Phase 10.1)** — 리더선→LWPOLYLINE, dogleg→LINE, content→MTEXT로
  분해해 native에서 내보냄(디코더 변경 없음). `04_참고도면.dwg` 239개 MLEADER 주석 렌더 확인.
- **POINT 지원 (Phase 10.2)** — 새 DWGB_TYPE_POINT(17). `DxfPoint` 모델, 줌 무관 1.5px 고정 점으로
  렌더(픽셀컬 예외). 클러스터링 제외(centroid=null)·extents 미반영(worldBounds=null), 공간인덱스엔
  포함. `04_참고도면.dwg` 2,763점 렌더(나머지는 XCLIP 클립 밖이라 컬링).
- **IMAGE/WIPEOUT/OLE2FRAME/MINSERT/ATTRIB 지원 (Phase 10.3)** — 모두 native에서 기존 레코드로 분해
  (디코더/모델/렌더 무변경): IMAGE/WIPEOUT=pt0+uvec·vvec 4코너 프레임→LWPOLYLINE, OLE2FRAME=pt1/pt2
  사각형, MINSERT=블록을 num_cols×num_rows 격자 전개, ATTRIB=INSERT 속성값→TEXT(부모 변환 적용).
  검증: "입면도"·"1:60"·"DRAWING NAME" 표제란 라벨(ATTRIB), 사진 프레임(IMAGE) 렌더 확인.
  엔티티 191,148→192,897(+1,749). ⚠️ ATTDEF(블록 내 속성 정의 템플릿)는 의도적 미렌더(ATTRIB 값으로 대체).
- **도면목록표/표지/사업개요 컬링 해결 (Phase 10.4)** — 원인: SheetClusterer가 P5-P95로 시트 bbox를
  클리핑해, 엔티티 적고 메인 위에 떨어진 저밀도 시트(표지·도면목록표·사업개요·위치도)가 상위 5%로
  밀려 검출 bbox에서 빠짐 → 그 합집합 displayExtents가 renderBounds로 그 시트들을 컬링. 수정:
  `SheetClusterer.inclusiveExtents` — 검출 시트 합집합을 seed로 1배 확장한 영역 안의 엔티티 bbox.
  인접 저밀도 시트는 포함, 먼 junk outlier(±수백만)는 제외. 도면목록표 표/사업개요/위치도 렌더 확인.
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
2. **"ㄱ 모양" 산발 마커 미조사 (Task 12)** — 시트 8/9에서 보고됨. LEADER arrowhead,
   DIMENSION 정의점, 작은 INSERT 심볼 중 하나로 추정. 에뮬레이터 줌인 후 원인 파악 필요.

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
