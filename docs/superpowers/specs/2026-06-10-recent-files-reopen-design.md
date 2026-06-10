# 설계 — 최근파일 재열기 (복사본 우선)

- 날짜: 2026-06-10
- 상태: **설계 승인됨** (브레인스토밍 완료, 구현 대기)
- 관련 핸드오프: `docs/superpowers/handoff/2026-06-10-next-recent-files-reopen.md`

## 배경 / 문제

카톡·공유(SEND)·일부 VIEW 인텐트로 받은 `content://` URI 는
`takePersistableUriPermission` 이 안 먹혀(비-persistable 플래그) 앱 프로세스 재시작 후
재열기 시 `SecurityException` 으로 실패한다. FileProvider URI 는 카톡이 자기 캐시를
지우면 원본 자체가 소멸하기도 한다. 현재 `RecentFilesManager` 는 URI 문자열·이름·
타임스탬프만 저장하고, 로드 시 만드는 `cacheDir` 복사본은 시스템이 삭제 가능하며
재열기 경로에서 활용되지 않는다 — 항상 원본 URI 를 다시 연다.

## 확정된 결정 (브레인스토밍 2026-06-10)

| 항목 | 결정 | 트레이드오프 |
|------|------|--------------|
| (a) 재열기 정본 | **복사본 우선** | 단순·일관, 원본 권한/소멸 무관. 원본이 나중에 수정돼도 "받았던 시점" 버전 표시 — 현장 도면 보기엔 거의 무영향 |
| (b) 보관 한도 | **10개 + 200MB** (둘 중 먼저 닿는 쪽) | 사용자 파일 4~13MB 수준, 거대 파일 여러 개 방어 |
| (c) 수동 삭제 | **길게 눌러 삭제** | 안드로이드 관용 제스처, 구현 단순 |
| (d) 목록 표시 | **파일명만** (현행 유지) | 범위 최소, 재열기 핵심 기능에 집중 |
| 마이그레이션 | 복사본 없는 기존 항목은 원본 URI 폴백, 실패 시 자동 제거 | prefs 키 부재 → `localPath=null` 로 자연 마이그레이션 |

## 설계

### 1. 데이터 모델 — `RecentFilesManager`

`RecentFile` 에 두 필드 추가:

- `localPath: String?` — 영구 복사본 경로(`filesDir/recent/<hash>.dwg`). 기존 항목은
  prefs 에 키가 없어 `null` 로 로드 → 자연 마이그레이션.
- `size: Long` — 복사본 바이트 수(용량 캡 계산용, UI 노출 안 함).

`save`/`getAll` 직렬화에 두 키 추가. `add(uri, name, localPath, size)` 시그니처 확장.
신규 메서드 `remove(uri)` — 목록에서 제거 + 복사본 파일 삭제.

### 2. 복사본 영구화 + 재열기 흐름 — `DrawingViewModel.load`

시그니처: `load(uri: Uri, localPath: String? = null, fallbackName: String? = null)`

- **신규 열기** (외부 인텐트, `localPath=null`): 기존대로 `cacheDir/dwg_<hash>.dwg` 복사
  → `isLikelyDwg` + 파싱 성공 → 그 복사본을 `filesDir/recent/<hash>.dwg` 로 **이동**
  (`renameTo`, 다른 마운트로 실패 시 `copyTo`+`delete`). 검증/파싱 실패 시 `filesDir` 는
  안 건드림(cacheDir 잔여물은 시스템이 정리). `displayName = query(uri) ?: uri.lastPathSegment`.
- **재열기** (`localPath` 존재 & 파일 있음): 복사 단계 생략, 그 파일을 바로
  `NativeDwg.parseToDrawing(localPath)`. `displayName = fallbackName ?: localPath 파일명`.
- **폴백**: `localPath` 가 null 이거나 파일이 사라졌으면 → 원본 URI 로 신규 열기 흐름 →
  성공하면 복사본이 새로 생성됨. 원본도 실패하면 `DrawingState.Error`.
- 성공 시 `state.uri` 는 **원본 content URI 유지**(목록 dedup 키). `DrawingState.Success`
  에 새로 생성/확정된 `localPath`·`size` 도 함께 실어 ViewerActivity 가 `add` 에 사용.

### 3. 보관 정책 — `RetentionPolicy.kt` (신규, 순수 함수)

```
applyRetention(list, maxItems = 10, maxBytes = 200 * 1024 * 1024): Pair<kept, evicted>
```

SharedPreferences/Context 의존 없는 순수 함수 — 단위 테스트 대상. 목록은 최신순 가정,
앞에서부터 누적 개수·용량을 채우다 한도 초과 지점부터 `evicted` 로 분리. `RecentFilesManager.add`
가 이 함수를 호출하고 `evicted` 각 항목의 `localPath` 파일을 디스크에서 삭제.

### 4. 수동 삭제 UI — `MainActivity`

최근파일 버튼에 `setOnLongClickListener` → `AlertDialog` 삭제 확인 →
`RecentFilesManager.remove(uri)` → `refreshRecentFiles()`. 짧게 누르면 기존대로 열기.

### 5. 고아 파일 정리

`MainActivity.onResume` 의 `refreshRecentFiles` 끝에서 `filesDir/recent` 의 `.dwg` 중
현재 목록의 `localPath` 집합에 없는 파일을 sweep 삭제 — 비정상 종료로 남은 고아 방지.
가벼운 연산(파일 10여 개)이라 메인 스레드 허용.

### 6. 자동 제거 (폴백 실패)

재열기에서 `localPath` 없고 원본 URI 도 실패 → `DrawingState.Error` → **ViewerActivity** 가
자신이 열려던 `incomingUri()` 를 `RecentFilesManager.remove` 로 목록에서 제거(다음
`MainActivity` 복귀 시 사라짐). 관심사 분리상 recents 관리는 UI 레이어가 담당.

## 변경 파일

- `ui/RecentFilesManager.kt` — 모델 필드, `add`/`remove`/직렬화 확장
- `ui/RetentionPolicy.kt` — **신규**, 순수 보관 정책 함수
- `ui/DrawingViewModel.kt` — `load` 시그니처·복사본 이동·재열기 폴백, `DrawingState.Success` 확장
- `ui/ViewerActivity.kt` — `localPath`/`name` 인텐트 수신·전달, 성공 시 `add(localPath,size)`, 폴백 실패 시 `remove`
- `MainActivity.kt` — `launchViewer(uri, localPath, name)`, 길게눌러 삭제, 고아 sweep
- 테스트 — `RetentionPolicy` 단위 테스트(신규)

## 테스트 전략

- **`RetentionPolicy` 단위 테스트**: 개수 캡 단독, 용량 캡 단독, 둘 중 먼저 닿는 케이스,
  `evicted` 정확성, 빈 목록/단일 항목 경계.
- 기존 단위 테스트 77개 회귀 확인.
- **실기기**(S21+): 카톡 받은 파일 열기 → 앱 강제종료 → 최근파일 재열기 **성공**(이전엔 실패);
  길게눌러 삭제 동작; 11번째 열기 시 가장 오래된 항목·복사본 자동 제거; 마이그레이션
  (업데이트 전 항목 클릭 → 원본 URI 폴백 또는 우아한 제거).

## 비범위 (Out of Scope)

- 목록에 날짜/크기 표시 (파일명만 유지)
- 원본 수정 반영 (복사본 우선 = 받은 시점 버전)
- 클라우드 동기화, 썸네일, 즐겨찾기/고정
