# 최근파일 재열기 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 카톡·공유로 받은 DWG를 앱 재시작 후에도 최근파일에서 다시 열 수 있게 한다 (로컬 복사본 우선).

**Architecture:** 로드 성공 시 `cacheDir` 복사본을 `filesDir/recent/<hash>.dwg`로 이동해 영구 보관하고, 재열기는 이 복사본을 정본으로 사용한다. 복사본이 없는 경우(기존 항목·데이터 삭제)만 원본 URI로 폴백한다. 보관은 개수 10개 + 용량 200MB 캡(순수 함수 `RetentionPolicy`)으로 관리하고, 목록에서 길게 눌러 삭제 + 고아 파일 sweep을 둔다.

**Tech Stack:** Kotlin, Android Views, AndroidViewModel + Kotlin Flow, JUnit4 (JVM 단위 테스트), SharedPreferences.

**참고 스펙:** `docs/superpowers/specs/2026-06-10-recent-files-reopen-design.md`

---

## File Structure

| 파일 | 책임 | 변경 |
|------|------|------|
| `ui/RetentionPolicy.kt` | 개수/용량 캡 순수 함수 | **신규** |
| `ui/RecentFilesManager.kt` | recents 영속화 (모델·직렬화·add/remove) | 수정 |
| `ui/DrawingState.kt` | 로드 상태 — Success에 복사본 경로/크기 | 수정 |
| `ui/DrawingViewModel.kt` | 로드: 복사본 영구화 + 재열기 폴백 | 수정 |
| `ui/ViewerActivity.kt` | 인텐트 수신·전달, add/remove 호출 | 수정 |
| `MainActivity.kt` | 재열기 launch, 길게눌러 삭제, 고아 sweep | 수정 |
| `test/.../ui/RetentionPolicyTest.kt` | RetentionPolicy 단위 테스트 | **신규** |

---

## Task 1: RetentionPolicy + RecentFile 복사본 필드 (TDD)

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/ui/RetentionPolicy.kt`
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/ui/RetentionPolicyTest.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/RecentFilesManager.kt`

- [ ] **Step 1: `RecentFile`에 `localPath`/`size` 추가 + 직렬화 + add(기본값)·remove**

`RecentFilesManager.kt` 전체를 아래로 교체:

```kotlin
package io.github.june690602_blip.cleancad.ui

import android.content.Context
import java.io.File

class RecentFilesManager(context: Context) {

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    data class RecentFile(
        val uri: String,
        val name: String,
        val timestamp: Long,
        val localPath: String? = null,
        val size: Long = 0L,
    )

    /** 로드 성공 시 호출. localPath/size 는 복사본이 확정된 뒤 전달(미확정이면 기본값). */
    fun add(uri: String, name: String, localPath: String? = null, size: Long = 0L) {
        val list = getAll().toMutableList()
        list.removeAll { it.uri == uri }
        list.add(0, RecentFile(uri, name, System.currentTimeMillis(), localPath, size))
        val (kept, evicted) = RetentionPolicy.applyRetention(list)
        evicted.forEach { f -> f.localPath?.let { runCatching { File(it).delete() } } }
        save(kept)
    }

    /** 수동 삭제 — 목록에서 빼고 복사본 파일도 제거. */
    fun remove(uri: String) {
        val list = getAll().toMutableList()
        val removed = list.filter { it.uri == uri }
        list.removeAll { it.uri == uri }
        removed.forEach { f -> f.localPath?.let { runCatching { File(it).delete() } } }
        save(list)
    }

    fun getAll(): List<RecentFile> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).map { i ->
            RecentFile(
                uri = prefs.getString("${KEY_URI}_$i", "") ?: "",
                name = prefs.getString("${KEY_NAME}_$i", "") ?: "",
                timestamp = prefs.getLong("${KEY_TS}_$i", 0L),
                localPath = prefs.getString("${KEY_PATH}_$i", "")?.takeIf { it.isNotEmpty() },
                size = prefs.getLong("${KEY_SIZE}_$i", 0L),
            )
        }
    }

    private fun save(list: List<RecentFile>) {
        prefs.edit().apply {
            // 이전 항목 잔여 키 제거(목록이 짧아질 때 stale 키가 남지 않도록)
            clear()
            putInt(KEY_COUNT, list.size)
            list.forEachIndexed { i, f ->
                putString("${KEY_URI}_$i", f.uri)
                putString("${KEY_NAME}_$i", f.name)
                putLong("${KEY_TS}_$i", f.timestamp)
                putString("${KEY_PATH}_$i", f.localPath ?: "")
                putLong("${KEY_SIZE}_$i", f.size)
            }
            apply()
        }
    }

    companion object {
        private const val KEY_COUNT = "count"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_TS = "ts"
        private const val KEY_PATH = "path"
        private const val KEY_SIZE = "size"
    }
}
```

> 참고: 기존 `MAX_ITEMS` 상수는 `RetentionPolicy`로 옮긴다. `save`에 `clear()`를 추가해 목록이 줄어들 때(삭제/퇴출) 인덱스 뒤쪽 stale 키가 남지 않게 한다.

- [ ] **Step 2: RetentionPolicy 단위 테스트 작성 (실패할 것)**

`RetentionPolicyTest.kt` 생성:

```kotlin
package io.github.june690602_blip.cleancad.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [RetentionPolicy] — 개수/용량 캡과 퇴출 항목 식별을 검증(순수 함수). */
class RetentionPolicyTest {

    private val MB = 1024L * 1024L

    private fun rf(id: Int, size: Long) =
        RecentFilesManager.RecentFile("uri$id", "name$id", id.toLong(), "/recent/$id.dwg", size)

    @Test
    fun itemCount_capEvictsOldest() {
        // 12개(최신순: index 0 최신), 용량 무제한 → 개수 캡 10
        val list = (0 until 12).map { rf(it, 1) }
        val (kept, evicted) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = Long.MAX_VALUE)
        assertEquals(10, kept.size)
        assertEquals(listOf("uri10", "uri11"), evicted.map { it.uri }) // 뒤(오래된) 2개 퇴출
    }

    @Test
    fun byteCap_evictsBeyondLimit() {
        // 각 60MB 5개, 200MB 캡 → 3개(180MB)만 보관
        val list = (0 until 5).map { rf(it, 60 * MB) }
        val (kept, evicted) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = 200 * MB)
        assertEquals(3, kept.size)
        assertEquals(2, evicted.size)
    }

    @Test
    fun byteCap_winsOverItemCap_whicheverFirst() {
        // 각 50MB 10개, 개수 10·용량 200MB → 용량이 먼저 닿아 4개
        val list = (0 until 10).map { rf(it, 50 * MB) }
        val (kept, _) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = 200 * MB)
        assertEquals(4, kept.size) // 50*4=200 <= 200, 5번째 250 > 200
    }

    @Test
    fun firstItem_keptEvenIfOverByteCap() {
        // 방금 연 500MB 단일 항목은 캡을 넘겨도 최소 1개 보관
        val list = listOf(rf(0, 500 * MB))
        val (kept, evicted) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = 200 * MB)
        assertEquals(1, kept.size)
        assertEquals(0, evicted.size)
    }

    @Test
    fun emptyList_returnsEmpty() {
        val (kept, evicted) = RetentionPolicy.applyRetention(emptyList())
        assertEquals(0, kept.size)
        assertEquals(0, evicted.size)
    }
}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleancad.ui.RetentionPolicyTest"`
Expected: 컴파일 실패 — `RetentionPolicy` 미해결(unresolved reference).

- [ ] **Step 4: RetentionPolicy 구현**

`RetentionPolicy.kt` 생성:

```kotlin
package io.github.june690602_blip.cleancad.ui

/**
 * 최근파일 보관 정책 — Context/SharedPreferences 의존 없는 순수 함수(단위 테스트 대상).
 */
object RetentionPolicy {
    const val MAX_ITEMS = 10
    const val MAX_BYTES = 200L * 1024 * 1024 // 200MB

    /**
     * 최신순 [list]를 개수·용량 한도로 가지치기.
     * 맨 앞(방금 연 파일)은 용량을 넘겨도 최소 1개 보관한다.
     * @return (보관, 퇴출). 퇴출 항목의 복사본 파일 삭제는 호출자 책임.
     */
    fun applyRetention(
        list: List<RecentFilesManager.RecentFile>,
        maxItems: Int = MAX_ITEMS,
        maxBytes: Long = MAX_BYTES,
    ): Pair<List<RecentFilesManager.RecentFile>, List<RecentFilesManager.RecentFile>> {
        val kept = ArrayList<RecentFilesManager.RecentFile>()
        val evicted = ArrayList<RecentFilesManager.RecentFile>()
        var cumBytes = 0L
        for (f in list) {
            val fits = kept.size < maxItems && cumBytes + f.size <= maxBytes
            if (kept.isEmpty() || fits) {
                kept.add(f)
                cumBytes += f.size
            } else {
                evicted.add(f)
            }
        }
        return kept to evicted
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.june690602_blip.cleancad.ui.RetentionPolicyTest"`
Expected: PASS (5개 테스트).

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/ui/RetentionPolicy.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/ui/RecentFilesManager.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/ui/RetentionPolicyTest.kt
git commit -m "feat(recent): RetentionPolicy 캡 + RecentFile 복사본 필드/직렬화"
```

---

## Task 2: 복사본 영구화 + 재열기 폴백 (DrawingViewModel)

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingState.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt`

- [ ] **Step 1: `DrawingState.Success`에 `localPath`/`size` 추가**

`DrawingState.kt` 전체를 아래로 교체:

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
        val uri: Uri,
        val localPath: String,
        val size: Long,
    ) : DrawingState()
    data class Error(val message: String) : DrawingState()
}
```

- [ ] **Step 2: `DrawingViewModel.load` 재작성 (복사본 이동 + 재열기 폴백)**

`DrawingViewModel.kt`의 `import` 블록에 `Drawing` 추가:

```kotlin
import io.github.june690602_blip.cleancad.model.Drawing
```

그리고 `load(uri: Uri)` 함수 전체(`fun load(...) { ... }`, 줄 25~91)를 아래로 교체:

```kotlin
    /**
     * @param uri         원본 content URI (목록 dedup 키로도 사용).
     * @param localPath   재열기 시 로컬 복사본 경로. 존재하면 복사 없이 바로 파싱.
     * @param fallbackName 재열기 시 목록의 표시 이름(원본 URI 권한이 없을 수 있어 우선 사용).
     */
    fun load(uri: Uri, localPath: String? = null, fallbackName: String? = null) {
        if (_state.value is DrawingState.Loading) return
        viewModelScope.launch {
            _state.value = DrawingState.Loading
            Log.i(TAG, "load: start uri=$uri localPath=$localPath")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()

                    // 파싱할 정본 파일을 결정한다.
                    val cached = localPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
                    val dwgFile: File
                    val displayName: String
                    if (cached != null) {
                        // 재열기: 영구 복사본 직접 사용(복사 생략).
                        dwgFile = cached
                        displayName = fallbackName ?: cached.name
                        Log.i(TAG, "load: reopen from copy ${cached.length()} bytes ($displayName)")
                    } else {
                        // 신규 열기(또는 복사본 소실 폴백): 원본 URI → cacheDir 복사 → 검증 → filesDir 이동.
                        displayName = ctx.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            ?: fallbackName ?: uri.lastPathSegment ?: uri.toString()

                        val tag = uri.hashCode().toUInt().toString(16)
                        val cacheFile = File(ctx.cacheDir, "dwg_$tag.dwg")
                        val stream = ctx.contentResolver.openInputStream(uri)
                            ?: throw IOException("파일을 열 수 없습니다: $uri")
                        val t0 = System.currentTimeMillis()
                        stream.use { it.copyTo(cacheFile.outputStream()) }
                        Log.i(TAG, "load: copied ${cacheFile.length()} bytes in ${System.currentTimeMillis() - t0}ms ($displayName)")

                        if (!isLikelyDwg(cacheFile, displayName)) {
                            Log.w(TAG, "load: not a DWG — name=$displayName")
                            throw IOException("DWG 도면 파일이 아닙니다. .dwg 파일을 열어주세요.")
                        }

                        // 복사본 영구화: filesDir/recent/<tag>.dwg 로 이동.
                        val recentDir = File(ctx.filesDir, "recent").apply { mkdirs() }
                        val dest = File(recentDir, "$tag.dwg")
                        if (!cacheFile.renameTo(dest)) {
                            cacheFile.copyTo(dest, overwrite = true)
                            cacheFile.delete()
                        }
                        dwgFile = dest
                    }

                    val t1 = System.currentTimeMillis()
                    val drawing = NativeDwg.parseToDrawing(dwgFile.absolutePath)
                    val t2 = System.currentTimeMillis()
                    val sheets = SheetClusterer.cluster(drawing.entities)
                    val t3 = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "load: parsed in ${t2 - t1}ms, clustered in ${t3 - t2}ms — " +
                            "entities=${drawing.entities.size}, layers=${drawing.layers.size}, " +
                            "entityColors=${drawing.entityColors.size}, sheets=${sheets.size}, " +
                            "extents=${drawing.extents}, displayExtents=${drawing.displayExtents}"
                    )
                    val displayExt = SheetClusterer.inclusiveExtents(drawing.entities, sheets)
                        ?: drawing.displayExtents
                    Log.i(TAG, "load: displayExtents(inclusive)=$displayExt")
                    LoadResult(
                        drawing.copy(sheets = sheets, displayExtents = displayExt),
                        displayName, uri, dwgFile.absolutePath, dwgFile.length()
                    )
                }
            }
            result.fold(
                onSuccess = { r ->
                    Log.i(TAG, "load: SUCCESS — ${r.drawing.entities.size} entities, copy=${r.localPath}")
                    _state.value = DrawingState.Success(r.drawing, r.displayName, r.uri, r.localPath, r.size)
                },
                onFailure = { e ->
                    Log.e(TAG, "load: FAILURE", e)
                    _state.value = DrawingState.Error(e.message ?: "알 수 없는 오류")
                }
            )
        }
    }

    private data class LoadResult(
        val drawing: Drawing,
        val displayName: String,
        val uri: Uri,
        val localPath: String,
        val size: Long,
    )
```

> `isLikelyDwg`와 파일 하단의 헬퍼는 그대로 둔다(같은 파일 내 top-level 함수).

- [ ] **Step 3: 빌드로 컴파일 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`ViewerActivity`의 `Success` 분기는 `drawing`/`displayName`/`uri`만 읽고, `add`는 Task 1의 기본값 인자로 컴파일된다.)

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingState.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt
git commit -m "feat(recent): 복사본 filesDir 영구화 + 재열기 폴백 (load 확장)"
```

---

## Task 3: ViewerActivity — 복사본 경로 저장 / 폴백 실패 시 제거

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt`

- [ ] **Step 1: 인텐트 EXTRA 상수 + 수신, load 전달, Success/Error 핸들러 갱신**

`ViewerActivity.kt`에서 세 곳을 수정한다.

(1) `Success` 분기(줄 54~67)를 아래로 교체 — `add`에 복사본 경로/크기 전달:

```kotlin
                        is DrawingState.Success -> {
                            if (state.uri == renderedUri) return@collect
                            renderedUri = state.uri
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    state.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            RecentFilesManager(this@ViewerActivity).add(
                                state.uri.toString(), state.displayName, state.localPath, state.size
                            )
                            showDrawing()
                            drawingView.setDrawing(state.drawing)
                        }
```

(2) `Error` 분기(줄 68~70)를 아래로 교체 — 재열기 폴백까지 실패한 URI를 목록에서 제거:

```kotlin
                        is DrawingState.Error -> {
                            // 복사본도 원본도 못 연 항목은 최근목록에서 제거(다음 복귀 시 사라짐).
                            incomingUri()?.let {
                                RecentFilesManager(this@ViewerActivity).remove(it.toString())
                            }
                            showError(getString(R.string.error_prefix) + state.message)
                        }
```

(3) `onCreate`의 초기 로드 분기(줄 76~79)를 아래로 교체 — 재열기 extra를 읽어 `load`에 전달:

```kotlin
        if (viewModel.state.value is DrawingState.Idle) {
            val uri = incomingUri()
            if (uri != null) {
                viewModel.load(
                    uri,
                    intent.getStringExtra(EXTRA_LOCAL_PATH),
                    intent.getStringExtra(EXTRA_NAME),
                )
            } else {
                openDoc.launch(arrayOf("*/*"))
            }
        }
```

- [ ] **Step 2: EXTRA 상수를 companion으로 추가**

`ViewerActivity` 클래스 맨 끝(마지막 `}` 직전, `showError` 함수 뒤)에 추가:

```kotlin

    companion object {
        const val EXTRA_LOCAL_PATH = "io.github.june690602_blip.cleancad.LOCAL_PATH"
        const val EXTRA_NAME = "io.github.june690602_blip.cleancad.NAME"
    }
```

- [ ] **Step 3: 빌드로 컴파일 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt
git commit -m "feat(recent): ViewerActivity 복사본 경로 저장 + 폴백 실패 시 목록 제거"
```

---

## Task 4: MainActivity — 재열기 launch + 길게눌러 삭제 + 고아 sweep

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt`

- [ ] **Step 1: import 추가**

`MainActivity.kt` import 블록에 추가:

```kotlin
import androidx.appcompat.app.AlertDialog
import java.io.File
```

- [ ] **Step 2: 목록 버튼에 클릭/롱클릭 + 고아 sweep**

`refreshRecentFiles()`(줄 59~77)를 아래로 교체:

```kotlin
    private fun refreshRecentFiles() {
        val container = findViewById<LinearLayout>(R.id.container_recent)
        val tvEmpty = findViewById<TextView>(R.id.tv_empty)
        container.removeAllViews()

        val files = RecentFilesManager(this).getAll()
        sweepOrphans(files)
        if (files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE
        files.forEach { file ->
            Button(this).apply {
                text = file.name
                setOnClickListener { launchViewer(file) }
                setOnLongClickListener { confirmDelete(file); true }
                container.addView(this)
            }
        }
    }

    /** 목록에 없는(비정상 종료 등으로 남은) filesDir/recent 복사본을 제거. */
    private fun sweepOrphans(files: List<RecentFilesManager.RecentFile>) {
        val keep = files.mapNotNull { it.localPath }.toSet()
        File(filesDir, "recent").listFiles()?.forEach { f ->
            if (f.absolutePath !in keep) runCatching { f.delete() }
        }
    }

    private fun confirmDelete(file: RecentFilesManager.RecentFile) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(getString(R.string.recent_delete_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                RecentFilesManager(this).remove(file.uri)
                refreshRecentFiles()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
```

- [ ] **Step 3: 재열기 launch 오버로드 추가**

기존 `launchViewer(uri: Uri)`(줄 79~81) 바로 위에 오버로드 추가(기존 함수는 그대로 두어 SAF 열기에 사용):

```kotlin
    private fun launchViewer(file: RecentFilesManager.RecentFile) {
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            data = Uri.parse(file.uri)
            putExtra(ViewerActivity.EXTRA_LOCAL_PATH, file.localPath)
            putExtra(ViewerActivity.EXTRA_NAME, file.name)
        })
    }
```

- [ ] **Step 4: 문자열 리소스 추가**

`app/src/main/res/values/strings.xml`의 `<resources>` 안에 추가:

```xml
    <string name="recent_delete_confirm">최근 목록에서 삭제할까요? (도면 원본은 그대로입니다)</string>
    <string name="delete">삭제</string>
    <string name="cancel">취소</string>
```

> 프로젝트엔 `app/src/main/res/values/strings.xml` 하나만 있고(번역 리소스 없음) 위 세 키는 미정의이므로, 이 한 곳에만 추가하면 된다.

- [ ] **Step 5: 빌드로 컴파일 확인**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(recent): 최근목록 길게눌러 삭제 + 고아 복사본 sweep"
```

---

## Task 5: 회귀 + 실기기 검증

**Files:** (없음 — 검증만)

- [ ] **Step 1: 전체 단위 테스트 회귀**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 기존 77개 + RetentionPolicy 5개 = 82개 PASS, 0 실패.

- [ ] **Step 2: 릴리즈가 아닌 디버그 APK 빌드**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 실기기 수동 검증 (S21+ 또는 에뮬레이터)**

> ⚠️ native `.so` 변경은 없지만, 재설치는 `-r`로 충분(Kotlin만 변경).

체크리스트:
- [ ] 카톡/공유로 받은 .dwg 열기 → 정상 렌더 → 앱 강제종료(최근앱에서 스와이프) → 다시 실행 → **최근파일 목록에서 그 파일 클릭 → 재열기 성공**(이전엔 권한 오류로 실패).
- [ ] 같은 파일을 SAF(파일 앱)로 열고 강제종료 후 재열기 → 성공.
- [ ] 최근파일 항목 **길게 누르기 → 삭제 확인 → 목록에서 사라짐**. (앱 데이터 폴더 확인 가능하면 `filesDir/recent`의 복사본도 사라졌는지)
- [ ] 11개 이상 열어 목록이 10개로 유지되는지(가장 오래된 항목 사라짐) 확인.
- [ ] (마이그레이션) 업데이트 전부터 있던 항목(복사본 없음) 클릭 → 원본 URI 폴백으로 열리거나, 못 열면 목록에서 조용히 제거되는지.

- [ ] **Step 4: 검증 결과를 CLAUDE.md 상태 + 핸드오프에 반영, 커밋**

`CLAUDE.md`의 "작동 중 ✅"에 최근파일 재열기 항목 추가, "진행 중 ⏳"의 1번(최근파일 재열기)을 완료로 이동. 핸드오프 노트(`docs/superpowers/handoff/2026-06-10-recent-files-reopen-done.md`)에 근본원인·검증 요약.

```bash
git add CLAUDE.md docs/superpowers/handoff/2026-06-10-recent-files-reopen-done.md
git commit -m "docs(recent): 최근파일 재열기 완료·검증 반영"
```

---

## Notes
- DRY: 보관 정책은 `RetentionPolicy` 한 곳. add/remove/퇴출/수동삭제 모두 동일 파일 삭제 경로(`localPath?.let { File(it).delete() }`).
- YAGNI: 목록 날짜/크기 표시·썸네일·즐겨찾기는 비범위(스펙 명시).
- TDD: 순수 로직(`RetentionPolicy`)만 단위 테스트. Activity/ViewModel의 IO·인텐트는 JVM 단위 테스트가 어려워 컴파일 + 실기기 체크리스트로 검증(정직하게 명시).
- 커밋: 각 Task 끝에 1커밋.
