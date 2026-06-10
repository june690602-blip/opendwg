package io.github.june690602_blip.cleancad.ui

/**
 * 최근파일 보관 정책 — Context/SharedPreferences 의존 없는 순수 함수(단위 테스트 대상).
 */
object RetentionPolicy {
    const val MAX_ITEMS = 10
    const val MAX_BYTES = 200L * 1024 * 1024 // 200MB

    /**
     * 최신순 [list]를 개수·용량 한도로 가지치기.
     * 맨 앞(방금 연 파일)은 용량을 넘겨도 최소 1개 보관한다.
     * @return (보관, 퇴출). 퇴출 항목의 복사본 파일 삭제는 호출자 책임.
     */
    fun applyRetention(
        list: List<RecentFilesManager.RecentFile>,
        maxItems: Int = MAX_ITEMS,
        maxBytes: Long = MAX_BYTES,
    ): Pair<List<RecentFilesManager.RecentFile>, List<RecentFilesManager.RecentFile>> {
        val kept = ArrayList<RecentFilesManager.RecentFile>()
        val evicted = ArrayList<RecentFilesManager.RecentFile>()
        var cumBytes = 0L
        for (f in list) {
            val fits = kept.size < maxItems && cumBytes + f.size <= maxBytes
            if (kept.isEmpty() || fits) {
                kept.add(f)
                cumBytes += f.size
            } else {
                evicted.add(f)
            }
        }
        return kept to evicted
    }
}
