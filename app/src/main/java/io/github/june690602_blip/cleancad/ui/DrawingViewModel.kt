package io.github.june690602_blip.cleancad.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.june690602_blip.cleancad.NativeDwg
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.model.SheetClusterer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class DrawingViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<DrawingState>(DrawingState.Idle)
    val state: StateFlow<DrawingState> = _state.asStateFlow()

    /**
     * @param uri         원본 content URI (목록 dedup 키로도 사용).
     * @param localPath   재열기 시 로컬 복사본 경로. 존재하면 복사 없이 바로 파싱.
     * @param fallbackName 재열기 시 목록의 표시 이름(원본 URI 권한이 없을 수 있어 우선 사용).
     */
    fun load(uri: Uri, localPath: String? = null, fallbackName: String? = null) {
        if (_state.value is DrawingState.Loading) return
        viewModelScope.launch {
            _state.value = DrawingState.Loading
            Log.i(TAG, "load: start uri=$uri localPath=$localPath")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()

                    // 파싱할 정본 파일을 결정한다.
                    val cached = localPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
                    val dwgFile: File
                    val displayName: String
                    if (cached != null) {
                        // 재열기: 영구 복사본 직접 사용(복사 생략).
                        dwgFile = cached
                        displayName = fallbackName ?: cached.name
                        Log.i(TAG, "load: reopen from copy ${cached.length()} bytes ($displayName)")
                    } else {
                        // 신규 열기(또는 복사본 소실 폴백): 원본 URI → cacheDir 복사 → 검증 → filesDir 이동.
                        displayName = ctx.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            ?: fallbackName ?: uri.lastPathSegment ?: uri.toString()

                        val tag = uri.hashCode().toUInt().toString(16)
                        val cacheFile = File(ctx.cacheDir, "dwg_$tag.dwg")
                        val stream = ctx.contentResolver.openInputStream(uri)
                            ?: throw IOException("파일을 열 수 없습니다: $uri")
                        val t0 = System.currentTimeMillis()
                        stream.use { it.copyTo(cacheFile.outputStream()) }
                        Log.i(TAG, "load: copied ${cacheFile.length()} bytes in ${System.currentTimeMillis() - t0}ms ($displayName)")

                        if (!isLikelyDwg(cacheFile, displayName)) {
                            Log.w(TAG, "load: not a DWG — name=$displayName")
                            throw IOException("DWG 도면 파일이 아닙니다. .dwg 파일을 열어주세요.")
                        }

                        // 복사본 영구화: filesDir/recent/<tag>.dwg 로 이동.
                        val recentDir = File(ctx.filesDir, "recent").apply { mkdirs() }
                        val dest = File(recentDir, "$tag.dwg")
                        if (!cacheFile.renameTo(dest)) {
                            cacheFile.copyTo(dest, overwrite = true)
                            cacheFile.delete()
                        }
                        dwgFile = dest
                    }

                    val t1 = System.currentTimeMillis()
                    val drawing = NativeDwg.parseToDrawing(dwgFile.absolutePath)
                    val t2 = System.currentTimeMillis()
                    val sheets = SheetClusterer.cluster(drawing.entities)
                    val t3 = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "load: parsed in ${t2 - t1}ms, clustered in ${t3 - t2}ms — " +
                            "entities=${drawing.entities.size}, layers=${drawing.layers.size}, " +
                            "entityColors=${drawing.entityColors.size}, sheets=${sheets.size}, " +
                            "extents=${drawing.extents}, displayExtents=${drawing.displayExtents}"
                    )
                    val displayExt = SheetClusterer.inclusiveExtents(drawing.entities, sheets)
                        ?: drawing.displayExtents
                    Log.i(TAG, "load: displayExtents(inclusive)=$displayExt")
                    LoadResult(
                        drawing.copy(sheets = sheets, displayExtents = displayExt),
                        displayName, uri, dwgFile.absolutePath, dwgFile.length()
                    )
                }
            }
            result.fold(
                onSuccess = { r ->
                    Log.i(TAG, "load: SUCCESS — ${r.drawing.entities.size} entities, copy=${r.localPath}")
                    _state.value = DrawingState.Success(r.drawing, r.displayName, r.uri, r.localPath, r.size)
                },
                onFailure = { e ->
                    Log.e(TAG, "load: FAILURE", e)
                    _state.value = DrawingState.Error(e.message ?: "알 수 없는 오류")
                }
            )
        }
    }

    private data class LoadResult(
        val drawing: Drawing,
        val displayName: String,
        val uri: Uri,
        val localPath: String,
        val size: Long,
    )

    private companion object {
        private const val TAG = "CleanCAD/ViewModel"
    }
}

/**
 * 복사된 파일이 실제 DWG 인지 가볍게 검증한다.
 * - 파일명이 .dwg 로 끝나면 신뢰(파일매니저/카톡은 원본 파일명을 유지).
 * - 그 외엔 DWG 매직헤더로 판별: 모든 DWG 는 "AC" + 버전("AC1003"~"AC1032", "AC1.x", "AC2.10").
 *
 * octet-stream 인텐트 필터를 폭넓게 열어둔 탓에 임의 바이너리가 들어올 수 있어,
 * 무거운 네이티브 파싱 전에 이걸로 거른다.
 */
internal fun isLikelyDwg(file: File, displayName: String): Boolean {
    if (displayName.endsWith(".dwg", ignoreCase = true)) return true
    return runCatching {
        file.inputStream().use { s ->
            val h = ByteArray(6)
            val n = s.read(h)
            n >= 3 && h[0] == 'A'.code.toByte() && h[1] == 'C'.code.toByte() &&
                (h[2] == '1'.code.toByte() || h[2] == '2'.code.toByte())
        }
    }.getOrDefault(false)
}
