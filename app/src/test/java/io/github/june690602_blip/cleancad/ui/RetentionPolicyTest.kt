package io.github.june690602_blip.cleancad.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [RetentionPolicy] — 개수/용량 캡과 퇴출 항목 식별을 검증(순수 함수). */
class RetentionPolicyTest {

    private val MB = 1024L * 1024L

    private fun rf(id: Int, size: Long) =
        RecentFilesManager.RecentFile("uri$id", "name$id", id.toLong(), "/recent/$id.dwg", size)

    @Test
    fun itemCount_capEvictsOldest() {
        // 12개(최신순: index 0 최신), 용량 무제한 → 개수 캡 10
        val list = (0 until 12).map { rf(it, 1) }
        val (kept, evicted) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = Long.MAX_VALUE)
        assertEquals(10, kept.size)
        assertEquals(listOf("uri10", "uri11"), evicted.map { it.uri }) // 뒤(오래된) 2개 퇴출
    }

    @Test
    fun byteCap_evictsBeyondLimit() {
        // 각 60MB 5개, 200MB 캡 → 3개(180MB)만 보관
        val list = (0 until 5).map { rf(it, 60 * MB) }
        val (kept, evicted) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = 200 * MB)
        assertEquals(3, kept.size)
        assertEquals(2, evicted.size)
    }

    @Test
    fun byteCap_winsOverItemCap_whicheverFirst() {
        // 각 50MB 10개, 개수 10·용량 200MB → 용량이 먼저 닿아 4개
        val list = (0 until 10).map { rf(it, 50 * MB) }
        val (kept, _) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = 200 * MB)
        assertEquals(4, kept.size) // 50*4=200 <= 200, 5번째 250 > 200
    }

    @Test
    fun firstItem_keptEvenIfOverByteCap() {
        // 방금 연 500MB 단일 항목은 캡을 넘겨도 최소 1개 보관
        val list = listOf(rf(0, 500 * MB))
        val (kept, evicted) = RetentionPolicy.applyRetention(list, maxItems = 10, maxBytes = 200 * MB)
        assertEquals(1, kept.size)
        assertEquals(0, evicted.size)
    }

    @Test
    fun emptyList_returnsEmpty() {
        val (kept, evicted) = RetentionPolicy.applyRetention(emptyList())
        assertEquals(0, kept.size)
        assertEquals(0, evicted.size)
    }
}
