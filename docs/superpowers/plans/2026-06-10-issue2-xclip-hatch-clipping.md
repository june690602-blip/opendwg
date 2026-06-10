# 이슈2: XCLIP 해치 경계 기하 클리핑 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** XCLIP된 INSERT의 자식 HATCH가 클립창을 부분 침범할 때, 경계 루프를 클립창으로
기하 클리핑해 채움/패턴/경계가 클립 밖(표제란 등)으로 번지지 않게 한다.

**Architecture:** `dwg_serialize.c` 단일 파일 수정. `write_hatch`가 루프를 월드좌표로
빌드한 직후, `g_clip_on`이면 각 루프를 Sutherland–Hodgman으로 축정렬 클립 사각형에
클립한다. 이후의 fillLines 생성·emit은 클립된 루프를 그대로 사용하므로 프로토콜/디코더/
렌더러 변경 없음. `write_entity`의 HATCH `overflow` 휴리스틱 컬링은 제거(클리핑이 대체).

**Tech Stack:** C (NDK), Sutherland–Hodgman 폴리곤 클리핑. 검증은 에뮬레이터(API 36)
수치 로그 + 실폰(S21+) 시각 확인 (native라 JVM 단위테스트 불가 — 프로젝트 관례).

**전제 상태 (이 세션에서 이미 만들어진 것):**
- 브랜치 `fix/issue2-xclip-hatch-bleed` (스펙 커밋 완료)
- 작업트리에 임시 진단 코드 존재 (커밋 금지, Task 5에서 제거):
  - `DrawingViewModel.kt`: TEMP-DIAG2v2 / TEMP-DIAG3 블록 + 진단용 import 4개
  - `dwg_serialize.c`: DLOG 매크로, xclip/hatch-clip/wipeout DLOG 3곳
- 에뮬레이터에 사용자 파일 = MediaStore `content://media/external/downloads/66`
- 검증 기준값: H[96943] 원본 bbox `(11857069,-699774)~(12073069,-680899)`,
  클립창 `(11870755,-726345)~(12050755,-590095)`, 로드 엔티티 수 101,603

---

### Task 1: 클립 함수 2개 추가

**Files:**
- Modify: `app/src/main/cpp/dwg_serialize.c` — `clip_seg` 함수 (Liang-Barsky) 정의
  바로 뒤에 추가 (~line 50, `/* Liang-Barsky ... */` 블록 다음)

- [ ] **Step 1: 함수 추가**

```c
/* ---- XCLIP: 해치 경계 루프 클리핑 (Sutherland–Hodgman, 축정렬 사각형) ----
 * 부분 침범 해치의 채움/패턴이 클립창 밖(표제란 등)으로 번지는 것 방지 (이슈2). */

/* 반평면 1개로 닫힌 루프 클립. axis: 0=x, 1=y. keep_ge: 1이면 좌표>=lim 유지, 0이면 <=lim.
 * in(2n doubles) → out(용량 ≥ 4n+8 doubles, 호출자 할당). 반환: 출력 점 수(≤2n). */
static int clip_loop_halfplane(const double *in, int n, double *out,
                               int axis, int keep_ge, double lim) {
    int m = 0;
    for (int i = 0; i < n; ++i) {
        int j = (i + 1) % n;
        double cx = in[2*i], cy = in[2*i+1];
        double nx = in[2*j], ny = in[2*j+1];
        double cv = axis ? cy : cx;
        double nv = axis ? ny : nx;
        int cin = keep_ge ? (cv >= lim) : (cv <= lim);
        int nin = keep_ge ? (nv >= lim) : (nv <= lim);
        if (cin) { out[2*m] = cx; out[2*m+1] = cy; ++m; }
        if (cin != nin) {                        /* 경계 교차점 추가 (cv!=nv 보장) */
            double t = (lim - cv) / (nv - cv);
            if (axis) { out[2*m] = cx + t * (nx - cx); out[2*m+1] = lim; }
            else      { out[2*m] = lim;                out[2*m+1] = cy + t * (ny - cy); }
            ++m;
        }
    }
    return m;
}

/* 닫힌 루프(*pts_io: 2*(*np_io) doubles, world)를 [x0,x1]×[y0,y1]로 클립.
 * 클립 결과로 버퍼 교체(원본 free). 결과 <3점이면 루프 드롭(*pts_io=NULL, *np_io=0).
 * malloc 실패 시 진행된 만큼만 적용(안전한 보수적 폴백). */
static void clip_loop_to_rect(double **pts_io, int *np_io,
                              double x0, double y0, double x1, double y1) {
    double *pts = *pts_io;
    int n = *np_io;
    if (!pts || n < 3) return;

    /* 빠른 경로: 루프 bbox가 클립창에 완전 포함이면 무변경 */
    double mnx = 1e18, mny = 1e18, mxx = -1e18, mxy = -1e18;
    for (int i = 0; i < n; ++i) {
        double px = pts[2*i], py = pts[2*i+1];
        if (px < mnx) mnx = px; if (px > mxx) mxx = px;
        if (py < mny) mny = py; if (py > mxy) mxy = py;
    }
    if (mnx >= x0 && mxx <= x1 && mny >= y0 && mxy <= y1) return;

    const int    axes[4]  = {0, 0, 1, 1};
    const int    keeps[4] = {1, 0, 1, 0};
    const double lims[4]  = {x0, x1, y0, y1};
    double *cur = pts; int cn = n;
    for (int p = 0; p < 4 && cn >= 3; ++p) {
        double *nb = (double *)malloc(sizeof(double) * (size_t)(4 * cn + 8));
        if (!nb) break;
        int m = clip_loop_halfplane(cur, cn, nb, axes[p], keeps[p], lims[p]);
        if (cur != pts) free(cur);
        cur = nb; cn = m;
    }
    if (cn < 3) {                                /* 완전 밖/퇴화 → 루프 드롭 */
        if (cur != pts) free(cur);
        free(pts);
        *pts_io = NULL; *np_io = 0;
        return;
    }
    if (cur == pts) return;                      /* 클립 패스 미수행(메모리 부족) */
    free(pts);
    *pts_io = cur; *np_io = cn;
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL` (미사용 함수 경고 가능 — Task 2에서 사용)

---

### Task 2: write_hatch 연결 + overflow 휴리스틱 제거

**Files:**
- Modify: `app/src/main/cpp/dwg_serialize.c` — `write_hatch` 루프 빌드 직후(~line 900) +
  `write_entity`의 `case DWG_TYPE_HATCH` (~line 1340)

- [ ] **Step 1: write_hatch에 클립 적용**

`write_hatch`에서 아래 기존 코드를:

```c
        for (int L = 0; L < nloops; ++L) {
            DBuf lb; dbuf_init(&lb);
            build_one_loop(&lb, &e->paths[L], &unsupported, sx, sy, rot, tx, ty);
            loop_pts[L] = lb.v; loop_np[L] = lb.n / 2;
        }
```

다음으로 교체 (클립 블록 추가):

```c
        for (int L = 0; L < nloops; ++L) {
            DBuf lb; dbuf_init(&lb);
            build_one_loop(&lb, &e->paths[L], &unsupported, sx, sy, rot, tx, ty);
            loop_pts[L] = lb.v; loop_np[L] = lb.n / 2;
        }
        /* XCLIP: 경계 루프를 클립창으로 기하 클리핑 — 부분 침범 해치의 채움/패턴/
         * 경계가 클립 밖으로 번지지 않게 (이슈2). 빈 루프(np=0)는 이후 단계
         * (emit_defline_fill·emit 루프)가 자연히 스킵한다. */
        if (g_clip_on) {
            for (int L = 0; L < nloops; ++L)
                clip_loop_to_rect(&loop_pts[L], &loop_np[L],
                                  g_clx0, g_cly0, g_clx1, g_cly1);
        }
```

- [ ] **Step 2: write_entity HATCH case에서 overflow 휴리스틱 제거**

기존 (TEMP-DIAG2 DLOG 포함 상태):

```c
                    double cw = g_clx1-g_clx0, ch = g_cly1-g_cly0;
                    int outside  = (wmxx<g_clx0||wmnx>g_clx1||wmxy<g_cly0||wmny>g_cly1);
                    int overflow = (wmnx<g_clx0-cw||wmxx>g_clx1+cw||wmny<g_cly0-ch||wmxy>g_cly1+ch);
                    /* TEMP-DIAG2: 큰 해치의 클립 판정 */
                    if (wmxx-wmnx > 50000 || wmxy-wmny > 50000)
                        DLOG("hatch-clip bbox=(%.0f,%.0f)~(%.0f,%.0f) clip=(%.0f,%.0f)~(%.0f,%.0f) out=%d ovf=%d",
                             wmnx, wmny, wmxx, wmxy, g_clx0, g_cly0, g_clx1, g_cly1, outside, overflow);
                    if (outside || overflow) return;
```

다음으로 교체 (overflow 제거; DLOG는 Task 3 검증까지 유지 후 Task 5에서 삭제):

```c
                    int outside  = (wmxx<g_clx0||wmnx>g_clx1||wmxy<g_cly0||wmny>g_cly1);
                    /* overflow(클립크기 이상 초과) 통째 컬링은 제거 — write_hatch 의
                     * 루프 기하 클리핑이 대체한다. 클립창 안 부분은 정상 표시. */
                    /* TEMP-DIAG2: 큰 해치의 클립 판정 */
                    if (wmxx-wmnx > 50000 || wmxy-wmny > 50000)
                        DLOG("hatch-clip bbox=(%.0f,%.0f)~(%.0f,%.0f) clip=(%.0f,%.0f)~(%.0f,%.0f) out=%d",
                             wmnx, wmny, wmxx, wmxy, g_clx0, g_cly0, g_clx1, g_cly1, outside);
                    if (outside) return;
```

주석의 Phase 9.4 시절 헤더(`- HATCH: world bbox 가 클립 밖 또는 클립크기 이상 overflow 면
컬링(회색 띠 방지)`)도 실태에 맞게 수정:

```c
    /* XCLIP 활성 시 클립 처리:
     *  - LINE/LWPOLYLINE/POLYLINE/INSERT/DIMENSION: 통과(leaf writer 가 잘라내거나 재귀가 처리)
     *  - HATCH: 완전 밖이면 컬링, 부분 침범은 write_hatch 가 루프를 기하 클리핑
     *  - 소형 타입(원/호/텍스트 등): 대표점이 클립 밖이면 컬링 */
```

- [ ] **Step 3: 빌드**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

---

### Task 3: 에뮬레이터 정량 검증 (사용자 파일)

**Files:** 없음 (검증만)

- [ ] **Step 1: 재설치 (native 변경 → uninstall+install 필수)**

```bash
ADB="/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s emulator-5554 uninstall io.github.june690602_blip.cleancad
"$ADB" -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`

- [ ] **Step 2: 로드 + 로그 수집**

```bash
"$ADB" -s emulator-5554 logcat -c
MSYS_NO_PATHCONV=1 "$ADB" -s emulator-5554 shell am start \
  -n io.github.june690602_blip.cleancad/.ui.ViewerActivity \
  -a android.intent.action.VIEW -d "content://media/external/downloads/66" \
  -t image/vnd.dwg --grant-read-uri-permission
sleep 14
"$ADB" -s emulator-5554 logcat -d | grep -E "DIAG2-H\[96943\]|DIAG2v2|load: (parsed|SUCCESS)"
```

Expected (정량 합격 기준):
- `DIAG2-H[96943] ... bbox=(11870755,...)~(12050755,...)` — **minX ≥ 11,870,755 그리고
  maxX ≤ 12,050,755** (클립창 안). dim 이 216000 → ~180000 으로 축소.
- `entities=101603` 유지 (이 파일은 overflow 컬링 대상이 없었으므로 수 불변).
- `load: SUCCESS`

- [ ] **Step 3: 시각 확인 (fit 스크린샷)**

```bash
"$ADB" -s emulator-5554 exec-out screencap -p > issue2_emul_after.png
```

Expected: 배치도 시트의 도로 밴드가 클립 경계(표제란 직전)에서 끊김.
이전 캡처(`issue2_emul_fit.png`)와 비교.

---

### Task 4: ref.dwg 회귀 검증 (overflow 컬링 제거 영향)

**Files:** 없음 (검증만)

- [ ] **Step 1: ref.dwg 푸시 + 로드**

```bash
ADB="/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s emulator-5554 push ref.dwg /sdcard/Download/ref.dwg
MSYS_NO_PATHCONV=1 "$ADB" -s emulator-5554 shell am broadcast \
  -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/ref.dwg"
MSYS_NO_PATHCONV=1 "$ADB" -s emulator-5554 shell content query \
  --uri content://media/external/downloads --projection _id:_display_name | grep ref.dwg
# 출력의 _id 를 <ID> 로 사용:
"$ADB" -s emulator-5554 logcat -c
MSYS_NO_PATHCONV=1 "$ADB" -s emulator-5554 shell am start \
  -n io.github.june690602_blip.cleancad/.ui.ViewerActivity \
  -a android.intent.action.VIEW -d "content://media/external/downloads/<ID>" \
  -t image/vnd.dwg --grant-read-uri-permission
sleep 16
"$ADB" -s emulator-5554 logcat -d | grep -E "load: (parsed|SUCCESS)"
"$ADB" -s emulator-5554 exec-out screencap -p > issue2_ref_after.png
```

Expected:
- `load: SUCCESS`. 엔티티 수 기준치 192,882 — overflow 컬링 제거로 **늘 수 있음**
  (이전에 통째 컬링되던 해치가 클립되어 복귀). 줄면 회귀 → 원인 조사.
- 스크린샷에 Phase 9.4 가 잡았던 "회색 띠" 재발 없어야 함 (클리핑이 잘라주므로).

---

### Task 5: 임시 진단 코드 제거 + 최종 검증 + 커밋

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt`
  (git checkout 으로 원복 — 진단 전용 변경뿐이므로)
- Modify: `app/src/main/cpp/dwg_serialize.c` (DLOG 3곳 + 매크로 제거, 클립 함수/연결은 유지)

- [ ] **Step 1: Kotlin 진단 원복**

```bash
git checkout app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt
```

- [ ] **Step 2: native 진단 제거**

`dwg_serialize.c`에서 다음 4곳 삭제:
1. 상단 `/* TEMP-DIAG2 ... */` 블록 (`#include <android/log.h>` + `#define DLOG ...`)
2. write_insert 의 `/* TEMP-DIAG2: XCLIP 윈도우 ... */` + `if (has_clip) DLOG(...)` (4줄)
3. write_entity HATCH case 의 `/* TEMP-DIAG2: 큰 해치의 클립 판정 */` + DLOG 3줄
4. emit switch 의 `case DWG_TYPE_WIPEOUT: { ... }` 진단 블록 전체
   (원래 WIPEOUT 은 case 없이 default 로 skip — Phase 10.5 상태로 복귀)

- [ ] **Step 3: 클린 빌드 + 단위테스트**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 테스트 전체 통과 (현재 77개, 실패 0)

- [ ] **Step 4: 클린 빌드 재설치 + 무회귀 확인**

Task 3 Step 1-2 의 재설치+로드 반복 (DIAG 로그는 이제 없음).
Expected: `load: SUCCESS — 101603 entities` + fit 스크린샷에서 도로 밴드 클립 유지.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/cpp/dwg_serialize.c
git commit -m "fix(native): XCLIP 부분침범 해치 경계를 클립창으로 기하 클리핑

배치도 XCLIP 인서트의 도로 해치(216K 폭)가 클립창을 22.3K 침범해
표제란 위로 솔리드 채움이 번지던 문제. 기존 HATCH 클립 처리는
완전밖/극단초과만 통째 컬링하고 부분 침범은 무클립 통과였다.
write_hatch 가 루프 빌드 직후 Sutherland-Hodgman 으로 각 루프를
클립창에 클립 — 채움/패턴라인/경계 stroke 모두 클립 안으로 제한.
overflow 통째 컬링 휴리스틱은 제거(클리핑이 대체, 클립 안 부분 표시).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: 실폰 검증 + 문서 갱신

**Files:**
- Modify: `CLAUDE.md` (Status 섹션 이슈2 항목 갱신)
- Create: `docs/superpowers/handoff/2026-06-10-issue2-xclip-hatch-clipping.md`

- [ ] **Step 1: 실폰 설치 (S21+, native 변경 → uninstall+install)**

```bash
ADB="/c/Users/bogeun/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s R3CR60N1Q3X uninstall io.github.june690602_blip.cleancad
"$ADB" -s R3CR60N1Q3X install app/build/outputs/apk/debug/app-debug.apk
```

⚠️ uninstall 로 최근파일 목록이 사라짐을 사용자에게 미리 알릴 것.

- [ ] **Step 2: 사용자 확인 요청**

사용자가 `01. 건축도면-1.dwg` 를 열어 건물배치도 표제란에서 회색 덩어리 소멸 확인.
(이 단계는 사용자 응답 대기 — 확인 후 다음 진행.)

- [ ] **Step 3: 문서 갱신 + 커밋**

CLAUDE.md: "진행 중" 의 이슈2 항목을 "작동 중 ✅" 로 이동, 근본원인/수정 요약 한 단락.
핸드오프 문서: 진단 과정(가설 기각 흐름), 검증 수치, 남은 관찰(세로 흰 선 미조사,
"ㄱ 모양" 마커 Task 12) 기록.

```bash
git add CLAUDE.md docs/superpowers/handoff/2026-06-10-issue2-xclip-hatch-clipping.md
git commit -m "docs: 이슈2 XCLIP 해치 클리핑 완료 — 상태/핸드오프 갱신

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 4: 머지 옵션 제시**

superpowers:finishing-a-development-branch 스킬로 PR/머지 여부 사용자에게 확인.
