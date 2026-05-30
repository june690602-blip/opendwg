# Phase 10 핸드오프 — 렌더링 성능 최적화

**작성: 2026-05-30**
**작업 위치: `C:/dev/opendwg` (main 워크스페이스, 워크트리 안 씀)**
**목표: 팬/줌 시 렉 제거 — ZWCAD에 근접한 부드러운 렌더링**

---

## 0. 먼저: 이전 작업(Phase 9.4 XCLIP)이 working tree에 미커밋 상태

이번 perf 세션 시작 전, **Phase 9.4까지의 변경을 먼저 커밋하는 것을 강력 권장**한다(perf 변경을 격리하기 위해). 커밋 안 하면 큰 diff 위에 또 쌓이게 됨.

미커밋 변경 파일:
- `app/src/main/cpp/dwg_serialize.c` — XCLIP 기하 클리핑 + base_pt 보정 (Phase 9.1/9.2도 포함)
- `app/src/main/java/.../render/DrawingView.kt`, `EntityRenderer.kt` — Phase 9.1/9.2
- `app/src/main/java/.../ui/DrawingViewModel.kt` — displayExtents=시트합집합
- `app/src/main/java/.../ui/ViewerActivity.kt` + `res/layout/activity_viewer.xml` — 시트 탭 제거
- `app/src/main/cpp/libredwg` — CRLF 더티(예상됨, **커밋하지 말 것**)
- `CLAUDE.md` — 상태 갱신

Phase 9.4에서 한 일 요약(상세: `2026-05-30-phase9.4-xclip-spatial-filter.md`):
떡덩어리/겹침의 근본원인은 XCLIP(SPATIAL_FILTER) 미지원이었고, INSERT의 클립 경계로 자식 엔티티를
기하 클리핑(선/폴리라인 Liang-Barsky 분할, 해치 overflow 컬링)하도록 구현. 시트 탭은 무의미하다는
사용자 판단으로 제거(SheetClusterer는 displayExtents 계산용으로만 내부 유지). **사용자 만족, 1차 완료.**

---

## 1. 문제 — 팬/줌이 ZWCAD 대비 매우 렉

`04_chamgo.dwg` 로딩 후 약 **187,907개 엔티티**. 팬/줌 시 버벅임.

### 근본 원인 (측정/코드 분석 기반)
1. **매 프레임 전체 엔티티 스캔.** `EntityRenderer.drawAll()`이 onDraw(프레임)마다 188K 엔티티
   전부 순회하며 각각:
   - `entity.worldBounds()` 호출 — **매 프레임 재계산**(캐싱 없음). `render/EntityBounds.kt`의 확장함수.
   - renderBounds 교차, viewport 교차, 픽셀 컬 체크
   - 색상별 FloatBuf에 라인 누적 → 색상마다 `canvas.drawLines()` flush
2. **공간 인덱스 없음.** 화면에 안 보이는 엔티티도 매 프레임 다 훑음(보이는 건 보통 수천 개).
3. **렌더 캐싱 없음.** 팬만 해도 처음부터 다시 그림 (matrix.postTranslate → invalidate → 전체 재렌더).
4. **메인 스레드 Canvas.** 프레임당 수만~십만 drawLines 명령.

### ZWCAD가 빠른 이유 (벤치마크)
네이티브 C++ + GPU 가속 + 공간 인덱스(R-tree/quadtree) + 디스플레이 리스트 + 변경영역만 부분갱신.
→ 우리도 **공간 인덱스 + 캐싱**으로 근접 가능. 근본적으로 불가능한 게 아니라 미구현일 뿐.

---

## 2. 최적화 plan (우선순위)

| # | 기법 | 기대효과 | 작업량 | 비고 |
|---|------|---------|--------|------|
| 1 | **worldBounds 1회 캐싱** | 중 | 작음 | 매 프레임 재계산 제거. 로드 시 `List<BoundingBox?>` 병렬배열(entityColors처럼) 또는 엔티티에 lazy 캐시 |
| 2 | **공간 그리드/quadtree 인덱스** | **큼** | 중 | 로드 시 1회 구축. drawAll에서 viewport와 겹치는 셀의 엔티티만 순회 (188K→수천) |
| 3 | **비트맵/타일 캐시** | 큼(팬) | 중 | 줌 고정 시 오프스크린 비트맵에 렌더, 팬=비트맵 translate. 줌 변경/캐시영역 이탈 시만 재렌더 |
| 4 | **LOD** | 중 | 작음 | 줌아웃 시 작은 엔티티 스킵(픽셀컬 있으나 순회비용은 #2가 해결). 더 공격적 임계 |

**권장 순서: 1 → 2 → (측정) → 3.** #1+#2만으로도 체감 크게 개선될 것. #3은 그 후 필요시.

### 설계 노트
- **공간 인덱스(#2)**: 균일 그리드 추천(quadtree보다 단순/충분). 셀 크기 ~ displayExtents/100 정도.
  로드 시 각 엔티티를 worldBounds 기준 겹치는 셀에 등록. drawAll에서 현재 viewport(world)와
  겹치는 셀 합집합의 엔티티만 그림. 중복 방문 방지(visited set 또는 frame id).
  - 인덱스 구축은 NativeDecoder 후 / DrawingViewModel에서 1회. Drawing 모델에 인덱스 부착하거나
    DrawingView가 setDrawing 시 구축.
- **worldBounds 캐싱(#1)**: 가장 싸고 즉효. `EntityRenderer.setDrawing`에서 `entityColorByIdentity`처럼
  `entityBounds: Map<DxfEntity,BoundingBox?>` 또는 인덱스 배열로 1회 계산해두고 drawAll에서 참조.
  (공간 인덱스 #2를 만들면 그 안에 bounds가 자연히 포함되므로 #1은 #2에 흡수 가능.)
- **비트맵 캐시(#3)**: `DrawingView`에 offscreen `Bitmap`+`Canvas`. 줌(scaleFactor) 변경 시 dirty.
  팬은 비트맵을 matrix로 blit. 단 메모리(큰 비트맵) 주의 — 화면크기+여유 정도만.
- **현재 라인 배칭(FloatBuf per color)은 유지** — 이미 drawLines 호출수는 줄여둠(Phase 9.2). 병목은
  "순회 자체"라 #2가 핵심.

### 주의/트레이드오프
- Phase 9.4 폴리라인 분할로 엔티티가 17K↑(170→188K). perf엔 불리하나 정확도 위해 유지. #2로 상쇄.
- 캐싱 도입 시 메모리 사용량 모니터(이미 13MB DWG → 188K 엔티티 → 큰 객체 그래프).

---

## 3. 핵심 파일

| 파일 | 역할 |
|------|------|
| `render/EntityRenderer.kt` | `drawAll()` — 매 프레임 핫루프. 여기가 주 수정 대상 |
| `render/DrawingView.kt` | `onDraw()`, 제스처(matrix), `fitToScreen()`. 비트맵 캐시는 여기 |
| `render/EntityBounds.kt` | `DxfEntity.worldBounds()` 확장함수 (캐싱 대상) |
| `render/CoordTransform.kt` | `worldToScreen`, `screenToWorldBounds`, `currentScale`, `fitMatrix` |
| `model/Drawing.kt`, `model/DxfEntity.kt` | 모델. 인덱스 부착 위치 후보 |
| `ui/DrawingViewModel.kt` | 로드 파이프라인(파싱→클러스터→displayExtents). 인덱스 구축 후보 |

---

## 4. 성능 측정 방법 (먼저 베이스라인 측정할 것)

추측 말고 측정 우선. drawAll 프레임타임을 재서 before/after 비교.

- **drawAll 타이밍 로그(임시)**: `EntityRenderer.drawAll` 시작/끝 `System.nanoTime()` 차이 로깅
  (단, 프레임마다 로그하면 폭증 → 30프레임 평균 또는 매 N프레임만).
- **gfxinfo**: `adb shell dumpsys gfxinfo io.github.june690602_blip.cleancad` — 프레임 통계(jank 비율).
  팬/줌 제스처 직후 호출.
- **programmatic 팬으로 재현**: `adb shell input swipe` 반복으로 일정한 팬을 만들어 before/after 동일조건 측정.
- 목표: 팬 중 프레임타임 < 16ms (60fps). 현재 추정 수십 ms.

---

## 5. 검증 환경 (디바이스/에뮬레이터) — ⚠️ 함정 주의

- **에뮬레이터**: AVD `Medium_Phone_API_36.1` (x86_64). 부팅:
  `"$SDK/emulator/emulator.exe" -avd Medium_Phone_API_36.1 -no-snapshot-load &`
  (SDK = `/c/Users/bogeun/AppData/Local/Android/Sdk`)
- **adb**: `/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb.exe` (PATH에 없음).
- **⚠️⚠️ 결정적 함정: `adb install -r` 이 이 에뮬레이터에서 네이티브 .so(libdwgjni.so)를 갱신하지 않음.**
  네이티브(.c) 변경 후엔 반드시 **`adb uninstall ...` 후 `adb install ...`** 으로 클린 설치할 것.
  (Kotlin만 바꿨으면 install -r로도 됨. 헷갈리면 그냥 항상 uninstall+install.)
  이걸 모르면 "코드 바꿨는데 화면 그대로"로 한참 헤맴(이번 세션에서 겪음).
  - 이번 perf 작업은 주로 **Kotlin(render)** 이라 install -r로도 대체로 OK. 단 native 건드리면 클린설치.
- **테스트 파일**: 에뮬레이터 `/sdcard/Download/04_chamgo.dwg`, MediaStore `_id=57`. 호스트 원본
  `C:/dev/opendwg/.analysis/samples/04_chamgo.dwg`. (재push 시 MEDIA_SCANNER 브로드캐스트로 재등록 후 _id 재확인)
- **로딩 명령** (EACCES 회피 — content:// 필수):
  ```
  "$ADB" shell am start -a android.intent.action.VIEW \
    -d "content://media/external/file/57" -t "application/octet-stream" \
    --grant-read-uri-permission io.github.june690602_blip.cleancad/.ui.ViewerActivity
  ```
- **재로딩**: 같은 액티비티가 떠 있으면 인텐트가 기존 인스턴스에 전달되어 reload 안 될 수 있음 →
  `am force-stop` 후 start.
- **스크린샷**: `"$ADB" exec-out screencap -p > out.png`. 확대분석은 `py -c "from PIL import Image..."`
  (`python3` 없음, `py` 사용).
- **로그**: 파싱/엔티티수 = tag `CleanCAD/ViewModel` ("load: parsed in ... entities=N"),
  native = `CleanCAD/dwgjni`. dwg_serialize.c에 임시 로그 추가 시 `#include <android/log.h>` +
  `#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,"dwgser",__VA_ARGS__)` (작업 후 제거).
- **빌드**: `./gradlew :app:assembleDebug` (NDK 30.0.14904198, CMake 4.1.2). 증분 ~3-15s.

---

## 6. 현재 동작 상태 (Phase 9.4 종료 시점)
- XCLIP 기하 클리핑 동작(seg_clipped=5,115, emitted=187,907). 떡/겹침 해소, 사용자 만족.
- 시트 탭 제거됨 — 전체보기 + 자유 팬/줌. fit/renderBounds는 시트합집합 displayExtents 사용.
- 남은 시각 이슈(사소, perf와 별개): XCLIP INSERT 밖 orphan 엔티티 일부 잔존, 폴리곤 클립 bbox 근사.
- base_pt 보정 + XCLIP 미커밋 — 정식 커밋 전 다른 실파일 추가 검증 권장.
