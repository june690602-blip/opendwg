package io.github.june690602_blip.cleancad.model

import kotlin.math.max

object SheetClusterer {

    /**
     * 그리드 분할 수 (P5-P95 범위의 긴 축 기준).
     * 일반 한국 건축 도면(mm 단위, 시트 폭 ~50K, 시트 간 간격 ~5K):
     *   cellSize ≈ 370K / 80 = 4,600mm → 간격 1개 셀, 시트 폭 10개 셀.
     */
    private const val GRID_DIVS = 80

    fun cluster(entities: List<DxfEntity>): List<Sheet> {
        if (entities.isEmpty()) return emptyList()

        val indexed = entities.mapIndexedNotNull { idx, e -> e.centroid()?.let { idx to it } }
        if (indexed.isEmpty()) return emptyList()

        val n = indexed.size

        // P5-P95 percentile로 outlier 제거 — cellSize가 outlier에 지배되지 않게 한다.
        val sortedX = indexed.map { (_, pt) -> pt.x }.sorted()
        val sortedY = indexed.map { (_, pt) -> pt.y }.sorted()
        val p5x  = sortedX[(n * 0.05).toInt().coerceIn(0, n - 1)]
        val p95x = sortedX[(n * 0.95).toInt().coerceIn(0, n - 1)]
        val p5y  = sortedY[(n * 0.05).toInt().coerceIn(0, n - 1)]
        val p95y = sortedY[(n * 0.95).toInt().coerceIn(0, n - 1)]

        val rangeX = p95x - p5x
        val rangeY = p95y - p5y

        // 모든 점이 거의 같은 위치 → 단일 시트
        if (rangeX < 1.0 && rangeY < 1.0) {
            return listOf(Sheet(0, null,
                BoundingBox(sortedX.first() - 1.0, sortedY.first() - 1.0,
                            sortedX.last()  + 1.0, sortedY.last()  + 1.0)))
        }

        // P5-P95 범위를 GRID_DIVS 개 셀로 나눔
        val cellSize = max(rangeX, rangeY) / GRID_DIVS
        val cols = ((rangeX / cellSize).toInt() + 1).coerceIn(1, 300)
        val rows = ((rangeY / cellSize).toInt() + 1).coerceIn(1, 300)

        // 그리드 구성 — P5 기준 origin, outlier는 경계 셀로 clamp
        val grid = Array(rows) { Array(cols) { mutableListOf<Int>() } }
        for ((idx, pt) in indexed) {
            val col = ((pt.x - p5x) / cellSize).toInt().coerceIn(0, cols - 1)
            val row = ((pt.y - p5y) / cellSize).toInt().coerceIn(0, rows - 1)
            grid[row][col].add(idx)
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val dirs = arrayOf(intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0))
        val minEntities = max(1, n / 500)
        // bbox 계산 시 outlier 제외 기준: P5-P95 범위 + 셀 2개 여유
        val bboxBuf = cellSize * 2
        val sheets = mutableListOf<Sheet>()
        var sheetId = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c].isNotEmpty() && !visited[r][c]) {
                    val queue = ArrayDeque<Pair<Int, Int>>()
                    val cells = mutableListOf<Pair<Int, Int>>()
                    queue.add(r to c); visited[r][c] = true
                    while (queue.isNotEmpty()) {
                        val (cr, cc) = queue.removeFirst()
                        cells.add(cr to cc)
                        for (d in dirs) {
                            val nr = cr + d[0]; val nc = cc + d[1]
                            if (nr in 0 until rows && nc in 0 until cols
                                && grid[nr][nc].isNotEmpty() && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }

                    var count = 0
                    var cMinX = Double.MAX_VALUE; var cMaxX = -Double.MAX_VALUE
                    var cMinY = Double.MAX_VALUE; var cMaxY = -Double.MAX_VALUE
                    for ((cr, cc) in cells) {
                        count += grid[cr][cc].size
                        for (idx in grid[cr][cc]) {
                            val pt = entities[idx].centroid() ?: continue
                            // outlier centroid는 bbox 계산에서 제외 (P5-P95 + 버퍼 내만 포함)
                            if (pt.x < p5x - bboxBuf || pt.x > p95x + bboxBuf ||
                                pt.y < p5y - bboxBuf || pt.y > p95y + bboxBuf) continue
                            if (pt.x < cMinX) cMinX = pt.x; if (pt.x > cMaxX) cMaxX = pt.x
                            if (pt.y < cMinY) cMinY = pt.y; if (pt.y > cMaxY) cMaxY = pt.y
                        }
                    }

                    if (count >= minEntities && cMinX <= cMaxX) {
                        val margin = cellSize * 0.5
                        sheets.add(Sheet(
                            id = sheetId++,
                            bbox = BoundingBox(cMinX - margin, cMinY - margin,
                                               cMaxX + margin, cMaxY + margin)
                        ))
                    }
                }
            }
        }

        return sheets
    }

    /**
     * displayExtents(렌더 컬링 경계)용 강건 bbox.
     *
     * 문제: [cluster]는 P5-P95 percentile로 시트 bbox를 클리핑하므로, 표지·도면목록표·
     * 사업개요처럼 엔티티 수가 적고 메인 클러스터 위/옆에 떨어진 시트는 상위 5%로 밀려
     * 검출 시트 bbox에서 빠진다. 그 합집합을 그대로 renderBounds로 쓰면 그 시트들이 컬링된다.
     *
     * 해결: 검출 시트 합집합(seed)을 1배 확장한 후보영역 안의 엔티티 centroid만의 bbox를
     * 반환한다. 인접한 저밀도 시트는 포함되고, 먼 junk outlier(±수백만)는 제외된다.
     */
    fun inclusiveExtents(entities: List<DxfEntity>, sheets: List<Sheet>): BoundingBox? {
        if (sheets.isEmpty()) return null
        val seedMinX = sheets.minOf { it.bbox.minX }; val seedMaxX = sheets.maxOf { it.bbox.maxX }
        val seedMinY = sheets.minOf { it.bbox.minY }; val seedMaxY = sheets.maxOf { it.bbox.maxY }
        val w = (seedMaxX - seedMinX).coerceAtLeast(1.0)
        val h = (seedMaxY - seedMinY).coerceAtLeast(1.0)
        val rx0 = seedMinX - w; val rx1 = seedMaxX + w
        val ry0 = seedMinY - h; val ry1 = seedMaxY + h

        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var found = false
        for (e in entities) {
            val p = e.centroid() ?: continue
            if (p.x < rx0 || p.x > rx1 || p.y < ry0 || p.y > ry1) continue
            found = true
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return if (found) BoundingBox(minX, minY, maxX, maxY)
               else BoundingBox(seedMinX, seedMinY, seedMaxX, seedMaxY)
    }
}

private fun DxfEntity.centroid(): Vec2? = when (this) {
    is DxfLine       -> Vec2((start.x + end.x) / 2.0, (start.y + end.y) / 2.0)
    is DxfCircle     -> center
    is DxfArc        -> center
    is DxfEllipse    -> center
    is DxfLwPolyline -> vertices.averageOrNull()
    is DxfPolyline   -> vertices.averageOrNull()
    is DxfSpline     -> controlPoints.averageOrNull()
    is Dxf3DFace     -> Vec2((corner1.x+corner2.x+corner3.x+corner4.x)/4.0,
                              (corner1.y+corner2.y+corner3.y+corner4.y)/4.0)
    is DxfSolid      -> Vec2((corner1.x+corner2.x+corner3.x+corner4.x)/4.0,
                              (corner1.y+corner2.y+corner3.y+corner4.y)/4.0)
    is DxfText       -> insertionPoint
    is DxfMText      -> insertionPoint
    is DxfInsert     -> insertionPoint
    is DxfDimension  -> definitionPoint
    is DxfHatch      -> paths.flatten().averageOrNull()
    is DxfLeader     -> vertices.firstOrNull()
    is DxfPoint      -> null   // 점은 시트 클러스터링에서 제외 (다수의 노드 마커가 그리드를 왜곡)
    is DxfUnknown    -> null
}

private fun List<Vec2>.averageOrNull(): Vec2? {
    if (isEmpty()) return null
    return Vec2(sumOf { it.x } / size, sumOf { it.y } / size)
}
