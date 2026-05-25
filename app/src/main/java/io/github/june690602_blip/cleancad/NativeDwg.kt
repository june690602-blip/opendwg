package io.github.june690602_blip.cleancad

object NativeDwg {
    init {
        System.loadLibrary("dwgjni")
    }

    external fun nativeLibredwgVersion(): String
}
