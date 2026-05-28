package io.github.june690602_blip.cleancad.model

import org.junit.Assert.*
import org.junit.Test

class SheetClustererTest {

    private fun lines(vararg coords: Double): List<DxfEntity> {
        require(coords.size % 4 == 0)
        return (coords.indices step 4).map { i ->
            DxfLine("0", Vec2(coords[i], coords[i + 1]), Vec2(coords[i + 2], coords[i + 3]))
        }
    }

    @Test
    fun cluster_empty_returnsEmpty() {
        val sheets = SheetClusterer.cluster(emptyList())
        assertTrue(sheets.isEmpty())
    }

    @Test
    fun cluster_singlePoint_returnsSingleSheet() {
        val entities = lines(0.0, 0.0, 10.0, 10.0)
        val sheets = SheetClusterer.cluster(entities)
        assertEquals(1, sheets.size)
    }

    @Test
    fun cluster_twoClustersWellSeparated_returnsTwoSheets() {
        // Cluster A: lines around (0, 0)
        val clusterA = (0 until 30).flatMap { i ->
            listOf(DxfLine("0", Vec2(i * 100.0, 0.0), Vec2(i * 100.0 + 50.0, 100.0)))
        }
        // Cluster B: lines around (500_000, 0) — far away
        val clusterB = (0 until 30).flatMap { i ->
            listOf(DxfLine("0", Vec2(500_000.0 + i * 100.0, 0.0), Vec2(500_000.0 + i * 100.0 + 50.0, 100.0)))
        }
        val sheets = SheetClusterer.cluster(clusterA + clusterB)
        assertEquals(2, sheets.size)
    }

    @Test
    fun cluster_twoClusters_bboxContainsAllEntities() {
        // Cluster A: lines in X range [0, 2900]
        val clusterA = (0 until 30).map { i ->
            DxfLine("0", Vec2(i * 100.0, 0.0), Vec2(i * 100.0 + 50.0, 500.0))
        }
        // Cluster B: lines in X range [500_000, 502_900]
        val clusterB = (0 until 30).map { i ->
            DxfLine("0", Vec2(500_000.0 + i * 100.0, 0.0), Vec2(500_000.0 + i * 100.0 + 50.0, 500.0))
        }
        val sheets = SheetClusterer.cluster(clusterA + clusterB)
        assertEquals(2, sheets.size)

        // Sort by minX to identify clusters
        val sorted = sheets.sortedBy { it.bbox.minX }
        assertTrue(sorted[0].bbox.minX <= 0.0)
        assertTrue(sorted[0].bbox.maxX >= 2950.0)
        assertTrue(sorted[1].bbox.minX <= 500_000.0)
        assertTrue(sorted[1].bbox.maxX >= 502_950.0)
    }

    @Test
    fun cluster_sheetIds_areUnique() {
        val clusterA = (0 until 30).map { i -> DxfLine("0", Vec2(i * 100.0, 0.0), Vec2(i * 100.0 + 50.0, 100.0)) }
        val clusterB = (0 until 30).map { i -> DxfLine("0", Vec2(500_000.0 + i * 100.0, 0.0), Vec2(500_000.0 + i * 100.0 + 50.0, 100.0)) }
        val sheets = SheetClusterer.cluster(clusterA + clusterB)
        val ids = sheets.map { it.id }.toSet()
        assertEquals(sheets.size, ids.size)
    }

    @Test
    fun cluster_onlyUnknownEntities_returnsEmpty() {
        val entities = listOf(DxfUnknown("0", "ACAD_TABLE"), DxfUnknown("0", "WIPEOUT"))
        val sheets = SheetClusterer.cluster(entities)
        // No meaningful centroids → no sheets
        assertTrue(sheets.isEmpty())
    }
}
