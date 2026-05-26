package io.github.june690602_blip.cleancad.render

import io.github.june690602_blip.cleancad.model.*

fun DxfEntity.worldBounds(): BoundingBox? = when (this) {
    is DxfLine       -> BoundingBox(
        minOf(start.x, end.x), minOf(start.y, end.y),
        maxOf(start.x, end.x), maxOf(start.y, end.y)
    )
    is DxfCircle     -> BoundingBox(
        center.x - radius, center.y - radius,
        center.x + radius, center.y + radius
    )
    is DxfArc        -> BoundingBox(
        center.x - radius, center.y - radius,
        center.x + radius, center.y + radius
    )
    is DxfLwPolyline -> if (vertices.isEmpty()) null else BoundingBox(
        vertices.minOf { it.x }, vertices.minOf { it.y },
        vertices.maxOf { it.x }, vertices.maxOf { it.y }
    )
    is DxfPolyline   -> if (vertices.isEmpty()) null else BoundingBox(
        vertices.minOf { it.x }, vertices.minOf { it.y },
        vertices.maxOf { it.x }, vertices.maxOf { it.y }
    )
    is Dxf3DFace     -> BoundingBox(
        minOf(corner1.x, corner2.x, corner3.x, corner4.x),
        minOf(corner1.y, corner2.y, corner3.y, corner4.y),
        maxOf(corner1.x, corner2.x, corner3.x, corner4.x),
        maxOf(corner1.y, corner2.y, corner3.y, corner4.y)
    )
    else -> null
}

fun BoundingBox.intersects(other: BoundingBox): Boolean =
    minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY
