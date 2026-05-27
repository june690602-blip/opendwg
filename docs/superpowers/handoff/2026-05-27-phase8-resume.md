# Phase 8 핸드오프 — 다음 세션 재개 가이드

**작성일:** 2026-05-27
**HEAD 커밋:** `0e6a49d`
**브랜치:** main

---

## 새 세션에서 시작할 때 첫 메시지로 붙여넣기

```
이 문서 읽고 작업 이어가줘:
docs/superpowers/handoff/2026-05-27-phase8-resume.md

기준 플랜: docs/superpowers/plans/2026-05-27-phase8-libredwg-native.md
CLAUDE.md의 ## Status 섹션도 같이 확인.
```

---

## 한눈에 현재 상태

**Phase 8 본진 Tasks 1-10 + 10.5 핫픽스 + 진단/컬링 완료.**

`ref.dwg` (13MB, 한국 건축 도면) 실제 디바이스 검증 결과:
- ✅ ANR 없이 1.2초 내 파싱·표시
- ✅ 한글 정상
- ✅ 색상 (per-entity + per-layer) 작동 — 단, 색상 7번은 라이트모드에서 검정 (AutoCAD 정상 동작)
- ❌ DIMENSION 화살표/수치 안 보임
- ❌ HATCH가 반투명 회색 (패턴 미지원)
- ❌ 여러 시트가 한 화면에 겹쳐 보임 (DWG 파일 구조 그대로)

---

## 다음 작업 (사용자 우선순위)

### Phase 8.5 — DIMENSION 화살표/수치 렌더 (가장 체감 큰 개선)

**근거:** DWG의 DIMENSION 엔티티는 def_pt/text_midpt 등 위치 정보만 가지고, 실제 화살표/연장선/측정값 text는 anonymous block 안에 들어있음. 우리 코드는 이 block을 안 펼침.

**참고 코드:**
- 현재 native 측 `write_dimension` (`app/src/main/cpp/dwg_serialize.c`): def_pt + text_midpt + user_text만 직렬화.
- LibreDWG의 `Dwg_Entity_DIMENSION_ALIGNED`등에 `block` field (Dwg_Object_Ref*) 있음 → BLOCK_HEADER로 이어짐.
- 우리 `write_insert`가 BLOCK_HEADER 자식 엔티티 순회하는 패턴 그대로 사용 가능.

**제안 작업:**
1. C 측에 `write_dimension_block()` 추가 — `e->block->obj->tio.object->tio.BLOCK_HEADER`의 자식 엔티티들을 `write_entity()`로 재귀 처리 (INSERT와 동일 패턴).
2. DIMENSION 자체는 type=DWGB_TYPE_DIMENSION으로 한 번 (def_pt 등) 직렬화하고, 추가로 block 자식들도 함께 직렬화 (transform 적용 안 함 — DIMENSION block의 좌표는 이미 world coord).
3. Kotlin 측은 변경 없음 (block 자식들은 LINE/MTEXT/SOLID 등 일반 엔티티로 옴).

**예상 결과:** 화살표/수치/연장선이 정상 표시됨.

**테스트:** 새 단위 테스트는 native fixture로 어려움. 디바이스에서 ref.dwg 열어 시각 비교.

### Phase 8.6 — HATCH 패턴 라인 생성 (선택적)

**근거:** 현재 모든 HATCH 솔리드는 반투명 회색 fallback. 패턴 HATCH도 stroke만. 다른 캐드앱(ZWCAD) 대비 시각 차이 큼.

**제안 작업:**
1. 자주 쓰이는 패턴 (ANSI31, ANSI32, AR-CONC, ANSI37 등) 10여개의 라인 방향/간격을 하드코딩.
2. Native 측이 HATCH의 pattern_name 추가 직렬화.
3. Kotlin 측에서 boundary 안에 패턴 라인 직접 생성하여 `canvas.drawLine` (clipping with Path).

**복잡도:** 중. 1~2일.

### Phase 8.7 — 시트 분리 UI (선택적)

**근거:** 한 model-space에 여러 시트(평면/입면/단면)가 다른 X/Y 좌표에 있음. 다 한꺼번에 보여서 겹쳐 보임.

**제안 작업:**
1. 엔티티 bounds로 DBSCAN/grid 클러스터링.
2. 발견된 시트 목록을 ViewerActivity 상단 탭/스와이프 UI로 노출.
3. 선택된 시트 영역으로 fit-to-screen.

**복잡도:** 중상. 2~3일.

### Phase 8.8 — 구 DXF 파이프라인 제거 (마무리)

원 플랜의 Task 11. Phase 8.5 안정화 후 삭제.

- `app/src/main/java/.../parser/DxfParser.kt`
- `app/src/main/java/.../parser/DxfReader.kt`
- `app/src/main/java/.../parser/DxfCharsetDetector.kt`
- 해당 테스트 3개
- `dwgjni.c`의 `nativeDwgToDxf` 함수
- `NativeDwg.kt`의 `nativeDwgToDxf` external fun

---

## 빠른 디버그 명령어

```bash
# 빌드 + 설치
./gradlew :app:installDebug

# 강제 종료 + 로그 클리어
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" shell am force-stop io.github.june690602_blip.cleancad
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" logcat -c

# 라이브 로그 (CleanCAD 태그만)
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" logcat -v time | grep --line-buffered -E "CleanCAD/|libdwgjni|FATAL EXCEPTION|ANR in io.github"

# 단위 테스트
./gradlew :app:testDebugUnitTest

# 단위 테스트 개수 확인
find app/build/test-results/testDebugUnitTest -name "*.xml" | xargs grep -c "testcase"
```

기대값: 80개 통과 (ExampleUnitTest 1 + EntityColorDecodingTest 3 + NativeDecoderTest 18 + DxfCharsetDetectorTest 5 + DxfParserTest 30 + DxfReaderTest 7 + AciColorTest 10 + CoordTransformTest 6).

---

## 핵심 파일 빠른 참조

| 영역 | 파일 |
|------|------|
| 바이너리 프로토콜 정의 | `app/src/main/cpp/dwg_serialize.h` |
| Native 직렬화 | `app/src/main/cpp/dwg_serialize.c` |
| JNI 진입점 | `app/src/main/cpp/dwgjni.c` |
| Kotlin 디코더 | `app/src/main/java/.../native/NativeDecoder.kt` |
| 진입 헬퍼 | `app/src/main/java/.../NativeDwg.kt` |
| ViewModel | `app/src/main/java/.../ui/DrawingViewModel.kt` |
| 렌더러 | `app/src/main/java/.../render/EntityRenderer.kt` |
| 색상 모델 | `app/src/main/java/.../model/EntityColor.kt` |
| Drawing model | `app/src/main/java/.../model/Drawing.kt` |
| ACI 팔레트 | `app/src/main/java/.../render/AciColor.kt` |

---

## 알려진 위험/주의사항

1. **`bit_TV_to_utf8` 메모리** — codepage가 CP_UTF8이면 src를 그대로 반환. `tv_to_utf8()`이 strdup으로 항상 malloc된 사본 반환하므로 호출자는 `free()`해도 안전. 이 규칙 깨면 LibreDWG 내부 메모리 손상 → crash.
2. **LibreDWG 필드명 deviation** — 플랜에 적힌 필드명이 LibreDWG 0.13.4와 가끔 다름. Task 8/9에서 발견된 사례:
   - `dwg_getall_LAYER()` (not `dwg_get_layers()`)
   - `dwg->header.codepage` (not `from_version`) for `bit_TV_to_utf8`
   - `lay->frozen/off/locked` (not `lay->flag` bitmask)
   - `Dwg_HATCH_Path.num_segs_or_paths` (둘 다 케이스에 사용)
   - `MTEXT.x_axis_dir` 으로 rotation 계산 (no `.rotation` field)
3. **MAX_ENTITIES = 100K** — `dwg_serialize.h`에 상수. 더 큰 도면이 잘리는 경우만 증가.
4. **Windows CRLF** — LibreDWG submodule의 `configure.ac` CRLF 변환은 `CMakeLists.txt`에서 strip. submodule 워킹트리가 dirty인 게 정상.

---

## TaskList 상태 정리

Phase 7 Tasks 1-8: 모두 완료 (DXF 시도, 부분 성공으로 폐기)
Phase 8 Tasks 1-10 + 10.5 hotfix: 모두 완료
진단 로그 + 렌더 컬링 Task: 완료

남은 pending:
- Phase 8 Task 11 (구 DXF 제거) — 8.5 검증 후 진행
- Phase 8 Task 12 (HATCH arc edges, CLAUDE.md) — 8.5 이후

새 phase (8.5, 8.6, 8.7) 우선.
