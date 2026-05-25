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
