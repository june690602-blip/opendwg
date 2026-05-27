package io.github.june690602_blip.cleancad.render

import android.graphics.*
import io.github.june690602_blip.cleancad.model.*
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.model.EntityColor
import kotlin.math.*

class EntityRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private var bgColor: Int = Color.WHITE
    private var defaultLineColor: Int = Color.BLACK
    private var layerColorMap: Map<String, Int> = emptyMap()
    private var entityColorByIdentity: Map<DxfEntity, Int> = emptyMap()

    fun setColors(bgColor: Int, lineColor: Int) {
        this.bgColor = bgColor
        this.defaultLineColor = lineColor
        linePaint.color = lineColor
        textPaint.color = lineColor
    }

    fun setLayers(layers: List<Layer>) {
        layerColorMap = layers.associate { layer ->
            layer.name to AciColor.toArgb(layer.colorIndex, fallback = defaultLineColor)
        }
    }

    /** Phase 8: Drawing 전체를 받아 엔티티별 색상까지 lookup 가능하게 한다. */
    fun setDrawing(drawing: Drawing) {
        setLayers(drawing.layers)
        if (drawing.entityColors.isEmpty()) {
            entityColorByIdentity = emptyMap()
            return
        }
        val map = HashMap<DxfEntity, Int>(drawing.entities.size)
        for (i in drawing.entities.indices) {
            val ec = drawing.entityColors[i]
            val color: Int = when {
                ec.hasRgb -> 0xFF000000.toInt() or ec.rgb
                ec.isByLayer || ec.isByBlock ->
                    layerColorMap[drawing.entities[i].layer] ?: defaultLineColor
                else -> AciColor.toArgb(
                    ec.colorIndex,
                    fallback = layerColorMap[drawing.entities[i].layer] ?: defaultLineColor
                )
            }
            map[drawing.entities[i]] = color
        }
        entityColorByIdentity = map
    }

    private fun colorFor(entity: DxfEntity): Int =
        entityColorByIdentity[entity]
            ?: layerColorMap[entity.layer]
            ?: defaultLineColor

    /** Drawing의 모든 엔티티를 렌더 순서대로 Canvas에 그린다.
     *  컬링 전략:
     *   - viewport 밖 엔티티 스킵 (worldBounds vs viewport)
     *   - 화면상 너무 작아서 점만도 안 보이는 엔티티 스킵 (< 1px)
     *   - 텍스트는 base 글자 높이가 4px 미만이면 스킵 (zoom out 상태에서 까만 덩어리 방지) */
    fun drawAll(
        entities: List<DxfEntity>,
        canvas: Canvas,
        matrix: Matrix,
        viewport: BoundingBox? = null
    ) {
        val scale = CoordTransform.currentScale(matrix)
        entities.forEach { entity ->
            if (entity !is DxfText && entity !is DxfMText) {
                val bounds = entity.worldBounds()
                if (bounds != null) {
                    if (viewport != null && !bounds.intersects(viewport)) return@forEach
                    // 화면상 1px 미만이면 스킵
                    val pixW = bounds.width * scale
                    val pixH = bounds.height * scale
                    if (pixW < 1.0 && pixH < 1.0) return@forEach
                }
                draw(entity, canvas, matrix)
            }
        }
        entities.forEach { entity ->
            when (entity) {
                is DxfText -> {
                    val pixSize = entity.height * scale
                    if (pixSize >= MIN_TEXT_BASE_PIXELS) draw(entity, canvas, matrix)
                }
                is DxfMText -> {
                    val pixSize = entity.height * scale
                    if (pixSize >= MIN_TEXT_BASE_PIXELS) draw(entity, canvas, matrix)
                }
                else -> { /* already drawn in first pass */ }
            }
        }
    }

    private companion object {
        /** zoom out 상태에서 글자 자체가 이 픽셀 수보다 작아지면 렌더 자체를 스킵.
         *  까만 글자 덩어리(수많은 작은 텍스트 mass) 방지. */
        const val MIN_TEXT_BASE_PIXELS: Double = 4.0

        /** HATCH 솔리드 채우기 알파 — 0x40 = 25% 불투명도 (반투명).
         *  패턴 hatch가 미지원이라 검정 솔리드 덩어리 방지 목적. */
        const val HATCH_FILL_ALPHA_MASK: Int = 0x40000000
    }

    private fun draw(entity: DxfEntity, canvas: Canvas, matrix: Matrix) {
        linePaint.color = colorFor(entity)
        textPaint.color = colorFor(entity)
        when (entity) {
            is DxfLine       -> drawLine(entity, canvas, matrix)
            is DxfCircle     -> drawCircle(entity, canvas, matrix)
            is DxfArc        -> drawArc(entity, canvas, matrix)
            is DxfLwPolyline -> drawLwPolyline(entity, canvas, matrix)
            is DxfPolyline   -> drawPolyline(entity, canvas, matrix)
            is Dxf3DFace     -> draw3dFace(entity, canvas, matrix)
            is DxfSolid      -> drawSolid(entity, canvas, matrix)
            is DxfEllipse    -> drawEllipse(entity, canvas, matrix)
            is DxfSpline     -> drawSpline(entity, canvas, matrix)
            is DxfText       -> drawText(entity, canvas, matrix)
            is DxfMText      -> drawMText(entity, canvas, matrix)
            is DxfDimension  -> drawDimension(entity, canvas, matrix)
            is DxfLeader     -> drawLeader(entity, canvas, matrix)
            is DxfHatch      -> drawHatch(entity, canvas, matrix)
            is DxfInsert, is DxfUnknown -> { /* skip */ }
        }
    }

    private fun drawLine(e: DxfLine, canvas: Canvas, matrix: Matrix) {
        val s = CoordTransform.worldToScreen(e.start, matrix)
        val end = CoordTransform.worldToScreen(e.end, matrix)
        canvas.drawLine(s.x, s.y, end.x, end.y, linePaint)
    }

    private fun drawCircle(e: DxfCircle, canvas: Canvas, matrix: Matrix) {
        val c = CoordTransform.worldToScreen(e.center, matrix)
        val r = (e.radius * CoordTransform.currentScale(matrix)).toFloat()
        canvas.drawCircle(c.x, c.y, r, linePaint)
    }

    private fun drawArc(e: DxfArc, canvas: Canvas, matrix: Matrix) {
        val c = CoordTransform.worldToScreen(e.center, matrix)
        val r = (e.radius * CoordTransform.currentScale(matrix)).toFloat()
        val oval = RectF(c.x - r, c.y - r, c.x + r, c.y + r)
        var sweep = (e.endAngleDeg - e.startAngleDeg).toFloat()
        if (sweep <= 0f) sweep += 360f
        val startAngle = (-e.endAngleDeg).toFloat()
        canvas.drawArc(oval, startAngle, sweep, false, linePaint)
    }

    private fun drawLwPolyline(e: DxfLwPolyline, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        if (e.closed) path.close()
        canvas.drawPath(path, linePaint)
    }

    private fun drawPolyline(e: DxfPolyline, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        if (e.closed) path.close()
        canvas.drawPath(path, linePaint)
    }

    private fun draw3dFace(e: Dxf3DFace, canvas: Canvas, matrix: Matrix) {
        val p1 = CoordTransform.worldToScreen(e.corner1, matrix)
        val p2 = CoordTransform.worldToScreen(e.corner2, matrix)
        val p3 = CoordTransform.worldToScreen(e.corner3, matrix)
        val p4 = CoordTransform.worldToScreen(e.corner4, matrix)
        val path = Path()
        path.moveTo(p1.x, p1.y)
        path.lineTo(p2.x, p2.y)
        path.lineTo(p3.x, p3.y)
        if (e.corner4 != e.corner3) path.lineTo(p4.x, p4.y)
        path.close()
        canvas.drawPath(path, linePaint)
    }

    private fun drawSolid(e: DxfSolid, canvas: Canvas, matrix: Matrix) {
        // SOLID vertex order: 1-2-4-3 (legacy)
        val p1 = CoordTransform.worldToScreen(e.corner1, matrix)
        val p2 = CoordTransform.worldToScreen(e.corner2, matrix)
        val p3 = CoordTransform.worldToScreen(e.corner3, matrix)
        val p4 = CoordTransform.worldToScreen(e.corner4, matrix)
        val path = Path()
        path.moveTo(p1.x, p1.y)
        path.lineTo(p2.x, p2.y)
        path.lineTo(p4.x, p4.y)   // 4 first (legacy order)
        if (e.corner3 != e.corner4) path.lineTo(p3.x, p3.y)
        path.close()
        fillPaint.color = linePaint.color
        canvas.drawPath(path, fillPaint)
    }

    private fun drawHatch(e: DxfHatch, canvas: Canvas, matrix: Matrix) {
        if (e.paths.isEmpty()) return
        val path = Path()
        for (boundary in e.paths) {
            if (boundary.size < 2) continue
            val first = CoordTransform.worldToScreen(boundary[0], matrix)
            path.moveTo(first.x, first.y)
            for (i in 1 until boundary.size) {
                val pt = CoordTransform.worldToScreen(boundary[i], matrix)
                path.lineTo(pt.x, pt.y)
            }
            path.close()
        }
        if (e.isSolid) {
            // 패턴 hatch 미지원 → 솔리드도 25% 알파로 반투명 채움
            // (검은 덩어리로 다른 도형 가리는 문제 방지)
            val baseColor = linePaint.color
            fillPaint.color = (baseColor and 0x00FFFFFF) or HATCH_FILL_ALPHA_MASK
            canvas.drawPath(path, fillPaint)
        }
        // 경계는 항상 그림 (솔리드든 패턴이든 boundary 시각화)
        canvas.drawPath(path, linePaint)
    }

    private fun drawEllipse(e: DxfEllipse, canvas: Canvas, matrix: Matrix) {
        val majorLen = sqrt(e.majorAxis.x * e.majorAxis.x + e.majorAxis.y * e.majorAxis.y)
        val minorLen = majorLen * e.minorRatio
        val rot = atan2(e.majorAxis.y, e.majorAxis.x)
        val steps = 72
        val path = Path()
        val paramRange = e.endParam - e.startParam
        for (i in 0..steps) {
            val t = e.startParam + i.toDouble() / steps * paramRange
            val wx = e.center.x + majorLen * cos(t) * cos(rot) - minorLen * sin(t) * sin(rot)
            val wy = e.center.y + majorLen * cos(t) * sin(rot) + minorLen * sin(t) * cos(rot)
            val pt = CoordTransform.worldToScreen(wx, wy, matrix)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawSpline(e: DxfSpline, canvas: Canvas, matrix: Matrix) {
        if (e.controlPoints.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.controlPoints[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.controlPoints.size) {
            val pt = CoordTransform.worldToScreen(e.controlPoints[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawText(e: DxfText, canvas: Canvas, matrix: Matrix) {
        val pt = CoordTransform.worldToScreen(e.insertionPoint, matrix)
        textPaint.textSize = (e.height * CoordTransform.currentScale(matrix)).toFloat()
            .coerceAtLeast(8f)
        canvas.save()
        canvas.translate(pt.x, pt.y)
        canvas.rotate(-e.rotationDeg.toFloat())
        canvas.drawText(e.text, 0f, 0f, textPaint)
        canvas.restore()
    }

    private fun drawMText(e: DxfMText, canvas: Canvas, matrix: Matrix) {
        val pt = CoordTransform.worldToScreen(e.insertionPoint, matrix)
        textPaint.textSize = (e.height * CoordTransform.currentScale(matrix)).toFloat()
            .coerceAtLeast(8f)
        val stripped = e.text
            .replace(Regex("\\\\[A-Za-z][^;]*;"), "")
            .replace(Regex("\\\\[^A-Za-z]"), "")
            .replace(Regex("[{}]"), "")
            .replace("\\P", "\n")
        canvas.save()
        canvas.translate(pt.x, pt.y)
        canvas.rotate(-e.rotationDeg.toFloat())
        canvas.drawText(stripped, 0f, 0f, textPaint)
        canvas.restore()
    }

    private fun drawDimension(e: DxfDimension, canvas: Canvas, matrix: Matrix) {
        val dp = CoordTransform.worldToScreen(e.definitionPoint, matrix)
        val tp = CoordTransform.worldToScreen(e.textMidPoint, matrix)
        canvas.drawLine(dp.x, dp.y, tp.x, tp.y, linePaint)
        if (e.textOverride.isNotBlank()) {
            textPaint.textSize = (2.5 * CoordTransform.currentScale(matrix)).toFloat().coerceAtLeast(8f)
            canvas.drawText(e.textOverride, tp.x, tp.y, textPaint)
        }
    }

    private fun drawLeader(e: DxfLeader, canvas: Canvas, matrix: Matrix) {
        if (e.vertices.size < 2) return
        val path = Path()
        val first = CoordTransform.worldToScreen(e.vertices[0], matrix)
        path.moveTo(first.x, first.y)
        for (i in 1 until e.vertices.size) {
            val pt = CoordTransform.worldToScreen(e.vertices[i], matrix)
            path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, linePaint)
    }
}
