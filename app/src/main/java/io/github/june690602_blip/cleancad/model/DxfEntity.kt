package io.github.june690602_blip.cleancad.model

data class Vec2(val x: Double, val y: Double)

sealed class DxfEntity {
    abstract val layer: String
}

data class DxfLine(
    override val layer: String,
    val start: Vec2,
    val end: Vec2
) : DxfEntity()

data class DxfCircle(
    override val layer: String,
    val center: Vec2,
    val radius: Double
) : DxfEntity()

data class DxfArc(
    override val layer: String,
    val center: Vec2,
    val radius: Double,
    val startAngleDeg: Double,
    val endAngleDeg: Double
) : DxfEntity()

data class DxfLwPolyline(
    override val layer: String,
    val vertices: List<Vec2>,
    val closed: Boolean
) : DxfEntity()

data class DxfPolyline(
    override val layer: String,
    val vertices: List<Vec2>,
    val closed: Boolean
) : DxfEntity()

data class Dxf3DFace(
    override val layer: String,
    val corner1: Vec2,
    val corner2: Vec2,
    val corner3: Vec2,
    val corner4: Vec2
) : DxfEntity()

data class DxfSolid(
    override val layer: String,
    val corner1: Vec2,
    val corner2: Vec2,
    val corner3: Vec2,
    val corner4: Vec2
) : DxfEntity()

data class DxfEllipse(
    override val layer: String,
    val center: Vec2,
    val majorAxis: Vec2,
    val minorRatio: Double,
    val startParam: Double,
    val endParam: Double
) : DxfEntity()

data class DxfSpline(
    override val layer: String,
    val degree: Int,
    val controlPoints: List<Vec2>
) : DxfEntity()

data class DxfText(
    override val layer: String,
    val insertionPoint: Vec2,
    val height: Double,
    val text: String,
    val rotationDeg: Double = 0.0
) : DxfEntity()

data class DxfMText(
    override val layer: String,
    val insertionPoint: Vec2,
    val height: Double,
    val text: String,
    val rotationDeg: Double = 0.0
) : DxfEntity()

data class DxfInsert(
    override val layer: String,
    val blockName: String,
    val insertionPoint: Vec2,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val rotationDeg: Double = 0.0
) : DxfEntity()

data class DxfDimension(
    override val layer: String,
    val definitionPoint: Vec2,
    val textMidPoint: Vec2,
    val dimType: Int,
    val textOverride: String = ""
) : DxfEntity()

data class DxfHatch(
    override val layer: String,
    val isSolid: Boolean,
    val paths: List<List<Vec2>>
) : DxfEntity()

data class DxfLeader(
    override val layer: String,
    val vertices: List<Vec2>
) : DxfEntity()

data class DxfUnknown(
    override val layer: String,
    val type: String
) : DxfEntity()
