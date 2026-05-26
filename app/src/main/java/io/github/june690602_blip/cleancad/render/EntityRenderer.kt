package io.github.june690602_blip.cleancad.render

import android.graphics.*
import io.github.june690602_blip.cleancad.model.*
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

    /** bgColor is reserved for future fill-color support (e.g., HATCH in Phase 6). */
    @Suppress("UNUSED_PARAMETER")
    fun setColors(bgColor: Int, lineColor: Int) {
        linePaint.color = lineColor
        textPaint.color = lineColor
    }

    /** Drawing의 모든 엔티티를 렌더 순서대로 Canvas에 그린다. */
    fun drawAll(entities: List<DxfEntity>, canvas: Canvas, matrix: Matrix) {
        // 1단계: 선 계열 (텍스트 제외)
        entities.forEach { entity ->
            if (entity !is DxfText && entity !is DxfMText) draw(entity, canvas, matrix)
        }
        // 2단계: 텍스트 (맨 위)
        entities.forEach { entity ->
            if (entity is DxfText || entity is DxfMText) draw(entity, canvas, matrix)
        }
    }

    private fun draw(entity: DxfEntity, canvas: Canvas, matrix: Matrix) {
        when (entity) {
            is DxfLine       -> drawLine(entity, canvas, matrix)
            is DxfCircle     -> drawCircle(entity, canvas, matrix)
            is DxfArc        -> drawArc(entity, canvas, matrix)
            is DxfLwPolyline -> drawLwPolyline(entity, canvas, matrix)
            is DxfEllipse    -> drawEllipse(entity, canvas, matrix)
            is DxfSpline     -> drawSpline(entity, canvas, matrix)
            is DxfText       -> drawText(entity, canvas, matrix)
            is DxfMText      -> drawMText(entity, canvas, matrix)
            is DxfDimension  -> drawDimension(entity, canvas, matrix)
            is DxfLeader     -> drawLeader(entity, canvas, matrix)
            is DxfHatch, is DxfInsert, is DxfUnknown -> { /* skip */ }
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
        // Y-flip: DXF CCW → screen CW
        // startAngle = -endAngleDeg, sweep = endAngleDeg - startAngleDeg
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

    private fun drawEllipse(e: DxfEllipse, canvas: Canvas, matrix: Matrix) {
        // 72개 선분으로 근사
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
        // 제어점 폴리라인 (B-스플라인 미근사 MVP)
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
        // MText 서식 코드 제거
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
            // drawText/drawMText와 동일하게 scale-aware 크기 적용 (기본 높이 2.5 월드 단위 기준)
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
