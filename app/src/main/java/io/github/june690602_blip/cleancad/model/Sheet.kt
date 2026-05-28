package io.github.june690602_blip.cleancad.model

data class Sheet(
    val id: Int,
    val name: String? = null,
    val bbox: BoundingBox
)
