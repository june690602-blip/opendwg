package io.github.june690602_blip.cleancad.native

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
        val out = ByteArray(total.position())
        System.arraycopy(total.array(), 0, out, 0, out.size)
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
