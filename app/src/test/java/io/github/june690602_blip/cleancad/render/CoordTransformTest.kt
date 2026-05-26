package io.github.june690602_blip.cleancad.render

import android.graphics.Matrix
import android.graphics.PointF
import io.github.june690602_blip.cleancad.model.BoundingBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoordTransformTest {

    @Test
    fun `fitMatrix maps bounding box center to view center`() {
        val box = BoundingBox(0.0, 0.0, 100.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        val pt = CoordTransform.worldToScreen(50.0, 50.0, matrix)
        assertEquals(200f, pt.x, 1f)
        assertEquals(200f, pt.y, 1f)
    }

    @Test
    fun `fitMatrix flips Y — world top maps to screen top`() {
        val box = BoundingBox(0.0, 0.0, 100.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        val top = CoordTransform.worldToScreen(50.0, box.maxY, matrix)
        val bottom = CoordTransform.worldToScreen(50.0, box.minY, matrix)
        assertTrue("world maxY should be smaller screenY than world minY", top.y < bottom.y)
    }

    @Test
    fun `fitMatrix respects aspect ratio — wide world in square view`() {
        val box = BoundingBox(0.0, 0.0, 200.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        // scale limited by width: (400/200)*0.95 = 1.9
        assertEquals(1.9f, CoordTransform.currentScale(matrix), 0.01f)
    }

    @Test
    fun `currentScale returns positive value despite Y-flip`() {
        val box = BoundingBox(0.0, 0.0, 10.0, 10.0)
        val matrix = CoordTransform.fitMatrix(box, 100, 100)
        assertTrue(CoordTransform.currentScale(matrix) > 0f)
    }

    @Test
    fun `worldToScreen maps world origin correctly`() {
        val box = BoundingBox(0.0, 0.0, 100.0, 100.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        val pt = CoordTransform.worldToScreen(0.0, 0.0, matrix)
        val scale = CoordTransform.currentScale(matrix)
        val expectedX = (400f - 100f * scale) / 2f
        val expectedY = 400f - (400f - 100f * scale) / 2f
        assertEquals(expectedX, pt.x, 1f)
        assertEquals(expectedY, pt.y, 1f)
    }

    @Test
    fun `fitMatrix handles non-origin bounding box`() {
        val box = BoundingBox(10.0, 20.0, 110.0, 120.0)
        val matrix = CoordTransform.fitMatrix(box, 400, 400)
        // 박스 중심 (60, 70)이 뷰 중심 (200, 200)에 매핑돼야 함
        val pt = CoordTransform.worldToScreen(60.0, 70.0, matrix)
        assertEquals(200f, pt.x, 1f)
        assertEquals(200f, pt.y, 1f)
    }
}
