# 다음 세션 준비 — 최근파일 재열기 (사용자 요청)

- 날짜: 2026-06-10 (이슈2 마무리 세션에서 작성)
- 상태: **미착수**. 사용자 원문: "최근에 열었던 파일은 다시 열 수 있게 해줘. 임시파일로
  하는건가?" — 시작 시 브레인스토밍으로 범위 확정 후 /plan.

## 1. 현행 구조 (이번 세션 실측)

- **`ui/RecentFilesManager.kt`** — SharedPreferences(`recent_files`)에 **URI 문자열 + 이름 +
  타임스탬프만** 최대 10개 저장. 파일 내용은 저장 안 함.
- **`ui/ViewerActivity.kt:58-64`** — 로드 성공 시 `takePersistableUriPermission`을
  `runCatching`으로 시도(실패 무시) 후 recents에 add.
- **`ui/DrawingViewModel.kt` load()** — 매 로드마다 원본 URI 를 `cacheDir/dwg_<uriHash>.dwg`
  로 복사해 파싱. 즉 **복사본은 이미 만들지만 캐시(시스템이 삭제 가능)이고, 재열기 경로가
  이 복사본을 활용하지 않음** — 항상 원본 URI 를 다시 연다.
- **`MainActivity.kt:64`** — 최근파일 목록 UI. 클릭 시 ViewerActivity로 URI 전달(추정 —
  클릭 핸들러 코드는 이번에 정독 안 함, 다음 세션에서 확인).

## 2. 문제 (왜 재열기가 깨지나)

- `takePersistableUriPermission`은 **SAF(ACTION_OPEN_DOCUMENT)로 받은 URI에만 성공**.
  카톡 "열기"(VIEW)/"공유"(SEND)로 받은 FileProvider·MediaStore URI는 persistable 플래그가
  없어 SecurityException → 조용히 삼켜짐 → **앱 프로세스 재시작 후 그 URI 재열기는 권한
  오류로 실패.**
- 카톡 FileProvider URI는 카톡이 자기 캐시를 지우면 **원본 자체가 소멸**할 수도 있음.
- 추가로 앱 삭제/재설치 시 SharedPreferences가 날아가 목록 자체가 초기화(이번 세션에서
  실폰 uninstall로 사용자가 직접 경험).

## 3. 제안 방향 (브레인스토밍 시드)

**로컬 복사본 영구 보관 + 폴백 열기:**
1. 로드 성공 시 `cacheDir` 복사본을 `filesDir/recent/<uriHash>.dwg` 로 이동/복사
   (최근 N개만, 총 용량 캡 — 예: 10개 또는 200MB, 사용자 파일은 4~13MB 수준).
2. `RecentFilesManager`에 로컬 복사본 경로 필드 추가.
3. 재열기: 원본 URI 시도 → SecurityException/소멸 시 **로컬 복사본으로 폴백**
   (또는 처음부터 복사본 우선 — 단순·일관, 단 원본이 수정된 경우 구버전을 보여줌).
4. 목록 UI에 파일 크기/날짜 표시 + 삭제(스와이프/길게누르기) 고려 — 범위는 사용자와 확정.

**브레인스토밍에서 확정할 것:**
- (a) 복사본 우선 vs 원본 우선+폴백 (원본 수정 반영 여부 트레이드오프)
- (b) 보관 개수/용량 정책, (c) 목록에서 수동 삭제 UI 필요 여부,
- (d) 기존 recents 마이그레이션(복사본 없는 항목 처리).

## 4. 관련 사실

- DWG 검증 게이트 `isLikelyDwg`(파일명/매직헤더)는 복사본 열기에도 그대로 적용 가능.
- 카톡 안드16(S25+) 한계는 Phase 11.2 참조 — SEND 경로로 받은 파일일수록 로컬 보관 가치 큼.
- 이슈2는 완료·머지됨 (`docs/superpowers/handoff/2026-06-10-issue2-xclip-hatch-clipping.md`).
  실폰 사용자 확인 "잘되더라" (2026-06-10).
