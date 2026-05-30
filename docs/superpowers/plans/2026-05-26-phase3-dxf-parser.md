# Phase 3: DwgToDxf JNI + DXF 파서 + Drawing 모델 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nativeDwgToDxf(inPath, outPath)` JNI 함수를 완성하고, Kotlin DXF 파서와 불변 Drawing 모델을 구현해 "DWG 파일을 열면 엔티티 목록이 추출된다"는 엔드투엔드 파이프라인을 동작시킨다.

**Architecture:** native는 `dwg_read_file` + `dwg_write_dxf`로 DWG → DXF 파일 변환만 담당(JNI 경계 최소화). Kotlin `DxfParser`가 DXF 텍스트를 섹션 단위로 읽어 `Drawing` 불변 모델로 변환한다. 렌더링은 Phase 4 담당이므로 이 Phase에서는 파싱까지만 구현한다.

**Tech Stack:** Kotlin(불변 data class, sealed class), Android JVM 단위 테스트(JUnit4), LibreDWG JNI(C, `dwg.h` / `out_dxf.h` / `bits.h`), AndroidJUnit4 instrumented 테스트.

**브랜치:** `phase2-libredwg`에서 `phase3-parser` 브랜치로 시작한다. Phase 2 PR이 아직 main에 머지 안 됐으므로 LibreDWG가 포함된 phase2-libredwg를 베이스로 사용한다.

```bash
cd /c/dev/opendwg
git checkout phase2-libredwg
git checkout -b phase3-parser
```

---

## File Structure

이 Phase에서 생성/수정하는 파일:

**수정:**
- `app/src/main/cpp/dwgjni.c` — `nativeDwgToDxf` JNI 함수 추가
- `app/src/main/cpp/CMakeLists.txt` — libredwg/src + 빌드 디렉토리를 include path에 추가
- `app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt` — `nativeDwgToDxf` external fun 추가

**신규 (모델):**
- `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt` — sealed class + 모든 엔티티 data class
- `app/src/main/java/io/github/june690602_blip/cleancad/model/Drawing.kt` — `Drawing`, `Layer`, `BoundingBox`, `Vec2` data class

**신규 (파서):**
- `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfReader.kt` — DXF 그룹코드 쌍 리더
- `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt` — TABLES/ENTITIES 섹션 파서 → Drawing

**신규 (테스트):**
- `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfReaderTest.kt` — JVM 단위 테스트
- `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt` — JVM 단위 테스트
- `app/src/androidTest/assets/circle.dwg` — 샘플 DWG (LibreDWG submodule 복사)
- `app/src/androidTest/java/io/github/june690602_blip/cleancad/NativePipelineTest.kt` — 엔드투엔드 instrumented 테스트

---

## Task 1: 브랜치 생성 + Drawing 모델

Drawing 파이프라인의 기반이 되는 불변 모델 클래스를 정의한다.

**Files:**
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/model/DxfEntity.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/model/Drawing.kt`

- [ ] **Step 1: 브랜치 생성**

```bash
cd /c/dev/opendwg
git checkout phase2-libredwg
git checkout -b phase3-parser
```

Expected: `Switched to a new branch 'phase3-parser'`

- [ ] **Step 2: `model/DxfEntity.kt` 작성**

```kotlin
package io.github.june690602_blip.cleancad.model

data class Vec2(val x: Double, val y: Double)

sealed class DxfEntity {
    abstract val layer: String
}

data class DxfLine(
    override val layer: String,
    val start: Vec2,
    val end: Vec2
) : DxfEntity()

data class DxfCircle(
    override val layer: String,
    val center: Vec2,
    val radius: Double
) : DxfEntity()

data class DxfArc(
    override val layer: String,
    val center: Vec2,
    val radius: Double,
    val startAngleDeg: Double,
    val endAngleDeg: Double
) : DxfEntity()

data class DxfLwPolyline(
    override val layer: String,
    val vertices: List<Vec2>,
    val closed: Boolean
) : DxfEntity()

data class DxfEllipse(
    override val layer: String,
    val center: Vec2,
    val majorAxis: Vec2,
    val minorRatio: Double,
    val startParam: Double,
    val endParam: Double
) : DxfEntity()

data class DxfSpline(
    override val layer: String,
    val degree: Int,
    val controlPoints: List<Vec2>
) : DxfEntity()

data class DxfText(
    override val layer: String,
    val insertionPoint: Vec2,
    val height: Double,
    val text: String,
    val rotationDeg: Double = 0.0
) : DxfEntity()

data class DxfMText(
    override val layer: String,
    val insertionPoint: Vec2,
    val height: Double,
    val text: String,
    val rotationDeg: Double = 0.0
) : DxfEntity()

data class DxfInsert(
    override val layer: String,
    val blockName: String,
    val insertionPoint: Vec2,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val rotationDeg: Double = 0.0
) : DxfEntity()

data class DxfDimension(
    override val layer: String,
    val definitionPoint: Vec2,
    val textMidPoint: Vec2,
    val dimType: Int,
    val textOverride: String = ""
) : DxfEntity()

data class DxfHatch(
    override val layer: String,
    val isSolid: Boolean
) : DxfEntity()

data class DxfLeader(
    override val layer: String,
    val vertices: List<Vec2>
) : DxfEntity()

data class DxfUnknown(
    override val layer: String,
    val type: String
) : DxfEntity()
```

- [ ] **Step 3: `model/Drawing.kt` 작성**

```kotlin
package io.github.june690602_blip.cleancad.model

data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

data class Layer(
    val name: String,
    val colorIndex: Int = 7
)

data class Drawing(
    val entities: List<DxfEntity>,
    val layers: List<Layer>,
    val extents: BoundingBox?
)
```

- [ ] **Step 4: 빌드 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL` (컴파일 에러 없음)

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/java/io/github/june690602_blip/cleancad/model/
git commit -m "feat: add DxfEntity sealed class hierarchy and Drawing model"
```

---

## Task 2: DxfReader (그룹코드 쌍 리더)

DXF 텍스트를 (코드 번호, 값) 쌍으로 읽어주는 저수준 리더. 상태(위치)를 갖고 있어 한 번 읽으면 진행된다.

**Files:**
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfReaderTest.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfReader.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfReaderTest.kt`:
```kotlin
package io.github.june690602_blip.cleancad.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfReaderTest {

    @Test
    fun next_returnsGroupCodeAndValue() {
        val reader = DxfReader("  0\nSECTION\n  2\nENTITIES")
        val gc = reader.next()
        assertEquals(0, gc.code)
        assertEquals("SECTION", gc.value)
    }

    @Test
    fun next_trimsWhitespaceFromCode() {
        val reader = DxfReader(" 10\n100.0\n 20\n50.0")
        val gc = reader.next()
        assertEquals(10, gc.code)
        assertEquals("100.0", gc.value)
    }

    @Test
    fun peek_returnsNextWithoutAdvancing() {
        val reader = DxfReader("  0\nLINE\n  8\nlayer0")
        val peeked = reader.peek()
        val next = reader.next()
        assertEquals(0, peeked!!.code)
        assertEquals("LINE", peeked.value)
        assertEquals(0, next.code)
        assertEquals("LINE", next.value)
    }

    @Test
    fun hasNext_falseWhenExhausted() {
        val reader = DxfReader("  0\nEOF")
        reader.next()
        assertFalse(reader.hasNext())
    }

    @Test
    fun peek_nullWhenExhausted() {
        val reader = DxfReader("  0\nEOF")
        reader.next()
        assertNull(reader.peek())
    }

    @Test
    fun handles_crlfLineEndings() {
        val reader = DxfReader("  0\r\nSECTION\r\n  2\r\nENTITIES")
        val gc = reader.next()
        assertEquals(0, gc.code)
        assertEquals("SECTION", gc.value)
    }
}
```

- [ ] **Step 2: 테스트 실행 → FAIL 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfReaderTest" --console=plain
```

Expected: 빌드 실패 (`DxfReader` 없음)

- [ ] **Step 3: `DxfReader.kt` 구현**

`app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfReader.kt`:
```kotlin
package io.github.june690602_blip.cleancad.parser

data class GroupCode(val code: Int, val value: String)

class DxfReader(input: String) {
    // 빈 줄 제거: DXF 파서가 빈 줄에서 NumberFormatException 발생하지 않도록
    private val lines = input.lines().filter { it.isNotBlank() }
    private var pos = 0

    fun hasNext(): Boolean = pos + 1 < lines.size

    fun next(): GroupCode {
        val code = lines[pos].trim().toInt()
        val value = lines[pos + 1].trim()
        pos += 2
        return GroupCode(code, value)
    }

    fun peek(): GroupCode? {
        if (pos + 1 >= lines.size) return null
        return try {
            GroupCode(lines[pos].trim().toInt(), lines[pos + 1].trim())
        } catch (_: NumberFormatException) {
            null
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 → PASS 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfReaderTest" --console=plain
```

Expected: `DxfReaderTest > 6 tests PASSED`, `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfReader.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfReaderTest.kt
git commit -m "feat: add DxfReader for DXF group code pair reading"
```

---

## Task 3: DxfParser — 파서 골격 + LINE / CIRCLE / ARC

DXF ENTITIES 섹션을 순회하며 엔티티를 파싱하는 파서 골격을 만들고, 기본 3개 엔티티를 구현한다.

**Files:**
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`

- [ ] **Step 1: 테스트 헬퍼 + 첫 3개 테스트 작성**

`app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`:
```kotlin
package io.github.june690602_blip.cleancad.parser

import io.github.june690602_blip.cleancad.model.*
import org.junit.Assert.*
import org.junit.Test

class DxfParserTest {

    // DXF 텍스트 내 ENTITIES 섹션을 감싸는 헬퍼
    private fun withEntities(block: String) = """
  0
SECTION
  2
ENTITIES
$block
  0
ENDSEC
  0
EOF""".trimIndent()

    // ---- LINE ----

    @Test
    fun parseLine_returnsCorrectCoordinates() {
        val dxf = withEntities("""
  0
LINE
  8
walls
 10
0.0
 20
0.0
 11
100.0
 21
50.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)

        assertEquals(1, drawing.entities.size)
        val line = drawing.entities[0] as DxfLine
        assertEquals("walls", line.layer)
        assertEquals(Vec2(0.0, 0.0), line.start)
        assertEquals(Vec2(100.0, 50.0), line.end)
    }

    @Test
    fun parseLine_defaultLayerIsZero() {
        val dxf = withEntities("""
  0
LINE
 10
1.0
 20
2.0
 11
3.0
 21
4.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        val line = drawing.entities[0] as DxfLine
        assertEquals("0", line.layer)
    }

    // ---- CIRCLE ----

    @Test
    fun parseCircle_returnsCorrectCenterAndRadius() {
        val dxf = withEntities("""
  0
CIRCLE
  8
pipes
 10
50.0
 20
75.0
 40
25.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)

        assertEquals(1, drawing.entities.size)
        val circle = drawing.entities[0] as DxfCircle
        assertEquals("pipes", circle.layer)
        assertEquals(Vec2(50.0, 75.0), circle.center)
        assertEquals(25.0, circle.radius, 1e-9)
    }

    // ---- ARC ----

    @Test
    fun parseArc_returnsAngles() {
        val dxf = withEntities("""
  0
ARC
  8
0
 10
0.0
 20
0.0
 40
10.0
 50
30.0
 51
120.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)

        assertEquals(1, drawing.entities.size)
        val arc = drawing.entities[0] as DxfArc
        assertEquals(Vec2(0.0, 0.0), arc.center)
        assertEquals(10.0, arc.radius, 1e-9)
        assertEquals(30.0, arc.startAngleDeg, 1e-9)
        assertEquals(120.0, arc.endAngleDeg, 1e-9)
    }

    // ---- 여러 엔티티 ----

    @Test
    fun parseMultipleEntities_allExtracted() {
        val dxf = withEntities("""
  0
LINE
 10
0.0
 20
0.0
 11
1.0
 21
1.0
  0
CIRCLE
 10
5.0
 20
5.0
 40
2.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        assertEquals(2, drawing.entities.size)
        assertTrue(drawing.entities[0] is DxfLine)
        assertTrue(drawing.entities[1] is DxfCircle)
    }

    // ---- 미지원 엔티티 ----

    @Test
    fun unknownEntity_returnedAsDxfUnknown() {
        val dxf = withEntities("""
  0
XLINE
  8
0
 10
0.0
 20
0.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        assertEquals(1, drawing.entities.size)
        assertTrue(drawing.entities[0] is DxfUnknown)
        assertEquals("XLINE", (drawing.entities[0] as DxfUnknown).type)
    }
}
```

- [ ] **Step 2: 테스트 실행 → FAIL 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfParserTest" --console=plain
```

Expected: 빌드 실패 (`DxfParser` 없음)

- [ ] **Step 3: `DxfParser.kt` 골격 + LINE/CIRCLE/ARC 구현**

`app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`:
```kotlin
package io.github.june690602_blip.cleancad.parser

import io.github.june690602_blip.cleancad.model.*

object DxfParser {

    fun parse(dxfContent: String): Drawing {
        val reader = DxfReader(dxfContent)
        val layers = mutableListOf<Layer>()
        val entities = mutableListOf<DxfEntity>()

        while (reader.hasNext()) {
            val gc = reader.next()
            if (gc.code == 0 && gc.value == "SECTION") {
                val name = reader.next()
                when (name.value) {
                    "TABLES" -> parseTables(reader, layers)
                    "ENTITIES" -> parseEntities(reader, entities)
                    else -> skipSection(reader)
                }
            }
        }

        return Drawing(
            entities = entities,
            layers = layers,
            extents = calculateBoundingBox(entities)
        )
    }

    // ---- 섹션 파싱 ----

    private fun skipSection(reader: DxfReader) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDSEC") { reader.next(); return }
            reader.next()
        }
    }

    private fun parseTables(reader: DxfReader, layers: MutableList<Layer>) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDSEC") { reader.next(); return }
            val next = reader.next()
            if (next.code == 0 && next.value == "TABLE") {
                val tableType = reader.next()
                if (tableType.value == "LAYER") parseLayerTable(reader, layers)
                else skipUntil(reader, "ENDTAB")
            }
        }
    }

    private fun parseLayerTable(reader: DxfReader, layers: MutableList<Layer>) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDTAB") { reader.next(); return }
            if (gc.code == 0 && gc.value == "LAYER") {
                reader.next()
                layers.add(parseLayerEntry(reader))
            } else {
                reader.next()
            }
        }
    }

    private fun parseLayerEntry(reader: DxfReader): Layer {
        var name = ""
        var colorIndex = 7
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                2 -> name = next.value
                62 -> colorIndex = next.value.toIntOrNull() ?: 7
            }
        }
        return Layer(name = name, colorIndex = colorIndex)
    }

    private fun skipUntil(reader: DxfReader, token: String) {
        while (reader.hasNext()) {
            val gc = reader.next()
            if (gc.code == 0 && gc.value == token) return
        }
    }

    private fun parseEntities(reader: DxfReader, entities: MutableList<DxfEntity>) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDSEC") { reader.next(); return }
            val next = reader.next()
            if (next.code == 0) {
                parseEntity(next.value, reader)?.let { entities.add(it) }
            }
        }
    }

    private fun parseEntity(type: String, reader: DxfReader): DxfEntity? = when (type) {
        "LINE"       -> parseLine(reader)
        "CIRCLE"     -> parseCircle(reader)
        "ARC"        -> parseArc(reader)
        "LWPOLYLINE" -> parseLwPolyline(reader)
        "ELLIPSE"    -> parseEllipse(reader)
        "SPLINE"     -> parseSpline(reader)
        "TEXT"       -> parseText(reader)
        "MTEXT"      -> parseMText(reader)
        "INSERT"     -> parseInsert(reader)
        "DIMENSION"  -> parseDimension(reader)
        "HATCH"      -> parseHatch(reader)
        "LEADER"     -> parseLeader(reader)
        else         -> { skipEntityBody(reader); DxfUnknown(layer = "", type = type) }
    }

    private fun skipEntityBody(reader: DxfReader) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            reader.next()
        }
    }

    // ---- 엔티티 파서 ----

    private fun parseLine(reader: DxfReader): DxfLine {
        var layer = "0"; var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x1 = next.value.toDouble()
                20 -> y1 = next.value.toDouble()
                11 -> x2 = next.value.toDouble()
                21 -> y2 = next.value.toDouble()
            }
        }
        return DxfLine(layer, Vec2(x1, y1), Vec2(x2, y2))
    }

    private fun parseCircle(reader: DxfReader): DxfCircle {
        var layer = "0"; var cx = 0.0; var cy = 0.0; var r = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> cx = next.value.toDouble()
                20 -> cy = next.value.toDouble()
                40 -> r = next.value.toDouble()
            }
        }
        return DxfCircle(layer, Vec2(cx, cy), r)
    }

    private fun parseArc(reader: DxfReader): DxfArc {
        var layer = "0"; var cx = 0.0; var cy = 0.0; var r = 0.0
        var startAngle = 0.0; var endAngle = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> cx = next.value.toDouble()
                20 -> cy = next.value.toDouble()
                40 -> r = next.value.toDouble()
                50 -> startAngle = next.value.toDouble()
                51 -> endAngle = next.value.toDouble()
            }
        }
        return DxfArc(layer, Vec2(cx, cy), r, startAngle, endAngle)
    }

    // Task 4에서 구현 (지금은 stub — skipEntityBody 처리)
    private fun parseLwPolyline(reader: DxfReader): DxfLwPolyline {
        skipEntityBody(reader); return DxfLwPolyline("0", emptyList(), false)
    }
    private fun parseEllipse(reader: DxfReader): DxfEllipse {
        skipEntityBody(reader); return DxfEllipse("0", Vec2(0.0, 0.0), Vec2(1.0, 0.0), 1.0, 0.0, Math.PI * 2)
    }
    private fun parseSpline(reader: DxfReader): DxfSpline {
        skipEntityBody(reader); return DxfSpline("0", 3, emptyList())
    }

    // Task 5에서 구현
    private fun parseText(reader: DxfReader): DxfText {
        skipEntityBody(reader); return DxfText("0", Vec2(0.0, 0.0), 0.0, "")
    }
    private fun parseMText(reader: DxfReader): DxfMText {
        skipEntityBody(reader); return DxfMText("0", Vec2(0.0, 0.0), 0.0, "")
    }
    private fun parseInsert(reader: DxfReader): DxfInsert {
        skipEntityBody(reader); return DxfInsert("0", "", Vec2(0.0, 0.0))
    }

    // Task 6에서 구현
    private fun parseDimension(reader: DxfReader): DxfDimension {
        skipEntityBody(reader); return DxfDimension("0", Vec2(0.0, 0.0), Vec2(0.0, 0.0), 0)
    }
    private fun parseHatch(reader: DxfReader): DxfHatch {
        skipEntityBody(reader); return DxfHatch("0", false)
    }
    private fun parseLeader(reader: DxfReader): DxfLeader {
        skipEntityBody(reader); return DxfLeader("0", emptyList())
    }

    // ---- BoundingBox ----

    private fun calculateBoundingBox(entities: List<DxfEntity>): BoundingBox? {
        val points = mutableListOf<Vec2>()
        for (entity in entities) {
            when (entity) {
                is DxfLine       -> { points.add(entity.start); points.add(entity.end) }
                is DxfCircle     -> { val r = entity.radius; val c = entity.center
                                      points.add(Vec2(c.x - r, c.y - r)); points.add(Vec2(c.x + r, c.y + r)) }
                is DxfArc        -> { val r = entity.radius; val c = entity.center
                                      points.add(Vec2(c.x - r, c.y - r)); points.add(Vec2(c.x + r, c.y + r)) }
                is DxfLwPolyline -> points.addAll(entity.vertices)
                is DxfEllipse    -> { val len = Math.hypot(entity.majorAxis.x, entity.majorAxis.y)
                                      val c = entity.center
                                      points.add(Vec2(c.x - len, c.y - len)); points.add(Vec2(c.x + len, c.y + len)) }
                is DxfSpline     -> points.addAll(entity.controlPoints)
                is DxfText       -> points.add(entity.insertionPoint)
                is DxfMText      -> points.add(entity.insertionPoint)
                is DxfInsert     -> points.add(entity.insertionPoint)
                is DxfDimension  -> { points.add(entity.definitionPoint); points.add(entity.textMidPoint) }
                is DxfLeader     -> points.addAll(entity.vertices)
                is DxfHatch, is DxfUnknown -> {}
            }
        }
        if (points.isEmpty()) return null
        return BoundingBox(
            minX = points.minOf { it.x }, minY = points.minOf { it.y },
            maxX = points.maxOf { it.x }, maxY = points.maxOf { it.y }
        )
    }
}
```

- [ ] **Step 4: 테스트 실행 → PASS 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfParserTest" --console=plain
```

Expected: `DxfParserTest > 6 tests PASSED`, `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt
git commit -m "feat: add DxfParser skeleton with LINE/CIRCLE/ARC parsing"
```

---

## Task 4: DxfParser — LWPOLYLINE / ELLIPSE / SPLINE

Task 3의 스텁 함수를 실제 구현으로 교체한다.

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`

- [ ] **Step 1: LWPOLYLINE / ELLIPSE / SPLINE 테스트 추가**

`DxfParserTest.kt` 파일 끝(마지막 `}` 바로 앞)에 추가:
```kotlin
    // ---- LWPOLYLINE ----

    @Test
    fun parseLwPolyline_returnsVertices() {
        val dxf = withEntities("""
  0
LWPOLYLINE
  8
0
 90
3
 70
0
 10
0.0
 20
0.0
 10
100.0
 20
0.0
 10
50.0
 20
50.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        val poly = drawing.entities[0] as DxfLwPolyline
        assertEquals(3, poly.vertices.size)
        assertEquals(Vec2(0.0, 0.0), poly.vertices[0])
        assertEquals(Vec2(100.0, 0.0), poly.vertices[1])
        assertEquals(Vec2(50.0, 50.0), poly.vertices[2])
        assertFalse(poly.closed)
    }

    @Test
    fun parseLwPolyline_closedFlag() {
        val dxf = withEntities("""
  0
LWPOLYLINE
 90
2
 70
1
 10
0.0
 20
0.0
 10
10.0
 20
0.0""".trimIndent())

        val poly = DxfParser.parse(dxf).entities[0] as DxfLwPolyline
        assertTrue(poly.closed)
    }

    // ---- ELLIPSE ----

    @Test
    fun parseEllipse_returnsCenterAndRatio() {
        val dxf = withEntities("""
  0
ELLIPSE
  8
0
 10
10.0
 20
20.0
 11
5.0
 21
0.0
 40
0.5
 41
0.0
 42
6.283185307179586""".trimIndent())

        val ellipse = DxfParser.parse(dxf).entities[0] as DxfEllipse
        assertEquals(Vec2(10.0, 20.0), ellipse.center)
        assertEquals(Vec2(5.0, 0.0), ellipse.majorAxis)
        assertEquals(0.5, ellipse.minorRatio, 1e-9)
    }

    // ---- SPLINE ----

    @Test
    fun parseSpline_returnsControlPoints() {
        val dxf = withEntities("""
  0
SPLINE
  8
0
 71
3
 73
3
 10
0.0
 20
0.0
 30
0.0
 10
5.0
 20
10.0
 30
0.0
 10
10.0
 20
0.0
 30
0.0""".trimIndent())

        val spline = DxfParser.parse(dxf).entities[0] as DxfSpline
        assertEquals(3, spline.degree)
        assertEquals(3, spline.controlPoints.size)
        assertEquals(Vec2(0.0, 0.0), spline.controlPoints[0])
        assertEquals(Vec2(5.0, 10.0), spline.controlPoints[1])
    }
```

- [ ] **Step 2: 테스트 실행 → FAIL 확인 (스텁이 빈 데이터 반환)**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfParserTest" --console=plain
```

Expected: `parseLwPolyline_returnsVertices FAILED`, `parseEllipse FAILED`, `parseSpline FAILED`

- [ ] **Step 3: DxfParser.kt에서 스텁 함수 3개를 실제 구현으로 교체**

`DxfParser.kt`의 `parseLwPolyline`을 찾아 아래 코드로 교체:
```kotlin
    private fun parseLwPolyline(reader: DxfReader): DxfLwPolyline {
        var layer = "0"; var closed = false
        val vertices = mutableListOf<Vec2>()
        var currentX = 0.0; var hasX = false
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                70 -> closed = (next.value.toIntOrNull() ?: 0) and 1 != 0
                10 -> { currentX = next.value.toDouble(); hasX = true }
                20 -> if (hasX) { vertices.add(Vec2(currentX, next.value.toDouble())); hasX = false }
            }
        }
        return DxfLwPolyline(layer, vertices, closed)
    }
```

`parseEllipse`를 교체:
```kotlin
    private fun parseEllipse(reader: DxfReader): DxfEllipse {
        var layer = "0"; var cx = 0.0; var cy = 0.0
        var majX = 0.0; var majY = 0.0; var minorRatio = 1.0
        var startParam = 0.0; var endParam = Math.PI * 2
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> cx = next.value.toDouble()
                20 -> cy = next.value.toDouble()
                11 -> majX = next.value.toDouble()
                21 -> majY = next.value.toDouble()
                40 -> minorRatio = next.value.toDouble()
                41 -> startParam = next.value.toDouble()
                42 -> endParam = next.value.toDouble()
            }
        }
        return DxfEllipse(layer, Vec2(cx, cy), Vec2(majX, majY), minorRatio, startParam, endParam)
    }
```

`parseSpline`을 교체:
```kotlin
    private fun parseSpline(reader: DxfReader): DxfSpline {
        var layer = "0"; var degree = 3
        val controlPoints = mutableListOf<Vec2>()
        var cpX = 0.0; var hasCpX = false
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                71 -> degree = next.value.toIntOrNull() ?: 3
                10 -> { cpX = next.value.toDouble(); hasCpX = true }
                20 -> if (hasCpX) { controlPoints.add(Vec2(cpX, next.value.toDouble())); hasCpX = false }
            }
        }
        return DxfSpline(layer, degree, controlPoints)
    }
```

- [ ] **Step 4: 테스트 전체 실행 → PASS 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfParserTest" --console=plain
```

Expected: 모든 테스트 PASSED, `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt
git commit -m "feat: implement LWPOLYLINE, ELLIPSE, SPLINE parsing"
```

---

## Task 5: DxfParser — TEXT / MTEXT / INSERT

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`

- [ ] **Step 1: TEXT / MTEXT / INSERT 테스트 추가** (DxfParserTest.kt 끝 `}` 앞에)

```kotlin
    // ---- TEXT ----

    @Test
    fun parseText_returnsString() {
        val dxf = withEntities("""
  0
TEXT
  8
text-layer
 10
10.0
 20
20.0
 40
2.5
  1
Hello World
 50
45.0""".trimIndent())

        val text = DxfParser.parse(dxf).entities[0] as DxfText
        assertEquals("text-layer", text.layer)
        assertEquals(Vec2(10.0, 20.0), text.insertionPoint)
        assertEquals(2.5, text.height, 1e-9)
        assertEquals("Hello World", text.text)
        assertEquals(45.0, text.rotationDeg, 1e-9)
    }

    // ---- MTEXT ----

    @Test
    fun parseMText_concatenatesChunks() {
        val dxf = withEntities("""
  0
MTEXT
  8
0
 10
0.0
 20
0.0
 40
3.0
  3
First part
  1
Second part""".trimIndent())

        val mtext = DxfParser.parse(dxf).entities[0] as DxfMText
        assertEquals("First partSecond part", mtext.text)
    }

    // ---- INSERT ----

    @Test
    fun parseInsert_returnsBlockNameAndPoint() {
        val dxf = withEntities("""
  0
INSERT
  8
0
  2
DOOR
 10
100.0
 20
200.0
 41
2.0
 42
2.0
 50
90.0""".trimIndent())

        val insert = DxfParser.parse(dxf).entities[0] as DxfInsert
        assertEquals("DOOR", insert.blockName)
        assertEquals(Vec2(100.0, 200.0), insert.insertionPoint)
        assertEquals(2.0, insert.scaleX, 1e-9)
        assertEquals(90.0, insert.rotationDeg, 1e-9)
    }
```

- [ ] **Step 2: 테스트 실행 → FAIL 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfParserTest" --console=plain
```

Expected: 방금 추가한 3개 테스트 FAILED (스텁이 빈 값 반환)

- [ ] **Step 3: DxfParser.kt에서 스텁 3개를 실제 구현으로 교체**

`parseText` 교체:
```kotlin
    private fun parseText(reader: DxfReader): DxfText {
        var layer = "0"; var x = 0.0; var y = 0.0
        var height = 0.0; var text = ""; var rotation = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x = next.value.toDouble()
                20 -> y = next.value.toDouble()
                40 -> height = next.value.toDouble()
                1  -> text = next.value
                50 -> rotation = next.value.toDouble()
            }
        }
        return DxfText(layer, Vec2(x, y), height, text, rotation)
    }
```

`parseMText` 교체:
```kotlin
    private fun parseMText(reader: DxfReader): DxfMText {
        var layer = "0"; var x = 0.0; var y = 0.0
        var height = 0.0; val textParts = mutableListOf<String>(); var rotation = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8     -> layer = next.value
                10    -> x = next.value.toDouble()
                20    -> y = next.value.toDouble()
                40    -> height = next.value.toDouble()
                1, 3  -> textParts.add(next.value)  // 3=이어지는 텍스트, 1=마지막 텍스트
                50    -> rotation = next.value.toDouble()
            }
        }
        return DxfMText(layer, Vec2(x, y), height, textParts.joinToString(""), rotation)
    }
```

`parseInsert` 교체:
```kotlin
    private fun parseInsert(reader: DxfReader): DxfInsert {
        var layer = "0"; var blockName = ""; var x = 0.0; var y = 0.0
        var scaleX = 1.0; var scaleY = 1.0; var rotation = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                2  -> blockName = next.value
                10 -> x = next.value.toDouble()
                20 -> y = next.value.toDouble()
                41 -> scaleX = next.value.toDouble()
                42 -> scaleY = next.value.toDouble()
                50 -> rotation = next.value.toDouble()
            }
        }
        return DxfInsert(layer, blockName, Vec2(x, y), scaleX, scaleY, rotation)
    }
```

- [ ] **Step 4: 전체 테스트 실행 → PASS 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfParserTest" --console=plain
```

Expected: 모든 테스트 PASSED, `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt
git commit -m "feat: implement TEXT, MTEXT, INSERT parsing"
```

---

## Task 6: DxfParser — TABLES(LAYER) + DIMENSION / HATCH / LEADER + BoundingBox 검증

**Files:**
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`

- [ ] **Step 1: LAYER 테이블 + DIMENSION/HATCH/LEADER + BoundingBox 테스트 추가**

`DxfParserTest.kt` 끝 `}` 앞에 추가:
```kotlin
    // ---- LAYER 테이블 ----

    @Test
    fun parseLayers_extractedFromTablesSection() {
        val dxf = """
  0
SECTION
  2
TABLES
  0
TABLE
  2
LAYER
 70
2
  0
LAYER
  2
walls
 62
3
  0
LAYER
  2
pipes
 62
1
  0
ENDTAB
  0
ENDSEC
  0
SECTION
  2
ENTITIES
  0
ENDSEC
  0
EOF""".trimIndent()

        val drawing = DxfParser.parse(dxf)
        assertEquals(2, drawing.layers.size)
        assertEquals("walls", drawing.layers[0].name)
        assertEquals(3, drawing.layers[0].colorIndex)
        assertEquals("pipes", drawing.layers[1].name)
        assertEquals(1, drawing.layers[1].colorIndex)
    }

    // ---- DIMENSION ----

    @Test
    fun parseDimension_returnsPoints() {
        val dxf = withEntities("""
  0
DIMENSION
  8
dim-layer
 10
100.0
 20
200.0
 11
150.0
 21
210.0
 70
1
  1
override""".trimIndent())

        val dim = DxfParser.parse(dxf).entities[0] as DxfDimension
        assertEquals("dim-layer", dim.layer)
        assertEquals(Vec2(100.0, 200.0), dim.definitionPoint)
        assertEquals(Vec2(150.0, 210.0), dim.textMidPoint)
        assertEquals(1, dim.dimType)
        assertEquals("override", dim.textOverride)
    }

    // ---- HATCH ----

    @Test
    fun parseHatch_solidFlag() {
        val dxf = withEntities("""
  0
HATCH
  8
0
 10
0.0
 20
0.0
 70
1""".trimIndent())

        val hatch = DxfParser.parse(dxf).entities[0] as DxfHatch
        assertTrue(hatch.isSolid)
    }

    // ---- LEADER ----

    @Test
    fun parseLeader_returnsVertices() {
        val dxf = withEntities("""
  0
LEADER
  8
0
 10
0.0
 20
0.0
 10
50.0
 20
50.0
 10
100.0
 20
25.0""".trimIndent())

        val leader = DxfParser.parse(dxf).entities[0] as DxfLeader
        assertEquals(3, leader.vertices.size)
        assertEquals(Vec2(0.0, 0.0), leader.vertices[0])
    }

    // ---- BoundingBox ----

    @Test
    fun boundingBox_calculatedFromLineEndpoints() {
        val dxf = withEntities("""
  0
LINE
 10
-10.0
 20
-20.0
 11
30.0
 21
40.0""".trimIndent())

        val box = DxfParser.parse(dxf).extents!!
        assertEquals(-10.0, box.minX, 1e-9)
        assertEquals(-20.0, box.minY, 1e-9)
        assertEquals(30.0, box.maxX, 1e-9)
        assertEquals(40.0, box.maxY, 1e-9)
    }

    @Test
    fun boundingBox_nullWhenNoEntities() {
        // withEntities("") 사용 시 빈 줄이 생기므로 직접 DXF 문자열 사용
        val dxf = "  0\nSECTION\n  2\nENTITIES\n  0\nENDSEC\n  0\nEOF"
        assertNull(DxfParser.parse(dxf).extents)
    }
```

- [ ] **Step 2: 테스트 실행 → FAIL 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.DxfParserTest" --console=plain
```

Expected: 방금 추가한 테스트들 FAILED (DIMENSION/HATCH/LEADER 스텁)

- [ ] **Step 3: DxfParser.kt에서 스텁 3개를 실제 구현으로 교체**

`parseDimension` 교체:
```kotlin
    private fun parseDimension(reader: DxfReader): DxfDimension {
        var layer = "0"; var defX = 0.0; var defY = 0.0
        var midX = 0.0; var midY = 0.0; var dimType = 0; var textOverride = ""
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> defX = next.value.toDouble()
                20 -> defY = next.value.toDouble()
                11 -> midX = next.value.toDouble()
                21 -> midY = next.value.toDouble()
                70 -> dimType = next.value.toIntOrNull() ?: 0
                1  -> textOverride = next.value
            }
        }
        return DxfDimension(layer, Vec2(defX, defY), Vec2(midX, midY), dimType, textOverride)
    }
```

`parseHatch` 교체:
```kotlin
    private fun parseHatch(reader: DxfReader): DxfHatch {
        var layer = "0"; var isSolid = false
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                70 -> isSolid = next.value.trim() == "1"
            }
        }
        return DxfHatch(layer, isSolid)
    }
```

`parseLeader` 교체:
```kotlin
    private fun parseLeader(reader: DxfReader): DxfLeader {
        var layer = "0"
        val vertices = mutableListOf<Vec2>()
        var vx = 0.0; var hasVx = false
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> { vx = next.value.toDouble(); hasVx = true }
                20 -> if (hasVx) { vertices.add(Vec2(vx, next.value.toDouble())); hasVx = false }
            }
        }
        return DxfLeader(layer, vertices)
    }
```

- [ ] **Step 4: 전체 단위 테스트 실행 → PASS 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:testDebugUnitTest --tests "*.parser.*" --console=plain
```

Expected: `DxfReaderTest` + `DxfParserTest` 전체 PASSED, `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt
git commit -m "feat: implement DIMENSION, HATCH, LEADER parsing + LAYER table"
```

---

## Task 7: JNI nativeDwgToDxf 함수

LibreDWG의 `dwg_read_file` + `dwg_write_dxf`를 JNI로 연결해 DWG → DXF 파일 변환을 native에서 수행한다.

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/cpp/dwgjni.c`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt`

배경:
- `dwg_write_dxf(Bit_Chain *dat, Dwg_Data *dwg)` 는 `out_dxf.h` 에 선언됨 (private 헤더)
- `Bit_Chain` 은 `bits.h` 에 정의됨 (private 헤더)
- 두 헤더 모두 `libredwg/src/` 에 있고, `config.h` 가 빌드 디렉토리에 생성됨
- CMakeLists.txt에 두 경로를 include_directories에 추가해야 함

- [ ] **Step 1: CMakeLists.txt에 include 경로 추가**

`app/src/main/cpp/CMakeLists.txt`의 `target_include_directories` 블록을 교체:
```cmake
target_include_directories(dwgjni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/libredwg/include
        ${CMAKE_CURRENT_SOURCE_DIR}/libredwg/src
        ${CMAKE_CURRENT_BINARY_DIR}/libredwg)
```

- [ ] **Step 2: dwgjni.c에 nativeDwgToDxf 함수 추가**

`app/src/main/cpp/dwgjni.c`를 아래로 교체:
```c
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <dwg.h>
#include <dwg_api.h>
#include "bits.h"
#include "out_dxf.h"

JNIEXPORT jstring JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeLibredwgVersion(
        JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, dwg_api_version_string());
}

JNIEXPORT jint JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeDwgToDxf(
        JNIEnv *env, jobject thiz,
        jstring inPathJ, jstring outPathJ) {
    const char *inPath  = (*env)->GetStringUTFChars(env, inPathJ, NULL);
    const char *outPath = (*env)->GetStringUTFChars(env, outPathJ, NULL);

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    int error = dwg_read_file(inPath, &dwg);

    if (error >= DWG_ERR_CRITICAL) {
        (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
        (*env)->ReleaseStringUTFChars(env, outPathJ, outPath);
        return error;
    }

    Bit_Chain dat;
    memset(&dat, 0, sizeof(Bit_Chain));
    dat.version      = dwg.header.version;
    dat.from_version = dwg.header.from_version;
    dat.fh           = fopen(outPath, "wb");

    if (!dat.fh) {
        dwg_free(&dwg);
        (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
        (*env)->ReleaseStringUTFChars(env, outPathJ, outPath);
        return DWG_ERR_IOERROR;
    }

    error = dwg_write_dxf(&dat, &dwg);
    fclose(dat.fh);
    dwg_free(&dwg);

    (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
    (*env)->ReleaseStringUTFChars(env, outPathJ, outPath);
    return (error >= DWG_ERR_CRITICAL) ? error : 0;
}
```

- [ ] **Step 3: NativeDwg.kt에 external fun 추가**

`NativeDwg.kt`의 `external fun nativeLibredwgVersion()` 다음 줄에 추가:
```kotlin
    external fun nativeDwgToDxf(inPath: String, outPath: String): Int
```

최종 파일 모습:
```kotlin
package io.github.june690602_blip.cleancad

object NativeDwg {
    init {
        System.loadLibrary("dwgjni")
    }

    external fun nativeLibredwgVersion(): String
    external fun nativeDwgToDxf(inPath: String, outPath: String): Int
}
```

- [ ] **Step 4: 빌드 확인**

```bash
cd /c/dev/opendwg
./gradlew :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`
빌드 실패 시 흔한 원인:
- `out_dxf.h not found` → CMakeLists의 `libredwg/src` 경로가 정확한지 확인
- `config.h not found` → `${CMAKE_CURRENT_BINARY_DIR}/libredwg` 경로가 CMakeLists에 있는지 확인
- `undefined reference to dwg_write_dxf` → `out_dxf.h`가 포함됐지만 함수가 `redwg` 라이브러리에 있는지 확인 (`target_link_libraries`에 `redwg` 포함 여부)

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/main/cpp/CMakeLists.txt \
        app/src/main/cpp/dwgjni.c \
        app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt
git commit -m "feat: add nativeDwgToDxf JNI function (dwg_read_file + dwg_write_dxf)"
```

---

## Task 8: 엔드투엔드 instrumented 테스트

샘플 DWG 파일을 DXF로 변환하고, 파싱해 엔티티가 추출되는지 에뮬레이터에서 검증한다.

**Files:**
- Create: `app/src/androidTest/assets/circle.dwg` (LibreDWG submodule에서 복사)
- Create: `app/src/androidTest/java/io/github/june690602_blip/cleancad/NativePipelineTest.kt`

- [ ] **Step 1: 샘플 DWG를 androidTest 에셋으로 복사**

```bash
cd /c/dev/opendwg
mkdir -p app/src/androidTest/assets
cp app/src/main/cpp/libredwg/test/test-data/2000/circle.dwg \
   app/src/androidTest/assets/circle.dwg
```

Expected: `app/src/androidTest/assets/circle.dwg` 파일 존재 확인:
```bash
ls -lh app/src/androidTest/assets/circle.dwg
```

- [ ] **Step 2: 실패하는 테스트 작성**

`app/src/androidTest/java/io/github/june690602_blip/cleancad/NativePipelineTest.kt`:
```kotlin
package io.github.june690602_blip.cleancad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleancad.parser.DxfParser
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativePipelineTest {

    @Test
    fun dwgToDxf_producesNonEmptyDxfFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cacheDir = instrumentation.targetContext.cacheDir

        // 1. 에셋에서 DWG 파일을 캐시에 복사
        val dwgFile = File(cacheDir, "test_circle.dwg")
        instrumentation.context.assets.open("circle.dwg").use { input ->
            dwgFile.outputStream().use { input.copyTo(it) }
        }
        assertTrue("DWG file should exist", dwgFile.exists())

        // 2. DXF 변환 수행
        val dxfFile = File(cacheDir, "test_circle.dxf")
        val result = NativeDwg.nativeDwgToDxf(dwgFile.absolutePath, dxfFile.absolutePath)

        // 3. 변환 결과 검증
        assertTrue("nativeDwgToDxf should return 0 (no critical error), got $result", result == 0)
        assertTrue("DXF file should exist after conversion", dxfFile.exists())
        assertTrue("DXF file should not be empty", dxfFile.length() > 0)

        // 4. DXF를 파싱해 Drawing 모델 검증
        val dxfContent = dxfFile.readText()
        val drawing = DxfParser.parse(dxfContent)
        assertTrue("Drawing should have at least 1 entity", drawing.entities.isNotEmpty())
        assertNotNull("BoundingBox should not be null", drawing.extents)
    }

    @Test
    fun dwgToDxf_invalidPath_returnsError() {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val result = NativeDwg.nativeDwgToDxf(
            "/nonexistent/path/fake.dwg",
            File(cacheDir, "out.dxf").absolutePath
        )
        assertTrue("Invalid path should return non-zero error code", result != 0)
    }
}
```

- [ ] **Step 3: 에뮬레이터 실행 + 테스트 실행**

x86_64 에뮬레이터 (`Medium_Phone_API_36.1`)가 실행 중인 상태에서:
```bash
cd /c/dev/opendwg
./gradlew :app:connectedDebugAndroidTest \
  --tests "*.NativePipelineTest" --console=plain
```

Expected:
```
NativePipelineTest > dwgToDxf_producesNonEmptyDxfFile PASSED
NativePipelineTest > dwgToDxf_invalidPath_returnsError PASSED
BUILD SUCCESSFUL
```

실패 시 확인사항:
- `result != 0` (변환 실패): `adb logcat -d | grep -i "cleancad\|libredwg"` 로 C 레이어 로그 확인
- `entities.isEmpty()`: DXF 파일을 `adb shell cat <dxfPath>` 로 확인해 ENTITIES 섹션이 있는지 점검

- [ ] **Step 4: Red-Green 확인 — 변환 실패 시 테스트가 진짜 FAIL하는지**

`NativePipelineTest.kt`에서 assertion을 일시적으로 반전:
```kotlin
assertTrue("...", drawing.entities.isEmpty())  // 임시로 isEmpty()로 바꿈
```

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*.NativePipelineTest" --console=plain`
Expected: `FAILED` (테스트가 실제로 엔티티를 검사함을 증명)

원래 `isNotEmpty()`로 되돌린 뒤 다시 실행 → Expected: `PASSED`

- [ ] **Step 5: 커밋**

```bash
cd /c/dev/opendwg
git add app/src/androidTest/assets/circle.dwg \
        app/src/androidTest/java/io/github/june690602_blip/cleancad/NativePipelineTest.kt
git commit -m "test: end-to-end native pipeline test (DWG → DXF → Drawing)"
```

---

## Phase 3 완료 기준 (Definition of Done)

- [ ] `./gradlew :app:testDebugUnitTest` — `DxfReaderTest` + `DxfParserTest` 전체 PASSED
- [ ] `./gradlew :app:connectedDebugAndroidTest` — `NativePipelineTest` 전체 PASSED
- [ ] `NativeDwg.nativeDwgToDxf(dwgPath, dxfPath)` 가 0을 반환하고 DXF 파일 생성
- [ ] `DxfParser.parse(dxfText)` 가 최소 1개 이상의 엔티티를 가진 `Drawing` 반환
- [ ] 모든 변경이 커밋됨 (브랜치: `phase3-parser`)

다음 단계(별도 계획): **Phase 4** — `DrawingView` 렌더러(Tier 2) + 뷰어 화면 + SAF 파일 피커 + 최근 파일
