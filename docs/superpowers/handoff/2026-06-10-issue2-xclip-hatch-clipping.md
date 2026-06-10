# 이슈2 핸드오프 — XCLIP 부분침범 해치 기하 클리핑 (2026-06-10)

- 브랜치: `fix/issue2-xclip-hatch-bleed` (main에서 분기). 커밋: 스펙 → 플랜 → fix(`72818f5`).
- 스펙: `docs/superpowers/specs/2026-06-10-issue2-xclip-hatch-clipping-design.md`
- 플랜: `docs/superpowers/plans/2026-06-10-issue2-xclip-hatch-clipping.md`

## 증상 → 근본원인 (진단 과정 요약)

증상: `01. 건축도면-1.dwg`(S21+) 건물배치도 시트에서 회색 반투명 사다리꼴이
표제란(DRAWN BY/건물배치도)을 덮음.

가설 기각 흐름 (전부 실측):
1. ~~"도면↔원점 걸침 stray 엔티티"~~ (전 세션 가설) — Kotlin DIAG 스캔: displayExtents와
   겹치며 50%+ 벗어나는 엔티티 **0건**.
2. ~~fillLines 경계 탈출 (스캔라인 버그)~~ — 0건.
3. ~~깨진 해치 경계 (CW-arc 후속 변종)~~ — 의심 해치 H[96943] 경계 64점을 로컬
   시각화(.analysis/issue2_hatch_check.py): **정상 도로 형상**, 자기교차 서브픽셀 2건뿐.
4. ✅ **XCLIP 부분침범**: H[96943]은 XCLIP(SPATIAL_FILTER)된 INSERT의 자식 도로 솔리드
   해치. 클립창 `(11870755,-726345)~(12050755,-590095)`의 오른쪽 끝 = 표제란 왼쪽 경계.
   해치 bbox `(11857069,-699774)~(12073069,-680899)`가 클립창을 좌 13.7K/우 22.3K 침범.
   기존 write_entity HATCH 클립 처리(완전밖/극단초과만 통째 컬링)는 부분 침범을
   **무클립 통과** → 채움이 표제란 위로 번짐. 침범폭 22,314 × 화면스케일 0.0363
   ≈ 810px ≈ 사용자 스크린샷 블롭 실측 816px (정량 일치). 같은 클립의 라인은
   Liang-Barsky로 잘리므로 라인은 안 번짐 — 증상과 정합.

결정적 진단 수단: ① 사용자 스크린샷의 `223-5 도` 라벨 → TEXT 엔티티 좌표 매칭으로
화면↔월드 캘리브레이션, ② native 임시 DLOG(xclip 윈도우/hatch-clip 판정/wipeout 위치).

## 수정 (dwg_serialize.c 단일 파일, +79/-4)

- `clip_loop_halfplane` + `clip_loop_to_rect` 신규: Sutherland–Hodgman, 축정렬 사각형,
  루프 bbox가 클립창 완전 포함이면 무변경 빠른 경로, 결과 <3점이면 루프 드롭(np=0).
- `write_hatch`: 루프 빌드 직후 `g_clip_on`이면 루프별 클립 → 이후의 fillLines 생성·
  emit이 클립된 루프 사용 (채움/패턴/경계 stroke 모두 제한). 프로토콜/디코더/렌더러 무변경.
- `write_entity` HATCH case: `outside`(완전밖) 컬링 유지, `overflow`(클립크기 이상)
  통째 컬링 **제거** — 클리핑이 대체.

## 검증 (전부 실행 증거)

| 항목 | 결과 |
|---|---|
| H[96943] bbox | 216000×18875 → **180000×16835**, minX/maxX가 클립창과 정확 일치 |
| 사용자 파일 엔티티 | 101,603 불변, load SUCCESS |
| ref.dwg | 192,958 (+76 — overflow 통째컬링되던 해치가 클립되어 복귀), 회색띠 재발 없음 |
| 단위테스트 | 77개 통과, 실패 0 (XML 리포트 집계) |
| H[97008] (클립창 완전 포함 해치) | 빠른 경로로 바이트 무변경 확인 |
| 실폰(S21+) | ✅ 사용자 확인 완료 — "잘되더라" (2026-06-10, main 머지됨) |

## 남은 관찰 (이번 범위 외)

- **세로 흰 선** (사용자 스크린샷의 화면 관통 가는 선): 사용자가 이번 타깃에서 제외.
  미조사. ref.dwg fit 뷰의 우상단 빨간 사선도 동류 가능성 — main에도 있던 기존 현상.
- **"ㄱ 모양" 산발 마커 (Task 12)**: 시트 8/9, 여전히 미조사.
- **거대 WIPEOUT**: `(11876956,-722606)~(12672605,73043)` bbox의 wipeout 존재 확인
  (이미지 정사각이라 실마스크는 더 작음). 마스킹 미구현은 Phase 10.5 결정 유지.
- `dwgjni.c`의 `in_plan_area` 로그(line ~118)는 04 파일 기준 하드코딩 좌표라 다른
  파일에선 무의미 — 다음 정리 때 제거 후보.

## 재현/검증 플레이북 (이 세션에서 검증된 명령)

- 에뮬 부팅: `emulator.exe -avd Medium_Phone_API_36.1 -no-snapshot-save -no-boot-anim`
- 사용자 파일 = 에뮬 MediaStore `content://media/external/downloads/66` (user.dwg),
  ref.dwg = `/41`. 실행:
  ```
  MSYS_NO_PATHCONV=1 adb -s emulator-5554 shell am start \
    -n io.github.june690602_blip.cleancad/.ui.ViewerActivity \
    -a android.intent.action.VIEW -d "content://media/external/downloads/66" \
    -t image/vnd.dwg --grant-read-uri-permission
  ```
- ⚠️ native 변경 시 uninstall+install (install -r은 .so 미갱신).
- 단위테스트 수 집계: `app/build/test-results/testDebugUnitTest/*.xml` 파싱.
