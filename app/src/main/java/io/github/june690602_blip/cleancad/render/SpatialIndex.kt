package io.github.june690602_blip.cleancad.render

import io.github.june690602_blip.cleancad.model.*
import kotlin.math.hypot

/**
 * primitive int 동적 배열. 매 프레임 viewport 조회 결과를 autoboxing 없이 담는다.
 * (단일 스레드 — UI 스레드 전용. 재사용 버퍼로 쓴다.)
 */
class IntList(initialCapacity: Int = 4096) {
    var data: IntArray = IntArray(initialCapacity); private set
    var size: Int = 0; private set
    fun clear() { size = 0 }
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    /** [0, size) 구간을 오름차순 정렬 (= 엔티티 인덱스 = 렌더 순서 복원). */
    fun sortAscending() = java.util.Arrays.sort(data, 0, size)
}

/**
 * 균일 그리드 공간 인덱스 + 엔티티 worldBounds 캐시 (Phase 10).
 *
 * 목적: `EntityRenderer.drawAll` 핫루프가 매 프레임 188K 엔티티를 전부 순회하며
 * worldBounds 를 재계산(람다 minOf → iterator/박싱 할당 → GC 폭증)하던 것을 제거.
 *  - 로드 시 1회 bounds 계산 + 그리드 구축.
 *  - drawAll 에서는 viewport 와 겹치는 셀의 엔티티만 [query] 로 조회 (188K → 가시 영역 수천).
 *
 * 셀은 거친 후보만 추리므로 [query] 가 정밀 bounds∩viewport 까지 걸러 반환한다.
 */
class SpatialIndex private constructor(
    /** 엔티티별 캐시된 월드 bounds. entities 와 평행. null = 그려도 아무것도 안 나오는 타입(Insert/Unknown). */
    val bounds: Array<BoundingBox?>,
    private val cells: Array<IntArray?>,
    private val originX: Double,
    private val originY: Double,
    private val invCell: Double,
    private val cols: Int,
    private val rows: Int,
) {
    // dedup 마커: 셀이 겹치면 한 엔티티가 여러 셀에 등록돼 중복 방문될 수 있다.
    // 프레임마다 epoch++ 로 clear 없이 visited 판별.
    private val visitedEpoch = IntArray(bounds.size)
    private var epoch = 0

    /**
     * [viewport] 와 정밀하게 겹치는 엔티티 인덱스를 렌더 순서로 [sink] 에 채운다.
     * cols==0(빈 인덱스)이면 아무것도 채우지 않는다.
     */
    fun query(viewport: BoundingBox, sink: IntList) {
        sink.clear()
        if (cols == 0 || rows == 0) return
        epoch++
        if (epoch == 0) { visitedEpoch.fill(0); epoch = 1 } // int overflow wrap 보호

        val c0 = colOf(viewport.minX)
        val c1 = colOf(viewport.maxX)
        val r0 = rowOf(viewport.minY)
        val r1 = rowOf(viewport.maxY)
        for (r in r0..r1) {
            val base = r * cols
            for (c in c0..c1) {
                val cell = cells[base + c] ?: continue
                for (idx in cell) {
                    if (visitedEpoch[idx] == epoch) continue
                    visitedEpoch[idx] = epoch
                    val b = bounds[idx]
                    if (b != null && b.intersects(viewport)) sink.add(idx)
                }
            }
        }
        sink.sortAscending()
    }

    private fun colOf(x: Double): Int =
        ((x - originX) * invCell).toInt().coerceIn(0, cols - 1)

    private fun rowOf(y: Double): Int =
        ((y - originY) * invCell).toInt().coerceIn(0, rows - 1)

    companion object {
        /** 긴 축을 이 개수로 분할. 셀 크기 = max(regionW, regionH) / GRID_DIV. */
        private const val GRID_DIV = 128

        /**
         * 엔티티 목록으로 인덱스를 구축한다. [region] 은 그리드를 깔 영역(보통 displayExtents).
         * region 이 null 이면 엔티티 bounds 로부터 계산한다. 그래도 없으면 빈 인덱스.
         */
        fun build(entities: List<DxfEntity>, region: BoundingBox?): SpatialIndex {
            val n = entities.size
            val bounds = arrayOfNulls<BoundingBox>(n)
            for (i in 0 until n) bounds[i] = boundsFor(entities[i])

            val reg = region ?: computeRegion(bounds)
            if (reg == null || reg.width <= 0.0 && reg.height <= 0.0) {
                return SpatialIndex(bounds, arrayOfNulls(0), 0.0, 0.0, 0.0, 0, 0)
            }

            val cellSize = (maxOf(reg.width, reg.height) / GRID_DIV).coerceAtLeast(1e-6)
            val invCell = 1.0 / cellSize
            val cols = (reg.width * invCell).toInt() + 1
            val rows = (reg.height * invCell).toInt() + 1
            val originX = reg.minX
            val originY = reg.minY

            fun colOf(x: Double) = ((x - originX) * invCell).toInt().coerceIn(0, cols - 1)
            fun rowOf(y: Double) = ((y - originY) * invCell).toInt().coerceIn(0, rows - 1)

            // 1차: 셀별 카운트 → IntArray 정확히 할당 (ArrayList<Int> 박싱 회피).
            val counts = IntArray(cols * rows)
            for (i in 0 until n) {
                val b = bounds[i] ?: continue
                val c0 = colOf(b.minX); val c1 = colOf(b.maxX)
                val r0 = rowOf(b.minY); val r1 = rowOf(b.maxY)
                for (r in r0..r1) {
                    val base = r * cols
                    for (c in c0..c1) counts[base + c]++
                }
            }
            val cells = arrayOfNulls<IntArray>(cols * rows)
            val fill = IntArray(cols * rows) // 셀별 다음 쓰기 위치
            for (k in counts.indices) if (counts[k] > 0) cells[k] = IntArray(counts[k])
            // 2차: 채우기 (entity 인덱스 오름차순으로 등록 → 셀 내부도 정렬 상태)
            for (i in 0 until n) {
                val b = bounds[i] ?: continue
                val c0 = colOf(b.minX); val c1 = colOf(b.maxX)
                val r0 = rowOf(b.minY); val r1 = rowOf(b.maxY)
                for (r in r0..r1) {
                    val base = r * cols
                    for (c in c0..c1) {
                        val k = base + c
                        cells[k]!![fill[k]++] = i
                    }
                }
            }
            return SpatialIndex(bounds, cells, originX, originY, invCell, cols, rows)
        }

        private fun computeRegion(bounds: Array<BoundingBox?>): BoundingBox? {
            var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            var any = false
            for (b in bounds) {
                if (b == null) continue
                any = true
                if (b.minX < minX) minX = b.minX
                if (b.minY < minY) minY = b.minY
                if (b.maxX > maxX) maxX = b.maxX
                if (b.maxY > maxY) maxY = b.maxY
            }
            return if (any) BoundingBox(minX, minY, maxX, maxY) else null
        }

        /**
         * 인덱싱/컬링용 bounds. 기하 타입은 기존 [worldBounds] 재사용,
         * 그 외 그려지는 타입(Text/MText/Dimension/Leader/Spline/Ellipse)은 여기서 보강한다.
         * (전역 [worldBounds] 는 computeExtents 와 공유되므로 의미를 바꾸지 않는다.)
         * Insert/Unknown 은 그려도 아무것도 안 나오므로 null → 인덱스 제외.
         */
        internal fun boundsFor(e: DxfEntity): BoundingBox? = when (e) {
            is DxfPoint -> BoundingBox(e.position.x, e.position.y, e.position.x, e.position.y)
            is DxfText  -> textBounds(e.insertionPoint, e.height, e.text)
            is DxfMText -> textBounds(e.insertionPoint, e.height, e.text)
            is DxfDimension -> BoundingBox(
                minOf(e.definitionPoint.x, e.textMidPoint.x),
                minOf(e.definitionPoint.y, e.textMidPoint.y),
                maxOf(e.definitionPoint.x, e.textMidPoint.x),
                maxOf(e.definitionPoint.y, e.textMidPoint.y),
            )
            is DxfLeader -> if (e.vertices.isEmpty()) null else BoundingBox(
                e.vertices.minOf { it.x }, e.vertices.minOf { it.y },
                e.vertices.maxOf { it.x }, e.vertices.maxOf { it.y },
            )
            is DxfSpline -> if (e.controlPoints.isEmpty()) null else BoundingBox(
                e.controlPoints.minOf { it.x }, e.controlPoints.minOf { it.y },
                e.controlPoints.maxOf { it.x }, e.controlPoints.maxOf { it.y },
            )
            is DxfEllipse -> {
                // major axis 길이를 반경 상한으로 — minor ≤ major 이므로 안전한 superset.
                val r = hypot(e.majorAxis.x, e.majorAxis.y)
                BoundingBox(e.center.x - r, e.center.y - r, e.center.x + r, e.center.y + r)
            }
            else -> e.worldBounds() // 기하 타입 + Insert/Unknown(null)
        }

        /** 회전·정렬 무관하게 안전하도록 삽입점 기준 넉넉한 정사각 박스(컬링 전용, over-include 허용). */
        private fun textBounds(p: Vec2, h: Double, text: String): BoundingBox {
            val ext = (text.length * h * 0.8).coerceAtLeast(h * 2.0)
            return BoundingBox(p.x - ext, p.y - ext, p.x + ext, p.y + ext)
        }
    }
}
