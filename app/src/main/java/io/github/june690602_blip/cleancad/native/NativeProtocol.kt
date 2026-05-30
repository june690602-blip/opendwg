package io.github.june690602_blip.cleancad.native

object NativeProtocol {
    const val MAGIC: Int = 0x42475744  // 'DWGB' little-endian
    const val VERSION: Int = 1

    const val TYPE_LINE: Int = 1
    const val TYPE_CIRCLE: Int = 2
    const val TYPE_ARC: Int = 3
    const val TYPE_LWPOLYLINE: Int = 4
    const val TYPE_POLYLINE_2D: Int = 5
    const val TYPE_POLYLINE_3D: Int = 6
    const val TYPE_TEXT: Int = 7
    const val TYPE_MTEXT: Int = 8
    const val TYPE_HATCH: Int = 10
    const val TYPE_DIMENSION: Int = 11
    const val TYPE_LEADER: Int = 12
    const val TYPE_ELLIPSE: Int = 13
    const val TYPE_SPLINE: Int = 14
    const val TYPE_3DFACE: Int = 15
    const val TYPE_SOLID: Int = 16
    const val TYPE_POINT: Int = 17
    const val TYPE_UNKNOWN: Int = 0xFF

    const val COLOR_BYLAYER: Short = -1
    const val COLOR_BYBLOCK: Short = 0
}
