package io.github.june690602_blip.cleancad.render

import android.graphics.Matrix
import android.graphics.PointF
import io.github.june690602_blip.cleancad.model.BoundingBox
import io.github.june690602_blip.cleancad.model.Vec2

object CoordTransform {

    /**
     * DXF 월드 BoundingBox를 뷰 크기에 맞추는 Matrix를 생성한다.
     * Y축을 플립한다 (DXF Y-up → Android Y-down).
     * 5% 여백을 남긴다.
     */
    fun fitMatrix(box: BoundingBox, viewW: Int, viewH: Int): Matrix {
        if (box.width <= 0.0 || box.height <= 0.0) return Matrix()
        val scale = minOf(viewW / box.width, viewH / box.height) * 0.95
        val tx = (viewW - box.width * scale) / 2.0 - box.minX * scale
        val ty = viewH - (viewH - box.height * scale) / 2.0 + box.minY * scale
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    scale.toFloat(), 0f, tx.toFloat(),
                    0f, (-scale).toFloat(), ty.toFloat(),
                    0f, 0f, 1f
                )
            )
        }
    }

    /** 월드 좌표 → 스크린 픽셀 좌표 */
    fun worldToScreen(wx: Double, wy: Double, matrix: Matrix): PointF {
        val pts = floatArrayOf(wx.toFloat(), wy.toFloat())
        matrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun worldToScreen(pt: Vec2, matrix: Matrix): PointF = worldToScreen(pt.x, pt.y, matrix)

    /**
     * 현재 스케일 (항상 양수).
     * Y 스케일은 음수이므로 X 스케일 값을 사용한다.
     * 균일 스케일링(postScale(s, s, ...))만 사용한다고 가정한다.
     */
    fun currentScale(matrix: Matrix): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    fun screenToWorldBounds(screenW: Int, screenH: Int, matrix: Matrix): BoundingBox {
        val inv = Matrix()
        matrix.invert(inv)
        val pts = floatArrayOf(0f, 0f, screenW.toFloat(), screenH.toFloat())
        inv.mapPoints(pts)
        return BoundingBox(
            minOf(pts[0], pts[2]).toDouble(), minOf(pts[1], pts[3]).toDouble(),
            maxOf(pts[0], pts[2]).toDouble(), maxOf(pts[1], pts[3]).toDouble()
        )
    }
}
