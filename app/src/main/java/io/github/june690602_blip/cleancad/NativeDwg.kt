package io.github.june690602_blip.cleancad

object NativeDwg {
    init {
        System.loadLibrary("dwgjni")
    }

    external fun nativeLibredwgVersion(): String
    external fun nativeDwgToDxf(inPath: String, outPath: String): Int
    external fun nativeDwgToBuffer(inPath: String): ByteArray?
}
