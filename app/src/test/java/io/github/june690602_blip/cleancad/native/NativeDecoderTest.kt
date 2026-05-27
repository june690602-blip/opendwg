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

    private fun buildBuffer(
        layers: List<Triple<String, Short, Int>> = emptyList(),  // name, colorIdx, rgb
        entities: List<ByteArray> = emptyList()
    ): ByteArray {
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
        val entityBytes = ByteArray(b.position())
        System.arraycopy(b.array(), 0, entityBytes, 0, entityBytes.size)

        val drawing = NativeDecoder.decode(buildBuffer(entities = listOf(entityBytes)))
        val p = drawing.entities[0] as io.github.june690602_blip.cleancad.model.DxfPolyline
        assertFalse(p.closed)
        assertEquals(2, p.vertices.size)
    }

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
}
