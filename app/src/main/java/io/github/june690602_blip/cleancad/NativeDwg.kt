package io.github.june690602_blip.cleancad

object NativeDwg {
    init {
        System.loadLibrary("dwgjni")
    }

    external fun nativeLibredwgVersion(): String
    external fun nativeDwgToBuffer(inPath: String): ByteArray?

    /**
     * DWG 파일을 native에서 파싱하여 Drawing으로 변환한다.
     * 실패 시 RuntimeException을 던진다.
     */
    fun parseToDrawing(dwgPath: String): io.github.june690602_blip.cleancad.model.Drawing {
        val bytes = nativeDwgToBuffer(dwgPath)
            ?: throw RuntimeException("DWG 파싱 실패: $dwgPath")
        return io.github.june690602_blip.cleancad.native.NativeDecoder.decode(bytes)
    }
}
