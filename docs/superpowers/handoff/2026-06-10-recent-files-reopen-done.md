# 최근파일 재열기 구현 완료 — 실폰 검증 대기

- 날짜: 2026-06-10
- 브랜치: `feat/recent-files-reopen` (**미머지**)
- 상태: 코드 구현 + 단위테스트 82통과 + 빌드 성공. **실폰(S21+) 검증 대기.**
- 스펙: `docs/superpowers/specs/2026-06-10-recent-files-reopen-design.md`
- 플랜: `docs/superpowers/plans/2026-06-10-recent-files-reopen.md`

## 무엇을 고쳤나 (복사본 우선)

카톡·공유로 받은 `content://` URI 는 `takePersistableUriPermission` 이 안 먹혀 앱
재시작 후 재열기가 권한 오류로 실패했다. 이제 로드 성공 시 `cacheDir` 복사본을
`filesDir/recent/<hash>.dwg` 로 **이동해 영구 보관**하고, 재열기는 이 복사본을 정본으로
직접 파싱한다(원본 URI 권한 불필요). 복사본이 없거나 소실된 경우에만 원본 URI 로
폴백하고, 그것도 실패하면 목록에서 자동 제거한다. 보관은 **10개 + 200MB 캡**
(`RetentionPolicy` 순수 함수), 목록 **길게 눌러 삭제** + 비정상 종료 잔여 **고아 sweep**.

## 변경 파일 (Task1~4, 각 1커밋)

- `ui/RetentionPolicy.kt` (신규) — 개수/용량 캡 순수 함수. 맨 앞(방금 연 파일)은 최소 1개 보관.
- `ui/RecentFilesManager.kt` — `localPath`/`size` 필드, `add`(기본값 인자)·`remove`,
  직렬화에 `clear()` 후 재기록(목록 축소 시 stale 키 방지).
- `ui/DrawingState.kt` — `Success` 에 `localPath`/`size`.
- `ui/DrawingViewModel.kt` — `load(uri, localPath?, fallbackName?)`, 복사본 filesDir 이동
  (`renameTo`→실패 시 copy+delete), 재열기 시 복사본 직접 파싱, 소실 시 원본 폴백.
- `ui/ViewerActivity.kt` — `EXTRA_LOCAL_PATH`/`EXTRA_NAME` 수신·`load` 전달, 성공 시
  `add(localPath,size)`, 폴백까지 실패한 URI 는 `remove`.
- `MainActivity.kt` — `launchViewer(file)` 오버로드(재열기), 길게눌러 삭제 다이얼로그,
  `sweepOrphans`.
- `res/values/strings.xml` — `recent_delete_confirm`/`delete`/`cancel`.
- `test/.../ui/RetentionPolicyTest.kt` (신규) — 5개.

## 확보된 증거

- **단위테스트 82개 통과** (기존 77 + RetentionPolicy 5), 0 실패/0 에러
  (`./gradlew :app:testDebugUnitTest`, XML 리포트 합산).
- **`assembleDebug` BUILD SUCCESSFUL** (arm64/armeabi/x86_64 native 포함).

## 실폰 검증 체크리스트 (사용자)

1. 카톡 받은 .dwg "열기" 또는 "공유 → CleanCAD" → 렌더 확인 → 앱 강제종료(최근앱 스와이프)
   → 재실행 → 최근파일 목록 클릭 → **재열기 성공**(이전엔 권한 오류로 실패).
2. 파일앱(SAF)으로 연 파일도 강제종료 후 재열기 성공.
3. 최근 항목 **길게 누르기 → 삭제 확인 → 목록에서 사라짐**.
4. 11개 이상 열어 목록이 **10개로 유지**(가장 오래된 항목 사라짐).
5. (마이그레이션) 이 업데이트 전부터 있던 항목(복사본 없음=`localPath` null) 클릭 →
   원본 URI 폴백으로 열리거나, 못 열면 **조용히 목록에서 제거**.

## 검증 후 할 일

- 사용자 OK → `main` 머지(이슈2 패턴), CLAUDE.md "작동 중 ✅"로 이동, 브랜치 삭제.
- 문제 시 → `adb logcat | grep CleanCAD/ViewModel` 의 `load: ...`(복사본 경로·재열기 흐름) 추적.

## 주의

- native `.so` 변경 없음 → `adb install -r` 로 충분(Kotlin만 변경).
- `filesDir/recent` 는 앱 내부 데이터라 **앱 삭제 시 함께 사라짐**(클라우드 동기화 아님 — 의도된 범위).
- 같은 파일을 다른 URI 로 받으면 hash 가 달라 별도 항목/복사본이 된다(출처별 구분, 허용).
