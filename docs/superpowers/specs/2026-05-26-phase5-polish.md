# Phase 5 — 다크모드 / 화면 회전 / Share Intent / 설정 화면

- **작성일**: 2026-05-26
- **상태**: 승인됨
- **선행 Phase**: Phase 4 완료 (DrawingView 렌더러 + SAF 뷰어 + 최근 파일)

---

## 1. 목표

| 기능 | 목표 |
|------|------|
| 화면 회전 | 회전 시 DWG 재파싱 없이 즉시 도면 재표시 |
| Share/View Intent | 파일 매니저·이메일 등에서 .dwg 파일 탭 시 CleanCAD로 열기 |
| 설정 화면 | 다크모드 토글(라이트/다크/시스템) + 앱 정보(버전, GPL) |
| 다크모드 | 앱 전체 테마 전환 (`AppCompatDelegate`) |

---

## 2. 아키텍처 변경 사항

### 2-1. DrawingViewModel (신규)

`Drawing` 파싱 결과와 로딩 상태를 ViewModel에 보관한다.  
`ViewerActivity`가 재생성(회전)되어도 ViewModel은 살아있으므로 재파싱이 불필요하다.

```kotlin
class DrawingViewModel : ViewModel() {
    val state: MutableStateFlow<DrawingState> = MutableStateFlow(DrawingState.Idle)
    fun load(uri: Uri, context: Context) { /* 파이프라인 로직 */ }
}

sealed class DrawingState {
    object Idle    : DrawingState()
    object Loading : DrawingState()
    data class Success(val drawing: Drawing) : DrawingState()
    data class Error(val message: String)   : DrawingState()
}
```

`ViewerActivity`는 `viewModels()` 델리게이트로 ViewModel을 획득하고  
`lifecycleScope.launch { repeatOnLifecycle(STARTED) { state.collect { ... } } }` 로 상태를 관찰한다.

**회전 동작:**
1. `onCreate` 재호출 → `viewModels()`가 동일 인스턴스 반환
2. `collect`가 즉시 현재 상태 방출 → `Success`이면 바로 `drawingView.setDrawing()`
3. 재파싱 없음

### 2-2. Share/View Intent

`AndroidManifest.xml`의 `ViewerActivity`에 intent-filter 3개를 추가한다.  
`exported="true"` 가 필요하다 (외부 앱에서 실행되므로).

```xml
<!-- application/acad -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/acad" />
</intent-filter>

<!-- application/x-dwg -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/x-dwg" />
</intent-filter>

<!-- octet-stream + .dwg 경로 패턴 -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/octet-stream"
          android:pathPattern=".*\\.dwg" />
</intent-filter>
```

`ViewerActivity.onCreate()`의 `intent.data` 분기 로직은 변경 없음.  
Intent로 열릴 때 `takePersistableUriPermission`이 실패할 수 있으나 이미 `runCatching`으로 감싸져 있어 무해하다.

### 2-3. 설정 화면

**`SettingsActivity`** (`AppCompatActivity`) + `activity_settings.xml` 레이아웃.  
AndroidX Preference 라이브러리 없음 — 항목이 2개뿐이므로 직접 XML로 구성.

레이아웃 구성:
- **다크모드 섹션**: `RadioGroup` — 시스템 따라가기 / 라이트 / 다크
- **앱 정보 섹션**: 버전명(`BuildConfig.VERSION_NAME`) + GPL v3 한 줄 고지

저장: `SharedPreferences("settings")` — 키 `night_mode`, 값 `"system"` / `"light"` / `"dark"`.

다크모드 적용:
```kotlin
// SharedPreferences 변경 즉시 적용
AppCompatDelegate.setDefaultNightMode(
    when (prefs.getString("night_mode", "system")) {
        "light"  -> MODE_NIGHT_NO
        "dark"   -> MODE_NIGHT_YES
        else     -> MODE_NIGHT_FOLLOW_SYSTEM
    }
)
```

RadioGroup 변경 시 저장 → `AppCompatDelegate` 갱신 → `recreate()` (앱 전체 즉시 반영).

앱 시작 시 (`CleanCadApp : Application` 신규) `onCreate()`에서 저장된 모드를 복원한다.

**메뉴**: `MainActivity`와 `ViewerActivity` 양쪽에 overflow menu "설정" 항목 추가.

### 2-4. DrawingView 다크모드 대응

`onDraw`에서 현재 테마를 확인하여 배경·선색을 전환한다:

| 테마 | 배경 | 선색 |
|------|------|------|
| 라이트 | `#FFFFFF` | `#000000` |
| 다크 | `#1C1C1E` | `#E0E0E0` |

판별: `resources.configuration.uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES`

`EntityRenderer`에 `setColors(bg: Int, line: Int)` 메서드를 추가하고,  
`DrawingView.onDraw()`에서 호출한다.

---

## 3. 파일 목록

| 파일 | 작업 |
|------|------|
| `ui/DrawingViewModel.kt` | **CREATE** |
| `ui/SettingsActivity.kt` | **CREATE** |
| `res/layout/activity_settings.xml` | **CREATE** |
| `CleanCadApp.kt` (Application 서브클래스) | **CREATE** |
| `AndroidManifest.xml` | **MODIFY** — Intent Filter, SettingsActivity, android:name=".CleanCadApp" |
| `ui/ViewerActivity.kt` | **MODIFY** — ViewModel 전환, 메뉴 추가 |
| `MainActivity.kt` | **MODIFY** — 메뉴 추가 |
| `render/DrawingView.kt` | **MODIFY** — 다크모드 색상 |
| `render/EntityRenderer.kt` | **MODIFY** — `setColors()` 추가 |
| `res/values/strings.xml` | **MODIFY** — 설정 관련 문자열 |
| `res/menu/menu_main.xml` | **CREATE** — overflow menu |

---

## 4. 데이터 흐름

### 화면 회전
```
회전 이벤트
  → ViewerActivity.onCreate() 재호출
  → viewModels() → 기존 DrawingViewModel 반환
  → state.collect() → Success(drawing) 즉시 방출
  → drawingView.setDrawing(drawing)  [재파싱 없음]
```

### 설정 저장 & 적용
```
사용자 RadioGroup 선택
  → SharedPreferences 저장
  → AppCompatDelegate.setDefaultNightMode()
  → SettingsActivity.recreate()
  → 전체 앱 테마 즉시 전환
```

### Intent로 DWG 열기
```
파일 매니저 .dwg 파일 탭
  → Android: intent-filter 매칭 → ViewerActivity 시작
  → intent.data = content/file URI
  → viewModel.load(uri) → 기존 파이프라인 실행
```

---

## 5. 테스트 전략

| 대상 | 방식 |
|------|------|
| DrawingViewModel 상태 전이 | JUnit 단위 테스트 (`kotlinx-coroutines-test`) |
| 화면 회전 | 에뮬레이터 수동 — 도면 열린 상태에서 회전, 재파싱 없이 즉시 표시 확인 |
| Intent 수신 | `adb shell am start -a VIEW -d file:///sdcard/test.dwg` 로 검증 |
| 다크모드 | 에뮬레이터 — 설정에서 각 3가지 모드 전환 후 UI/DrawingView 색상 확인 |
| 설정 화면 | 에뮬레이터 수동 — 메뉴 → 설정 진입, 버전·라이선스 표시 확인 |

---

## 6. 비목표 (이 Phase에서 제외)

- 레이어 켜기/끄기
- Play Store 출시 준비 (Phase 6)
- 선 굵기·색상 커스터마이징 (Phase 6 이후 검토)
