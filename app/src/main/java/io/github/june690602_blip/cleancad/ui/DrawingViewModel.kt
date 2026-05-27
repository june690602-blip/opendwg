package io.github.june690602_blip.cleancad.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.june690602_blip.cleancad.NativeDwg
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

                    val tag = uri.hashCode().toUInt().toString(16)
                    val dwgFile = File(ctx.cacheDir, "dwg_$tag.dwg")
                    val stream = ctx.contentResolver.openInputStream(uri)
                        ?: throw IOException("파일을 열 수 없습니다: $uri")
                    stream.use { it.copyTo(dwgFile.outputStream()) }

                    val drawing = NativeDwg.parseToDrawing(dwgFile.absolutePath)
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
