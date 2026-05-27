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
}
