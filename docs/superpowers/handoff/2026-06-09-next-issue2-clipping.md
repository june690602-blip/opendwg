# 다음 세션 준비 — 이슈 2: 도면영역 클리핑

- 날짜: 2026-06-09 (Phase 8.6 HATCH 마무리 세션에서 작성)
- 상태: **미착수**. 다음 세션 시작 시 **사용자와 범위 확정(브레인스토밍) 먼저**.

## 0. 가장 중요한 교훈 (이번 세션에서 비싸게 배움)

⚠️ **디바이스 진단은 반드시 "사용자가 실제로 보는 그 파일"로 한다.**
이번에 HATCH 버그를 `ref.dwg`(엉뚱한 파일)로 진단해 한참 헛수고했다. 사용자 파일은
**`01.건축도면-1.dwg`** 였다. 다음 세션 시작 시 폰 `logcat`으로 실파일을 먼저 확인할 것:
```
adb -s <phone> logcat -d | grep "CleanCAD/ViewModel: load:"
# → entities/layers/sheets/extents 로 어떤 파일인지 식별
```

## 1. 이슈 (추정 — 확인 필요)

"도면영역 클리핑" = **도면 영역(시트/displayExtents) 밖으로 뻗는 stray 지오메트리가 화면에
길게 그려지는 것**을 잘라내고 싶다(추정). 근거:
- 사용자 파일 full extents 는 거대(X: -1.26e7 ~ +1.30e7)인데 displayExtents 는 훨씬 작음
  (X: 1.188e7 ~ 1.286e7). 즉 **도면 본체에서 멀리 떨어진(원점/음수영역) outlier 지오메트리 존재.**
- 이번에 고친 HATCH 버그 중 하나가 정확히 이 증상: `ANSI37` 해치가 endpoint (0,0) 때문에
  bbox 가 원점까지 폭주(폭 1,237만) → 도면 가로지르는 거대 형상. (그 hatch 는 고쳤지만
  **같은 종류의 "도면↔원점을 걸치는" 라인/엔티티가 더 있을 가능성**.)
- 초기 스크린샷(사용자)에 우상단 코너로 뻗는 **빨간 사선** 관측 — 도면 밖으로 걸친 선으로 추정.

**브레인스토밍에서 확인할 것:** (a) stray 선/형상 제거가 목적인지, (b) 뷰를 시트/도면영역에
클립(crop)하는 게 목적인지, (c) 시트별 클립인지. **그 artifact 가 보이는 화면 캡처를 받을 것.**

## 2. 핵심 원인 가설

현재 도면영역 컬링은 **bbox-intersect 기반**이라, 도면 본체에서 원점까지 **걸치는** 엔티티는
bbox 가 displayExtents 와 겹쳐 **컬링되지 않고** 화면에 긴 stray 선으로 그려진다(추정).
→ 필요한 건 **기하 클리핑**(영역 밖 부분을 잘라냄) 또는 **걸침 엔티티 판별 컬링**.

## 3. 관련 코드 (정확한 위치)

- **`native/NativeDecoder.kt` `computeExtents()`** — 엔티티 worldBounds 로 `extents`(전체) +
  `displayExtents`(중심점 5/95 percentile trim) 계산. outlier 가 trim 으로 displayExtents 에서
  빠지지만, 전체 extents 엔 남음.
- **`model/SheetClusterer.kt`** — 시트 클러스터 + `inclusiveExtents`(검출 시트 합집합 1배 확장
  영역 내 엔티티 bbox). Phase 10.4 에서 저밀도 시트 누락 보완. 시트 bbox 리스트 제공.
- **`render/DrawingView.kt` `setDrawing()`** — `renderer.setRenderBounds(displayExtents ?: extents)`.
  `fitToScreen(box?)` 로 영역 맞춤(시트 bbox 도 가능).
- **`render/EntityRenderer.kt` `drawAll()`** — `rb = renderBounds`; 각 엔티티
  `if (rb != null && !bounds.intersects(rb)) continue` ← **bbox-intersect 컬링(핵심 한계).**
  걸침 엔티티는 통과됨. (viewport 컬링도 동일 bbox-intersect.)
- **`render/SpatialIndex.kt`** — displayExtents 영역으로 그리드 구축, viewport 후보 조회.
- **`cpp/dwg_serialize.c` XCLIP (Phase 9.4)** — `g_clip_on`/`clip_seg`(Liang-Barsky)로 **INSERT
  자식만** 기하 클리핑. top-level model 지오메트리엔 미적용. → 이 메커니즘을 도면영역으로
  확장하는 게 한 후보.

## 4. 후보 접근 (브레인스토밍 시드)

1. **렌더러 clipRect** — `drawAll` 전에 `canvas.clipRect(displayExtents→screen)`. 가장 단순.
   단 사각형 클립이라 비사각 도면/패닝 시 한계. fit 시엔 효과적.
2. **걸침 엔티티 컬링 강화** — bbox-intersect 대신 "centroid 가 영역 밖" 또는 "bbox 가 영역보다
   N배 크게 overflow"면 컬링(XCLIP 의 hatch overflow 컬링과 동일 발상). 긴 정상 선 오컬링 주의.
2.5 **데이터 레벨 garbage 정리** — (0,0)/극단 좌표 stray 는 native 에서 드롭(이번 ct3/4 수정의 일반화).
3. **native 기하 클리핑 확장** — XCLIP `clip_seg` 를 top-level 지오메트리에 displayExtents(또는
   시트 합집합)로 적용해 영역 밖 부분 잘라냄. 가장 근본적이나 작업량 큼.

⚖️ 트레이드오프: 1·2 는 렌더 단계(빠름, 데이터 보존) / 3 는 native(영구, 데이터 축소). 성능은
Phase 10 SpatialIndex 와 충돌 없게(컬링은 이미 인덱스 후 단계).

## 5. 재현/테스트 플레이북 (이번 세션에서 확립)

- 기기: 에뮬 `emulator-5554`(x86_64) + 실폰 `R3CR60N1Q3X`(S21+, arm64). `adb` 경로:
  `/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb.exe`.
- **사용자 파일을 기기에 올려 여는 법** (file:// 은 scoped-storage EACCES → MediaStore content URI):
  ```
  adb -s emulator-5554 push "01. 건축도면-1.dwg" /sdcard/Download/user.dwg
  adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/user.dwg"
  adb -s emulator-5554 shell content query --uri content://media/external/downloads --projection _id:_display_name | grep user.dwg   # → _id
  adb -s emulator-5554 shell am start -n io.github.june690602_blip.cleancad/.ui.ViewerActivity \
      -a android.intent.action.VIEW -d "content://media/external/downloads/<id>" -t image/vnd.dwg --grant-read-uri-permission
  # MSYS_NO_PATHCONV=1 로 /sdcard·file:// 경로 변환 방지
  ```
- 빌드/설치: native 변경 시 **uninstall+install 필수**(`install -r` 은 .so 갱신 안 함).
  에뮬 저장공간 부족 시 `pm clear com.google.android.gms` 등으로 확보.
- 스크린샷: `adb -s <dev> exec-out screencap -p > out.png` (원격경로 인자 X → Git Bash 경로변환 회피).
- 줌 스크립팅 불가(핀치) — 시각 확인은 사용자 협조 또는 native 수치 로그(bbox 등 임시 `__android_log_print`)로.
- 디버그 로그 진입점: `dwg_serialize.c` 에 임시 `#include <android/log.h>` + `__android_log_print(4,"CleanCAD/xxx",...)`.
  엔티티 bbox/구조 덤프로 outlier 식별 가능(이번에 hatch 원인 이렇게 잡음). 끝나면 `git checkout` 으로 제거.

## 6. 워크플로우 권장

- HARD-GATE: 코드 전 `/plan`. 먼저 brainstorming 으로 **이슈 범위 확정**(섹션 1 질문 + artifact 캡처).
- 이번 세션 PR(`feat/hatch-pattern-rendering`, PR #4)이 미머지면, 이슈 2 는 머지 후 새 브랜치 권장.
