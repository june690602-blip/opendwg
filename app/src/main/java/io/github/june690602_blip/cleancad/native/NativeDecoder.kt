package io.github.june690602_blip.cleancad.native

import io.github.june690602_blip.cleancad.model.BoundingBox
import io.github.june690602_blip.cleancad.model.Dxf3DFace
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.model.DxfArc
import io.github.june690602_blip.cleancad.model.DxfCircle
import io.github.june690602_blip.cleancad.model.DxfDimension
import io.github.june690602_blip.cleancad.model.DxfEllipse
import io.github.june690602_blip.cleancad.model.DxfEntity
import io.github.june690602_blip.cleancad.model.DxfHatch
import io.github.june690602_blip.cleancad.model.DxfLeader
import io.github.june690602_blip.cleancad.model.DxfLine
import io.github.june690602_blip.cleancad.model.DxfLwPolyline
import io.github.june690602_blip.cleancad.model.DxfMText
import io.github.june690602_blip.cleancad.model.DxfPolyline
import io.github.june690602_blip.cleancad.model.DxfSolid
import io.github.june690602_blip.cleancad.model.DxfSpline
import io.github.june690602_blip.cleancad.model.DxfText
import io.github.june690602_blip.cleancad.model.Layer
import io.github.june690602_blip.cleancad.model.Vec2
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeDecoder {

    fun decode(bytes: ByteArray): Drawing {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        require(magic == NativeProtocol.MAGIC) {
            "잘못된 magic: 0x${magic.toUInt().toString(16)}"
        }
        val version = buf.short.toInt()
        require(version == NativeProtocol.VERSION) {
            "지원하지 않는 프로토콜 버전: $version (필요: ${NativeProtocol.VERSION})"
        }
        buf.short  // reserved
        val numLayers = buf.int
        val numEntities = buf.int
        val extentsPresent = buf.int

        val extents = if (extentsPresent == 1) {
            BoundingBox(buf.double, buf.double, buf.double, buf.double)
        } else null

        val layers = decodeLayers(buf, numLayers)
        val entities = decodeEntities(buf, numEntities, layers)

        return Drawing(entities, layers, extents, extents)
    }

    private fun decodeLayers(buf: ByteBuffer, count: Int): List<Layer> =
        List(count) {
            val nameLen = buf.short.toInt() and 0xFFFF
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)
            val colorIndex = buf.short.toInt()
            buf.int   // rgb (Task 9에서 사용)
            buf.get() // flags (Task 9에서 사용)
            Layer(name, colorIndex)
        }

    private fun decodeEntities(
        buf: ByteBuffer, count: Int, layers: List<Layer>
    ): List<DxfEntity> {
        val result = ArrayList<DxfEntity>(count)
        repeat(count) {
            val typeId = buf.get().toInt() and 0xFF
            val layerIdx = buf.int
            val colorIdx = buf.short
            val rgb = buf.int
            val layerName = layerNameAt(layers, layerIdx)
            val entity: DxfEntity? = when (typeId) {
                NativeProtocol.TYPE_LINE            -> decodeLine(buf, layerName)
                NativeProtocol.TYPE_CIRCLE          -> decodeCircle(buf, layerName)
                NativeProtocol.TYPE_ARC             -> decodeArc(buf, layerName)
                NativeProtocol.TYPE_LWPOLYLINE      -> decodeLwPolyline(buf, layerName)
                NativeProtocol.TYPE_POLYLINE_2D     -> decodePolyline(buf, layerName)
                NativeProtocol.TYPE_POLYLINE_3D     -> decodePolyline(buf, layerName)
                NativeProtocol.TYPE_TEXT            -> decodeText(buf, layerName)
                NativeProtocol.TYPE_MTEXT           -> decodeMText(buf, layerName)
                NativeProtocol.TYPE_3DFACE          -> decode3dFace(buf, layerName)
                NativeProtocol.TYPE_SOLID           -> decodeSolid(buf, layerName)
                NativeProtocol.TYPE_HATCH           -> decodeHatch(buf, layerName)
                NativeProtocol.TYPE_DIMENSION       -> decodeDimension(buf, layerName)
                NativeProtocol.TYPE_LEADER          -> decodeLeader(buf, layerName)
                NativeProtocol.TYPE_ELLIPSE         -> decodeEllipse(buf, layerName)
                NativeProtocol.TYPE_SPLINE          -> decodeSpline(buf, layerName)
                else -> {
                    skipUnknownPayload(buf, typeId)
                    null
                }
            }
            if (entity != null) result.add(entity)
        }
        return result
    }

    private fun layerNameAt(layers: List<Layer>, idx: Int): String =
        if (idx in layers.indices) layers[idx].name else "0"

    private fun decodeLine(buf: ByteBuffer, layer: String): DxfLine {
        val sx = buf.double; val sy = buf.double
        val ex = buf.double; val ey = buf.double
        return DxfLine(layer, Vec2(sx, sy), Vec2(ex, ey))
    }

    private fun decodeCircle(buf: ByteBuffer, layer: String): DxfCircle {
        val cx = buf.double; val cy = buf.double; val r = buf.double
        return DxfCircle(layer, Vec2(cx, cy), r)
    }

    private fun decodeArc(buf: ByteBuffer, layer: String): DxfArc {
        val cx = buf.double; val cy = buf.double; val r = buf.double
        val start = buf.double; val end = buf.double
        return DxfArc(layer, Vec2(cx, cy), r, start, end)
    }

    private fun decodeLwPolyline(buf: ByteBuffer, layer: String): DxfLwPolyline {
        val closed = buf.get() != 0.toByte()
        val n = buf.int
        val verts = List(n) { Vec2(buf.double, buf.double) }
        return DxfLwPolyline(layer, verts, closed)
    }

    private fun decodePolyline(buf: ByteBuffer, layer: String): DxfPolyline {
        val closed = buf.get() != 0.toByte()
        val n = buf.int
        val verts = List(n) { Vec2(buf.double, buf.double) }
        return DxfPolyline(layer, verts, closed)
    }

    private fun decodeText(buf: ByteBuffer, layer: String): DxfText {
        val ix = buf.double; val iy = buf.double
        val height = buf.double; val rot = buf.double
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len); buf.get(bytes)
        return DxfText(layer, Vec2(ix, iy), height, String(bytes, Charsets.UTF_8), rot)
    }

    private fun decodeMText(buf: ByteBuffer, layer: String): DxfMText {
        val ix = buf.double; val iy = buf.double
        val height = buf.double; val rot = buf.double
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len); buf.get(bytes)
        return DxfMText(layer, Vec2(ix, iy), height, String(bytes, Charsets.UTF_8), rot)
    }

    private fun decode3dFace(buf: ByteBuffer, layer: String): Dxf3DFace {
        val c1 = Vec2(buf.double, buf.double); val c2 = Vec2(buf.double, buf.double)
        val c3 = Vec2(buf.double, buf.double); val c4 = Vec2(buf.double, buf.double)
        return Dxf3DFace(layer, c1, c2, c3, c4)
    }

    private fun decodeSolid(buf: ByteBuffer, layer: String): DxfSolid {
        val c1 = Vec2(buf.double, buf.double); val c2 = Vec2(buf.double, buf.double)
        val c3 = Vec2(buf.double, buf.double); val c4 = Vec2(buf.double, buf.double)
        return DxfSolid(layer, c1, c2, c3, c4)
    }

    private fun decodeEllipse(buf: ByteBuffer, layer: String): DxfEllipse {
        val center = Vec2(buf.double, buf.double)
        val major = Vec2(buf.double, buf.double)
        val ratio = buf.double; val start = buf.double; val end = buf.double
        return DxfEllipse(layer, center, major, ratio, start, end)
    }

    private fun decodeSpline(buf: ByteBuffer, layer: String): DxfSpline {
        val degree = buf.int; val n = buf.int
        val ctrl = List(n) { Vec2(buf.double, buf.double) }
        return DxfSpline(layer, degree, ctrl)
    }

    private fun decodeHatch(buf: ByteBuffer, layer: String): DxfHatch {
        val isSolid = buf.get() != 0.toByte()
        val numPaths = buf.int
        val paths = List(numPaths) {
            val n = buf.int
            List(n) { Vec2(buf.double, buf.double) }
        }
        return DxfHatch(layer, isSolid, paths)
    }

    private fun decodeDimension(buf: ByteBuffer, layer: String): DxfDimension {
        val def = Vec2(buf.double, buf.double)
        val tp = Vec2(buf.double, buf.double)
        val dimType = buf.int
        val len = buf.short.toInt() and 0xFFFF
        val bytes = ByteArray(len); buf.get(bytes)
        return DxfDimension(layer, def, tp, dimType, String(bytes, Charsets.UTF_8))
    }

    private fun decodeLeader(buf: ByteBuffer, layer: String): DxfLeader {
        val n = buf.int
        val verts = List(n) { Vec2(buf.double, buf.double) }
        return DxfLeader(layer, verts)
    }

    /** UNKNOWN 또는 아직 미지원 타입의 페이로드를 건너뛴다. Task 별로 케이스 추가. */
    private fun skipUnknownPayload(buf: ByteBuffer, typeId: Int) {
        if (typeId == NativeProtocol.TYPE_UNKNOWN) {
            val len = buf.short.toInt() and 0xFFFF
            buf.position(buf.position() + len)
        }
        // 그 외 미지원 타입은 후속 Task에서 처리.
    }
}
