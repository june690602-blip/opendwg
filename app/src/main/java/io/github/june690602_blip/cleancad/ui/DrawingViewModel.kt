package io.github.june690602_blip.cleancad.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.june690602_blip.cleancad.NativeDwg
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

    fun load(uri: Uri) {
        if (_state.value is DrawingState.Loading) return
        viewModelScope.launch {
            _state.value = DrawingState.Loading
            Log.i(TAG, "load: start uri=$uri")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()

                    val displayName = ctx.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: uri.toString()

                    val tag = uri.hashCode().toUInt().toString(16)
                    val dwgFile = File(ctx.cacheDir, "dwg_$tag.dwg")
                    val stream = ctx.contentResolver.openInputStream(uri)
                        ?: throw IOException("파일을 열 수 없습니다: $uri")
                    val t0 = System.currentTimeMillis()
                    stream.use { it.copyTo(dwgFile.outputStream()) }
                    val t1 = System.currentTimeMillis()
                    Log.i(TAG, "load: copied ${dwgFile.length()} bytes to cache in ${t1 - t0}ms ($displayName)")

                    // octet-stream 인텐트 필터를 폭넓게 열어둔 탓에 카톡 외 임의 바이너리도
                    // 들어올 수 있다. 무거운 네이티브 파싱 전에 실제 DWG 인지 가볍게 검증한다.
                    if (!isLikelyDwg(dwgFile, displayName)) {
                        Log.w(TAG, "load: not a DWG — name=$displayName")
                        throw IOException("DWG 도면 파일이 아닙니다. .dwg 파일을 열어주세요.")
                    }

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
                    sheets.forEachIndexed { i, s ->
                        Log.i(TAG, "load: sheet[$i] bbox=(${s.bbox.minX.toInt()},${s.bbox.minY.toInt()})~(${s.bbox.maxX.toInt()},${s.bbox.maxY.toInt()}) size=${s.bbox.width.toInt()}x${s.bbox.height.toInt()}")
                    }
                    // displayExtents: 검출 시트 합집합을 seed 로 1배 확장한 영역 안의 엔티티 bbox.
                    // 순수 sheet-union 은 표지·도면목록표·사업개요 같은 저밀도 시트(P5-P95 밖으로
                    // 밀려 검출 bbox에서 빠짐)를 renderBounds 로 컬링해 버린다. inclusiveExtents 는
                    // 인접 저밀도 시트는 포함하고 먼 junk outlier(±수백만)는 제외한다 (Phase 10.4).
                    val displayExt = SheetClusterer.inclusiveExtents(drawing.entities, sheets)
                        ?: drawing.displayExtents
                    Log.i(TAG, "load: displayExtents(inclusive)=$displayExt")
                    Triple(drawing.copy(sheets = sheets, displayExtents = displayExt), displayName, uri)
                }
            }
            result.fold(
                onSuccess = { (drawing, displayName, loadedUri) ->
                    Log.i(TAG, "load: SUCCESS — ${drawing.entities.size} entities")
                    _state.value = DrawingState.Success(drawing, displayName, loadedUri)
                },
                onFailure = { e ->
                    Log.e(TAG, "load: FAILURE", e)
                    _state.value = DrawingState.Error(e.message ?: "알 수 없는 오류")
                }
            )
        }
    }

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
