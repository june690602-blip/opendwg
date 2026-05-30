# Phase 4 — DrawingView Renderer + Viewer UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DWG 파일을 실제로 화면에 렌더링한다 — SAF로 파일을 열고, JNI→DXF→파싱 파이프라인을 실행하고, DrawingView(Canvas)로 표시하며, 최근 파일 목록을 홈 화면에 보여준다.

**Architecture:** 좌표 변환 객체(CoordTransform)가 DXF 월드 좌표를 스크린 픽셀로 명시적으로 변환한다. EntityRenderer는 변환된 픽셀 좌표로 Canvas에 직접 그린다. DrawingView는 Pan/Pinch-Zoom/더블탭-Fit 제스처로 Matrix를 갱신하고 무효화한다.

**Tech Stack:** Kotlin, android.view.View, android.graphics.{Canvas, Matrix, Path, Paint, RectF}, ScaleGestureDetector, GestureDetector, ActivityResultContracts.OpenDocument (SAF), lifecycleScope + Dispatchers.IO (코루틴), SharedPreferences (최근 파일)

---

## 렌더러 핵심 설계 결정 (읽어야 함)

### 좌표계

DXF는 Y-up, Android Canvas는 Y-down. Matrix의 Y 스케일을 음수로 설정해 플립한다:

```
screen_x =  world_x * scale + tx
screen_y = -world_y * scale + ty   ← Y-flip
```

`canvas.concat(matrix)` 대신 **각 점을 명시적으로 변환** (`matrix.mapPoints()`)한다.
이유: concat 후 텍스트가 뒤집히고, 아크 각도가 헷갈린다.

### Fit-to-screen Matrix

```
scale = min(viewW / worldW, viewH / worldH) * 0.95   // 5% 여백
tx    = (viewW - worldW * scale) / 2  -  minX * scale
ty    = viewH - (viewH - worldH * scale) / 2  +  minY * scale
```

### 아크 각도 변환 (Y-flip)

DXF: CCW, 도(degree). Android drawArc: 양수 sweep = CW.  
Y-flip으로 CCW가 CW가 되므로, 방향은 맞지만 시작각이 뒤집힌다:

```
screenStart = -endAngleDeg
screenSweep = endAngleDeg - startAngleDeg   (≤0이면 +360)
```

### 렌더 순서

1. HATCH (채움, 맨 뒤) — 단순히 skip (위치 표시만)
2. 선 계열: LINE, CIRCLE, ARC, LWPOLYLINE, ELLIPSE, SPLINE, LEADER, DIMENSION
3. 텍스트: TEXT, MTEXT (맨 앞)

INSERT / DxfUnknown은 skip.

---

## 파일 구성

| 파일 | 동작 |
|------|------|
| **CREATE** `render/CoordTransform.kt` | fitMatrix, worldToScreen, currentScale |
| **CREATE** `render/EntityRenderer.kt` | DxfEntity → Canvas 그리기 dispatcher |
| **CREATE** `render/DrawingView.kt` | custom View + 제스처 |
| **CREATE** `ui/ViewerActivity.kt` | SAF → 파이프라인 → DrawingView |
| **CREATE** `ui/RecentFilesManager.kt` | SharedPreferences CRUD |
| **MODIFY** `MainActivity.kt` | 홈 화면 (최근 파일 + 파일 열기 버튼) |
| **CREATE** `res/layout/activity_viewer.xml` | 뷰어 레이아웃 |
| **MODIFY** `res/layout/activity_main.xml` | 홈 레이아웃 |
| **MODIFY** `res/values/strings.xml` | 문자열 리소스 |
| **MODIFY** `AndroidManifest.xml` | ViewerActivity 등록 |
| **MODIFY** `gradle/libs.versions.toml` | lifecycle 버전 추가 |
| **MODIFY** `app/build.gradle.kts` | lifecycle-runtime-ktx 의존성 추가 |
| **CREATE** `src/test/.../render/CoordTransformTest.kt` | 단위 테스트 |

---

## Task 1: CoordTransform — 좌표 변환 수학

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/render/CoordTransform.kt`
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/render/CoordTransformTest.kt`

- [ ] **Step 1: 테스트 파일 작성 (먼저)**

`app/src/test/java/io/github/june690602_blip/cleancad/render/CoordTransformTest.kt`:

```kotlin
package io.github.june690602_blip.cleancad.render

import android.graphics.Matrix
import android.graphics.PointF
import io.github.june690602_blip.cleancad.model.BoundingBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoordTransformTest {

    @Test
    fun `fitMatrix maps bounding box center to view center`() {
        val box = BoundingBox(0.0, 0.0, 100.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        val pt = CoordTransform.worldToScreen(50.0, 50.0, matrix)
        assertEquals(200f, pt.x, 1f)
        assertEquals(200f, pt.y, 1f)
    }

    @Test
    fun `fitMatrix flips Y — world top maps to screen top`() {
        val box = BoundingBox(0.0, 0.0, 100.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        val top = CoordTransform.worldToScreen(50.0, box.maxY, matrix)
        val bottom = CoordTransform.worldToScreen(50.0, box.minY, matrix)
        assertTrue("world maxY should be smaller screenY than world minY", top.y < bottom.y)
    }

    @Test
    fun `fitMatrix respects aspect ratio — wide world in square view`() {
        val box = BoundingBox(0.0, 0.0, 200.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        // scale limited by width: (400/200)*0.95 = 1.9
        assertEquals(1.9f, CoordTransform.currentScale(matrix), 0.01f)
    }

    @Test
    fun `currentScale returns positive value despite Y-flip`() {
        val box = BoundingBox(0.0, 0.0, 10.0, 10.0)
        val matrix = CoordTransform.fitMatrix(box, 100, 100)
        assertTrue(CoordTransform.currentScale(matrix) > 0f)
    }

    @Test
    fun `worldToScreen maps world origin correctly`() {
        val box = BoundingBox(0.0, 0.0, 100.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        val pt = CoordTransform.worldToScreen(0.0, 0.0, matrix)
        // world (0,0) is bottom-left → screen bottom-left in the padded region
        val scale = CoordTransform.currentScale(matrix)
        val expectedX = (400f - 100f * scale) / 2f
        val expectedY = 400f - (400f - 100f * scale) / 2f
        assertEquals(expectedX, pt.x, 1f)
        assertEquals(expectedY, pt.y, 1f)
    }
}
```

> **주의:** Robolectric이 없으면 Matrix가 Android 환경 없이 동작하지 않는다. Robolectric을 추가해야 한다.

- [ ] **Step 2: Robolectric 의존성 추가**

`gradle/libs.versions.toml`에 추가:
```toml
[versions]
# 기존 버전들...
robolectric = "4.14.1"
lifecycle = "2.9.0"

[libraries]
# 기존 라이브러리들...
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
```

`app/build.gradle.kts`의 `dependencies` 블록에 추가:
```kotlin
dependencies {
    // 기존 의존성들 유지 ...
    testImplementation(libs.robolectric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
```

그리고 `android {}` 블록 안에 추가:
```kotlin
android {
    // 기존 설정들...
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
```

- [ ] **Step 3: 테스트 실행 → FAIL 확인**

```
./gradlew :app:test --tests "*.CoordTransformTest" 2>&1 | tail -20
```

예상: `CoordTransform`이 없으므로 컴파일 오류 또는 `ClassNotFoundException`

- [ ] **Step 4: CoordTransform 구현**

`app/src/main/java/io/github/june690602_blip/cleancad/render/CoordTransform.kt`:

```kotlin
package io.github.june690602_blip.cleancad.render

import android.graphics.Matrix
import android.graphics.PointF
import io.github.june690602_blip.cleancad.model.BoundingBox
import io.github.june690602_blip.cleancad.model.Vec2

object CoordTransform {

    /**
     * DXF 월드 BoundingBox를 뷰 크기에 맞추는 Matrix를 생성한다.
     * Y축을 플립한다 (DXF Y-up → Android Y-down).
     * 5% 여백을 남긴다.
     */
    fun fitMatrix(box: BoundingBox, viewW: Int, viewH: Int): Matrix {
        val scale = minOf(viewW / box.width, viewH / box.height) * 0.95
        val tx = (viewW - box.width * scale) / 2.0 - box.minX * scale
        val ty = viewH - (viewH - box.height * scale) / 2.0 + box.minY * scale
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    scale.toFloat(), 0f, tx.toFloat(),
                    0f, (-scale).toFloat(), ty.toFloat(),
                    0f, 0f, 1f
                )
            )
        }
    }

    /** 월드 좌표 → 스크린 픽셀 좌표 */
    fun worldToScreen(wx: Double, wy: Double, matrix: Matrix): PointF {
        val pts = floatArrayOf(wx.toFloat(), wy.toFloat())
        matrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun worldToScreen(pt: Vec2, matrix: Matrix): PointF = worldToScreen(pt.x, pt.y, matrix)

    /**
     * 현재 스케일 (항상 양수).
     * Y 스케일은 음수이므로 X 스케일 값을 사용한다.
     */
    fun currentScale(matrix: Matrix): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }
}
```

- [ ] **Step 5: 테스트 실행 → PASS 확인**

```
./gradlew :app:test --tests "*.CoordTransformTest" 2>&1 | tail -10
```

예상: `5 tests completed, 0 failures`

- [ ] **Step 6: 커밋**

```bash
git -C "C:/dev/opendwg" add \
  gradle/libs.versions.toml \
  app/build.gradle.kts \
  app/src/main/java/io/github/june690602_blip/cleancad/render/CoordTransform.kt \
  app/src/test/java/io/github/june690602_blip/cleancad/render/CoordTransformTest.kt
git -C "C:/dev/opendwg" commit -m "feat: Phase 4 — CoordTransform world→screen math + Robolectric tests"
```

---

## Task 2: EntityRenderer — Canvas 그리기 dispatcher

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`

(Canvas 단위 테스트는 Robolectric으로도 어렵다. 시각 검증은 Task 7 이후 실기기/에뮬레이터로.)

- [ ] **Step 1: EntityRenderer 구현**

`app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`:

```kotlin
package io.github.june690602_blip.cleancad.render

import android.graphics.*
import io.github.june690602_blip.cleancad.model.*
import kotlin.math.*

class EntityRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f
    }

    /** Drawing의 모든 엔티티를 렌더 순서대로 Canvas에 그린다. */
    fun drawAll(entities: List<DxfEntity>, canvas: Canvas, matrix: Matrix) {
        // 1단계: 채움 계열 (HATCH — 현재는 skip, 위치만 표시하지 않음)
        // 2단계: 선 계열
        entities.forEach { entity ->
            if (entity !is DxfText && entity !is DxfMText) draw(entity, canvas, matrix)
        }
        // 3단계: 텍스트 (맨 위)
        entities.forEach { entity ->
            if (entity is DxfText || entity is DxfMText) draw(entity, canvas, matrix)
        }
    }

    private fun draw(entity: DxfEntity, canvas: Canvas, matrix: Matrix) {
        when (entity) {
            is DxfLine       -> drawLine(entity, canvas, matrix)
            is DxfCircle     -> drawCircle(entity, canvas, matrix)
            is DxfArc        -> drawArc(entity, canvas, matrix)
            is DxfLwPolyline -> drawLwPolyline(entity, canvas, matrix)
            is DxfEllipse    -> drawEllipse(entity, canvas, matrix)
            is DxfSpline     -> drawSpline(entity, canvas, matrix)
            is DxfText       -> drawText(entity, canvas, matrix)
            is DxfMText      -> drawMText(entity, canvas, matrix)
            is DxfDimension  -> drawDimension(entity, canvas, matrix)
            is DxfLeader     -> drawLeader(entity, canvas, matrix)
            is DxfHatch, is DxfInsert, is DxfUnknown -> { /* skip */ }
        }
    }

    private fun drawLine(e: DxfLine, canvas: Canvas, matrix: Matrix) {
        val s = CoordTransform.worldToScreen(e.start, matrix)
        val end = CoordTransform.worldToScreen(e.end, matrix)
        canvas.drawLine(s.x, s.y, end.x, end.y, linePaint)
    }

    private fun drawCircle(e: DxfCircle, canvas: Canvas, matrix: Matrix) {
        val c = CoordTransform.worldToScreen(e.center, matrix)
        val r = (e.radius * CoordTransform.currentScale(matrix)).toFloat()
        canvas.drawCircle(c.x, c.y, r, linePaint)
    }

    private fun drawArc(e: DxfArc, canvas: Canvas, matrix: Matrix) {
        val c = CoordTransform.worldToScreen(e.center, matrix)
        val r = (e.radius * CoordTransform.currentScale(matrix)).toFloat()
        val oval = RectF(c.x - r, c.y - r, c.x + r, c.y + r)
        // Y-flip: DXF CCW → screen CW
        // startAngle = -endAngleDeg, sweep = endAngleDeg - startAngleDeg
        var sweep = (e.endAngleDeg - e.startAngleDeg).toFloat()
        if (sweep <= 0f) sweep += 360f
        val startAngle = (-e.endAngleDeg).toFloat()
        canvas.drawArc(oval, startAngle, sweep, false, linePaint)
    }

    private fun drawLwPolyline(e: DxfLwPolyline, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        if (e.closed) path.close()
        canvas.drawPath(path, linePaint)
    }

    private fun drawEllipse(e: DxfEllipse, canvas: Canvas, matrix: Matrix) {
        // 72개 선분으로 근사
        val majorLen = sqrt(e.majorAxis.x * e.majorAxis.x + e.majorAxis.y * e.majorAxis.y)
        val minorLen = majorLen * e.minorRatio
        val rot = atan2(e.majorAxis.y, e.majorAxis.x)
        val steps = 72
        val path = Path()
        val paramRange = e.endParam - e.startParam
        for (i in 0..steps) {
            val t = e.startParam + i.toDouble() / steps * paramRange
            val wx = e.center.x + majorLen * cos(t) * cos(rot) - minorLen * sin(t) * sin(rot)
            val wy = e.center.y + majorLen * cos(t) * sin(rot) + minorLen * sin(t) * cos(rot)
            val pt = CoordTransform.worldToScreen(wx, wy, matrix)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawSpline(e: DxfSpline, canvas: Canvas, matrix: Matrix) {
        if (e.controlPoints.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.controlPoints[0], matrix)
        path.moveTo(first.x, first.y)
        // 제어점을 폴리라인으로 연결 (B-스플라인 근사)
        for (i in 1 until e.controlPoints.size) {
            val pt = CoordTransform.worldToScreen(e.controlPoints[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawText(e: DxfText, canvas: Canvas, matrix: Matrix) {
        val pt = CoordTransform.worldToScreen(e.insertionPoint, matrix)
        textPaint.textSize = (e.height * CoordTransform.currentScale(matrix)).toFloat()
            .coerceAtLeast(8f)
        canvas.save()
        canvas.translate(pt.x, pt.y)
        canvas.rotate(-e.rotationDeg.toFloat())
        canvas.drawText(e.text, 0f, 0f, textPaint)
        canvas.restore()
    }

    private fun drawMText(e: DxfMText, canvas: Canvas, matrix: Matrix) {
        val pt = CoordTransform.worldToScreen(e.insertionPoint, matrix)
        textPaint.textSize = (e.height * CoordTransform.currentScale(matrix)).toFloat()
            .coerceAtLeast(8f)
        // MText 서식 코드 제거: {\fArial;...}, \P(줄바꿈), {} 등
        val stripped = e.text
            .replace(Regex("\\\\[A-Za-z][^;]*;"), "")
            .replace(Regex("\\\\[^A-Za-z]"), "")
            .replace(Regex("[{}]"), "")
            .replace("\\P", "\n")
        canvas.save()
        canvas.translate(pt.x, pt.y)
        canvas.rotate(-e.rotationDeg.toFloat())
        canvas.drawText(stripped, 0f, 0f, textPaint)
        canvas.restore()
    }

    private fun drawDimension(e: DxfDimension, canvas: Canvas, matrix: Matrix) {
        val dp = CoordTransform.worldToScreen(e.definitionPoint, matrix)
        val tp = CoordTransform.worldToScreen(e.textMidPoint, matrix)
        canvas.drawLine(dp.x, dp.y, tp.x, tp.y, linePaint)
        if (e.textOverride.isNotBlank()) {
            textPaint.textSize = 12f
            canvas.drawText(e.textOverride, tp.x, tp.y, textPaint)
        }
    }

    private fun drawLeader(e: DxfLeader, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
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
git -C "C:/dev/opendwg" add app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt
git -C "C:/dev/opendwg" commit -m "feat: EntityRenderer — Canvas drawing for all Tier-2 DXF entities"
```

---

## Task 3: DrawingView — 커스텀 View + 제스처

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt`

- [ ] **Step 1: DrawingView 구현**

`app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt`:

```kotlin
package io.github.june690602_blip.cleancad.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import io.github.june690602_blip.cleancad.model.Drawing

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawing: Drawing? = null
    private var matrix = Matrix()
    private val renderer = EntityRenderer()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val s = detector.scaleFactor.coerceIn(0.5f, 2.0f)
                matrix.postScale(s, s, detector.focusX, detector.focusY)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                matrix.postTranslate(-distanceX, -distanceY)
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                fitToScreen()
                return true
            }
        }
    )

    /** 외부에서 Drawing 모델을 설정한다. 메인 스레드에서 호출해야 한다. */
    fun setDrawing(drawing: Drawing) {
        this.drawing = drawing
        if (width > 0 && height > 0) fitToScreen() else matrix = Matrix()
        invalidate()
    }

    /** 화면에 도면 전체가 들어오도록 Matrix를 초기화한다. */
    fun fitToScreen() {
        val box = drawing?.extents ?: return
        if (width <= 0 || height <= 0) return
        matrix = CoordTransform.fitMatrix(box, width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawing != null) fitToScreen()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val d = drawing ?: return
        renderer.drawAll(d.entities, canvas, matrix)
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
git -C "C:/dev/opendwg" add app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt
git -C "C:/dev/opendwg" commit -m "feat: DrawingView — custom View with pan/pinch-zoom/double-tap-fit gestures"
```

---

## Task 4: RecentFilesManager

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/ui/RecentFilesManager.kt`

- [ ] **Step 1: 구현**

`app/src/main/java/io/github/june690602_blip/cleancad/ui/RecentFilesManager.kt`:

```kotlin
package io.github.june690602_blip.cleancad.ui

import android.content.Context

class RecentFilesManager(context: Context) {

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    data class RecentFile(val uri: String, val name: String, val timestamp: Long)

    fun add(uri: String, name: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.uri == uri }
        list.add(0, RecentFile(uri, name, System.currentTimeMillis()))
        if (list.size > MAX_ITEMS) list.removeAt(list.size - 1)
        save(list)
    }

    fun getAll(): List<RecentFile> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).map { i ->
            RecentFile(
                uri = prefs.getString("${KEY_URI}_$i", "") ?: "",
                name = prefs.getString("${KEY_NAME}_$i", "") ?: "",
                timestamp = prefs.getLong("${KEY_TS}_$i", 0L)
            )
        }
    }

    private fun save(list: List<RecentFile>) {
        prefs.edit().apply {
            putInt(KEY_COUNT, list.size)
            list.forEachIndexed { i, f ->
                putString("${KEY_URI}_$i", f.uri)
                putString("${KEY_NAME}_$i", f.name)
                putLong("${KEY_TS}_$i", f.timestamp)
            }
            apply()
        }
    }

    companion object {
        private const val MAX_ITEMS = 10
        private const val KEY_COUNT = "count"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_TS = "ts"
    }
}
```

- [ ] **Step 2: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

- [ ] **Step 3: 커밋**

```bash
git -C "C:/dev/opendwg" add app/src/main/java/io/github/june690602_blip/cleancad/ui/RecentFilesManager.kt
git -C "C:/dev/opendwg" commit -m "feat: RecentFilesManager — SharedPreferences-based recent files list"
```

---

## Task 5: 레이아웃 XML + 문자열 리소스

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/layout/activity_viewer.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: strings.xml 업데이트**

`app/src/main/res/values/strings.xml` 전체 교체:

```xml
<resources>
    <string name="app_name">CleanCAD Viewer</string>
    <string name="open_file">DWG 파일 열기</string>
    <string name="recent_files">최근 파일</string>
    <string name="no_recent_files">최근 파일이 없습니다</string>
    <string name="loading">도면 불러오는 중…</string>
    <string name="error_prefix">열기 실패: </string>
    <string name="fit_to_screen">화면 맞춤</string>
</resources>
```

- [ ] **Step 2: activity_main.xml (홈 화면) 교체**

`app/src/main/res/layout/activity_main.xml` 전체 교체:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textSize="24sp"
        android:textStyle="bold"
        android:layout_marginBottom="24dp" />

    <Button
        android:id="@+id/btn_open"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/open_file" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/recent_files"
        android:textSize="16sp"
        android:textStyle="bold"
        android:layout_marginTop="24dp"
        android:layout_marginBottom="8dp" />

    <TextView
        android:id="@+id/tv_empty"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/no_recent_files"
        android:visibility="gone" />

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <LinearLayout
            android:id="@+id/container_recent"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical" />

    </ScrollView>

</LinearLayout>
```

- [ ] **Step 3: activity_viewer.xml 생성**

`app/src/main/res/layout/activity_viewer.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#FFFFFF">

    <io.github.june690602_blip.cleancad.render.DrawingView
        android:id="@+id/drawing_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <ProgressBar
        android:id="@+id/progress_bar"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:visibility="gone" />

    <TextView
        android:id="@+id/tv_error"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:padding="16dp"
        android:textSize="16sp"
        android:visibility="gone" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fab_fit"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:contentDescription="@string/fit_to_screen"
        android:src="@android:drawable/ic_menu_zoom"
        android:visibility="gone" />

</FrameLayout>
```

- [ ] **Step 4: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git -C "C:/dev/opendwg" add \
  app/src/main/res/layout/activity_main.xml \
  app/src/main/res/layout/activity_viewer.xml \
  app/src/main/res/values/strings.xml
git -C "C:/dev/opendwg" commit -m "feat: home + viewer XML layouts and string resources"
```

---

## Task 6: ViewerActivity — 파이프라인 + DrawingView 호스트

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt`

- [ ] **Step 1: ViewerActivity 구현**

`app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt`:

```kotlin
package io.github.june690602_blip.cleancad.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.june690602_blip.cleancad.NativeDwg
import io.github.june690602_blip.cleancad.R
import io.github.june690602_blip.cleancad.parser.DxfParser
import io.github.june690602_blip.cleancad.render.DrawingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var fabFit: FloatingActionButton

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openFile(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        drawingView = findViewById(R.id.drawing_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        fabFit = findViewById(R.id.fab_fit)

        fabFit.setOnClickListener { drawingView.fitToScreen() }

        val uri = intent.data
        if (uri != null) openFile(uri) else openDoc.launch(arrayOf("*/*"))
    }

    private fun openFile(uri: Uri) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                // 1. content URI → 캐시 파일 (JNI는 실제 파일 경로 필요)
                val dwgFile = File(cacheDir, "current.dwg")
                contentResolver.openInputStream(uri)!!.use { it.copyTo(dwgFile.outputStream()) }

                // 2. JNI: DWG → DXF
                val dxfFile = File(cacheDir, "current.dxf")
                val rc = NativeDwg.nativeDwgToDxf(dwgFile.absolutePath, dxfFile.absolutePath)
                if (rc != 0) throw RuntimeException("DWG 변환 실패 (코드: $rc)")

                // 3. DXF 파싱 → Drawing 모델
                val dxfContent = dxfFile.readText()
                DxfParser.parse(dxfContent)
            }

            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { drawing ->
                        // 최근 파일 저장
                        val name = uri.lastPathSegment ?: uri.toString()
                        RecentFilesManager(this@ViewerActivity).add(uri.toString(), name)
                        showDrawing()
                        drawingView.setDrawing(drawing)
                    },
                    onFailure = { e ->
                        showError(getString(R.string.error_prefix) + (e.message ?: "알 수 없는 오류"))
                    }
                )
            }
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
git -C "C:/dev/opendwg" add app/src/main/java/io/github/june690602_blip/cleancad/ui/ViewerActivity.kt
git -C "C:/dev/opendwg" commit -m "feat: ViewerActivity — SAF + coroutine DWG→DXF→Drawing pipeline"
```

---

## Task 7: MainActivity 홈 화면 교체

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt`

- [ ] **Step 1: MainActivity 교체**

`app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt` 전체 교체:

```kotlin
package io.github.june690602_blip.cleancad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import io.github.june690602_blip.cleancad.ui.RecentFilesManager
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

- [ ] **Step 2: 빌드 확인**

```
./gradlew :app:assembleDebug 2>&1 | tail -5
```

예상: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git -C "C:/dev/opendwg" add app/src/main/java/io/github/june690602_blip/cleancad/MainActivity.kt
git -C "C:/dev/opendwg" commit -m "feat: MainActivity — home screen with recent files list and file picker"
```

---

## Task 8: AndroidManifest — ViewerActivity 등록

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: AndroidManifest.xml 수정**

현재 `</application>` 닫기 태그 바로 전에 ViewerActivity를 추가한다:

```xml
        <activity
            android:name=".ui.ViewerActivity"
            android:label="@string/app_name"
            android:exported="false" />
```

최종 `AndroidManifest.xml`은 아래와 같다 (기존 MainActivity 설정은 유지):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Opendwg">

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
            android:exported="false" />

    </application>

</manifest>
```

- [ ] **Step 2: 전체 빌드 + 기존 테스트 확인**

```
./gradlew :app:assembleDebug :app:test 2>&1 | tail -10
```

예상: `BUILD SUCCESSFUL`, 테스트 전부 PASS

- [ ] **Step 3: 최종 커밋**

```bash
git -C "C:/dev/opendwg" add app/src/main/AndroidManifest.xml
git -C "C:/dev/opendwg" commit -m "feat: Phase 4 complete — register ViewerActivity in manifest"
```

---

## Task 9: 에뮬레이터 시각 검증

Phase 4의 핵심 가치는 도면이 실제로 보이는 것이다. 코드만으로는 확인 불가 — 에뮬레이터에서 직접 실행해야 한다.

- [ ] **Step 1: 앱 설치 후 실행**

```
./gradlew :app:installDebug
```

AVD `Medium_Phone_API_36.1` 에뮬레이터가 실행 중이어야 한다.

- [ ] **Step 2: 검증 체크리스트**

- [ ] 홈 화면이 뜬다 (앱 이름 + "DWG 파일 열기" 버튼)
- [ ] "DWG 파일 열기" 버튼 → SAF 파일 피커가 열린다
- [ ] `circle.dwg` (instrumented 테스트용 샘플) 선택 → 로딩 스피너 → 도면 렌더링
- [ ] 도면이 흰 배경에 검정 선으로 표시된다
- [ ] 핀치-줌으로 확대/축소 가능
- [ ] 드래그(Pan)로 이동 가능
- [ ] 더블탭 → 전체 화면 맞춤(Fit-to-screen)
- [ ] FAB(돋보기) 버튼 → Fit-to-screen
- [ ] 뒤로 가기 → 홈 화면, 최근 파일에 파일명이 표시된다
- [ ] 최근 파일 항목 탭 → 다시 뷰어 열림

- [ ] **Step 3: 결함 발견 시 수정 후 커밋**

각 버그마다 별도 커밋. 메시지 형식: `fix: <증상 설명>`

---

## 자가 검증 (Spec Coverage)

| 설계 문서 항목 | 담당 Task |
|---------------|-----------|
| DrawingView — Canvas + Matrix | Task 1, 3 |
| Pan / Pinch-Zoom / Fit-to-screen | Task 3 |
| SAF 파일 열기 | Task 6, 7 |
| 백그라운드 파싱 (Dispatchers.IO) | Task 6 |
| 로딩 스피너 / 에러 메시지 | Task 6 |
| 최근 파일 목록 | Task 4, 7 |
| Tier 2 엔티티 렌더링 | Task 2 |
| Y-axis flip | Task 1 (CoordTransform) |
| 좌표 변환 단위 테스트 | Task 1 |
