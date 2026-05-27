package io.github.june690602_blip.cleancad.native

import io.github.june690602_blip.cleancad.model.BoundingBox
import io.github.june690602_blip.cleancad.model.Drawing
import io.github.june690602_blip.cleancad.model.DxfEntity
import io.github.june690602_blip.cleancad.model.DxfLine
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
                NativeProtocol.TYPE_LINE -> decodeLine(buf, layerName)
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

    /** UNKNOWN 또는 아직 미지원 타입의 페이로드를 건너뛴다. Task 별로 케이스 추가. */
    private fun skipUnknownPayload(buf: ByteBuffer, typeId: Int) {
        if (typeId == NativeProtocol.TYPE_UNKNOWN) {
            val len = buf.short.toInt() and 0xFFFF
            buf.position(buf.position() + len)
        }
        // 그 외 미지원 타입은 후속 Task에서 처리.
    }
}
