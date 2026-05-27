package io.github.june690602_blip.cleancad.model

data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

data class Layer(
    val name: String,
    val colorIndex: Int = 7
)

data class Drawing(
    val entities: List<DxfEntity>,
    val layers: List<Layer>,
    val extents: BoundingBox?,
    val displayExtents: BoundingBox?,
    val entityColors: List<EntityColor> = emptyList()  // entities와 1:1, 비어있으면 모두 BYLAYER로 간주
)
