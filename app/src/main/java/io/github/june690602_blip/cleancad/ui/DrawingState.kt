package io.github.june690602_blip.cleancad.ui

import android.net.Uri
import io.github.june690602_blip.cleancad.model.Drawing

sealed class DrawingState {
    object Idle    : DrawingState()
    object Loading : DrawingState()
    data class Success(
        val drawing: Drawing,
        val displayName: String,
        val uri: Uri
    ) : DrawingState()
    data class Error(val message: String) : DrawingState()
}
