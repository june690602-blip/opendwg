package io.github.june690602_blip.cleancad.render

import android.graphics.*
import io.github.june690602_blip.cleancad.model.*
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.model.EntityColor
import kotlin.math.*

class EntityRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // Phase 9.2: 2f → 1f. 대용량 도면 줌아웃 시 빽빽한 entity가 검은/흰 떡덩어리로
        // 뭉치는 것을 완화. anti-alias로 회색 trail 효과.
        strokeWidth = 1f
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
    private var isDarkBg: Boolean = false

    /** displayExtents 등 "이 박스 밖 엔티티는 영구 컬링" 용도. null이면 컬링 없음.
     *  화면 viewport와 별개 — 사용자가 줌아웃해도 outlier가 다시 보이지 않도록. */
    private var renderBounds: BoundingBox? = null

    /** LINE batch: 색상별로 primitive FloatArray로 [x1,y1,x2,y2, ...] 누적 후 한 번에 drawLines.
     *  ArrayList<Float>는 매 frame 수십만 Float autobox → GC 폭증 (Phase 9.2 1차 시도 후 Davey
     *  1~7초 잔존). primitive FloatArray로 autoboxing 제거. */
    private val lineBatch = HashMap<Int, FloatBuf>(64)

    private class FloatBuf(initialCapacity: Int = 512) {
        var arr: FloatArray = FloatArray(initialCapacity); private set
        var size: Int = 0; private set
        fun add4(a: Float, b: Float, c: Float, d: Float) {
            if (size + 4 > arr.size) arr = arr.copyOf((arr.size * 2).coerceAtLeast(size + 4))
            arr[size]     = a
            arr[size + 1] = b
            arr[size + 2] = c
            arr[size + 3] = d
            size += 4
        }
        fun clear() { size = 0 }
        /** size 미만만 유효. drawLines 에는 trimmed copy 필요. */
        fun trimmedCopy(): FloatArray = arr.copyOf(size)
    }

    fun setColors(bgColor: Int, lineColor: Int) {
        this.bgColor = bgColor
        this.defaultLineColor = lineColor
        this.isDarkBg = ColorInvert.isDarkBackground(bgColor)
        linePaint.color = lineColor
        textPaint.color = lineColor
    }

    fun setRenderBounds(box: BoundingBox?) {
        renderBounds = box
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

    private fun colorFor(entity: DxfEntity): Int {
        val raw = entityColorByIdentity[entity]
            ?: layerColorMap[entity.layer]
            ?: defaultLineColor
        return ColorInvert.maybeInvert(raw, isDarkBg)
    }

    /** Drawing의 모든 엔티티를 렌더 순서대로 Canvas에 그린다.
     *  컬링 전략:
     *   - renderBounds(displayExtents) 밖 엔티티 영구 스킵 (outlier 차단 — Phase 9.2)
     *   - viewport 밖 엔티티 스킵 (worldBounds vs viewport)
     *   - 화면상 너무 작아서 점만도 안 보이는 엔티티 스킵 (< 2px)
     *   - 텍스트는 base 글자 높이가 10px 미만이면 스킵 (zoom out 상태에서 까만 덩어리 방지)
     *  성능 전략 (Phase 9.2):
     *   - DxfLine 은 색상별 FloatArray 로 누적 후 canvas.drawLines() 한 번으로 flush.
     *     drawPath 호출 수십만 회 → drawLines 호출 수십 회 (색상 종류 수).
     *     GPU 명령 큐 ~100배 감소 → 메인 스레드 ANR 해결. */
    fun drawAll(
        entities: List<DxfEntity>,
        canvas: Canvas,
        matrix: Matrix,
        viewport: BoundingBox? = null
    ) {
        val scale = CoordTransform.currentScale(matrix)
        lineBatch.values.forEach { it.clear() }
        entities.forEach { entity ->
            if (entity !is DxfText && entity !is DxfMText) {
                val bounds = entity.worldBounds()
                if (bounds != null) {
                    val rb = renderBounds
                    if (rb != null && !bounds.intersects(rb)) return@forEach
                    if (viewport != null && !bounds.intersects(viewport)) return@forEach
                    // Phase 9.2: pixel cull 강화. 가로+세로 합이 3px 미만이면 스킵.
                    // 이전 (W<2 AND H<2)는 길쭉한 segment를 못 막아 떡덩어리 잔존.
                    val pixW = bounds.width * scale
                    val pixH = bounds.height * scale
                    if (pixW + pixH < 3.0) return@forEach
                }
                if (entity is DxfLine) {
                    val s = CoordTransform.worldToScreen(entity.start, matrix)
                    val e = CoordTransform.worldToScreen(entity.end, matrix)
                    val color = colorFor(entity)
                    val buf = lineBatch.getOrPut(color) { FloatBuf(1024) }
                    buf.add4(s.x, s.y, e.x, e.y)
                } else {
                    draw(entity, canvas, matrix)
                }
            }
        }
        // flush LINE batch — 색상마다 한 번씩 drawLines.
        for ((color, buf) in lineBatch) {
            if (buf.size == 0) continue
            linePaint.color = color
            canvas.drawLines(buf.trimmedCopy(), linePaint)
        }
        entities.forEach { entity ->
            when (entity) {
                is DxfText -> {
                    val pixSize = entity.height * scale
                    if (pixSize < MIN_TEXT_BASE_PIXELS) return@forEach
                    // text도 viewport culling 적용 (Phase 9.1)
                    if (viewport != null) {
                        val b = entity.worldBounds()
                        if (b != null && !b.intersects(viewport)) return@forEach
                    }
                    draw(entity, canvas, matrix)
                }
                is DxfMText -> {
                    val pixSize = entity.height * scale
                    if (pixSize < MIN_TEXT_BASE_PIXELS) return@forEach
                    if (viewport != null) {
                        val b = entity.worldBounds()
                        if (b != null && !b.intersects(viewport)) return@forEach
                    }
                    draw(entity, canvas, matrix)
                }
                else -> { /* already drawn in first pass */ }
            }
        }
    }

    private companion object {
        /** zoom out 상태에서 글자 자체가 이 픽셀 수보다 작아지면 렌더 자체를 스킵.
         *  까만 글자 덩어리(수많은 작은 텍스트 mass) 방지. Phase 9.1: 4→10 (대용량 도면 ANR 방지). */
        const val MIN_TEXT_BASE_PIXELS: Double = 10.0

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
