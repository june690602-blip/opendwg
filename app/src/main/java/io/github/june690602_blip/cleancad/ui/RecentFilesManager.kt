package io.github.june690602_blip.cleancad.ui

import android.content.Context
import java.io.File

class RecentFilesManager(context: Context) {

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    data class RecentFile(
        val uri: String,
        val name: String,
        val timestamp: Long,
        val localPath: String? = null,
        val size: Long = 0L,
    )

    /** 로드 성공 시 호출. localPath/size 는 복사본이 확정된 뒤 전달(미확정이면 기본값). */
    fun add(uri: String, name: String, localPath: String? = null, size: Long = 0L) {
        val list = getAll().toMutableList()
        list.removeAll { it.uri == uri }
        list.add(0, RecentFile(uri, name, System.currentTimeMillis(), localPath, size))
        val (kept, evicted) = RetentionPolicy.applyRetention(list)
        evicted.forEach { f -> f.localPath?.let { runCatching { File(it).delete() } } }
        save(kept)
    }

    /** 수동 삭제 — 목록에서 빼고 복사본 파일도 제거. */
    fun remove(uri: String) {
        val list = getAll().toMutableList()
        val removed = list.filter { it.uri == uri }
        list.removeAll { it.uri == uri }
        removed.forEach { f -> f.localPath?.let { runCatching { File(it).delete() } } }
        save(list)
    }

    fun getAll(): List<RecentFile> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).map { i ->
            RecentFile(
                uri = prefs.getString("${KEY_URI}_$i", "") ?: "",
                name = prefs.getString("${KEY_NAME}_$i", "") ?: "",
                timestamp = prefs.getLong("${KEY_TS}_$i", 0L),
                localPath = prefs.getString("${KEY_PATH}_$i", "")?.takeIf { it.isNotEmpty() },
                size = prefs.getLong("${KEY_SIZE}_$i", 0L),
            )
        }
    }

    private fun save(list: List<RecentFile>) {
        prefs.edit().apply {
            // 이전 항목 잔여 키 제거(목록이 짧아질 때 stale 키가 남지 않도록)
            clear()
            putInt(KEY_COUNT, list.size)
            list.forEachIndexed { i, f ->
                putString("${KEY_URI}_$i", f.uri)
                putString("${KEY_NAME}_$i", f.name)
                putLong("${KEY_TS}_$i", f.timestamp)
                putString("${KEY_PATH}_$i", f.localPath ?: "")
                putLong("${KEY_SIZE}_$i", f.size)
            }
            apply()
        }
    }

    companion object {
        private const val KEY_COUNT = "count"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_TS = "ts"
        private const val KEY_PATH = "path"
        private const val KEY_SIZE = "size"
    }
}
