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
