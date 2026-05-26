package io.github.june690602_blip.cleancad.render

import org.junit.Assert.assertEquals
import org.junit.Test

class AciColorTest {

    @Test
    fun toArgb_index1_isRed() {
        assertEquals(0xFFFF0000.toInt(), AciColor.toArgb(1, fallback = 0))
    }

    @Test
    fun toArgb_index2_isYellow() {
        assertEquals(0xFFFFFF00.toInt(), AciColor.toArgb(2, fallback = 0))
    }

    @Test
    fun toArgb_index3_isGreen() {
        assertEquals(0xFF00FF00.toInt(), AciColor.toArgb(3, fallback = 0))
    }

    @Test
    fun toArgb_index5_isBlue() {
        assertEquals(0xFF0000FF.toInt(), AciColor.toArgb(5, fallback = 0))
    }

    @Test
    fun toArgb_index7_returnsFallback() {
        val fallback = 0xFF112233.toInt()
        assertEquals(fallback, AciColor.toArgb(7, fallback = fallback))
    }

    @Test
    fun toArgb_index0_returnsFallback() {
        val fallback = 0xFF445566.toInt()
        assertEquals(fallback, AciColor.toArgb(0, fallback = fallback))
    }

    @Test
    fun toArgb_index256_returnsFallback() {
        val fallback = 0xFF778899.toInt()
        assertEquals(fallback, AciColor.toArgb(256, fallback = fallback))
    }

    @Test
    fun toArgb_outOfRange_returnsFallback() {
        val fallback = 0xFFAABBCC.toInt()
        assertEquals(fallback, AciColor.toArgb(999, fallback = fallback))
        assertEquals(fallback, AciColor.toArgb(-1, fallback = fallback))
    }

    @Test
    fun toArgb_index250_isDarkGray() {
        assertEquals(0xFF333333.toInt(), AciColor.toArgb(250, fallback = 0))
    }

    @Test
    fun toArgb_index255_isWhite() {
        assertEquals(0xFFFFFFFF.toInt(), AciColor.toArgb(255, fallback = 0))
    }
}
