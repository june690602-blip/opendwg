package io.github.june690602_blip.cleancad.render

/**
 * 다크모드에서 너무 어두워서 안 보이는 entity 색상을 RGB invert.
 * 순수 함수 모음. Android Color API를 안 거치므로 unit test 가능.
 *
 * 임계값 정책 (Phase 9.2):
 *   - 배경 luminance < 0.5 → dark background
 *   - dark bg + color luminance < 0.25 → RGB invert (alpha 보존)
 *   - 0.25 임계값으로 빨강(luminance ≈ 0.299), 파랑(0.114는 invert),
 *     녹색(0.587)을 적절히 구분. 검정/매우 어두운 회색만 invert.
 *
 *   *주의*: 파랑(0.114)은 임계값 미만이라 invert됨. AutoCAD ACI 5번
 *   순수 blue(0,0,255)는 다크 배경에서도 거의 안 보이므로 invert가
 *   사실상 가시성에 도움. 사용자 피드백 보고 조정.
 */
object ColorInvert {

    private const val DARK_BG_THRESHOLD = 0.5
    private const val INVERT_THRESHOLD = 0.25

    /** ITU-R BT.601 weighted luminance ∈ [0.0, 1.0]. Alpha 무시. */
    fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    fun isDarkBackground(argb: Int): Boolean = luminance(argb) < DARK_BG_THRESHOLD

    /** dark 배경에서만, 색상 luminance < INVERT_THRESHOLD 인 경우 RGB invert. */
    fun maybeInvert(color: Int, isDarkBg: Boolean): Int {
        if (!isDarkBg) return color
        if (luminance(color) >= INVERT_THRESHOLD) return color
        val a = (color shr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (a shl 24) or ((255 - r) shl 16) or ((255 - g) shl 8) or (255 - b)
    }
}
