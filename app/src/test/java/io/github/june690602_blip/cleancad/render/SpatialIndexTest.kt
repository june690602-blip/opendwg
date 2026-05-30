package io.github.june690602_blip.cleancad.render

import io.github.june690602_blip.cleancad.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialIndexTest {

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double) =
        DxfLine("0", Vec2(x0, y0), Vec2(x1, y1))

    private fun IntList.toList(): List<Int> = (0 until size).map { data[it] }

    @Test
    fun query_smallViewport_returnsOnlyOverlapping() {
        val entities = listOf(
            line(0.0, 0.0, 10.0, 10.0),       // 0 — 좌하단
            line(100.0, 100.0, 110.0, 110.0), // 1 — 우상단
            DxfCircle("0", Vec2(200.0, 200.0), 5.0), // 2 — 멀리
        )
        val idx = SpatialIndex.build(entities, BoundingBox(0.0, 0.0, 210.0, 210.0))
        val out = IntList()
        idx.query(BoundingBox(-1.0, -1.0, 20.0, 20.0), out)
        assertEquals(listOf(0), out.toList())
    }

    @Test
    fun query_fullViewport_returnsAllDrawableInRenderOrder() {
        val entities = listOf(
            line(0.0, 0.0, 10.0, 10.0),       // 0
            line(100.0, 100.0, 110.0, 110.0), // 1
            DxfCircle("0", Vec2(200.0, 200.0), 5.0), // 2
        )
        val idx = SpatialIndex.build(entities, BoundingBox(0.0, 0.0, 210.0, 210.0))
        val out = IntList()
        idx.query(BoundingBox(-100.0, -100.0, 300.0, 300.0), out)
        // 렌더 순서(=엔티티 인덱스 오름차순) 복원
        assertEquals(listOf(0, 1, 2), out.toList())
    }

    @Test
    fun nullBoundsEntities_areExcluded() {
        val entities = listOf(
            DxfInsert("0", "BLK", Vec2(5.0, 5.0)), // null bounds → 제외
            line(0.0, 0.0, 10.0, 10.0),            // 1
            DxfUnknown("0", "FOO"),                // null bounds → 제외
        )
        val idx = SpatialIndex.build(entities, BoundingBox(0.0, 0.0, 20.0, 20.0))
        val out = IntList()
        idx.query(BoundingBox(-100.0, -100.0, 100.0, 100.0), out)
        assertEquals(listOf(1), out.toList())
    }

    @Test
    fun multiCellEntity_returnedOnce() {
        // 영역 전체를 가로지르는 긴 선 → 여러 셀에 등록되지만 dedup 으로 1회만.
        val entities = listOf(line(0.0, 0.0, 1000.0, 1000.0))
        val idx = SpatialIndex.build(entities, BoundingBox(0.0, 0.0, 1000.0, 1000.0))
        val out = IntList()
        idx.query(BoundingBox(-10.0, -10.0, 1010.0, 1010.0), out)
        assertEquals(listOf(0), out.toList())
    }

    @Test
    fun viewportOutsideRegion_returnsNothing() {
        val entities = listOf(line(0.0, 0.0, 10.0, 10.0))
        val idx = SpatialIndex.build(entities, BoundingBox(0.0, 0.0, 100.0, 100.0))
        val out = IntList()
        idx.query(BoundingBox(500.0, 500.0, 600.0, 600.0), out)
        assertTrue(out.toList().isEmpty())
    }

    @Test
    fun nullRegion_computedFromEntities() {
        val entities = listOf(
            line(0.0, 0.0, 10.0, 10.0),
            line(50.0, 50.0, 60.0, 60.0),
        )
        val idx = SpatialIndex.build(entities, region = null)
        val out = IntList()
        idx.query(BoundingBox(-1.0, -1.0, 100.0, 100.0), out)
        assertEquals(listOf(0, 1), out.toList())
    }
}
