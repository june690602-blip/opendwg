# Phase 5 — Dark Mode / Screen Rotation / Share Intent / Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 화면 회전 시 재파싱 없이 도면 유지, 외부 앱에서 .dwg 파일을 CleanCAD로 열기, 다크모드 토글 + 앱 정보 설정 화면 추가.

**Architecture:** `DrawingViewModel`(`AndroidViewModel`)이 파이프라인 로직을 인수하고 `StateFlow<DrawingState>`로 UI에 상태를 방출한다. `ViewerActivity`는 `viewModels()` 델리게이트로 ViewModel을 획득하고 `repeatOnLifecycle`로 상태를 관찰한다. `CleanCadApp`(`Application` 서브클래스)이 앱 시작 시 저장된 다크모드 설정을 `AppCompatDelegate`에 적용한다.

**Tech Stack:** Kotlin, `AndroidViewModel`, `StateFlow`, `viewModelScope`, `repeatOnLifecycle`, `AppCompatDelegate`, `SharedPreferences`, `activity-ktx`, `lifecycle-viewmodel-ktx`

**⚠️ 작업 순서 중요:** `SettingsActivity` 클래스(Task 5)가 먼저 존재해야 `ViewerActivity`·`MainActivity`(Task 6·7)가 참조할 수 있다. 순서대로 진행할 것.

---

## 파일 목록

| 파일 | 작업 |
|------|------|
| `gradle/libs.versions.toml` | MODIFY — activity-ktx, lifecycle-viewmodel-ktx 추가 |
| `app/build.gradle.kts` | MODIFY — 의존성 추가, buildConfig 활성화 |
| `ui/DrawingState.kt` | CREATE — sealed class |
| `ui/DrawingViewModel.kt` | CREATE — AndroidViewModel, 파이프라인 로직 |
| `res/menu/menu_main.xml` | CREATE — overflow 메뉴 |
| `res/values/strings.xml` | MODIFY — 설정 관련 문자열 추가 |
| `CleanCadApp.kt` | CREATE — Application 서브클래스, 다크모드 복원 |
| `AndroidManifest.xml` | MODIFY — CleanCadApp, Intent Filters, SettingsActivity |
| `res/layout/activity_settings.xml` | CREATE |
| `ui/SettingsActivity.kt` | CREATE — 다크모드 + 앱 정보 |
| `ui/ViewerActivity.kt` | MODIFY — ViewModel 전환, 메뉴 추가 |
| `MainActivity.kt` | MODIFY — 메뉴 추가 |
| `render/EntityRenderer.kt` | MODIFY — setColors() 추가 |
| `render/DrawingView.kt` | MODIFY — 다크모드 색상 |

---

## Task 1: Gradle 의존성 추가 + BuildConfig 활성화

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: libs.versions.toml 수정**

`gradle/libs.versions.toml`의 `[libraries]` 섹션에 두 줄 추가. `activity`(1.13.0)와 `lifecycle`(2.9.0) 버전은 이미 존재하므로 `version.ref`를 재사용한다:

```toml
[libraries]
# 기존 항목들 유지 …
androidx-activity-ktx = { group = "androidx.activity", name = "activity-ktx", version.ref = "activity" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
```

- [ ] **Step 2: app/build.gradle.kts 수정**

`android {}` 블록 안에 `buildFeatures` 추가, `dependencies {}` 에 두 줄 추가:

```kotlin
android {
    // 기존 설정 유지 …

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // 기존 의존성 유지 …
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
```

- [ ] **Step 3: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add activity-ktx, lifecycle-viewmodel-ktx, enable buildConfig"
```

---

## Task 2: DrawingState + DrawingViewModel

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingState.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt`

- [ ] **Step 1: DrawingState.kt 생성**

`app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingState.kt`:

```kotlin
package io.github.june690602_blip.cleancad.ui

import android.net.Uri
import io.github.june690602_blip.cleancad.model.Drawing

sealed class DrawingState {
    object Idle    : DrawingState()
    object Loading : DrawingState()
    data class Success(
        val drawing: Drawing,
        val displayName: String,
        val uri: Uri
    ) : DrawingState()
    data class Error(val message: String) : DrawingState()
}
```

- [ ] **Step 2: DrawingViewModel.kt 생성**

`app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt`:

```kotlin
package io.github.june690602_blip.cleancad.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.june690602_blip.cleancad.NativeDwg
import io.github.june690602_blip.cleancad.parser.DxfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class DrawingViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<DrawingState>(DrawingState.Idle)
    val state: StateFlow<DrawingState> = _state.asStateFlow()

    fun load(uri: Uri) {
        if (_state.value is DrawingState.Loading) return
        viewModelScope.launch {
            _state.value = DrawingState.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()

                    val displayName = ctx.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: uri.toString()

                    val dwgFile = File(ctx.cacheDir, "current.dwg")
                    val stream = ctx.contentResolver.openInputStream(uri)
                        ?: throw IOException("파일을 열 수 없습니다: $uri")
                    stream.use { it.copyTo(dwgFile.outputStream()) }

                    val dxfFile = File(ctx.cacheDir, "current.dxf")
                    val rc = NativeDwg.nativeDwgToDxf(dwgFile.absolutePath, dxfFile.absolutePath)
                    if (rc != 0) throw RuntimeException("DWG 변환 실패 (코드: $rc)")

                    val drawing = DxfParser.parse(dxfFile.readText())
                    Triple(drawing, displayName, uri)
                }
            }
            result.fold(
                onSuccess = { (drawing, displayName, loadedUri) ->
                    _state.value = DrawingState.Success(drawing, displayName, loadedUri)
                },
                onFailure = { e ->
                    _state.value = DrawingState.Error(e.message ?: "알 수 없는 오류")
                }
            )
        }
    }
}
```

- [ ] **Step 3: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add \
  app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingState.kt \
  app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt
git commit -m "feat: DrawingState sealed class + DrawingViewModel (pipeline + StateFlow)"
```

---

## Task 3: overflow 메뉴 리소스 + strings 업데이트

**Files:**
- Create: `app/src/main/res/menu/menu_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: res/menu/menu_main.xml 생성**

`app/src/main/res/menu/menu_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/action_settings"
        android:title="@string/settings"
        android:showAsAction="never" />
</menu>
```

- [ ] **Step 2: strings.xml 전체 교체**

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">CleanCAD Viewer</string>
    <string name="open_file">DWG 파일 열기</string>
    <string name="recent_files">최근 파일</string>
    <string name="no_recent_files">최근 파일이 없습니다</string>
    <string name="loading">도면 불러오는 중…</string>
    <string name="error_prefix">열기 실패: </string>
    <string name="fit_to_screen">화면 맞춤</string>
    <string name="settings">설정</string>
    <string name="settings_dark_mode_title">다크모드</string>
    <string name="settings_theme_system">시스템 설정 따라가기</string>
    <string name="settings_theme_light">라이트</string>
    <string name="settings_theme_dark">다크</string>
    <string name="settings_about_title">앱 정보</string>
    <string name="settings_version">버전 %s</string>
    <string name="settings_license">라이선스: GPL v3 — 소스코드 https://github.com/june690602-blip/opendwg</string>
</resources>
```

- [ ] **Step 3: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add \
  app/src/main/res/menu/menu_main.xml \
  app/src/main/res/values/strings.xml
git commit -m "feat: overflow menu resource + settings strings"
```

---

## Task 4: CleanCadApp + AndroidManifest

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/CleanCadApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: CleanCadApp.kt 생성**

`app/src/main/java/io/github/june690602_blip/cleancad/CleanCadApp.kt`:

```kotlin
package io.github.june690602_blip.cleancad

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class CleanCadApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme(this)
    }

    companion object {
        fun applyTheme(context: Context) {
            val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
            AppCompatDelegate.setDefaultNightMode(
                when (prefs.getString("night_mode", "system")) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
```

- [ ] **Step 2: AndroidManifest.xml 전체 교체**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:name=".CleanCadApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.CleanCAD">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.ViewerActivity"
            android:label="@string/app_name"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/acad" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/x-dwg" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/octet-stream"
                      android:pathPattern=".*\\.dwg" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.SettingsActivity"
            android:label="@string/settings"
            android:exported="false" />

    </application>

</manifest>
```

- [ ] **Step 3: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL` (SettingsActivity 클래스는 아직 없지만 Manifest 선언만으로는 빌드 가능)

- [ ] **Step 4: 커밋**

```bash
git add \
  app/src/main/java/io/github/june690602_blip/cleancad/CleanCadApp.kt \
  app/src/main/AndroidManifest.xml
git commit -m "feat: CleanCadApp dark mode restore on startup; Manifest Intent Filters + SettingsActivity"
```

---

## Task 5: SettingsActivity

**Files:**
- Create: `app/src/main/res/layout/activity_settings.xml`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/ui/SettingsActivity.kt`

- [ ] **Step 1: activity_settings.xml 생성**

`app/src/main/res/layout/activity_settings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize" />

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_dark_mode_title"
                android:textSize="16sp"
                android:textStyle="bold"
                android:layout_marginBottom="8dp" />

            <RadioGroup
                android:id="@+id/rg_theme"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="32dp">

                <RadioButton
                    android:id="@+id/rb_system"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_theme_system" />

                <RadioButton
                    android:id="@+id/rb_light"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_theme_light" />

                <RadioButton
                    android:id="@+id/rb_dark"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/settings_theme_dark" />
            </RadioGroup>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_about_title"
                android:textSize="16sp"
                android:textStyle="bold"
                android:layout_marginBottom="8dp" />

            <TextView
                android:id="@+id/tv_version"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="4dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/settings_license" />

        </LinearLayout>
    </ScrollView>

</LinearLayout>
```

- [ ] **Step 2: SettingsActivity.kt 생성**

`app/src/main/java/io/github/june690602_blip/cleancad/ui/SettingsActivity.kt`:

```kotlin
package io.github.june690602_blip.cleancad.ui

import android.content.Context
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import io.github.june690602_blip.cleancad.BuildConfig
import io.github.june690602_blip.cleancad.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.tv_version).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val rgTheme = findViewById<RadioGroup>(R.id.rg_theme)

        when (prefs.getString("night_mode", "system")) {
            "light" -> findViewById<RadioButton>(R.id.rb_light).isChecked = true
            "dark"  -> findViewById<RadioButton>(R.id.rb_dark).isChecked = true
            else    -> findViewById<RadioButton>(R.id.rb_system).isChecked = true
        }

        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_light -> "light"
                R.id.rb_dark  -> "dark"
                else          -> "system"
            }
            prefs.edit().putString("night_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            recreate()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
```

- [ ] **Step 3: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add \
  app/src/main/res/layout/activity_settings.xml \
  app/src/main/java/io/github/june690602_blip/cleancad/ui/SettingsActivity.kt
git commit -m "feat: SettingsActivity — dark mode toggle (light/dark/system) + app info"
```

---

## Task 6: ViewerActivity — ViewModel 전환 + 메뉴

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt`

> `SettingsActivity`가 Task 5에서 생성된 후 진행한다.

- [ ] **Step 1: ViewerActivity.kt 전체 교체**

`app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt`:

```kotlin
package io.github.june690602_blip.cleancad.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.june690602_blip.cleancad.R
import io.github.june690602_blip.cleancad.render.DrawingView
import kotlinx.coroutines.launch

class ViewerActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var fabFit: FloatingActionButton

    private val viewModel: DrawingViewModel by viewModels()

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.load(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        drawingView = findViewById(R.id.drawing_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        fabFit = findViewById(R.id.fab_fit)
        fabFit.setOnClickListener { drawingView.fitToScreen() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is DrawingState.Idle    -> { /* 초기 상태 */ }
                        is DrawingState.Loading -> showLoading()
                        is DrawingState.Success -> {
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    state.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            RecentFilesManager(this@ViewerActivity).add(
                                state.uri.toString(), state.displayName
                            )
                            showDrawing()
                            drawingView.setDrawing(state.drawing)
                        }
                        is DrawingState.Error -> {
                            showError(getString(R.string.error_prefix) + state.message)
                        }
                    }
                }
            }
        }

        // 회전 복귀 시 ViewModel이 이미 로드된 상태이면 재로드하지 않음
        if (viewModel.state.value is DrawingState.Idle) {
            val uri = intent.data
            if (uri != null) viewModel.load(uri) else openDoc.launch(arrayOf("*/*"))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        drawingView.visibility = View.GONE
        tvError.visibility = View.GONE
        fabFit.visibility = View.GONE
    }

    private fun showDrawing() {
        progressBar.visibility = View.GONE
        tvError.visibility = View.GONE
        drawingView.visibility = View.VISIBLE
        fabFit.visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        drawingView.visibility = View.GONE
        fabFit.visibility = View.GONE
        tvError.visibility = View.VISIBLE
        tvError.text = msg
    }
}
```

- [ ] **Step 2: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt
git commit -m "feat: ViewerActivity — DrawingViewModel + rotation guard + settings menu"
```

---

## Task 7: MainActivity — 메뉴 추가

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt`

> `SettingsActivity`가 Task 5에서 생성된 후 진행한다.

- [ ] **Step 1: MainActivity.kt 전체 교체**

`app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt`:

```kotlin
package io.github.june690602_blip.cleancad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.june690602_blip.cleancad.ui.RecentFilesManager
import io.github.june690602_blip.cleancad.ui.SettingsActivity
import io.github.june690602_blip.cleancad.ui.ViewerActivity

class MainActivity : AppCompatActivity() {

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { launchViewer(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_open).setOnClickListener {
            openDoc.launch(arrayOf("*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecentFiles()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshRecentFiles() {
        val container = findViewById<LinearLayout>(R.id.container_recent)
        val tvEmpty = findViewById<TextView>(R.id.tv_empty)
        container.removeAllViews()

        val files = RecentFilesManager(this).getAll()
        if (files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE
        files.forEach { file ->
            Button(this).apply {
                text = file.name
                setOnClickListener { launchViewer(Uri.parse(file.uri)) }
                container.addView(this)
            }
        }
    }

    private fun launchViewer(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java).apply { data = uri })
    }
}
```

- [ ] **Step 2: 빌드 + 단위 테스트 확인**

```
./gradlew :app:assembleDebug :app:test 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, 기존 테스트 전부 PASS

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt
git commit -m "feat: MainActivity — settings menu"
```

---

## Task 8: DrawingView + EntityRenderer 다크모드 색상

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt`

- [ ] **Step 1: EntityRenderer.kt — setColors() 추가**

`EntityRenderer.kt`의 `class EntityRenderer {` 블록 안, `linePaint`/`textPaint` 선언 바로 아래에 메서드를 추가한다:

```kotlin
fun setColors(bgColor: Int, lineColor: Int) {
    linePaint.color = lineColor
    textPaint.color = lineColor
}
```

(`bgColor`는 향후 확장을 위해 시그니처에 포함하나 배경은 `DrawingView`에서 직접 그린다.)

- [ ] **Step 2: DrawingView.kt — import 추가**

파일 상단 import 목록에 추가:

```kotlin
import android.content.res.Configuration
```

(기존 `import android.graphics.Color`는 이미 있으므로 유지)

- [ ] **Step 3: DrawingView.kt — onDraw() 교체**

기존 `onDraw()` 메서드 전체를 아래로 교체한다:

```kotlin
override fun onDraw(canvas: Canvas) {
    val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val bgColor   = if (nightMode) Color.parseColor("#1C1C1E") else Color.WHITE
    val lineColor = if (nightMode) Color.parseColor("#E0E0E0") else Color.BLACK
    canvas.drawColor(bgColor)
    val d = drawing ?: return
    renderer.setColors(bgColor, lineColor)
    renderer.drawAll(d.entities, canvas, matrix)
}
```

- [ ] **Step 4: 빌드 + 단위 테스트 확인**

```
./gradlew :app:assembleDebug :app:test 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, 기존 테스트 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add \
  app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt \
  app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt
git commit -m "feat: dark mode colors in DrawingView + EntityRenderer.setColors()"
```

---

## Task 9: 에뮬레이터 수동 검증

Phase 5의 핵심 기능은 시각·동작 검증이 필수다. AVD `Medium_Phone_API_36.1` 에뮬레이터가 실행 중이어야 한다.

- [ ] **Step 1: 앱 설치**

```
./gradlew :app:installDebug
```

- [ ] **Step 2: 화면 회전 검증**

1. DWG 파일 열기 → 도면 로딩 완료 확인
2. 에뮬레이터 회전 (Ctrl+Left 또는 Ctrl+Right) → 도면이 재파싱 없이 즉시 표시되는지 확인
3. 줌/팬 상태는 fit-to-screen으로 리셋되는 것이 정상

- [ ] **Step 3: 설정 화면 검증**

1. MainActivity 또는 ViewerActivity 우측 상단 점 3개 메뉴 → "설정" 진입
2. 버전명(`1.0`) 표시 확인
3. GPL v3 라이선스 고지 텍스트 표시 확인
4. "다크" 선택 → 앱 전체 다크 테마 전환 확인
5. DrawingView 배경이 어두운 색(`#1C1C1E`)으로 변경 확인
6. "시스템 설정 따라가기" 복원 후 앱 재시작 → 시스템 테마 따름 확인

- [ ] **Step 4: Share/View Intent 검증**

```bash
adb push <로컬_경로>/test.dwg /sdcard/test.dwg
adb shell am start -a android.intent.action.VIEW \
  -d "file:///sdcard/test.dwg" \
  -t "application/acad" \
  io.github.june690602_blip.cleancad/.ui.ViewerActivity
```

예상: CleanCAD가 직접 실행되면서 도면 로딩

- [ ] **Step 5: 결함 발견 시 수정 후 커밋**

각 버그마다 별도 커밋. 메시지 형식: `fix: <증상 설명>`

---

## 자가 검증 (Spec Coverage)

| 설계 문서 항목 | 담당 Task |
|---------------|-----------|
| DrawingViewModel StateFlow | Task 2 |
| 화면 회전 재파싱 없음 | Task 6 (Idle guard) |
| Share/View Intent (.dwg + 3 MIME) | Task 4 (Manifest) |
| CleanCadApp 시작 시 테마 복원 | Task 4 |
| 설정 화면 레이아웃 | Task 5 |
| 다크모드 토글 (3가지) | Task 5 |
| 앱 정보 (버전 + GPL) | Task 5 |
| overflow 메뉴 — MainActivity | Task 7 |
| overflow 메뉴 — ViewerActivity | Task 6 |
| DrawingView 다크 색상 | Task 8 |
| EntityRenderer setColors() | Task 8 |
