package io.github.june690602_blip.cleancad.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorInvertTest {

    private val BLACK = 0xFF000000.toInt()
    private val WHITE = 0xFFFFFFFFF.toInt() and 0xFFFFFFFF.toInt()
    private val RED   = 0xFFFF0000.toInt()
    private val GREEN = 0xFF00FF00.toInt()
    private val BLUE  = 0xFF0000FF.toInt()
    private val DARK_BG  = 0xFF1C1C1E.toInt()
    private val LIGHT_BG = 0xFFFFFFFF.toInt()

    @Test
    fun luminance_blackIsZero() {
        assertEquals(0.0, ColorInvert.luminance(BLACK), 1e-6)
    }

    @Test
    fun luminance_whiteIsOne() {
        assertEquals(1.0, ColorInvert.luminance(WHITE), 1e-6)
    }

    @Test
    fun luminance_redIsAround_0_299() {
        assertEquals(0.299, ColorInvert.luminance(RED), 1e-3)
    }

    @Test
    fun luminance_greenIsAround_0_587() {
        assertEquals(0.587, ColorInvert.luminance(GREEN), 1e-3)
    }

    @Test
    fun luminance_blueIsAround_0_114() {
        assertEquals(0.114, ColorInvert.luminance(BLUE), 1e-3)
    }

    @Test
    fun isDarkBackground_trueForDark() {
        assertTrue(ColorInvert.isDarkBackground(DARK_BG))
    }

    @Test
    fun isDarkBackground_falseForWhite() {
        assertFalse(ColorInvert.isDarkBackground(LIGHT_BG))
    }

    @Test
    fun maybeInvert_onLightBg_neverInverts() {
        assertEquals(BLACK, ColorInvert.maybeInvert(BLACK, isDarkBg = false))
        assertEquals(RED, ColorInvert.maybeInvert(RED, isDarkBg = false))
        assertEquals(WHITE, ColorInvert.maybeInvert(WHITE, isDarkBg = false))
    }

    @Test
    fun maybeInvert_onDarkBg_invertsBlackToWhite() {
        // Black has luminance 0 (< threshold) → invert to white. Alpha preserved.
        assertEquals(0xFFFFFFFF.toInt(), ColorInvert.maybeInvert(BLACK, isDarkBg = true))
    }

    @Test
    fun maybeInvert_onDarkBg_keepsBrightColors() {
        // White luminance 1.0, well above threshold → unchanged.
        assertEquals(WHITE, ColorInvert.maybeInvert(WHITE, isDarkBg = true))
        // Green luminance ~0.587 → unchanged.
        assertEquals(GREEN, ColorInvert.maybeInvert(GREEN, isDarkBg = true))
    }

    @Test
    fun maybeInvert_preservesAlphaChannel() {
        val translucentBlack = 0x80000000.toInt()
        val result = ColorInvert.maybeInvert(translucentBlack, isDarkBg = true)
        // High byte (alpha) preserved
        assertEquals(0x80, (result shr 24) and 0xFF)
        // RGB inverted
        assertEquals(0xFF, (result shr 16) and 0xFF)
        assertEquals(0xFF, (result shr 8) and 0xFF)
        assertEquals(0xFF, result and 0xFF)
    }

    @Test
    fun maybeInvert_onDarkBg_keepsRedAtThreshold() {
        // Red luminance 0.299 — at boundary. Threshold should keep "primary" red unchanged.
        // We pick threshold = 0.25 so red (0.299) passes through.
        assertEquals(RED, ColorInvert.maybeInvert(RED, isDarkBg = true))
    }

    @Test
    fun maybeInvert_onDarkBg_invertsVeryDarkGray() {
        // Very dark gray (16,16,16) → luminance ~0.063 → invert.
        val veryDark = 0xFF101010.toInt()
        val result = ColorInvert.maybeInvert(veryDark, isDarkBg = true)
        assertEquals(0xFFEFEFEF.toInt(), result)
    }
}
