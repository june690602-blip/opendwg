package io.github.june690602_blip.cleancad.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.june690602_blip.cleancad.NativeDwg
import io.github.june690602_blip.cleancad.parser.DxfParser
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()

                    val displayName = ctx.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: uri.toString()

                    val dwgFile = File(ctx.cacheDir, "current.dwg")
                    val stream = ctx.contentResolver.openInputStream(uri)
                        ?: throw IOException("파일을 열 수 없습니다: $uri")
                    stream.use { it.copyTo(dwgFile.outputStream()) }

                    val dxfFile = File(ctx.cacheDir, "current.dxf")
                    val rc = NativeDwg.nativeDwgToDxf(dwgFile.absolutePath, dxfFile.absolutePath)
                    if (rc != 0) throw RuntimeException("DWG 변환 실패 (코드: $rc)")

                    val drawing = DxfParser.parse(dxfFile.readText())
                    Triple(drawing, displayName, uri)
                }
            }
            result.fold(
                onSuccess = { (drawing, displayName, loadedUri) ->
                    _state.value = DrawingState.Success(drawing, displayName, loadedUri)
                },
                onFailure = { e ->
                    _state.value = DrawingState.Error(e.message ?: "알 수 없는 오류")
                }
            )
        }
    }
}
