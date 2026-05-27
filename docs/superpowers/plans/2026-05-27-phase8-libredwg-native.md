# Phase 8 Implementation Plan — LibreDWG 바이너리 API 직접 사용 (DXF 중간단계 제거)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DWG → DXF 텍스트 → DxfParser 경로를 제거하고, LibreDWG의 `Dwg_Data` 구조체를 JNI에서 직접 읽어 바이너리 버퍼로 Kotlin에 전달한다. 인코딩 손실·색상 누락·엔티티 누락을 원천 차단한다.

**Architecture:**
1. **C 측 (native):** `dwgjni.c`가 `dwg_read_file()` 호출 후 `dwg.object[]` 배열을 순회하면서 자체 정의한 **간단한 TLV 바이너리 프로토콜**로 직렬화한다. LibreDWG가 codepage를 이미 처리한 `char*`를 `bit_TV_to_utf8()`로 UTF-8 변환해 그대로 버퍼에 쓴다. 단일 JNI 호출로 전체 도면을 전달 (call overhead 최소화).
2. **Kotlin 측:** `NativeDwgReader`가 `ByteArray`를 받아 `ByteBuffer.LITTLE_ENDIAN`으로 디코딩 → 기존 `Drawing` 객체로 매핑. `EntityRenderer`/`DrawingView`는 모델 레이어 위에서 동작하므로 그대로 재사용.
3. **마이그레이션:** 신규 경로(`NativeDwg.parseToDrawing()`)를 추가하고 `DrawingViewModel.load()`만 갈아끼운다. Task 10까지는 구 DXF 파이프라인(`DxfParser`, `DxfReader`, `DxfCharsetDetector`)을 그대로 두어 회귀 시 즉시 복귀할 수 있게 한다. Task 10에서 검증 후 일괄 삭제.

**Tech Stack:**
- C99 (LibreDWG 0.13.4 API: `dwg_read_file`, `dwg.object[].fixedtype`, `dwg.object[].tio.entity->tio.<TYPE>`)
- JNI direct ByteBuffer / byte array transfer
- Kotlin `ByteBuffer.wrap(...).order(LITTLE_ENDIAN)` for decoding
- JUnit 단위 테스트 (네이티브 출력은 fixture 바이너리 + Kotlin 측 디코더에 대해 검증)

**시작 기준 커밋:** `eb39838` (Phase 7 완료, 단위 테스트 59개 통과)

**예상 결과:**
- 한글 텍스트 정상 표시 (LibreDWG가 CP949 → UTF-8 변환을 수행하므로 100% 보존)
- 엔티티별/레이어별 색상 정확 (per-entity `color.index`, RGB 24-bit override 모두 지원)
- 모든 LibreDWG 지원 엔티티 타입 표시 (POLYLINE_3D, ELLIPSE, SPLINE, HATCH arc edges 등)
- DXF 텍스트 파일 임시 생성 단계 제거 → I/O 절반 절감
- 단위 테스트 59 → 90개 이상 (+30개)

---

## 시작 전 체크리스트

```bash
cd C:\dev\opendwg
git status                                      # 작업 트리 깨끗한지
git log --oneline -3                            # eb39838이 HEAD
./gradlew :app:testDebugUnitTest 2>&1 | tail -5 # BUILD SUCCESSFUL, 59 tests
./gradlew :app:assembleDebug 2>&1 | tail -5     # BUILD SUCCESSFUL
```

문제 있으면 STOP.

---

## 바이너리 프로토콜 명세 (Task 전반에 걸쳐 참조)

**Endianness:** LITTLE_ENDIAN (Android는 ARM little, x86 little — 모두 일치).

**Header (24 bytes 고정):**
```
u32  magic            = 0x42475744 ('DWGB' little endian)
u16  protocol_version = 1
u16  reserved         = 0
i32  num_layers
i32  num_entities
i32  extents_present  (0 = no extents, 1 = followed by 4 f64)
[if extents_present == 1]
  f64  extents_min_x, extents_min_y, extents_max_x, extents_max_y
```

**Layer record (가변):**
```
u16  name_len_bytes    (UTF-8 바이트 수)
u8[] name              (UTF-8)
i16  color_index       (1..255 ACI, 256=BYLAYER 의미 없음 — 레이어 자체이므로 무시)
u32  rgb               (0 = 미사용; 0x00RRGGBB)
u8   flags             (bit0: frozen, bit1: off, bit2: locked)
```

**Entity record (가변):**
```
u8   type_id           (아래 표; 0xFF = UNKNOWN)
i32  layer_idx         (Layer 테이블 인덱스; -1 = 미설정 → "0" 레이어)
i16  color_index       (-1 = BYLAYER, 0 = BYBLOCK, 1..255 = ACI)
u32  rgb               (0 = 미사용; 0x00RRGGBB)
... type-specific payload ...
```

**Type IDs (고정):**
| ID | 엔티티 | 페이로드 (Header 다음) |
|----|--------|----------------------|
| 1  | LINE        | f64 sx, sy, ex, ey |
| 2  | CIRCLE      | f64 cx, cy, r |
| 3  | ARC         | f64 cx, cy, r, startDeg, endDeg |
| 4  | LWPOLYLINE  | u8 closed, i32 n, f64[2n] xy |
| 5  | POLYLINE_2D | u8 closed, i32 n, f64[2n] xy |
| 6  | POLYLINE_3D | u8 closed, i32 n, f64[2n] xy (z 폐기) |
| 7  | TEXT        | f64 ix, iy, height, rotDeg; u16 text_len; u8[] text(UTF-8) |
| 8  | MTEXT       | 동일 |
| 9  | (예약)      | (INSERT는 C 측에서 전개되므로 스트림에 안 옴) |
| 10 | HATCH       | u8 isSolid; i32 num_paths; [각 path: i32 n; f64[2n] xy] |
| 11 | DIMENSION   | f64 defX, defY, textX, textY; i32 dimType; u16 text_len; u8[] textOverride |
| 12 | LEADER      | i32 n; f64[2n] xy |
| 13 | ELLIPSE     | f64 cx, cy, mx, my, minorRatio, startParam, endParam |
| 14 | SPLINE      | i32 degree; i32 n_ctrl; f64[2n_ctrl] xy (z 폐기) |
| 15 | 3DFACE      | f64 c1x,c1y, c2x,c2y, c3x,c3y, c4x,c4y |
| 16 | SOLID       | 동일 |
| 0xFF | UNKNOWN  | u16 type_name_len; u8[] type_name |

**버전 정책:** 프로토콜 변경 시 `protocol_version`을 올리고 Kotlin 디코더에서 거부.

---

## Task 1: 바이너리 프로토콜 디코더 (Kotlin) + 빈 도면 처리

**근거:** C 측을 건드리기 전에 Kotlin 디코더부터 TDD로 만든다. fixture 바이너리를 직접 만들어 디코딩 검증.

### Files
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeProtocol.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt`
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt`

### Steps

- [ ] **1-1. `NativeProtocol.kt` — 상수 정의**

```kotlin
package io.github.june690602_blip.cleancad.native

object NativeProtocol {
    const val MAGIC: Int = 0x42475744  // 'DWGB' little-endian
    const val VERSION: Int = 1

    const val TYPE_LINE: Int = 1
    const val TYPE_CIRCLE: Int = 2
    const val TYPE_ARC: Int = 3
    const val TYPE_LWPOLYLINE: Int = 4
    const val TYPE_POLYLINE_2D: Int = 5
    const val TYPE_POLYLINE_3D: Int = 6
    const val TYPE_TEXT: Int = 7
    const val TYPE_MTEXT: Int = 8
    const val TYPE_HATCH: Int = 10
    const val TYPE_DIMENSION: Int = 11
    const val TYPE_LEADER: Int = 12
    const val TYPE_ELLIPSE: Int = 13
    const val TYPE_SPLINE: Int = 14
    const val TYPE_3DFACE: Int = 15
    const val TYPE_SOLID: Int = 16
    const val TYPE_UNKNOWN: Int = 0xFF

    const val COLOR_BYLAYER: Short = -1
    const val COLOR_BYBLOCK: Short = 0
}
```

- [ ] **1-2. 테스트 작성 (RED) — 빈 도면 파싱**

`NativeDecoderTest.kt`:
```kotlin
package io.github.june690602_blip.cleancad.native

import io.github.june690602_blip.cleancad.model.Drawing
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeDecoderTest {

    private fun emptyBuffer(): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(NativeProtocol.MAGIC)
        buf.putShort(NativeProtocol.VERSION.toShort())
        buf.putShort(0)             // reserved
        buf.putInt(0)               // num_layers
        buf.putInt(0)               // num_entities
        buf.putInt(0)               // extents_present = 0
        return buf.array()
    }

    @Test
    fun decode_emptyBuffer_returnsEmptyDrawing() {
        val drawing: Drawing = NativeDecoder.decode(emptyBuffer())
        assertEquals(0, drawing.entities.size)
        assertEquals(0, drawing.layers.size)
        assertNull(drawing.extents)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_wrongMagic_throws() {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x12345678)  // 잘못된 magic
        buf.putShort(1); buf.putShort(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        NativeDecoder.decode(buf.array())
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_wrongVersion_throws() {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(NativeProtocol.MAGIC)
        buf.putShort(99); buf.putShort(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        NativeDecoder.decode(buf.array())
    }
}
```

- [ ] **1-3. 테스트 실행 → RED**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 컴파일 에러 (NativeDecoder 없음).

- [ ] **1-4. `NativeDecoder.kt` — 빈 도면 + 헤더 검증만**

```kotlin
package io.github.june690602_blip.cleancad.native

import io.github.june690602_blip.cleancad.model.BoundingBox
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.model.DxfEntity
import io.github.june690602_blip.cleancad.model.Layer
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeDecoder {

    fun decode(bytes: ByteArray): Drawing {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        require(magic == NativeProtocol.MAGIC) {
            "잘못된 magic: 0x${magic.toUInt().toString(16)}"
        }
        val version = buf.short.toInt()
        require(version == NativeProtocol.VERSION) {
            "지원하지 않는 프로토콜 버전: $version (필요: ${NativeProtocol.VERSION})"
        }
        buf.short  // reserved
        val numLayers = buf.int
        val numEntities = buf.int
        val extentsPresent = buf.int

        val extents = if (extentsPresent == 1) {
            BoundingBox(buf.double, buf.double, buf.double, buf.double)
        } else null

        val layers = decodeLayers(buf, numLayers)
        val entities = decodeEntities(buf, numEntities, layers)

        return Drawing(entities, layers, extents, extents)
    }

    private fun decodeLayers(buf: ByteBuffer, count: Int): List<Layer> =
        List(count) {
            val nameLen = buf.short.toInt() and 0xFFFF
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)
            val colorIndex = buf.short.toInt()
            buf.int   // rgb (Task 9에서 사용)
            buf.get() // flags (Task 9에서 사용)
            Layer(name, colorIndex)
        }

    private fun decodeEntities(
        buf: ByteBuffer, count: Int, layers: List<Layer>
    ): List<DxfEntity> = emptyList()  // Task 2부터 채움
}
```

- [ ] **1-5. 테스트 실행 → GREEN**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 62 tests (59 + 3 new).

- [ ] **1-6. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/native/ \
        app/src/test/java/io/github/june690602_blip/cleancad/native/

git commit -m "$(cat <<'EOF'
feat(phase8): 바이너리 프로토콜 디코더 골격 — 빈 도면/헤더 검증

- NativeProtocol — magic/version/type ID 상수
- NativeDecoder — magic/version 검증, 빈 도면 디코딩
- 테스트 3개 (empty / wrong magic / wrong version)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 레이어 테이블 디코딩 + LINE 엔티티

**근거:** 가장 단순한 엔티티 LINE을 끝에서 끝까지 검증한다. 레이어 인덱싱 패턴을 여기서 확정.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt`

### Steps

- [ ] **2-1. 테스트 추가 (RED) — 레이어 + LINE**

`NativeDecoderTest.kt` 끝에 추가:
```kotlin
    private fun buildBuffer(
        layers: List<Triple<String, Short, Int>> = emptyList(),  // name, colorIdx, rgb
        entities: List<ByteArray> = emptyList()
    ): ByteArray {
        // 사이즈 추정 후 ByteBuffer 할당 — 충분히 크게 잡음
        val buf = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(NativeProtocol.MAGIC)
        buf.putShort(NativeProtocol.VERSION.toShort())
        buf.putShort(0)
        buf.putInt(layers.size)
        buf.putInt(entities.size)
        buf.putInt(0)  // no extents
        layers.forEach { (name, colorIdx, rgb) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            buf.putShort(nameBytes.size.toShort())
            buf.put(nameBytes)
            buf.putShort(colorIdx)
            buf.putInt(rgb)
            buf.put(0)  // flags
        }
        entities.forEach { buf.put(it) }
        val out = ByteArray(buf.position())
        System.arraycopy(buf.array(), 0, out, 0, out.size)
        return out
    }

    private fun lineEntity(
        layerIdx: Int, colorIdx: Short, rgb: Int,
        sx: Double, sy: Double, ex: Double, ey: Double
    ): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 + 2 + 4 + 8 * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_LINE.toByte())
        b.putInt(layerIdx)
        b.putShort(colorIdx)
        b.putInt(rgb)
        b.putDouble(sx); b.putDouble(sy)
        b.putDouble(ex); b.putDouble(ey)
        return b.array()
    }

    @Test
    fun decode_singleLine_returnsDxfLineOnCorrectLayer() {
        val bytes = buildBuffer(
            layers = listOf(Triple("walls", 7.toShort(), 0)),
            entities = listOf(lineEntity(0, NativeProtocol.COLOR_BYLAYER, 0, 0.0, 0.0, 10.0, 5.0))
        )
        val drawing = NativeDecoder.decode(bytes)
        assertEquals(1, drawing.layers.size)
        assertEquals("walls", drawing.layers[0].name)
        assertEquals(7, drawing.layers[0].colorIndex)
        assertEquals(1, drawing.entities.size)
        val line = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfLine
        assertEquals("walls", line.layer)
        assertEquals(0.0, line.start.x, 1e-9)
        assertEquals(5.0, line.end.y, 1e-9)
    }

    @Test
    fun decode_lineWithLayerIdxMinus1_usesLayer0() {
        val bytes = buildBuffer(
            entities = listOf(lineEntity(-1, NativeProtocol.COLOR_BYLAYER, 0, 0.0, 0.0, 1.0, 1.0))
        )
        val drawing = NativeDecoder.decode(bytes)
        val line = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfLine
        assertEquals("0", line.layer)
    }
```

- [ ] **2-2. 테스트 실행 → RED**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 컴파일 통과(테스트 코드만), 런타임 실패 (entities 빈 채로 반환됨).

- [ ] **2-3. `NativeDecoder.decodeEntities` 구현 (LINE만)**

`NativeDecoder.kt`의 `decodeEntities`를 다음으로 교체:

```kotlin
    private fun decodeEntities(
        buf: ByteBuffer, count: Int, layers: List<Layer>
    ): List<DxfEntity> {
        val result = ArrayList<DxfEntity>(count)
        repeat(count) {
            val typeId = buf.get().toInt() and 0xFF
            val layerIdx = buf.int
            val colorIdx = buf.short
            val rgb = buf.int
            val layerName = layerNameAt(layers, layerIdx)
            val entity: DxfEntity? = when (typeId) {
                NativeProtocol.TYPE_LINE -> decodeLine(buf, layerName)
                else -> {
                    skipUnknownPayload(buf, typeId)
                    null
                }
            }
            if (entity != null) result.add(entity)
        }
        return result
    }

    private fun layerNameAt(layers: List<Layer>, idx: Int): String =
        if (idx in layers.indices) layers[idx].name else "0"

    private fun decodeLine(buf: ByteBuffer, layer: String): DxfLine {
        val sx = buf.double; val sy = buf.double
        val ex = buf.double; val ey = buf.double
        return DxfLine(layer, Vec2(sx, sy), Vec2(ex, ey))
    }

    /** UNKNOWN 또는 아직 미지원 타입의 페이로드를 건너뛴다. Task 별로 케이스 추가. */
    private fun skipUnknownPayload(buf: ByteBuffer, typeId: Int) {
        // Phase 8 진행하면서 점진적으로 더 정확하게 처리. 지금은 UNKNOWN만 대응.
        if (typeId == NativeProtocol.TYPE_UNKNOWN) {
            val len = buf.short.toInt() and 0xFFFF
            buf.position(buf.position() + len)
        }
        // 그 외 타입은 후속 Task에서 처리. 빌드는 통과해도 디코딩은 불완전할 수 있음.
    }
```

`import` 추가 (파일 상단):
```kotlin
import io.github.june690602_blip.cleancad.model.DxfLine
import io.github.june690602_blip.cleancad.model.Vec2
```

- [ ] **2-4. 테스트 실행 → GREEN**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 64 tests (62 + 2 new).

- [ ] **2-5. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt

git commit -m "$(cat <<'EOF'
feat(phase8): 레이어 디코딩 + LINE 엔티티 디코딩

- decodeEntities — typeId 기반 dispatch (LINE 우선)
- layerNameAt — layer_idx → 레이어명, 미설정 시 "0"
- 테스트 2개 (single line on layer / layer_idx=-1)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: CIRCLE, ARC, LWPOLYLINE, POLYLINE_2D 디코더

**근거:** 가장 흔한 도면 엔티티들을 한꺼번에. 이들은 모두 Kotlin 측에서 디코딩 로직만 추가.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt`

### Steps

- [ ] **3-1. 테스트 작성 (RED) — 4 케이스**

`NativeDecoderTest.kt` 끝에 추가:
```kotlin
    private fun circleEntity(layerIdx: Int, cx: Double, cy: Double, r: Double): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 + 2 + 4 + 8 * 3).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_CIRCLE.toByte())
        b.putInt(layerIdx); b.putShort(-1); b.putInt(0)
        b.putDouble(cx); b.putDouble(cy); b.putDouble(r)
        return b.array()
    }

    private fun arcEntity(
        layerIdx: Int, cx: Double, cy: Double, r: Double, start: Double, end: Double
    ): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 + 2 + 4 + 8 * 5).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_ARC.toByte())
        b.putInt(layerIdx); b.putShort(-1); b.putInt(0)
        b.putDouble(cx); b.putDouble(cy); b.putDouble(r)
        b.putDouble(start); b.putDouble(end)
        return b.array()
    }

    private fun lwPolylineEntity(
        layerIdx: Int, closed: Boolean, verts: List<Pair<Double, Double>>
    ): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 + 2 + 4 + 1 + 4 + 16 * verts.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_LWPOLYLINE.toByte())
        b.putInt(layerIdx); b.putShort(-1); b.putInt(0)
        b.put(if (closed) 1 else 0)
        b.putInt(verts.size)
        verts.forEach { (x, y) -> b.putDouble(x); b.putDouble(y) }
        return b.array()
    }

    @Test
    fun decode_circle() {
        val drawing = NativeDecoder.decode(buildBuffer(
            entities = listOf(circleEntity(-1, 5.0, 5.0, 2.5))
        ))
        val c = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfCircle
        assertEquals(5.0, c.center.x, 1e-9)
        assertEquals(2.5, c.radius, 1e-9)
    }

    @Test
    fun decode_arc() {
        val drawing = NativeDecoder.decode(buildBuffer(
            entities = listOf(arcEntity(-1, 0.0, 0.0, 1.0, 30.0, 120.0))
        ))
        val a = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfArc
        assertEquals(30.0, a.startAngleDeg, 1e-9)
        assertEquals(120.0, a.endAngleDeg, 1e-9)
    }

    @Test
    fun decode_lwPolyline_closed() {
        val drawing = NativeDecoder.decode(buildBuffer(
            entities = listOf(lwPolylineEntity(
                -1, closed = true,
                verts = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0)
            ))
        ))
        val p = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfLwPolyline
        assertTrue(p.closed)
        assertEquals(3, p.vertices.size)
    }

    @Test
    fun decode_polyline2D_mappedToDxfPolyline() {
        // POLYLINE_2D는 DxfPolyline으로 매핑 (Phase 7과 호환)
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_POLYLINE_2D.toByte())
        b.putInt(-1); b.putShort(-1); b.putInt(0)
        b.put(0); b.putInt(2)
        b.putDouble(0.0); b.putDouble(0.0)
        b.putDouble(5.0); b.putDouble(0.0)
        val entityBytes = ByteArray(b.position()); System.arraycopy(b.array(), 0, entityBytes, 0, entityBytes.size)

        val drawing = NativeDecoder.decode(buildBuffer(entities = listOf(entityBytes)))
        val p = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfPolyline
        assertFalse(p.closed)
        assertEquals(2, p.vertices.size)
    }
```

- [ ] **3-2. RED 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

- [ ] **3-3. 디코더에 분기 추가**

`NativeDecoder.kt`의 `decodeEntities` 내부 `when (typeId)`에 케이스 추가:
```kotlin
                NativeProtocol.TYPE_CIRCLE      -> decodeCircle(buf, layerName)
                NativeProtocol.TYPE_ARC         -> decodeArc(buf, layerName)
                NativeProtocol.TYPE_LWPOLYLINE  -> decodeLwPolyline(buf, layerName)
                NativeProtocol.TYPE_POLYLINE_2D -> decodePolyline(buf, layerName)
                NativeProtocol.TYPE_POLYLINE_3D -> decodePolyline(buf, layerName)
```

`decodeLine` 아래에 추가:
```kotlin
    private fun decodeCircle(buf: ByteBuffer, layer: String): DxfCircle {
        val cx = buf.double; val cy = buf.double; val r = buf.double
        return DxfCircle(layer, Vec2(cx, cy), r)
    }

    private fun decodeArc(buf: ByteBuffer, layer: String): DxfArc {
        val cx = buf.double; val cy = buf.double; val r = buf.double
        val start = buf.double; val end = buf.double
        return DxfArc(layer, Vec2(cx, cy), r, start, end)
    }

    private fun decodeLwPolyline(buf: ByteBuffer, layer: String): DxfLwPolyline {
        val closed = buf.get() != 0.toByte()
        val n = buf.int
        val verts = List(n) { Vec2(buf.double, buf.double) }
        return DxfLwPolyline(layer, verts, closed)
    }

    private fun decodePolyline(buf: ByteBuffer, layer: String): DxfPolyline {
        val closed = buf.get() != 0.toByte()
        val n = buf.int
        val verts = List(n) { Vec2(buf.double, buf.double) }
        return DxfPolyline(layer, verts, closed)
    }
```

`import`:
```kotlin
import io.github.june690602_blip.cleancad.model.DxfArc
import io.github.june690602_blip.cleancad.model.DxfCircle
import io.github.june690602_blip.cleancad.model.DxfLwPolyline
import io.github.june690602_blip.cleancad.model.DxfPolyline
```

- [ ] **3-4. GREEN 확인**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 68 tests (64 + 4).

- [ ] **3-5. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt

git commit -m "$(cat <<'EOF'
feat(phase8): CIRCLE/ARC/LWPOLYLINE/POLYLINE_2D 디코더

- decodeCircle/Arc/LwPolyline/Polyline 추가
- POLYLINE_3D는 z 폐기 후 DxfPolyline으로 매핑
- 테스트 4개

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: TEXT / MTEXT — 한글 검증 포인트

**근거:** 인코딩이 보존되는지 확인하는 핵심 Task. C 측이 아직 안 만들어졌어도 디코더 단위 테스트로는 검증 가능 (UTF-8 바이트를 직접 fixture에 박음).

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt`

### Steps

- [ ] **4-1. 테스트 (RED) — 한글 TEXT/MTEXT**

`NativeDecoderTest.kt`에 추가:
```kotlin
    private fun textEntity(
        typeId: Int, layerIdx: Int,
        ix: Double, iy: Double, height: Double, rotDeg: Double, text: String
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(1 + 4 + 2 + 4 + 8 * 4 + 2 + textBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.put(typeId.toByte())
        b.putInt(layerIdx); b.putShort(-1); b.putInt(0)
        b.putDouble(ix); b.putDouble(iy); b.putDouble(height); b.putDouble(rotDeg)
        b.putShort(textBytes.size.toShort()); b.put(textBytes)
        return b.array()
    }

    @Test
    fun decode_text_koreanPreserved() {
        val bytes = buildBuffer(
            entities = listOf(textEntity(
                NativeProtocol.TYPE_TEXT, -1, 0.0, 0.0, 2.5, 0.0, "철근콘크리트 D13"
            ))
        )
        val t = NativeDecoder.decode(bytes).entities[0]
            as io.github.june690602_blip.cleancad.model.DxfText
        assertEquals("철근콘크리트 D13", t.text)
        assertEquals(2.5, t.height, 1e-9)
    }

    @Test
    fun decode_mtext_withFormattingCodes() {
        val bytes = buildBuffer(
            entities = listOf(textEntity(
                NativeProtocol.TYPE_MTEXT, -1, 0.0, 0.0, 3.0, 45.0,
                """\fArial|b0|i0;벽체\P두께 200"""
            ))
        )
        val m = NativeDecoder.decode(bytes).entities[0]
            as io.github.june690602_blip.cleancad.model.DxfMText
        // 포맷 코드는 EntityRenderer.drawMText가 strip; 디코더는 원문 그대로 보존
        assertTrue(m.text.contains("벽체"))
        assertTrue(m.text.contains("두께 200"))
        assertEquals(45.0, m.rotationDeg, 1e-9)
    }
```

- [ ] **4-2. RED 확인**

- [ ] **4-3. 디코더 분기 추가**

`when (typeId)`에:
```kotlin
                NativeProtocol.TYPE_TEXT  -> decodeText(buf, layerName)
                NativeProtocol.TYPE_MTEXT -> decodeMText(buf, layerName)
```

함수 추가:
```kotlin
    private fun decodeText(buf: ByteBuffer, layer: String): DxfText {
        val ix = buf.double; val iy = buf.double
        val height = buf.double; val rot = buf.double
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len); buf.get(bytes)
        return DxfText(layer, Vec2(ix, iy), height, String(bytes, Charsets.UTF_8), rot)
    }

    private fun decodeMText(buf: ByteBuffer, layer: String): DxfMText {
        val ix = buf.double; val iy = buf.double
        val height = buf.double; val rot = buf.double
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len); buf.get(bytes)
        return DxfMText(layer, Vec2(ix, iy), height, String(bytes, Charsets.UTF_8), rot)
    }
```

`import` 추가:
```kotlin
import io.github.june690602_blip.cleancad.model.DxfMText
import io.github.june690602_blip.cleancad.model.DxfText
```

- [ ] **4-4. GREEN 확인**

Expected: 70 tests (68 + 2).

- [ ] **4-5. 커밋**

```bash
git commit -am "feat(phase8): TEXT/MTEXT 디코더 — UTF-8 그대로 보존"
```
(Co-Authored-By 라인 포함)

---

## Task 5: 3DFACE, SOLID, ELLIPSE, SPLINE 디코더

(Task 3과 동일한 패턴. 페이로드만 다름.)

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt`

### Steps

- [ ] **5-1. 테스트 (RED) — 4개**

`NativeDecoderTest.kt`에 추가:
```kotlin
    private fun fourCornerEntity(
        typeId: Int, layerIdx: Int, corners: List<Pair<Double, Double>>
    ): ByteArray {
        require(corners.size == 4)
        val b = ByteBuffer.allocate(1 + 4 + 2 + 4 + 8 * 8).order(ByteOrder.LITTLE_ENDIAN)
        b.put(typeId.toByte())
        b.putInt(layerIdx); b.putShort(-1); b.putInt(0)
        corners.forEach { (x, y) -> b.putDouble(x); b.putDouble(y) }
        return b.array()
    }

    @Test
    fun decode_3dface() {
        val drawing = NativeDecoder.decode(buildBuffer(
            entities = listOf(fourCornerEntity(
                NativeProtocol.TYPE_3DFACE, -1,
                listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)
            ))
        ))
        val f = drawing.entities[0] as io.github.june690602_blip.cleancad.model.Dxf3DFace
        assertEquals(10.0, f.corner3.x, 1e-9)
    }

    @Test
    fun decode_solid_legacyVertexOrder() {
        // C 측이 1-2-3-4 순서로 직렬화한다고 가정 (legacy 1-2-4-3은 EntityRenderer.drawSolid가 처리)
        val drawing = NativeDecoder.decode(buildBuffer(
            entities = listOf(fourCornerEntity(
                NativeProtocol.TYPE_SOLID, -1,
                listOf(0.0 to 0.0, 10.0 to 0.0, 0.0 to 10.0, 10.0 to 10.0)
            ))
        ))
        val s = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfSolid
        assertEquals(0.0, s.corner3.x, 1e-9)
        assertEquals(10.0, s.corner4.x, 1e-9)
    }

    @Test
    fun decode_ellipse() {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_ELLIPSE.toByte())
        b.putInt(-1); b.putShort(-1); b.putInt(0)
        b.putDouble(0.0); b.putDouble(0.0)  // center
        b.putDouble(5.0); b.putDouble(0.0)  // majorAxis
        b.putDouble(0.5)                    // minorRatio
        b.putDouble(0.0); b.putDouble(Math.PI)  // params
        val entity = ByteArray(b.position()); System.arraycopy(b.array(), 0, entity, 0, entity.size)

        val drawing = NativeDecoder.decode(buildBuffer(entities = listOf(entity)))
        val e = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfEllipse
        assertEquals(0.5, e.minorRatio, 1e-9)
        assertEquals(5.0, e.majorAxis.x, 1e-9)
    }

    @Test
    fun decode_spline() {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_SPLINE.toByte())
        b.putInt(-1); b.putShort(-1); b.putInt(0)
        b.putInt(3)         // degree
        b.putInt(2)         // n_ctrl
        b.putDouble(0.0); b.putDouble(0.0)
        b.putDouble(1.0); b.putDouble(1.0)
        val entity = ByteArray(b.position()); System.arraycopy(b.array(), 0, entity, 0, entity.size)

        val drawing = NativeDecoder.decode(buildBuffer(entities = listOf(entity)))
        val s = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfSpline
        assertEquals(3, s.degree)
        assertEquals(2, s.controlPoints.size)
    }
```

- [ ] **5-2. RED 확인**

- [ ] **5-3. 디코더 분기 추가**

`when (typeId)`:
```kotlin
                NativeProtocol.TYPE_3DFACE  -> decode3dFace(buf, layerName)
                NativeProtocol.TYPE_SOLID   -> decodeSolid(buf, layerName)
                NativeProtocol.TYPE_ELLIPSE -> decodeEllipse(buf, layerName)
                NativeProtocol.TYPE_SPLINE  -> decodeSpline(buf, layerName)
```

함수:
```kotlin
    private fun decode3dFace(buf: ByteBuffer, layer: String): Dxf3DFace {
        val c1 = Vec2(buf.double, buf.double); val c2 = Vec2(buf.double, buf.double)
        val c3 = Vec2(buf.double, buf.double); val c4 = Vec2(buf.double, buf.double)
        return Dxf3DFace(layer, c1, c2, c3, c4)
    }

    private fun decodeSolid(buf: ByteBuffer, layer: String): DxfSolid {
        val c1 = Vec2(buf.double, buf.double); val c2 = Vec2(buf.double, buf.double)
        val c3 = Vec2(buf.double, buf.double); val c4 = Vec2(buf.double, buf.double)
        return DxfSolid(layer, c1, c2, c3, c4)
    }

    private fun decodeEllipse(buf: ByteBuffer, layer: String): DxfEllipse {
        val center = Vec2(buf.double, buf.double)
        val major = Vec2(buf.double, buf.double)
        val ratio = buf.double; val start = buf.double; val end = buf.double
        return DxfEllipse(layer, center, major, ratio, start, end)
    }

    private fun decodeSpline(buf: ByteBuffer, layer: String): DxfSpline {
        val degree = buf.int; val n = buf.int
        val ctrl = List(n) { Vec2(buf.double, buf.double) }
        return DxfSpline(layer, degree, ctrl)
    }
```

`import`:
```kotlin
import io.github.june690602_blip.cleancad.model.Dxf3DFace
import io.github.june690602_blip.cleancad.model.DxfEllipse
import io.github.june690602_blip.cleancad.model.DxfSolid
import io.github.june690602_blip.cleancad.model.DxfSpline
```

- [ ] **5-4. GREEN 확인**

Expected: 74 tests (70 + 4).

- [ ] **5-5. 커밋**

`git commit -am "feat(phase8): 3DFACE/SOLID/ELLIPSE/SPLINE 디코더"`

---

## Task 6: HATCH, DIMENSION, LEADER 디코더

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt`

### Steps

- [ ] **6-1. 테스트 (RED) — 3개**

`NativeDecoderTest.kt`에 추가:
```kotlin
    @Test
    fun decode_hatch_twoSolidPaths() {
        val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_HATCH.toByte())
        b.putInt(-1); b.putShort(-1); b.putInt(0)
        b.put(1)         // isSolid
        b.putInt(2)      // num_paths
        // path 1: 4 vertices
        b.putInt(4)
        b.putDouble(0.0); b.putDouble(0.0)
        b.putDouble(1.0); b.putDouble(0.0)
        b.putDouble(1.0); b.putDouble(1.0)
        b.putDouble(0.0); b.putDouble(1.0)
        // path 2: 3 vertices
        b.putInt(3)
        b.putDouble(2.0); b.putDouble(2.0)
        b.putDouble(3.0); b.putDouble(2.0)
        b.putDouble(2.5); b.putDouble(3.0)
        val entity = ByteArray(b.position()); System.arraycopy(b.array(), 0, entity, 0, entity.size)

        val drawing = NativeDecoder.decode(buildBuffer(entities = listOf(entity)))
        val h = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfHatch
        assertTrue(h.isSolid)
        assertEquals(2, h.paths.size)
        assertEquals(4, h.paths[0].size)
        assertEquals(3, h.paths[1].size)
    }

    @Test
    fun decode_dimension_withTextOverride() {
        val textBytes = "100mm".toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_DIMENSION.toByte())
        b.putInt(-1); b.putShort(-1); b.putInt(0)
        b.putDouble(0.0); b.putDouble(0.0)   // def
        b.putDouble(5.0); b.putDouble(5.0)   // text mid
        b.putInt(0)                          // dimType
        b.putShort(textBytes.size.toShort())
        b.put(textBytes)
        val entity = ByteArray(b.position()); System.arraycopy(b.array(), 0, entity, 0, entity.size)

        val drawing = NativeDecoder.decode(buildBuffer(entities = listOf(entity)))
        val d = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfDimension
        assertEquals("100mm", d.textOverride)
    }

    @Test
    fun decode_leader() {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put(NativeProtocol.TYPE_LEADER.toByte())
        b.putInt(-1); b.putShort(-1); b.putInt(0)
        b.putInt(3)
        b.putDouble(0.0); b.putDouble(0.0)
        b.putDouble(5.0); b.putDouble(0.0)
        b.putDouble(7.0); b.putDouble(3.0)
        val entity = ByteArray(b.position()); System.arraycopy(b.array(), 0, entity, 0, entity.size)

        val drawing = NativeDecoder.decode(buildBuffer(entities = listOf(entity)))
        val l = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfLeader
        assertEquals(3, l.vertices.size)
    }
```

- [ ] **6-2. RED 확인**

- [ ] **6-3. 디코더 분기 추가**

`when (typeId)`:
```kotlin
                NativeProtocol.TYPE_HATCH     -> decodeHatch(buf, layerName)
                NativeProtocol.TYPE_DIMENSION -> decodeDimension(buf, layerName)
                NativeProtocol.TYPE_LEADER    -> decodeLeader(buf, layerName)
```

함수:
```kotlin
    private fun decodeHatch(buf: ByteBuffer, layer: String): DxfHatch {
        val isSolid = buf.get() != 0.toByte()
        val numPaths = buf.int
        val paths = List(numPaths) {
            val n = buf.int
            List(n) { Vec2(buf.double, buf.double) }
        }
        return DxfHatch(layer, isSolid, paths)
    }

    private fun decodeDimension(buf: ByteBuffer, layer: String): DxfDimension {
        val def = Vec2(buf.double, buf.double)
        val tp = Vec2(buf.double, buf.double)
        val dimType = buf.int
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len); buf.get(bytes)
        return DxfDimension(layer, def, tp, dimType, String(bytes, Charsets.UTF_8))
    }

    private fun decodeLeader(buf: ByteBuffer, layer: String): DxfLeader {
        val n = buf.int
        val verts = List(n) { Vec2(buf.double, buf.double) }
        return DxfLeader(layer, verts)
    }
```

`import`:
```kotlin
import io.github.june690602_blip.cleancad.model.DxfDimension
import io.github.june690602_blip.cleancad.model.DxfHatch
import io.github.june690602_blip.cleancad.model.DxfLeader
```

- [ ] **6-4. GREEN**

Expected: 77 tests (74 + 3).

- [ ] **6-5. 커밋**

`git commit -am "feat(phase8): HATCH/DIMENSION/LEADER 디코더"`

---

## Task 7: 엔티티 색상 — Drawing/Layer/DxfEntity 모델 확장

**근거:** 현재 `DxfEntity`는 `layer`만 있고 entity-level color는 없다. native에서 색상을 받으려면 모델 확장이 필요.

**전략:** `Drawing`에 `entityColors: List<EntityColor>`를 병렬 배열로 추가 (기존 sealed class를 건드리지 않음). EntityRenderer가 `entities[i]`에 매핑되는 `entityColors[i]`를 lookup.

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/model/Drawing.kt`
- Create: `app/src/main/java/io/github/june690602_blip/cleancad/model/EntityColor.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt`
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/native/EntityColorDecodingTest.kt`
- Modify: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDecoderTest.kt` (회귀)

### Steps

- [ ] **7-1. `EntityColor.kt`**

```kotlin
package io.github.june690602_blip.cleancad.model

/**
 * 엔티티 1개의 색상 결정 정보.
 *
 * - colorIndex == -1: BYLAYER (레이어 색 사용)
 * - colorIndex == 0:  BYBLOCK (block 색 사용; INSERT 전개 시 부모 색 전달)
 * - colorIndex == 1..255: ACI 인덱스
 * - rgb != 0: 24-bit true color (colorIndex보다 우선)
 */
data class EntityColor(
    val colorIndex: Int = -1,
    val rgb: Int = 0
) {
    val isByLayer: Boolean get() = colorIndex == -1
    val isByBlock: Boolean get() = colorIndex == 0
    val hasRgb: Boolean get() = rgb != 0

    companion object {
        val BYLAYER = EntityColor(-1, 0)
    }
}
```

- [ ] **7-2. `Drawing` 확장**

`Drawing.kt`의 `Drawing` 데이터 클래스를 다음으로 교체:
```kotlin
data class Drawing(
    val entities: List<DxfEntity>,
    val layers: List<Layer>,
    val extents: BoundingBox?,
    val displayExtents: BoundingBox?,
    val entityColors: List<EntityColor> = emptyList()  // entities와 1:1, 비어있으면 모두 BYLAYER로 간주
)
```

- [ ] **7-3. 테스트 (RED) — 색상 디코딩**

`EntityColorDecodingTest.kt`:
```kotlin
package io.github.june690602_blip.cleancad.native

import io.github.june690602_blip.cleancad.model.EntityColor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EntityColorDecodingTest {

    private fun bufferWithLine(colorIdx: Short, rgb: Int): ByteArray {
        val total = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        // header
        total.putInt(NativeProtocol.MAGIC); total.putShort(NativeProtocol.VERSION.toShort())
        total.putShort(0); total.putInt(0); total.putInt(1); total.putInt(0)
        // entity
        total.put(NativeProtocol.TYPE_LINE.toByte())
        total.putInt(-1); total.putShort(colorIdx); total.putInt(rgb)
        total.putDouble(0.0); total.putDouble(0.0); total.putDouble(1.0); total.putDouble(1.0)
        val out = ByteArray(total.position()); System.arraycopy(total.array(), 0, out, 0, out.size)
        return out
    }

    @Test
    fun decode_entity_byLayer() {
        val drawing = NativeDecoder.decode(bufferWithLine(-1, 0))
        assertEquals(1, drawing.entityColors.size)
        assertTrue(drawing.entityColors[0].isByLayer)
    }

    @Test
    fun decode_entity_aciColor3() {
        val drawing = NativeDecoder.decode(bufferWithLine(3, 0))
        assertEquals(3, drawing.entityColors[0].colorIndex)
        assertFalse(drawing.entityColors[0].hasRgb)
    }

    @Test
    fun decode_entity_rgb() {
        val drawing = NativeDecoder.decode(bufferWithLine(-1, 0xFF8800))
        assertTrue(drawing.entityColors[0].hasRgb)
        assertEquals(0xFF8800, drawing.entityColors[0].rgb)
    }
}
```

- [ ] **7-4. RED 확인**

- [ ] **7-5. `NativeDecoder` — 색상 캡처**

`decodeEntities`를 다음으로 교체:
```kotlin
    private fun decodeEntities(
        buf: ByteBuffer, count: Int, layers: List<Layer>
    ): Pair<List<DxfEntity>, List<EntityColor>> {
        val entities = ArrayList<DxfEntity>(count)
        val colors = ArrayList<EntityColor>(count)
        repeat(count) {
            val typeId = buf.get().toInt() and 0xFF
            val layerIdx = buf.int
            val colorIdx = buf.short.toInt()
            val rgb = buf.int
            val layerName = layerNameAt(layers, layerIdx)
            val entity: DxfEntity? = when (typeId) {
                NativeProtocol.TYPE_LINE        -> decodeLine(buf, layerName)
                NativeProtocol.TYPE_CIRCLE      -> decodeCircle(buf, layerName)
                NativeProtocol.TYPE_ARC         -> decodeArc(buf, layerName)
                NativeProtocol.TYPE_LWPOLYLINE  -> decodeLwPolyline(buf, layerName)
                NativeProtocol.TYPE_POLYLINE_2D -> decodePolyline(buf, layerName)
                NativeProtocol.TYPE_POLYLINE_3D -> decodePolyline(buf, layerName)
                NativeProtocol.TYPE_TEXT        -> decodeText(buf, layerName)
                NativeProtocol.TYPE_MTEXT       -> decodeMText(buf, layerName)
                NativeProtocol.TYPE_3DFACE      -> decode3dFace(buf, layerName)
                NativeProtocol.TYPE_SOLID       -> decodeSolid(buf, layerName)
                NativeProtocol.TYPE_ELLIPSE     -> decodeEllipse(buf, layerName)
                NativeProtocol.TYPE_SPLINE      -> decodeSpline(buf, layerName)
                NativeProtocol.TYPE_HATCH       -> decodeHatch(buf, layerName)
                NativeProtocol.TYPE_DIMENSION   -> decodeDimension(buf, layerName)
                NativeProtocol.TYPE_LEADER      -> decodeLeader(buf, layerName)
                else -> { skipUnknownPayload(buf, typeId); null }
            }
            if (entity != null) {
                entities.add(entity)
                colors.add(EntityColor(colorIdx, rgb))
            }
        }
        return entities to colors
    }
```

`decode()` 메서드 끝부분 갱신:
```kotlin
        val (entities, entityColors) = decodeEntities(buf, numEntities, layers)
        return Drawing(entities, layers, extents, extents, entityColors)
```

`import`:
```kotlin
import io.github.june690602_blip.cleancad.model.EntityColor
```

- [ ] **7-6. `EntityRenderer` — entity-level color 사용**

`EntityRenderer.kt`의 색상 결정 로직을 교체:
```kotlin
    private var layerColorMap: Map<String, Int> = emptyMap()
    private var entityColorByIdentity: Map<DxfEntity, Int> = emptyMap()

    fun setLayers(layers: List<Layer>) {
        layerColorMap = layers.associate { layer ->
            layer.name to AciColor.toArgb(layer.colorIndex, fallback = defaultLineColor)
        }
    }

    /** Phase 8: Drawing 전체를 받아 엔티티별 색상까지 lookup 가능하게 한다. */
    fun setDrawing(drawing: Drawing) {
        setLayers(drawing.layers)
        if (drawing.entityColors.isEmpty()) {
            entityColorByIdentity = emptyMap()
            return
        }
        val map = HashMap<DxfEntity, Int>(drawing.entities.size)
        for (i in drawing.entities.indices) {
            val ec = drawing.entityColors[i]
            val color: Int = when {
                ec.hasRgb -> 0xFF000000.toInt() or ec.rgb
                ec.isByLayer || ec.isByBlock ->
                    layerColorMap[drawing.entities[i].layer] ?: defaultLineColor
                else -> AciColor.toArgb(
                    ec.colorIndex,
                    fallback = layerColorMap[drawing.entities[i].layer] ?: defaultLineColor
                )
            }
            map[drawing.entities[i]] = color
        }
        entityColorByIdentity = map
    }

    private fun colorFor(entity: DxfEntity): Int =
        entityColorByIdentity[entity]
            ?: layerColorMap[entity.layer]
            ?: defaultLineColor
```

`import`:
```kotlin
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.model.EntityColor
```

- [ ] **7-7. `DrawingView.setDrawing` 갱신**

`DrawingView.kt`의 `setDrawing` 본문 교체:
```kotlin
    fun setDrawing(drawing: Drawing) {
        this.drawing = drawing
        renderer.setDrawing(drawing)  // setLayers 대신 setDrawing 사용
        if (width > 0 && height > 0) fitToScreen() else matrix = Matrix()
        invalidate()
    }
```

- [ ] **7-8. GREEN 확인 + 회귀 테스트**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```
Expected: 80 tests (77 + 3 new). 기존 테스트도 모두 통과 (Drawing 기본 인자 = emptyList 이므로 회귀 없음).

- [ ] **7-9. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/model/EntityColor.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/model/Drawing.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/native/NativeDecoder.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/EntityRenderer.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/render/DrawingView.kt \
        app/src/test/java/io/github/june690602_blip/cleancad/native/

git commit -m "$(cat <<'EOF'
feat(phase8): 엔티티별 색상 (BYLAYER/BYBLOCK/ACI/RGB)

- EntityColor — colorIndex + rgb, BYLAYER/BYBLOCK 의미 분기
- Drawing.entityColors — entities와 1:1 병렬 배열
- NativeDecoder — 엔티티 헤더의 colorIdx/rgb 캡처
- EntityRenderer.setDrawing — entity 단위 색상 lookup
- DrawingView.setDrawing — renderer.setDrawing(drawing) 호출
- 테스트 3개 (BYLAYER / ACI / RGB)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: C 측 직렬화 — 최소 viable 구현 (LINE/CIRCLE/ARC + 레이어)

**근거:** Kotlin 디코더는 완성. 이제 C 측에서 실제로 채워보자. 가장 단순한 3개 엔티티로 시작 — 빌드 검증과 메모리 안전성이 핵심.

### Files
- Modify: `app/src/main/cpp/dwgjni.c`
- Create: `app/src/main/cpp/dwg_serialize.h`
- Create: `app/src/main/cpp/dwg_serialize.c`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt`

### Steps

- [ ] **8-1. `dwg_serialize.h` — 인터페이스**

```c
#ifndef CLEANCAD_DWG_SERIALIZE_H
#define CLEANCAD_DWG_SERIALIZE_H

#include <dwg.h>
#include <stddef.h>
#include <stdint.h>

#define DWGB_MAGIC            0x42475744u
#define DWGB_PROTOCOL_VERSION 1

#define DWGB_TYPE_LINE        1
#define DWGB_TYPE_CIRCLE      2
#define DWGB_TYPE_ARC         3
#define DWGB_TYPE_LWPOLYLINE  4
#define DWGB_TYPE_POLYLINE_2D 5
#define DWGB_TYPE_POLYLINE_3D 6
#define DWGB_TYPE_TEXT        7
#define DWGB_TYPE_MTEXT       8
#define DWGB_TYPE_HATCH       10
#define DWGB_TYPE_DIMENSION   11
#define DWGB_TYPE_LEADER      12
#define DWGB_TYPE_ELLIPSE     13
#define DWGB_TYPE_SPLINE      14
#define DWGB_TYPE_3DFACE      15
#define DWGB_TYPE_SOLID       16
#define DWGB_TYPE_UNKNOWN     0xFF

/**
 * Dwg_Data 전체를 동적 바이트 버퍼로 직렬화한다.
 * 반환: 호출자가 free() 해야 하는 malloc된 버퍼. 실패 시 NULL.
 * out_len: 버퍼 크기 (바이트).
 */
uint8_t *dwgb_serialize(const Dwg_Data *dwg, size_t *out_len);

#endif
```

- [ ] **8-2. `dwg_serialize.c` — 골격 + LINE/CIRCLE/ARC**

```c
#include "dwg_serialize.h"
#include <dwg.h>
#include <dwg_api.h>
#include "bits.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    uint8_t *buf;
    size_t   len;
    size_t   cap;
    int      ok;
} Writer;

static void w_reserve(Writer *w, size_t need) {
    if (!w->ok) return;
    if (w->len + need > w->cap) {
        size_t new_cap = w->cap ? w->cap * 2 : 4096;
        while (new_cap < w->len + need) new_cap *= 2;
        uint8_t *nb = (uint8_t *)realloc(w->buf, new_cap);
        if (!nb) { w->ok = 0; return; }
        w->buf = nb; w->cap = new_cap;
    }
}

static void w_u8 (Writer *w, uint8_t  v) { w_reserve(w, 1); if(w->ok){w->buf[w->len++]=v;} }
static void w_u16(Writer *w, uint16_t v) { w_reserve(w, 2); if(w->ok){memcpy(w->buf+w->len,&v,2); w->len+=2;} }
static void w_u32(Writer *w, uint32_t v) { w_reserve(w, 4); if(w->ok){memcpy(w->buf+w->len,&v,4); w->len+=4;} }
static void w_i16(Writer *w, int16_t  v) { w_reserve(w, 2); if(w->ok){memcpy(w->buf+w->len,&v,2); w->len+=2;} }
static void w_i32(Writer *w, int32_t  v) { w_reserve(w, 4); if(w->ok){memcpy(w->buf+w->len,&v,4); w->len+=4;} }
static void w_f64(Writer *w, double   v) { w_reserve(w, 8); if(w->ok){memcpy(w->buf+w->len,&v,8); w->len+=8;} }

static void w_string_utf8(Writer *w, const char *s) {
    size_t n = s ? strlen(s) : 0;
    if (n > 0xFFFF) n = 0xFFFF;
    w_u16(w, (uint16_t)n);
    w_reserve(w, n);
    if (w->ok && n > 0) { memcpy(w->buf + w->len, s, n); w->len += n; }
}

/* LibreDWG의 TV(텍스트 값)는 codepage가 이미 적용된 char* 또는 R2007+의 TU(UTF-16).
 * bit_TV_to_utf8()이 두 경우 모두 안전하게 UTF-8 char*를 반환한다.
 * 호출 후에는 free()로 해제. NULL이면 빈 문자열로 처리.
 */
static char *tv_to_utf8(const Dwg_Data *dwg, BITCODE_TV tv) {
    if (!tv) return NULL;
    // dwg->header.from_version >= R_2007 인 경우 TV가 실제로는 wchar_t* (TU)
    // bit_TV_to_utf8은 from_version을 사용하여 적절히 변환한다.
    return bit_TV_to_utf8(tv, dwg->header.from_version);
}

/* ---- Layer 테이블 ---- */

static int write_layer_table(Writer *w, const Dwg_Data *dwg) {
    BITCODE_BL num_layers = dwg_get_layer_count((Dwg_Data *)dwg);
    Dwg_Object_LAYER **layers = dwg_get_layers((Dwg_Data *)dwg);
    int written = 0;
    if (!layers) { w_i32(w, 0); return 0; }
    // 1차 패스: 카운트만 위해 미리 i32 자리를 비우진 않고, 헤더에서 처리한다고 가정.
    // 여기서는 layer 1개씩 직접 쓴다 (caller가 num_layers를 헤더에 박음).
    for (BITCODE_BL i = 0; i < num_layers; ++i) {
        Dwg_Object_LAYER *lay = layers[i];
        if (!lay) continue;
        char *name = tv_to_utf8(dwg, lay->name);
        w_string_utf8(w, name ? name : "");
        free(name);
        int16_t ci = (int16_t)(lay->color.index);
        w_i16(w, ci);
        uint32_t rgb = 0;
        if (lay->color.flag & 0x80 && !(lay->color.flag & 0x40)) {
            rgb = (uint32_t)(lay->color.rgb & 0x00FFFFFFu);
        }
        w_u32(w, rgb);
        uint8_t flags = 0;
        if (lay->flag & 1)  flags |= 1;  // frozen
        if (lay->on == 0)   flags |= 2;  // off
        if (lay->flag & 4)  flags |= 4;  // locked
        w_u8(w, flags);
        written++;
    }
    free(layers);
    return written;
}

/* ---- Entity 헤더 (typeId, layer_idx, color, rgb) ---- */

/* layer 객체 핸들로부터 우리 테이블 인덱스 찾기.
 * Phase 8 초기에는 layer name 문자열 매칭으로 단순화. */
static int32_t resolve_layer_idx(const Dwg_Data *dwg, const Dwg_Object *obj) {
    /* TODO: O(N) name lookup — Task 11에서 hash로 개선 */
    Dwg_Object_Entity *ent = obj->tio.entity;
    if (!ent || !ent->layer || !ent->layer->obj) return -1;
    Dwg_Object_LAYER *lay = ent->layer->obj->tio.object->tio.LAYER;
    if (!lay || !lay->name) return -1;
    char *name = tv_to_utf8(dwg, lay->name);
    if (!name) return -1;
    BITCODE_BL n = dwg_get_layer_count((Dwg_Data *)dwg);
    Dwg_Object_LAYER **arr = dwg_get_layers((Dwg_Data *)dwg);
    int32_t idx = -1;
    for (BITCODE_BL i = 0; i < n && arr; ++i) {
        if (!arr[i] || !arr[i]->name) continue;
        char *nm = tv_to_utf8(dwg, arr[i]->name);
        if (nm && strcmp(nm, name) == 0) { idx = (int32_t)i; free(nm); break; }
        free(nm);
    }
    free(name);
    free(arr);
    return idx;
}

static void write_entity_header(Writer *w, const Dwg_Data *dwg,
                                const Dwg_Object *obj, uint8_t typeId) {
    w_u8(w, typeId);
    w_i32(w, resolve_layer_idx(dwg, obj));
    Dwg_Object_Entity *ent = obj->tio.entity;
    int16_t ci = -1;
    uint32_t rgb = 0;
    if (ent) {
        ci = (int16_t)(ent->color.index);
        if (ent->color.flag & 0x80 && !(ent->color.flag & 0x40)) {
            rgb = (uint32_t)(ent->color.rgb & 0x00FFFFFFu);
        }
    }
    w_i16(w, ci);
    w_u32(w, rgb);
}

/* ---- 엔티티 ---- */

static void write_line(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_LINE *e = obj->tio.entity->tio.LINE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_LINE);
    w_f64(w, e->start.x); w_f64(w, e->start.y);
    w_f64(w, e->end.x);   w_f64(w, e->end.y);
}

static void write_circle(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_CIRCLE *e = obj->tio.entity->tio.CIRCLE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_CIRCLE);
    w_f64(w, e->center.x); w_f64(w, e->center.y);
    w_f64(w, e->radius);
}

static void write_arc(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_ARC *e = obj->tio.entity->tio.ARC;
    write_entity_header(w, dwg, obj, DWGB_TYPE_ARC);
    w_f64(w, e->center.x); w_f64(w, e->center.y);
    w_f64(w, e->radius);
    /* LibreDWG는 radian으로 보관 */
    double sd = e->start_angle * 180.0 / 3.14159265358979323846;
    double ed = e->end_angle   * 180.0 / 3.14159265358979323846;
    w_f64(w, sd); w_f64(w, ed);
}

/* ---- 메인 진입점 ---- */

uint8_t *dwgb_serialize(const Dwg_Data *dwg, size_t *out_len) {
    Writer w = {0};
    w.ok = 1;

    /* Header */
    w_u32(&w, DWGB_MAGIC);
    w_u16(&w, DWGB_PROTOCOL_VERSION);
    w_u16(&w, 0);

    /* num_layers / num_entities placeholder — 나중에 채움 */
    size_t pos_num_layers = w.len;     w_i32(&w, 0);
    size_t pos_num_entities = w.len;   w_i32(&w, 0);

    /* extents: 일단 미사용 */
    w_i32(&w, 0);

    /* Layers */
    int n_layers = write_layer_table(&w, dwg);
    memcpy(w.buf + pos_num_layers, &n_layers, 4);

    /* Entities */
    int n_entities = 0;
    for (BITCODE_BL i = 0; i < dwg->num_objects; ++i) {
        const Dwg_Object *obj = &dwg->object[i];
        if (obj->supertype != DWG_SUPERTYPE_ENTITY) continue;
        switch (obj->fixedtype) {
            case DWG_TYPE_LINE:   write_line  (&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_CIRCLE: write_circle(&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_ARC:    write_arc   (&w, dwg, obj); n_entities++; break;
            default: break;  /* Task 9~10에서 더 추가 */
        }
        if (!w.ok) break;
    }
    memcpy(w.buf + pos_num_entities, &n_entities, 4);

    if (!w.ok) { free(w.buf); return NULL; }
    *out_len = w.len;
    return w.buf;
}
```

- [ ] **8-3. `dwgjni.c` — `nativeDwgToBuffer` 추가**

`dwgjni.c` 끝에 추가:
```c
#include "dwg_serialize.h"

JNIEXPORT jbyteArray JNICALL
Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeDwgToBuffer(
        JNIEnv *env, jobject thiz, jstring inPathJ) {
    const char *inPath = (*env)->GetStringUTFChars(env, inPathJ, NULL);
    if (!inPath) return NULL;

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    int err = dwg_read_file(inPath, &dwg);
    (*env)->ReleaseStringUTFChars(env, inPathJ, inPath);
    if (err >= DWG_ERR_CRITICAL) { dwg_free(&dwg); return NULL; }

    size_t len = 0;
    uint8_t *bytes = dwgb_serialize(&dwg, &len);
    dwg_free(&dwg);
    if (!bytes) return NULL;

    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (!out) { free(bytes); return NULL; }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)bytes);
    free(bytes);
    return out;
}
```

`#include <stdint.h>`를 파일 상단에 추가 (이미 있다면 생략).

- [ ] **8-4. `CMakeLists.txt` — dwg_serialize.c 추가**

`add_library(dwgjni SHARED dwgjni.c)` 라인을 다음으로 교체:
```cmake
add_library(dwgjni SHARED dwgjni.c dwg_serialize.c)
```

- [ ] **8-5. `NativeDwg.kt` — Kotlin 진입점 추가**

`NativeDwg.kt`에 외부 메서드 추가:
```kotlin
    external fun nativeDwgToBuffer(inPath: String): ByteArray?
```

- [ ] **8-6. 빌드 검증**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL. C 컴파일 워닝은 무시 가능하지만 에러 있으면 STOP.

- [ ] **8-7. 커밋**

```bash
git add app/src/main/cpp/dwg_serialize.h \
        app/src/main/cpp/dwg_serialize.c \
        app/src/main/cpp/dwgjni.c \
        app/src/main/cpp/CMakeLists.txt \
        app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt

git commit -m "$(cat <<'EOF'
feat(phase8): native serializer 골격 + LINE/CIRCLE/ARC

- dwg_serialize.[ch] — 동적 버퍼 + LITTLE_ENDIAN 직렬화
- 레이어 테이블 (name UTF-8, ACI, RGB, flags)
- LINE/CIRCLE/ARC 처리 (radian→degree 변환 포함)
- nativeDwgToBuffer JNI 진입점

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: C 측 — 나머지 엔티티 (POLYLINE/TEXT/MTEXT/INSERT 전개/HATCH/...)

**근거:** 도면에서 빈도 높은 순서로 추가. 각 항목마다 끝-to-끝 작동 확인.

### Files
- Modify: `app/src/main/cpp/dwg_serialize.c`

### Steps

- [ ] **9-1. LWPOLYLINE / POLYLINE_2D / POLYLINE_3D**

`write_arc` 아래에 추가:
```c
static void write_lwpolyline(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_LWPOLYLINE *e = obj->tio.entity->tio.LWPOLYLINE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_LWPOLYLINE);
    uint8_t closed = (e->flag & 512) ? 1 : 0;  /* group 70 bit 1 = closed (libredwg: flag) */
    /* LibreDWG: flag bit 0 = closed in many versions; cross-check via spec.
     * 안전을 위해: closed = (flag & 1) || (flag & 512) ? 1 : 0; */
    closed = ((e->flag & 1) || (e->flag & 512)) ? 1 : 0;
    w_u8(w, closed);
    w_i32(w, (int32_t)e->num_points);
    for (BITCODE_BL i = 0; i < e->num_points; ++i) {
        w_f64(w, e->points[i].x); w_f64(w, e->points[i].y);
    }
}

static void write_polyline_2d(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_POLYLINE_2D *e = obj->tio.entity->tio.POLYLINE_2D;
    write_entity_header(w, dwg, obj, DWGB_TYPE_POLYLINE_2D);
    uint8_t closed = (e->flag & 1) ? 1 : 0;
    w_u8(w, closed);
    /* vertices는 자식 엔티티 (DWG_TYPE_VERTEX_2D) 또는 e->vertex 배열 */
    BITCODE_BL n = e->num_owned;
    w_i32(w, (int32_t)n);
    for (BITCODE_BL i = 0; i < n; ++i) {
        if (!e->vertex || !e->vertex[i] || !e->vertex[i]->obj) {
            w_f64(w, 0.0); w_f64(w, 0.0); continue;
        }
        Dwg_Entity_VERTEX_2D *v = e->vertex[i]->obj->tio.entity->tio.VERTEX_2D;
        w_f64(w, v->point.x); w_f64(w, v->point.y);
    }
}

static void write_polyline_3d(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_POLYLINE_3D *e = obj->tio.entity->tio.POLYLINE_3D;
    write_entity_header(w, dwg, obj, DWGB_TYPE_POLYLINE_3D);
    uint8_t closed = (e->flag & 1) ? 1 : 0;
    w_u8(w, closed);
    BITCODE_BL n = e->num_owned;
    w_i32(w, (int32_t)n);
    for (BITCODE_BL i = 0; i < n; ++i) {
        if (!e->vertex || !e->vertex[i] || !e->vertex[i]->obj) {
            w_f64(w, 0.0); w_f64(w, 0.0); continue;
        }
        Dwg_Entity_VERTEX_3D *v = e->vertex[i]->obj->tio.entity->tio.VERTEX_3D;
        w_f64(w, v->point.x); w_f64(w, v->point.y);
    }
}
```

`switch`에 추가:
```c
            case DWG_TYPE_LWPOLYLINE:  write_lwpolyline (&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_POLYLINE_2D: write_polyline_2d(&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_POLYLINE_3D: write_polyline_3d(&w, dwg, obj); n_entities++; break;
```

빌드: `./gradlew :app:assembleDebug 2>&1 | tail -5`

- [ ] **9-2. TEXT / MTEXT — UTF-8 변환 검증**

```c
static void write_text(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_TEXT *e = obj->tio.entity->tio.TEXT;
    write_entity_header(w, dwg, obj, DWGB_TYPE_TEXT);
    w_f64(w, e->ins_pt.x); w_f64(w, e->ins_pt.y);
    w_f64(w, e->height);
    double rot_deg = e->rotation * 180.0 / 3.14159265358979323846;
    w_f64(w, rot_deg);
    char *utf8 = tv_to_utf8(dwg, e->text_value);
    w_string_utf8(w, utf8 ? utf8 : "");
    free(utf8);
}

static void write_mtext(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_MTEXT *e = obj->tio.entity->tio.MTEXT;
    write_entity_header(w, dwg, obj, DWGB_TYPE_MTEXT);
    w_f64(w, e->ins_pt.x); w_f64(w, e->ins_pt.y);
    w_f64(w, e->text_height);
    double rot_deg = e->rotation * 180.0 / 3.14159265358979323846;
    w_f64(w, rot_deg);
    char *utf8 = tv_to_utf8(dwg, e->text);
    w_string_utf8(w, utf8 ? utf8 : "");
    free(utf8);
}
```

switch에 추가:
```c
            case DWG_TYPE_TEXT:  write_text (&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_MTEXT: write_mtext(&w, dwg, obj); n_entities++; break;
```

빌드 검증.

- [ ] **9-3. 3DFACE / SOLID / ELLIPSE / SPLINE**

```c
static void write_3dface(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity__3DFACE *e = obj->tio.entity->tio._3DFACE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_3DFACE);
    w_f64(w, e->corner1.x); w_f64(w, e->corner1.y);
    w_f64(w, e->corner2.x); w_f64(w, e->corner2.y);
    w_f64(w, e->corner3.x); w_f64(w, e->corner3.y);
    w_f64(w, e->corner4.x); w_f64(w, e->corner4.y);
}

static void write_solid(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_SOLID *e = obj->tio.entity->tio.SOLID;
    write_entity_header(w, dwg, obj, DWGB_TYPE_SOLID);
    /* LibreDWG: corner1..corner4 (각 BITCODE_2RD).
     * Kotlin 측 EntityRenderer.drawSolid가 legacy 1-2-4-3 순서를 다루므로
     * native에서도 그대로 1-2-3-4 순서로 보냄. */
    w_f64(w, e->corner1.x); w_f64(w, e->corner1.y);
    w_f64(w, e->corner2.x); w_f64(w, e->corner2.y);
    w_f64(w, e->corner3.x); w_f64(w, e->corner3.y);
    w_f64(w, e->corner4.x); w_f64(w, e->corner4.y);
}

static void write_ellipse(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_ELLIPSE *e = obj->tio.entity->tio.ELLIPSE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_ELLIPSE);
    w_f64(w, e->center.x); w_f64(w, e->center.y);
    w_f64(w, e->sm_axis.x); w_f64(w, e->sm_axis.y);
    w_f64(w, e->axis_ratio);
    w_f64(w, e->start_angle); w_f64(w, e->end_angle);
}

static void write_spline(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_SPLINE *e = obj->tio.entity->tio.SPLINE;
    write_entity_header(w, dwg, obj, DWGB_TYPE_SPLINE);
    w_i32(w, (int32_t)e->degree);
    w_i32(w, (int32_t)e->num_ctrl_pts);
    for (BITCODE_BL i = 0; i < e->num_ctrl_pts; ++i) {
        w_f64(w, e->ctrl_pts[i].x); w_f64(w, e->ctrl_pts[i].y);
    }
}
```

switch에 추가:
```c
            case DWG_TYPE__3DFACE: write_3dface (&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_SOLID:   write_solid  (&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_ELLIPSE: write_ellipse(&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_SPLINE:  write_spline (&w, dwg, obj); n_entities++; break;
```

빌드 검증.

- [ ] **9-4. DIMENSION / LEADER**

```c
static void write_dimension(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    /* LibreDWG는 DIMENSION을 여러 sub-type으로 보관:
     *  DIMENSION_ALIGNED, DIMENSION_LINEAR, DIMENSION_ANG2LN, ...
     *  공통 헤더(DIMENSION_common)에 def_pt, text_midpt, flag1 등이 있다.
     *  여기서는 generic 접근을 위해 _ALIGNED만 사용 (다른 sub-type은 fallthrough). */
    Dwg_Entity_DIMENSION_ALIGNED *e = obj->tio.entity->tio.DIMENSION_ALIGNED;
    write_entity_header(w, dwg, obj, DWGB_TYPE_DIMENSION);
    w_f64(w, e->def_pt.x); w_f64(w, e->def_pt.y);
    w_f64(w, e->text_midpt.x); w_f64(w, e->text_midpt.y);
    w_i32(w, 0);  /* dimType — 단순화 */
    char *utf8 = tv_to_utf8(dwg, e->user_text);
    w_string_utf8(w, utf8 ? utf8 : "");
    free(utf8);
}

static void write_leader(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_LEADER *e = obj->tio.entity->tio.LEADER;
    write_entity_header(w, dwg, obj, DWGB_TYPE_LEADER);
    w_i32(w, (int32_t)e->num_points);
    for (BITCODE_BL i = 0; i < e->num_points; ++i) {
        w_f64(w, e->points[i].x); w_f64(w, e->points[i].y);
    }
}
```

switch에 추가:
```c
            case DWG_TYPE_DIMENSION_ALIGNED:
            case DWG_TYPE_DIMENSION_LINEAR:
            case DWG_TYPE_DIMENSION_ANG3PT:
            case DWG_TYPE_DIMENSION_ANG2LN:
            case DWG_TYPE_DIMENSION_RADIUS:
            case DWG_TYPE_DIMENSION_DIAMETER:
            case DWG_TYPE_DIMENSION_ORDINATE:
                write_dimension(&w, dwg, obj); n_entities++; break;
            case DWG_TYPE_LEADER:  write_leader(&w, dwg, obj); n_entities++; break;
```

빌드 검증.

- [ ] **9-5. HATCH — polyline + line edges**

```c
static void write_hatch(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Entity_HATCH *e = obj->tio.entity->tio.HATCH;
    write_entity_header(w, dwg, obj, DWGB_TYPE_HATCH);
    uint8_t isSolid = (e->is_solid_fill ? 1 : 0);
    w_u8(w, isSolid);

    /* boundary path 개수 — 직렬화 후 채워넣기 위해 자리 잡기 */
    size_t pos_num_paths = w->len;
    w_i32(w, 0);
    int32_t actual_paths = 0;

    for (BITCODE_BL p = 0; p < e->num_paths; ++p) {
        Dwg_HATCH_Path *path = &e->paths[p];
        /* path_type 비트 2 = polyline (DXF 92 비트 1과 의미 동일) */
        int is_polyline = (path->flag & 2) != 0;
        size_t pos_num_verts = w->len;
        w_i32(w, 0);
        int32_t nv = 0;

        if (is_polyline) {
            for (BITCODE_BL v = 0; v < path->num_path_segs; ++v) {
                /* polyline boundary의 정점은 path->polyline_paths[v].point */
                w_f64(w, path->polyline_paths[v].point.x);
                w_f64(w, path->polyline_paths[v].point.y);
                nv++;
            }
        } else {
            /* edge boundary */
            for (BITCODE_BL s = 0; s < path->num_segs_or_paths; ++s) {
                Dwg_HATCH_PathSeg *seg = &path->segs[s];
                if (seg->curve_type == 1) {  /* line edge */
                    w_f64(w, seg->first_endpoint.x); w_f64(w, seg->first_endpoint.y);
                    w_f64(w, seg->second_endpoint.x); w_f64(w, seg->second_endpoint.y);
                    nv += 2;
                }
                /* curve_type 2=arc, 3=ellipse_arc, 4=spline — Task 12에서 보강 */
            }
        }
        if (!w->ok) return;
        memcpy(w->buf + pos_num_verts, &nv, 4);
        if (nv > 0) actual_paths++;
    }
    if (!w->ok) return;
    memcpy(w->buf + pos_num_paths, &actual_paths, 4);
}
```

switch에 추가:
```c
            case DWG_TYPE_HATCH: write_hatch(&w, dwg, obj); n_entities++; break;
```

빌드 검증.

- [ ] **9-6. INSERT 블록 재귀 전개**

INSERT는 매우 중요하므로 별도 함수로 분리. C 측에서 재귀 전개하면 Kotlin 측이 단순해진다.

`write_arc` 아래에 추가:
```c
/* Forward declare so write_insert can dispatch entities. */
static void write_entity(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot_rad,
                         int depth, int *count_ptr);

/* 2D affine: scale → rotate → translate */
static void affine_point(double *px, double *py,
                         double sx, double sy, double rot_rad, double tx, double ty) {
    double x = (*px) * sx;
    double y = (*py) * sy;
    double cs = cos(rot_rad), sn = sin(rot_rad);
    double rx = x * cs - y * sn;
    double ry = x * sn + y * cs;
    *px = rx + tx;
    *py = ry + ty;
}
```

(이 패턴은 작업이 큼: write_line, write_circle 등 모든 함수가 변환을 받아야 함.
실제 구현에서는 entity별 변환 헬퍼를 만들거나, 변환 후 dwg 객체를 임시 복제하는 방식 등을 선택.
**단순화 전략 — 변환은 호출 시점에 좌표에 직접 적용:**)

위 affine_point를 각 write_* 함수의 좌표 쓰기 직전에 적용. 다음과 같이 패턴을 통일:

`write_line`을 다음으로 교체 (전 함수 시그니처 확장):
```c
static void write_line(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                       double tx, double ty, double sx, double sy, double rot) {
    Dwg_Entity_LINE *e = obj->tio.entity->tio.LINE;
    double x1 = e->start.x, y1 = e->start.y;
    double x2 = e->end.x,   y2 = e->end.y;
    if (tx != 0.0 || ty != 0.0 || sx != 1.0 || sy != 1.0 || rot != 0.0) {
        affine_point(&x1, &y1, sx, sy, rot, tx, ty);
        affine_point(&x2, &y2, sx, sy, rot, tx, ty);
    }
    write_entity_header(w, dwg, obj, DWGB_TYPE_LINE);
    w_f64(w, x1); w_f64(w, y1); w_f64(w, x2); w_f64(w, y2);
}
```

**중요 — 작업 범위가 크다. 이 단계는 다음과 같이 분할:**

  1. 모든 `write_*` 함수에 `(tx, ty, sx, sy, rot)` 매개변수 추가
  2. 좌표를 출력하기 직전에 affine_point 적용
  3. `dwgb_serialize` 메인 루프에서는 `tx=ty=0, sx=sy=1, rot=0` 으로 호출
  4. INSERT 케이스에서 block 본문을 재귀 호출

`write_insert` 추가 + 메인 루프에 INSERT 케이스:
```c
#define DWGB_MAX_INSERT_DEPTH 5
#define DWGB_MAX_ENTITIES 100000

static void write_insert(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr) {
    if (depth >= DWGB_MAX_INSERT_DEPTH) return;
    Dwg_Entity_INSERT *e = obj->tio.entity->tio.INSERT;
    if (!e->block_header || !e->block_header->obj) return;
    Dwg_Object_BLOCK_HEADER *bh = e->block_header->obj->tio.object->tio.BLOCK_HEADER;
    if (!bh || !bh->first_entity || !bh->last_entity) return;

    /* INSERT 자체의 변환: parent_T 합성 */
    double ix = e->ins_pt.x, iy = e->ins_pt.y;
    affine_point(&ix, &iy, sx, sy, rot, tx, ty);
    double new_sx = sx * e->scale.x;
    double new_sy = sy * e->scale.y;
    double new_rot = rot + e->rotation;

    /* BLOCK_HEADER->entities (Dwg_Object_Ref**) 를 통한 자식 순회.
     * R2004+ 에서는 num_owned가 채워져 있고, 더 오래된 버전이면 first/last_entity로
     * 폴백한다. */
    if (bh->entities && bh->num_owned > 0) {
        for (BITCODE_BL i = 0; i < bh->num_owned; ++i) {
            if (*count_ptr >= DWGB_MAX_ENTITIES) break;
            if (!bh->entities[i] || !bh->entities[i]->obj) continue;
            write_entity(w, dwg, bh->entities[i]->obj,
                         ix, iy, new_sx, new_sy, new_rot, depth + 1, count_ptr);
        }
    } else if (bh->first_entity && bh->first_entity->obj
               && bh->last_entity && bh->last_entity->obj) {
        BITCODE_BL start = bh->first_entity->obj->index;
        BITCODE_BL end   = bh->last_entity->obj->index;
        if (end >= dwg->num_objects) end = dwg->num_objects - 1;
        for (BITCODE_BL i = start; i <= end; ++i) {
            if (*count_ptr >= DWGB_MAX_ENTITIES) break;
            const Dwg_Object *child = &dwg->object[i];
            if (child->supertype != DWG_SUPERTYPE_ENTITY) continue;
            write_entity(w, dwg, child, ix, iy, new_sx, new_sy, new_rot,
                         depth + 1, count_ptr);
        }
    }
}

static void write_entity(Writer *w, const Dwg_Data *dwg, const Dwg_Object *obj,
                         double tx, double ty, double sx, double sy, double rot,
                         int depth, int *count_ptr) {
    if (*count_ptr >= DWGB_MAX_ENTITIES) return;
    switch (obj->fixedtype) {
        case DWG_TYPE_LINE:        write_line       (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_CIRCLE:      write_circle     (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_ARC:         write_arc        (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_LWPOLYLINE:  write_lwpolyline (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_POLYLINE_2D: write_polyline_2d(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_POLYLINE_3D: write_polyline_3d(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_TEXT:        write_text       (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_MTEXT:       write_mtext      (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE__3DFACE:     write_3dface     (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_SOLID:       write_solid      (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_ELLIPSE:     write_ellipse    (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_SPLINE:      write_spline     (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_HATCH:       write_hatch      (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_LEADER:      write_leader     (w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_DIMENSION_ALIGNED:
        case DWG_TYPE_DIMENSION_LINEAR:
        case DWG_TYPE_DIMENSION_ANG3PT:
        case DWG_TYPE_DIMENSION_ANG2LN:
        case DWG_TYPE_DIMENSION_RADIUS:
        case DWG_TYPE_DIMENSION_DIAMETER:
        case DWG_TYPE_DIMENSION_ORDINATE:
            write_dimension(w, dwg, obj, tx, ty, sx, sy, rot); (*count_ptr)++; break;
        case DWG_TYPE_INSERT:
            write_insert(w, dwg, obj, tx, ty, sx, sy, rot, depth, count_ptr); break;
        default: break;
    }
}
```

`dwgb_serialize`의 메인 루프를 다음으로 교체:
```c
    int n_entities = 0;
    for (BITCODE_BL i = 0; i < dwg->num_objects; ++i) {
        const Dwg_Object *obj = &dwg->object[i];
        if (obj->supertype != DWG_SUPERTYPE_ENTITY) continue;
        /* Block table 내부 엔티티는 BLOCK_HEADER->entities를 통해서만 노출.
         * Modelspace/Paperspace block의 직속 엔티티만 메인 루프에서 처리한다.
         * 단순화: 메인 루프에서 모든 entity를 처리하되, INSERT 만나면 전개. */
        write_entity(&w, dwg, obj, 0.0, 0.0, 1.0, 1.0, 0.0, 0, &n_entities);
        if (!w.ok || n_entities >= DWGB_MAX_ENTITIES) break;
    }
    memcpy(w.buf + pos_num_entities, &n_entities, 4);
```

**중요 검증:** 작은 INSERT가 포함된 DWG를 빌드해서 확인 — 단위 테스트로 작성 불가하므로 Task 11에서 통합 검증.

빌드 검증.

- [ ] **9-7. 커밋**

```bash
git add app/src/main/cpp/dwg_serialize.c

git commit -m "$(cat <<'EOF'
feat(phase8): native serializer — 전 엔티티 타입 + INSERT 재귀 전개

- POLYLINE_2D/3D, LWPOLYLINE
- TEXT/MTEXT (UTF-8 변환)
- 3DFACE/SOLID/ELLIPSE/SPLINE
- HATCH (polyline + line edges; arc edges는 Task 12)
- DIMENSION (모든 sub-type → 공통 페이로드)
- LEADER
- INSERT 재귀 전개 (depth 5, MAX_ENTITIES 100K)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: 통합 진입점 — `NativeDwg.parseToDrawing()` + DrawingViewModel 전환

**근거:** native + decoder를 묶고 ViewModel을 전환한다. 구 DXF 파이프라인은 보존 (회귀 시 복귀용).

### Files
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt`
- Create: `app/src/test/java/io/github/june690602_blip/cleancad/native/NativeDwgIntegrationTest.kt` (선택)

### Steps

- [ ] **10-1. `NativeDwg.parseToDrawing()` 헬퍼**

`NativeDwg.kt` 끝에 추가:
```kotlin
    /**
     * DWG 파일을 native에서 파싱하여 Drawing으로 변환한다.
     * 실패 시 RuntimeException을 던진다.
     */
    fun parseToDrawing(dwgPath: String): io.github.june690602_blip.cleancad.model.Drawing {
        val bytes = nativeDwgToBuffer(dwgPath)
            ?: throw RuntimeException("DWG 파싱 실패: $dwgPath")
        return io.github.june690602_blip.cleancad.native.NativeDecoder.decode(bytes)
    }
```

- [ ] **10-2. `DrawingViewModel.load()` 갈아끼기**

`DrawingViewModel.kt`의 `load()` 본문 내 DXF 변환/파싱 블록을 다음으로 교체:

기존:
```kotlin
                    val dxfFile = File(ctx.cacheDir, "dwg_$tag.dxf")
                    val rc = NativeDwg.nativeDwgToDxf(dwgFile.absolutePath, dxfFile.absolutePath)
                    if (rc != 0) throw RuntimeException("DWG 변환 실패 (코드: $rc)")

                    val charset = dxfFile.inputStream().use {
                        DxfCharsetDetector.detect(it)
                    }
                    val drawing = dxfFile.inputStream().bufferedReader(charset).use {
                        DxfParser.parse(it)
                    }
```

→ 다음으로 교체:
```kotlin
                    val drawing = NativeDwg.parseToDrawing(dwgFile.absolutePath)
```

import 정리 — 더 이상 필요 없는 것들 제거:
```kotlin
// 제거:
// import io.github.june690602_blip.cleancad.parser.DxfCharsetDetector
// import io.github.june690602_blip.cleancad.parser.DxfParser
```

- [ ] **10-3. 빌드 + 단위 테스트**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -5
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
```
Expected: 둘 다 BUILD SUCCESSFUL. 단위 테스트 80개 통과 (Task 7 끝 기준).

- [ ] **10-4. 에뮬레이터 수동 검증 (필수)**

```bash
# 디바이스 연결 확인
$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe devices

# 설치
./gradlew :app:installDebug

# 04_참고도면.dwg와 워킹타워.dwg를 디바이스 Downloads에 둔 뒤 앱에서 열기
```

확인 항목:
- 한글 텍스트 정상 표시 (`?` 박스 없음)
- 레이어별 색 구분
- POLYLINE/HATCH/3DFACE/SOLID 정상 표시
- ZWCAD Mobile과 시각적으로 비슷한 수준

문제 발견 시 즉시 복귀: `git revert HEAD` 로 ViewModel 변경만 되돌리면 Phase 7 상태로 복원 (구 DXF 파이프라인 그대로 보존되어 있음).

- [ ] **10-5. 커밋**

```bash
git add app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt \
        app/src/main/java/io/github/june690602_blip/cleancad/ui/DrawingViewModel.kt

git commit -m "$(cat <<'EOF'
feat(phase8): DrawingViewModel 전환 — native parser 사용

- NativeDwg.parseToDrawing() 헬퍼 추가
- DrawingViewModel.load() — DXF 중간단계 제거
- 구 DXF 파이프라인(DxfParser/Reader/CharsetDetector)은 회귀 대비로 보존

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: 회귀 검증 통과 후 구 DXF 파이프라인 제거 + 최적화

**근거:** Task 10에서 실제 도면으로 확인이 끝나면 구 코드를 깨끗이 제거. resolve_layer_idx의 O(N²) 도 hash로 개선.

### Files
- Delete: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt`
- Delete: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfReader.kt`
- Delete: `app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetector.kt`
- Delete: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt`
- Delete: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfReaderTest.kt`
- Delete: `app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetectorTest.kt`
- Modify: `app/src/main/cpp/dwgjni.c` (`nativeDwgToDxf` 제거)
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/NativeDwg.kt` (외부 함수 제거)
- Modify: `app/src/main/cpp/dwg_serialize.c` (resolve_layer_idx 캐시화)

### Steps

- [ ] **11-1. Task 10에서 수동 검증이 통과되었음을 명시적으로 확인**

이 Task를 실행하기 전 반드시 Task 10-4의 모든 체크 항목이 통과해야 함. 미통과 시 STOP.

- [ ] **11-2. 구 DXF 파일들 삭제**

```bash
git rm app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfParser.kt \
       app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfReader.kt \
       app/src/main/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetector.kt \
       app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfParserTest.kt \
       app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfReaderTest.kt \
       app/src/test/java/io/github/june690602_blip/cleancad/parser/DxfCharsetDetectorTest.kt
```

- [ ] **11-3. `nativeDwgToDxf` 제거**

`dwgjni.c`에서 `Java_io_github_june690602_1blip_cleancad_NativeDwg_nativeDwgToDxf` 함수 전체 삭제. `#include "out_dxf.h"` 도 제거.

`NativeDwg.kt`에서 다음 라인 제거:
```kotlin
    external fun nativeDwgToDxf(inPath: String, outPath: String): Int
```

- [ ] **11-4. `resolve_layer_idx` hash 캐시 개선**

`dwg_serialize.c`에 정적 캐시 추가:
```c
/* 직렬화 한 번에 유효한 레이어 이름→인덱스 매핑. 메인 함수에서 빌드/해제. */
typedef struct {
    char    *name;      /* malloc된 UTF-8 */
    int32_t  index;
} LayerCacheEntry;

typedef struct {
    LayerCacheEntry *entries;
    int              count;
} LayerCache;

static LayerCache g_layer_cache = {NULL, 0};

static void layer_cache_build(const Dwg_Data *dwg) {
    BITCODE_BL n = dwg_get_layer_count((Dwg_Data *)dwg);
    Dwg_Object_LAYER **arr = dwg_get_layers((Dwg_Data *)dwg);
    if (!arr) { g_layer_cache.entries = NULL; g_layer_cache.count = 0; return; }
    g_layer_cache.entries = (LayerCacheEntry *)calloc(n, sizeof(LayerCacheEntry));
    g_layer_cache.count = (int)n;
    for (BITCODE_BL i = 0; i < n; ++i) {
        if (!arr[i] || !arr[i]->name) {
            g_layer_cache.entries[i].name = NULL;
            g_layer_cache.entries[i].index = -1;
            continue;
        }
        g_layer_cache.entries[i].name = tv_to_utf8(dwg, arr[i]->name);
        g_layer_cache.entries[i].index = (int32_t)i;
    }
    free(arr);
}

static void layer_cache_free(void) {
    if (!g_layer_cache.entries) return;
    for (int i = 0; i < g_layer_cache.count; ++i) free(g_layer_cache.entries[i].name);
    free(g_layer_cache.entries);
    g_layer_cache.entries = NULL;
    g_layer_cache.count = 0;
}

/* O(N) but with cached names — 더 빠름. 추후 hash table로 개선 가능. */
static int32_t resolve_layer_idx_cached(const Dwg_Data *dwg, const Dwg_Object *obj) {
    Dwg_Object_Entity *ent = obj->tio.entity;
    if (!ent || !ent->layer || !ent->layer->obj) return -1;
    Dwg_Object_LAYER *lay = ent->layer->obj->tio.object->tio.LAYER;
    if (!lay || !lay->name) return -1;
    char *target = tv_to_utf8(dwg, lay->name);
    if (!target) return -1;
    int32_t result = -1;
    for (int i = 0; i < g_layer_cache.count; ++i) {
        if (g_layer_cache.entries[i].name &&
            strcmp(g_layer_cache.entries[i].name, target) == 0) {
            result = g_layer_cache.entries[i].index;
            break;
        }
    }
    free(target);
    return result;
}
```

기존 `resolve_layer_idx`를 삭제하고 호출부를 `resolve_layer_idx_cached`로 교체.

`dwgb_serialize` 시작 부분에 `layer_cache_build(dwg);` 추가, 끝에 `layer_cache_free();` 추가.

- [ ] **11-5. 빌드 + 테스트**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -5
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL. 단위 테스트 수: 80 − (5+7+24+2+1+1+1+2+1+2)=80 − ~ Phase 7+이전 모든 DxfParser/Reader/CharsetDetector 테스트. 새로 추가된 native 테스트가 21개(5+10+...) 가 아니라 21개의 신규 (Task 1~7), 기존 38개 중 DXF 관련은 모두 사라짐.

실제 카운트 확인:
```bash
find app/build/test-results/testDebugUnitTest -name "*.xml" | xargs grep -c "testcase"
```
Expected:
- NativeDecoderTest: 약 17 (Task 1~6 누적)
- EntityColorDecodingTest: 3
- CoordTransformTest: 6
- AciColorTest: 10
- ExampleUnitTest: 1
- 합: ~37 (또는 그 이상; native 추가분에 따름)

- [ ] **11-6. 에뮬레이터 회귀 재확인**

```bash
./gradlew :app:installDebug
```
같은 두 도면을 다시 열어 회귀 없음 확인.

- [ ] **11-7. 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(phase8): 구 DXF 파이프라인 제거 + 레이어 캐시 최적화

- DxfParser/DxfReader/DxfCharsetDetector + 테스트 삭제
- nativeDwgToDxf JNI 진입점 제거
- resolve_layer_idx — UTF-8 이름 캐시화로 O(N²) → O(N)
- 단위 테스트는 native 디코더로 완전 대체

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: 마무리 — HATCH arc edges 보강 + CLAUDE.md + 출시 준비

**근거:** 잔여 정밀도 항목과 출시 메타데이터.

### Files
- Modify: `app/src/main/cpp/dwg_serialize.c` (HATCH arc edges)
- Modify: `CLAUDE.md`
- Modify: `app/src/main/java/io/github/june690602_blip/cleancad/ui/AboutActivity.kt` (LICENSE 정보)

### Steps

- [ ] **12-1. HATCH arc/ellipse edges — polyline 근사 추가**

`write_hatch` 함수 내 edge boundary 루프에 케이스 추가:
```c
                if (seg->curve_type == 2) {  /* arc edge */
                    /* 원호를 32개 line segment로 근사하여 정점 수집 */
                    double cx = seg->center.x, cy = seg->center.y;
                    double r = seg->radius;
                    double sa = seg->start_angle;
                    double ea = seg->end_angle;
                    int ccw = seg->is_ccw ? 1 : 0;
                    if (!ccw) { double t = sa; sa = ea; ea = t; }
                    double range = ea - sa;
                    if (range <= 0) range += 6.283185307179586;
                    int steps = 32;
                    for (int k = 0; k <= steps; ++k) {
                        double t = sa + range * ((double)k / steps);
                        w_f64(w, cx + r * cos(t));
                        w_f64(w, cy + r * sin(t));
                        nv++;
                    }
                }
                /* curve_type 3 (ellipse), 4 (spline)은 Phase 9에서 추가 */
```

`#include <math.h>` 추가.

빌드 검증.

- [ ] **12-2. AboutActivity 갱신 (LibreDWG 사용 명시 강화)**

`AboutActivity.kt` 확인 — 이미 LibreDWG 저작권을 표시하고 있다면 추가 변경 없음. GPL v3 의무사항이므로 누락 없는지 확인.

- [ ] **12-3. 단위 테스트 + 빌드 최종**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -5
./gradlew :app:assembleDebug 2>&1 | tail -5
./gradlew :app:assembleRelease 2>&1 | tail -5
```
Expected: 모두 BUILD SUCCESSFUL.

- [ ] **12-4. `CLAUDE.md` 업데이트**

`## Status (2026-05-27)` 섹션을 다음으로 교체:
```markdown
## Status (2026-05-27)
- Phase 0–8 전부 완료. 단위 테스트 ~40개 통과 (native decoder 기반). 실제 DWG 2종에서 한글·색상·전체 엔티티 정상 표시 확인.
- **Phase 8 완료**: LibreDWG 바이너리 API 직접 사용 (DXF 중간단계 완전 제거).
  - JNI에서 `Dwg_Data` 구조체를 직접 순회 → 자체 정의 바이너리 프로토콜로 Kotlin에 전달
  - `bit_TV_to_utf8()` 기반 인코딩 보존 — 한글 100% 정상
  - 엔티티별 색상 (BYLAYER/BYBLOCK/ACI/RGB 24-bit) 완전 반영
  - INSERT 블록 C 측에서 재귀 전개 (depth 5, MAX_ENTITIES 100K)
  - HATCH polyline + line + arc edges 모두 지원
  - 구 DXF 파이프라인(DxfParser/Reader/CharsetDetector) 완전 제거
  플랜: `docs/superpowers/plans/2026-05-27-phase8-libredwg-native.md`
- **Phase 7 완료** (`eb39838`): DXF 기반 렌더링 품질 시도 — 한글/색상 부분만 해결됐고 누락 엔티티는 미해결 → Phase 8 채택 배경.
- **Phase 6 완료**: Play Store 출시 준비 (텍스트 이스케이프, 컬링, 릴리즈 서명).
- **Phase 5 완료**: 다크모드, 설정, 최근파일, Share/View Intent.
- **Next = Play Store 출시**: keystore 생성 후 `assembleRelease` → Google Play Console 업로드.
```

- [ ] **12-5. 커밋**

```bash
git add app/src/main/cpp/dwg_serialize.c CLAUDE.md \
        app/src/main/java/io/github/june690602_blip/cleancad/ui/AboutActivity.kt 2>/dev/null

git commit -m "$(cat <<'EOF'
feat(phase8): HATCH arc edges 보강 + CLAUDE.md/About 갱신

- HATCH arc edge boundary — 32 segment polyline 근사
- CLAUDE.md Status: Phase 8 완료 명시
- About: GPL v3 + LibreDWG 저작권 (확인)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **12-6. 최종 git log**

```bash
git log --oneline -15
```
Expected: Phase 8 커밋 12개 (Task 1~12 각각), 모두 main 브랜치.

---

## 파일 변경 요약

| Task | 신규 파일 | 수정 파일 | 삭제 파일 |
|------|-----------|-----------|-----------|
| 1 | `NativeProtocol.kt`, `NativeDecoder.kt`, `NativeDecoderTest.kt` | — | — |
| 2 | — | `NativeDecoder.kt`, `NativeDecoderTest.kt` | — |
| 3 | — | (Task 2와 동일) | — |
| 4 | — | (Task 2와 동일) | — |
| 5 | — | (Task 2와 동일) | — |
| 6 | — | (Task 2와 동일) | — |
| 7 | `EntityColor.kt`, `EntityColorDecodingTest.kt` | `Drawing.kt`, `NativeDecoder.kt`, `EntityRenderer.kt`, `DrawingView.kt` | — |
| 8 | `dwg_serialize.h`, `dwg_serialize.c` | `dwgjni.c`, `CMakeLists.txt`, `NativeDwg.kt` | — |
| 9 | — | `dwg_serialize.c` | — |
| 10 | — | `NativeDwg.kt`, `DrawingViewModel.kt` | — |
| 11 | — | `dwgjni.c`, `NativeDwg.kt`, `dwg_serialize.c` | 6개 DXF 파일 |
| 12 | — | `dwg_serialize.c`, `CLAUDE.md`, `AboutActivity.kt` | — |

**누적 LOC 변동:** ~+1500 lines C, ~+500 lines Kotlin native/, ~-1000 lines DXF parser (Task 11 삭제분).

---

## 위험과 완화

| 위험 | 완화 |
|------|------|
| LibreDWG 0.13.4 API 시그니처가 우리가 가정한 것과 다름 | Task 8에서 첫 빌드부터 컴파일 에러로 즉시 발견. `dwg_api.h`/`dwg.h` 참조. 필요 시 `examples/load_dwg.c` 패턴 추종. |
| `bit_TV_to_utf8()`이 R2007+ 파일에서 잘못 변환 | dwg.header.from_version을 정확히 전달. 안 되면 dwg_codepage_dxfstr_to_utf8()로 폴백. |
| INSERT 재귀 전개 시 무한 루프 (cyclic block ref) | depth ≥ 5 차단 + MAX_ENTITIES 100K 차단으로 OOM·ANR 방지. |
| HATCH `polyline_paths` 필드명이 LibreDWG 버전에 따라 다름 | 빌드 에러 시 `dwg.h`에서 `Dwg_HATCH_Path` 정의 확인하고 fix. 대안: `paths[].seg`/`paths[].polyline_paths` 둘 다 시도. |
| `BLOCK_HEADER->entities` 접근 시 NULL | NULL 가드 + `bh->first_entity`/`last_entity` 폴백. |
| native 측 메모리 leak (UTF-8 char* free 누락) | 모든 `tv_to_utf8()` 호출 직후 free. AddressSanitizer를 dev 빌드에 활성화하여 검출. |
| 100K 엔티티 도면에서 ByteArray가 너무 큼 (>50MB) | Direct ByteBuffer로 전환 시 추가 비용 없음. 메모리는 한 번만 잡고 즉시 해제. |
| 색상 RGB가 R2004 이전 파일에서 항상 0 | 의도된 동작 — RGB는 R2004+ true color 기능. fallback이 ACI 인덱스. |
| EntityRenderer가 identity map (DxfEntity 객체 참조) 기반이라 entities를 복제하면 색상 누락 | DrawingView/Renderer가 setDrawing 후 entities를 변형하지 않도록 보장. 변형이 필요하면 Drawing 전체를 재생성하고 setDrawing 재호출. |

---

## 이 파일을 새 세션에서 여는 방법

```
이 파일을 읽고 Phase 8을 시작해줘:
docs/superpowers/plans/2026-05-27-phase8-libredwg-native.md
```

CLAUDE.md의 `## Status` 섹션도 업데이트하는 것을 잊지 말 것 (Task 12-4).
