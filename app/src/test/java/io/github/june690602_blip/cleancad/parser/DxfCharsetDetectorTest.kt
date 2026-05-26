package io.github.june690602_blip.cleancad.parser

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

class DxfCharsetDetectorTest {

    private fun makeHeader(codepageValue: String): ByteArray {
        val text = """  0
SECTION
  2
HEADER
  9
${'$'}DWGCODEPAGE
  3
$codepageValue
  0
ENDSEC
"""
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun detect_ansi949_returnsMs949() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_949")))
        assertEquals(Charset.forName("MS949"), charset)
    }

    @Test
    fun detect_ansi1252_returnsWindows1252() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_1252")))
        assertEquals(Charset.forName("windows-1252"), charset)
    }

    @Test
    fun detect_ansi932_returnsShiftJis() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_932")))
        assertEquals(Charset.forName("Shift_JIS"), charset)
    }

    @Test
    fun detect_noCodepage_returnsUtf8() {
        val text = "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n"
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(text.toByteArray()))
        assertEquals(Charsets.UTF_8, charset)
    }

    @Test
    fun detect_unknownCodepage_returnsUtf8() {
        val charset = DxfCharsetDetector.detect(ByteArrayInputStream(makeHeader("ANSI_99999")))
        assertEquals(Charsets.UTF_8, charset)
    }
}
