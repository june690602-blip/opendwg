package io.github.june690602_blip.cleancad.model

import kotlin.math.max

object SheetClusterer {

    private const val GRID_DIVS = 80

    fun cluster(entities: List<DxfEntity>): List<Sheet> {
        if (entities.isEmpty()) return emptyList()

        val indexed = entities.mapIndexedNotNull { idx, e -> e.centroid()?.let { idx to it } }
        if (indexed.isEmpty()) return emptyList()

        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for ((_, pt) in indexed) {
            if (pt.x < minX) minX = pt.x; if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y; if (pt.y > maxY) maxY = pt.y
        }

        val rangeX = maxX - minX
        val rangeY = maxY - minY
        if (rangeX < 1.0 && rangeY < 1.0) {
            return listOf(Sheet(0, null, BoundingBox(minX - 1.0, minY - 1.0, maxX + 1.0, maxY + 1.0)))
        }

        val cellSize = max(rangeX, rangeY) / GRID_DIVS
        val cols = ((rangeX / cellSize).toInt() + 1).coerceIn(1, 300)
        val rows = ((rangeY / cellSize).toInt() + 1).coerceIn(1, 300)

        val grid = Array(rows) { Array(cols) { mutableListOf<Int>() } }
        for ((idx, pt) in indexed) {
            val col = ((pt.x - minX) / cellSize).toInt().coerceIn(0, cols - 1)
            val row = ((pt.y - minY) / cellSize).toInt().coerceIn(0, rows - 1)
            grid[row][col].add(idx)
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val dirs = arrayOf(intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0))
        val minEntities = max(1, indexed.size / 500)
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
                            if (pt.x < cMinX) cMinX = pt.x; if (pt.x > cMaxX) cMaxX = pt.x
                            if (pt.y < cMinY) cMinY = pt.y; if (pt.y > cMaxY) cMaxY = pt.y
                        }
                    }

                    if (count >= minEntities) {
                        val margin = cellSize * 0.5
                        sheets.add(Sheet(
                            id = sheetId++,
                            bbox = BoundingBox(cMinX - margin, cMinY - margin, cMaxX + margin, cMaxY + margin)
                        ))
                    }
                }
            }
        }

        return sheets
    }
}

private fun DxfEntity.centroid(): Vec2? = when (this) {
    is DxfLine -> Vec2((start.x + end.x) / 2.0, (start.y + end.y) / 2.0)
    is DxfCircle -> center
    is DxfArc -> center
    is DxfEllipse -> center
    is DxfLwPolyline -> vertices.averageOrNull()
    is DxfPolyline -> vertices.averageOrNull()
    is DxfSpline -> controlPoints.averageOrNull()
    is Dxf3DFace -> Vec2(
        (corner1.x + corner2.x + corner3.x + corner4.x) / 4.0,
        (corner1.y + corner2.y + corner3.y + corner4.y) / 4.0
    )
    is DxfSolid -> Vec2(
        (corner1.x + corner2.x + corner3.x + corner4.x) / 4.0,
        (corner1.y + corner2.y + corner3.y + corner4.y) / 4.0
    )
    is DxfText -> insertionPoint
    is DxfMText -> insertionPoint
    is DxfInsert -> insertionPoint
    is DxfDimension -> definitionPoint
    is DxfHatch -> paths.flatten().averageOrNull()
    is DxfLeader -> vertices.firstOrNull()
    is DxfUnknown -> null
}

private fun List<Vec2>.averageOrNull(): Vec2? {
    if (isEmpty()) return null
    return Vec2(sumOf { it.x } / size, sumOf { it.y } / size)
}
