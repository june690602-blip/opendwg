package io.github.june690602_blip.cleancad.ui

import android.content.Context

class RecentFilesManager(context: Context) {

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    data class RecentFile(val uri: String, val name: String, val timestamp: Long)

    fun add(uri: String, name: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.uri == uri }
        list.add(0, RecentFile(uri, name, System.currentTimeMillis()))
        if (list.size > MAX_ITEMS) list.removeAt(list.size - 1)
        save(list)
    }

    fun getAll(): List<RecentFile> {
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).map { i ->
            RecentFile(
                uri = prefs.getString("${KEY_URI}_$i", "") ?: "",
                name = prefs.getString("${KEY_NAME}_$i", "") ?: "",
                timestamp = prefs.getLong("${KEY_TS}_$i", 0L)
            )
        }
    }

    private fun save(list: List<RecentFile>) {
        prefs.edit().apply {
            putInt(KEY_COUNT, list.size)
            list.forEachIndexed { i, f ->
                putString("${KEY_URI}_$i", f.uri)
                putString("${KEY_NAME}_$i", f.name)
                putLong("${KEY_TS}_$i", f.timestamp)
            }
            apply()
        }
    }

    companion object {
        private const val MAX_ITEMS = 10
        private const val KEY_COUNT = "count"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_TS = "ts"
    }
}
