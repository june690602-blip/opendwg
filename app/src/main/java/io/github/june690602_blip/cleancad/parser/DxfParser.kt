package io.github.june690602_blip.cleancad.parser

import io.github.june690602_blip.cleancad.model.*
import java.io.BufferedReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

object DxfParser {

    private fun decodeDxfText(raw: String): String = raw
        .replace(Regex("%%[Dd]"), "°")
        .replace(Regex("%%[Pp]"), "±")
        .replace(Regex("%%[Cc]"), "⌀")
        .replace(Regex("%%[Uu]"), "")
        .replace(Regex("%%[Oo]"), "")
        .replace(Regex("%%([0-9]{3})")) { mr ->
            mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: mr.value
        }

    /** 렌더링 성능을 보장하기 위한 최대 엔티티 수. 이 값을 초과하면 확장을 중단한다. */
    private const val MAX_ENTITIES = 50_000

    /**
     * 스트리밍 진입점 — 대용량 DXF 파일에 적합.
     * [BufferedReader]를 직접 받아 전체 파일을 메모리에 올리지 않는다.
     */
    fun parse(input: BufferedReader): Drawing {
        val reader = DxfReader(input)
        val layers = mutableListOf<Layer>()
        val rawEntities = mutableListOf<DxfEntity>()
        val blockDefs = mutableMapOf<String, MutableList<DxfEntity>>()

        while (reader.hasNext()) {
            val gc = reader.next()
            if (gc.code == 0 && gc.value == "SECTION") {
                val name = reader.next()
                when (name.value) {
                    "TABLES"   -> parseTables(reader, layers)
                    "BLOCKS"   -> parseAllBlockDefs(reader, blockDefs)
                    "ENTITIES" -> parseEntities(reader, rawEntities)
                    else       -> skipSection(reader)
                }
            }
        }

        // INSERT 블록 참조를 실제 지오메트리로 펼친다 (최대 5단계 깊이, MAX_ENTITIES 상한)
        val count = intArrayOf(0)
        val entities = expandEntities(rawEntities, blockDefs, depth = 0, count = count)

        return Drawing(
            entities = entities,
            layers = layers,
            extents = calculateBoundingBox(entities),
            displayExtents = trimmedBoundingBox(entities)
        )
    }

    /**
     * 문자열 진입점 — 단위 테스트 및 소형 DXF 문자열 전용.
     * 대용량 파일에는 [parse(BufferedReader)]를 사용할 것.
     */
    fun parse(dxfContent: String): Drawing = dxfContent.reader().buffered().use { parse(it) }

    // ---- INSERT 블록 확장 ----

    /**
     * INSERT 엔티티를 재귀적으로 실제 지오메트리로 펼친다.
     * [count]는 누적 엔티티 수를 추적하는 공유 카운터 (IntArray(1)).
     * [MAX_ENTITIES] 도달 시 확장을 조기 종료하여 ANR/OOM을 방지한다.
     */
    private fun expandEntities(
        list: List<DxfEntity>,
        defs: Map<String, List<DxfEntity>>,
        depth: Int,
        count: IntArray
    ): List<DxfEntity> {
        if (depth > 5 || count[0] >= MAX_ENTITIES) return emptyList()
        val result = mutableListOf<DxfEntity>()
        for (e in list) {
            if (count[0] >= MAX_ENTITIES) break
            if (e is DxfInsert) {
                val blockEntities = defs[e.blockName]
                if (blockEntities == null) {
                    // 블록 정의 없음 → INSERT 원본 유지 (레거시 파일 호환)
                    result.add(e)
                    count[0]++
                } else {
                    val transformed = blockEntities.mapNotNull { child ->
                        transformEntity(child, e)
                    }
                    result.addAll(expandEntities(transformed, defs, depth + 1, count))
                }
            } else {
                result.add(e)
                count[0]++
            }
        }
        return result
    }

    private fun transformEntity(e: DxfEntity, ins: DxfInsert): DxfEntity? {
        fun tp(pt: Vec2): Vec2 {
            val rad = ins.rotationDeg * PI / 180.0
            val c = cos(rad); val s = sin(rad)
            val sx = pt.x * ins.scaleX; val sy = pt.y * ins.scaleY
            return Vec2(ins.insertionPoint.x + sx * c - sy * s,
                        ins.insertionPoint.y + sx * s + sy * c)
        }
        return when (e) {
            is DxfLine       -> e.copy(start = tp(e.start), end = tp(e.end))
            is DxfCircle     -> e.copy(center = tp(e.center))
            is DxfArc        -> e.copy(center = tp(e.center))
            is DxfLwPolyline -> e.copy(vertices = e.vertices.map { tp(it) })
            is DxfPolyline   -> e.copy(vertices = e.vertices.map { tp(it) })
            is Dxf3DFace     -> e.copy(
                corner1 = tp(e.corner1), corner2 = tp(e.corner2),
                corner3 = tp(e.corner3), corner4 = tp(e.corner4)
            )
            is DxfSolid      -> e.copy(
                corner1 = tp(e.corner1), corner2 = tp(e.corner2),
                corner3 = tp(e.corner3), corner4 = tp(e.corner4)
            )
            is DxfEllipse    -> e.copy(center = tp(e.center))
            is DxfSpline     -> e.copy(controlPoints = e.controlPoints.map { tp(it) })
            is DxfText       -> e.copy(insertionPoint = tp(e.insertionPoint))
            is DxfMText      -> e.copy(insertionPoint = tp(e.insertionPoint))
            is DxfInsert     -> e.copy(insertionPoint = tp(e.insertionPoint))
            is DxfDimension  -> e.copy(definitionPoint = tp(e.definitionPoint),
                                       textMidPoint   = tp(e.textMidPoint))
            is DxfLeader     -> e.copy(vertices = e.vertices.map { tp(it) })
            is DxfHatch      -> e.copy(paths = e.paths.map { path -> path.map { tp(it) } })
            is DxfUnknown    -> null
        }
    }

    // ---- 섹션 파싱 ----

    private fun skipSection(reader: DxfReader) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDSEC") { reader.next(); return }
            reader.next()
        }
    }

    private fun parseTables(reader: DxfReader, layers: MutableList<Layer>) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDSEC") { reader.next(); return }
            val next = reader.next()
            if (next.code == 0 && next.value == "TABLE") {
                val tableType = reader.next()
                if (tableType.value == "LAYER") parseLayerTable(reader, layers)
                else skipUntil(reader, "ENDTAB")
            }
        }
    }

    private fun parseLayerTable(reader: DxfReader, layers: MutableList<Layer>) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDTAB") { reader.next(); return }
            if (gc.code == 0 && gc.value == "LAYER") {
                reader.next()
                layers.add(parseLayerEntry(reader))
            } else {
                reader.next()
            }
        }
    }

    private fun parseLayerEntry(reader: DxfReader): Layer {
        var name = ""
        var colorIndex = 7
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                2 -> name = next.value
                62 -> colorIndex = next.value.toIntOrNull() ?: 7
            }
        }
        return Layer(name = name, colorIndex = colorIndex)
    }

    private fun skipUntil(reader: DxfReader, token: String) {
        while (reader.hasNext()) {
            val gc = reader.next()
            if (gc.code == 0 && gc.value == token) return
        }
    }

    // BLOCKS 섹션을 파싱해 모든 명명 블록 정의를 수집한다.
    // *Model_Space, *Paper_Space 등 특수 블록(* 접두어)은 제외한다.
    private fun parseAllBlockDefs(
        reader: DxfReader,
        defs: MutableMap<String, MutableList<DxfEntity>>
    ) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDSEC") { reader.next(); return }
            val next = reader.next()
            if (next.code == 0 && next.value == "BLOCK") {
                // 블록 헤더에서 이름(그룹코드 2) 읽기
                var blockName = ""
                while (reader.hasNext()) {
                    val hdr = reader.peek() ?: break
                    if (hdr.code == 0) break
                    val h = reader.next()
                    if (h.code == 2) blockName = h.value
                }
                val isSpecial = blockName.startsWith("*", ignoreCase = false)
                val blockEntities: MutableList<DxfEntity>? = if (!isSpecial) mutableListOf() else null
                // 블록 본문 파싱
                while (reader.hasNext()) {
                    val body = reader.peek() ?: break
                    if (body.code == 0 && body.value == "ENDBLK") { reader.next(); break }
                    val b = reader.next()
                    if (b.code == 0) {
                        val entity = parseEntity(b.value, reader)
                        if (blockEntities != null && entity != null) blockEntities.add(entity)
                        else if (blockEntities == null) { /* special block — entities already skipped by parseEntity */ }
                    }
                }
                if (blockEntities != null && blockName.isNotEmpty()) {
                    defs[blockName] = blockEntities
                }
            }
        }
    }

    private fun parseEntities(reader: DxfReader, entities: MutableList<DxfEntity>) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0 && gc.value == "ENDSEC") { reader.next(); return }
            val next = reader.next()
            if (next.code == 0) {
                parseEntity(next.value, reader)?.let { entities.add(it) }
            }
        }
    }

    private fun parseEntity(type: String, reader: DxfReader): DxfEntity? = when (type) {
        "LINE"       -> parseLine(reader)
        "CIRCLE"     -> parseCircle(reader)
        "ARC"        -> parseArc(reader)
        "LWPOLYLINE" -> parseLwPolyline(reader)
        "POLYLINE"   -> parsePolyline(reader)
        "3DFACE"     -> parse3dFace(reader)
        "SOLID"      -> parseSolid(reader)
        "ELLIPSE"    -> parseEllipse(reader)
        "SPLINE"     -> parseSpline(reader)
        "TEXT"       -> parseText(reader)
        "MTEXT"      -> parseMText(reader)
        "INSERT"     -> parseInsert(reader)
        "DIMENSION"  -> parseDimension(reader)
        "HATCH"      -> parseHatch(reader)
        "LEADER"     -> parseLeader(reader)
        else         -> { skipEntityBody(reader); DxfUnknown(layer = "", type = type) }
    }

    private fun skipEntityBody(reader: DxfReader) {
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            reader.next()
        }
    }

    // ---- 엔티티 파서 ----

    private fun parseLine(reader: DxfReader): DxfLine {
        var layer = "0"; var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x1 = next.value.toDouble()
                20 -> y1 = next.value.toDouble()
                11 -> x2 = next.value.toDouble()
                21 -> y2 = next.value.toDouble()
            }
        }
        return DxfLine(layer, Vec2(x1, y1), Vec2(x2, y2))
    }

    private fun parseCircle(reader: DxfReader): DxfCircle {
        var layer = "0"; var cx = 0.0; var cy = 0.0; var r = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> cx = next.value.toDouble()
                20 -> cy = next.value.toDouble()
                40 -> r = next.value.toDouble()
            }
        }
        return DxfCircle(layer, Vec2(cx, cy), r)
    }

    private fun parseArc(reader: DxfReader): DxfArc {
        var layer = "0"; var cx = 0.0; var cy = 0.0; var r = 0.0
        var startAngle = 0.0; var endAngle = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> cx = next.value.toDouble()
                20 -> cy = next.value.toDouble()
                40 -> r = next.value.toDouble()
                50 -> startAngle = next.value.toDouble()
                51 -> endAngle = next.value.toDouble()
            }
        }
        return DxfArc(layer, Vec2(cx, cy), r, startAngle, endAngle)
    }

    private fun parseLwPolyline(reader: DxfReader): DxfLwPolyline {
        var layer = "0"; var closed = false
        val vertices = mutableListOf<Vec2>()
        var currentX = 0.0; var hasX = false
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                70 -> closed = (next.value.toIntOrNull() ?: 0) and 1 != 0
                10 -> { currentX = next.value.toDouble(); hasX = true }
                20 -> if (hasX) { vertices.add(Vec2(currentX, next.value.toDouble())); hasX = false }
            }
        }
        return DxfLwPolyline(layer, vertices, closed)
    }

    private fun parsePolyline(reader: DxfReader): DxfPolyline {
        var layer = "0"
        var closed = false
        val vertices = mutableListOf<Vec2>()

        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                70 -> closed = (next.value.toIntOrNull() ?: 0) and 1 != 0
            }
        }

        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code != 0) { reader.next(); continue }
            val typeGc = reader.next()
            when (typeGc.value) {
                "VERTEX" -> {
                    var vx = 0.0; var vy = 0.0
                    while (reader.hasNext()) {
                        val v = reader.peek() ?: break
                        if (v.code == 0) break
                        val nv = reader.next()
                        when (nv.code) {
                            10 -> vx = nv.value.toDouble()
                            20 -> vy = nv.value.toDouble()
                        }
                    }
                    vertices.add(Vec2(vx, vy))
                }
                "SEQEND" -> {
                    while (reader.hasNext()) {
                        val s = reader.peek() ?: break
                        if (s.code == 0) break
                        reader.next()
                    }
                    return DxfPolyline(layer, vertices, closed)
                }
                else -> {
                    while (reader.hasNext()) {
                        val s = reader.peek() ?: break
                        if (s.code == 0) break
                        reader.next()
                    }
                }
            }
        }
        return DxfPolyline(layer, vertices, closed)
    }

    private fun parse3dFace(reader: DxfReader): Dxf3DFace {
        var layer = "0"
        var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        var x3 = 0.0; var y3 = 0.0; var x4 = 0.0; var y4 = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x1 = next.value.toDouble()
                20 -> y1 = next.value.toDouble()
                11 -> x2 = next.value.toDouble()
                21 -> y2 = next.value.toDouble()
                12 -> x3 = next.value.toDouble()
                22 -> y3 = next.value.toDouble()
                13 -> x4 = next.value.toDouble()
                23 -> y4 = next.value.toDouble()
            }
        }
        return Dxf3DFace(layer, Vec2(x1, y1), Vec2(x2, y2), Vec2(x3, y3), Vec2(x4, y4))
    }

    private fun parseSolid(reader: DxfReader): DxfSolid {
        var layer = "0"
        var x1 = 0.0; var y1 = 0.0; var x2 = 0.0; var y2 = 0.0
        var x3 = 0.0; var y3 = 0.0; var x4 = 0.0; var y4 = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x1 = next.value.toDouble()
                20 -> y1 = next.value.toDouble()
                11 -> x2 = next.value.toDouble()
                21 -> y2 = next.value.toDouble()
                12 -> x3 = next.value.toDouble()
                22 -> y3 = next.value.toDouble()
                13 -> x4 = next.value.toDouble()
                23 -> y4 = next.value.toDouble()
            }
        }
        return DxfSolid(layer, Vec2(x1, y1), Vec2(x2, y2), Vec2(x3, y3), Vec2(x4, y4))
    }

    private fun parseEllipse(reader: DxfReader): DxfEllipse {
        var layer = "0"; var cx = 0.0; var cy = 0.0
        var majX = 0.0; var majY = 0.0; var minorRatio = 1.0
        var startParam = 0.0; var endParam = Math.PI * 2
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> cx = next.value.toDouble()
                20 -> cy = next.value.toDouble()
                11 -> majX = next.value.toDouble()
                21 -> majY = next.value.toDouble()
                40 -> minorRatio = next.value.toDouble()
                41 -> startParam = next.value.toDouble()
                42 -> endParam = next.value.toDouble()
            }
        }
        return DxfEllipse(layer, Vec2(cx, cy), Vec2(majX, majY), minorRatio, startParam, endParam)
    }

    private fun parseSpline(reader: DxfReader): DxfSpline {
        var layer = "0"; var degree = 3
        val controlPoints = mutableListOf<Vec2>()
        var cpX = 0.0; var hasCpX = false
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                71 -> degree = next.value.toIntOrNull() ?: 3
                10 -> { cpX = next.value.toDouble(); hasCpX = true }
                20 -> if (hasCpX) { controlPoints.add(Vec2(cpX, next.value.toDouble())); hasCpX = false }
            }
        }
        return DxfSpline(layer, degree, controlPoints)
    }

    private fun parseText(reader: DxfReader): DxfText {
        var layer = "0"; var x = 0.0; var y = 0.0
        var height = 0.0; var text = ""; var rotation = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> x = next.value.toDouble()
                20 -> y = next.value.toDouble()
                40 -> height = next.value.toDouble()
                1  -> text = next.value
                50 -> rotation = next.value.toDouble()
            }
        }
        return DxfText(layer, Vec2(x, y), height, decodeDxfText(text), rotation)
    }

    private fun parseMText(reader: DxfReader): DxfMText {
        var layer = "0"; var x = 0.0; var y = 0.0
        var height = 0.0; val textParts = mutableListOf<String>(); var rotation = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8     -> layer = next.value
                10    -> x = next.value.toDouble()
                20    -> y = next.value.toDouble()
                40    -> height = next.value.toDouble()
                1, 3  -> textParts.add(next.value)
                50    -> rotation = next.value.toDouble()
            }
        }
        return DxfMText(layer, Vec2(x, y), height, decodeDxfText(textParts.joinToString("")), rotation)
    }

    private fun parseInsert(reader: DxfReader): DxfInsert {
        var layer = "0"; var blockName = ""; var x = 0.0; var y = 0.0
        var scaleX = 1.0; var scaleY = 1.0; var rotation = 0.0
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                2  -> blockName = next.value
                10 -> x = next.value.toDouble()
                20 -> y = next.value.toDouble()
                41 -> scaleX = next.value.toDouble()
                42 -> scaleY = next.value.toDouble()
                50 -> rotation = next.value.toDouble()
            }
        }
        return DxfInsert(layer, blockName, Vec2(x, y), scaleX, scaleY, rotation)
    }

    private fun parseDimension(reader: DxfReader): DxfDimension {
        var layer = "0"; var defX = 0.0; var defY = 0.0
        var midX = 0.0; var midY = 0.0; var dimType = 0; var textOverride = ""
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> defX = next.value.toDouble()
                20 -> defY = next.value.toDouble()
                11 -> midX = next.value.toDouble()
                21 -> midY = next.value.toDouble()
                70 -> dimType = next.value.toIntOrNull() ?: 0
                1  -> textOverride = next.value
            }
        }
        return DxfDimension(layer, Vec2(defX, defY), Vec2(midX, midY), dimType, textOverride)
    }

    private fun parseHatch(reader: DxfReader): DxfHatch {
        var layer = "0"
        var isSolid = false
        var numPaths = 0
        val paths = mutableListOf<List<Vec2>>()

        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) return DxfHatch(layer, isSolid, paths)
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                70 -> isSolid = next.value.trim() == "1"
                91 -> { numPaths = next.value.toIntOrNull() ?: 0; break }
            }
        }

        repeat(numPaths) {
            val path = parseHatchBoundaryPath(reader) ?: return@repeat
            if (path.isNotEmpty()) paths.add(path)
        }

        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            reader.next()
        }

        return DxfHatch(layer, isSolid, paths)
    }

    private fun parseHatchBoundaryPath(reader: DxfReader): List<Vec2>? {
        var isPolyline = false
        var sawNumItems = false
        val vertices = mutableListOf<Vec2>()
        var lastVx = 0.0; var lastVy = 0.0; var sawLastVx = false

        while (reader.hasNext()) {
            val gc = reader.peek() ?: break
            if (gc.code == 0) break
            if (gc.code == 97) break
            if (gc.code == 75) break
            if (gc.code == 92 && sawNumItems) break

            val next = reader.next()
            when (next.code) {
                92 -> {
                    val pathTypeFlag = next.value.toIntOrNull() ?: 0
                    isPolyline = (pathTypeFlag and 2) != 0
                }
                93 -> {
                    sawNumItems = true
                }
                72 -> {
                    if (!isPolyline) {
                        sawLastVx = false
                    }
                }
                10 -> {
                    lastVx = next.value.toDouble(); sawLastVx = true
                }
                20 -> {
                    if (sawLastVx) {
                        vertices.add(Vec2(lastVx, next.value.toDouble()))
                        sawLastVx = false
                    }
                }
                11 -> {
                    if (!isPolyline) { lastVx = next.value.toDouble(); sawLastVx = true }
                }
                21 -> {
                    if (!isPolyline && sawLastVx) {
                        vertices.add(Vec2(lastVx, next.value.toDouble()))
                        sawLastVx = false
                    }
                }
            }
        }
        return vertices
    }

    private fun parseLeader(reader: DxfReader): DxfLeader {
        var layer = "0"
        val vertices = mutableListOf<Vec2>()
        var vx = 0.0; var hasVx = false
        while (reader.hasNext()) {
            val gc = reader.peek() ?: break; if (gc.code == 0) break
            val next = reader.next()
            when (next.code) {
                8  -> layer = next.value
                10 -> { vx = next.value.toDouble(); hasVx = true }
                20 -> if (hasVx) { vertices.add(Vec2(vx, next.value.toDouble())); hasVx = false }
            }
        }
        return DxfLeader(layer, vertices)
    }

    // ---- BoundingBox ----

    private fun collectBoundingPoints(entities: List<DxfEntity>): List<Vec2> {
        val points = mutableListOf<Vec2>()
        for (entity in entities) {
            when (entity) {
                is DxfLine       -> { points.add(entity.start); points.add(entity.end) }
                is DxfCircle     -> { val r = entity.radius; val c = entity.center
                                      points.add(Vec2(c.x - r, c.y - r)); points.add(Vec2(c.x + r, c.y + r)) }
                is DxfArc        -> { val r = entity.radius; val c = entity.center
                                      points.add(Vec2(c.x - r, c.y - r)); points.add(Vec2(c.x + r, c.y + r)) }
                is DxfLwPolyline -> points.addAll(entity.vertices)
                is DxfPolyline   -> points.addAll(entity.vertices)
                is Dxf3DFace     -> {
                    points.add(entity.corner1); points.add(entity.corner2)
                    points.add(entity.corner3); points.add(entity.corner4)
                }
                is DxfSolid      -> {
                    points.add(entity.corner1); points.add(entity.corner2)
                    points.add(entity.corner3); points.add(entity.corner4)
                }
                is DxfEllipse    -> { val len = Math.hypot(entity.majorAxis.x, entity.majorAxis.y)
                                      val c = entity.center
                                      points.add(Vec2(c.x - len, c.y - len)); points.add(Vec2(c.x + len, c.y + len)) }
                is DxfSpline     -> points.addAll(entity.controlPoints)
                is DxfText       -> points.add(entity.insertionPoint)
                is DxfMText      -> points.add(entity.insertionPoint)
                is DxfInsert     -> points.add(entity.insertionPoint)
                is DxfDimension  -> { points.add(entity.definitionPoint); points.add(entity.textMidPoint) }
                is DxfLeader     -> points.addAll(entity.vertices)
                is DxfHatch      -> entity.paths.forEach { points.addAll(it) }
                is DxfUnknown    -> {}
            }
        }
        return points
    }

    private fun calculateBoundingBox(entities: List<DxfEntity>): BoundingBox? {
        val points = collectBoundingPoints(entities)
        if (points.isEmpty()) return null
        return BoundingBox(
            minX = points.minOf { it.x }, minY = points.minOf { it.y },
            maxX = points.maxOf { it.x }, maxY = points.maxOf { it.y }
        )
    }

    private fun trimmedBoundingBox(entities: List<DxfEntity>, trimRatio: Double = 0.05): BoundingBox? {
        val points = collectBoundingPoints(entities)
        if (points.size < 4) return calculateBoundingBox(entities)
        val xs = points.map { it.x }.sorted()
        val ys = points.map { it.y }.sorted()
        val lo = (points.size * trimRatio).toInt().coerceAtLeast(0)
        val hi = (points.size * (1 - trimRatio)).toInt().coerceAtMost(points.size - 1)
        if (lo >= hi) return calculateBoundingBox(entities)
        return BoundingBox(xs[lo], ys[lo], xs[hi], ys[hi])
    }
}
