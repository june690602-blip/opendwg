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
