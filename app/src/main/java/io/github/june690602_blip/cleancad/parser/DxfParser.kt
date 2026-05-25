package io.github.june690602_blip.cleancad.parser

import io.github.june690602_blip.cleancad.model.*

object DxfParser {

    fun parse(dxfContent: String): Drawing {
        val reader = DxfReader(dxfContent)
        val layers = mutableListOf<Layer>()
        val entities = mutableListOf<DxfEntity>()

        while (reader.hasNext()) {
            val gc = reader.next()
            if (gc.code == 0 && gc.value == "SECTION") {
                val name = reader.next()
                when (name.value) {
                    "TABLES" -> parseTables(reader, layers)
                    "ENTITIES" -> parseEntities(reader, entities)
                    else -> skipSection(reader)
                }
            }
        }

        return Drawing(
            entities = entities,
            layers = layers,
            extents = calculateBoundingBox(entities)
        )
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
        return DxfText(layer, Vec2(x, y), height, text, rotation)
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
        return DxfMText(layer, Vec2(x, y), height, textParts.joinToString(""), rotation)
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

    // Task 6에서 구현
    private fun parseDimension(reader: DxfReader): DxfDimension {
        skipEntityBody(reader); return DxfDimension("0", Vec2(0.0, 0.0), Vec2(0.0, 0.0), 0)
    }
    private fun parseHatch(reader: DxfReader): DxfHatch {
        skipEntityBody(reader); return DxfHatch("0", false)
    }
    private fun parseLeader(reader: DxfReader): DxfLeader {
        skipEntityBody(reader); return DxfLeader("0", emptyList())
    }

    // ---- BoundingBox ----

    private fun calculateBoundingBox(entities: List<DxfEntity>): BoundingBox? {
        val points = mutableListOf<Vec2>()
        for (entity in entities) {
            when (entity) {
                is DxfLine       -> { points.add(entity.start); points.add(entity.end) }
                is DxfCircle     -> { val r = entity.radius; val c = entity.center
                                      points.add(Vec2(c.x - r, c.y - r)); points.add(Vec2(c.x + r, c.y + r)) }
                is DxfArc        -> { val r = entity.radius; val c = entity.center
                                      points.add(Vec2(c.x - r, c.y - r)); points.add(Vec2(c.x + r, c.y + r)) }
                is DxfLwPolyline -> points.addAll(entity.vertices)
                is DxfEllipse    -> { val len = Math.hypot(entity.majorAxis.x, entity.majorAxis.y)
                                      val c = entity.center
                                      points.add(Vec2(c.x - len, c.y - len)); points.add(Vec2(c.x + len, c.y + len)) }
                is DxfSpline     -> points.addAll(entity.controlPoints)
                is DxfText       -> points.add(entity.insertionPoint)
                is DxfMText      -> points.add(entity.insertionPoint)
                is DxfInsert     -> points.add(entity.insertionPoint)
                is DxfDimension  -> { points.add(entity.definitionPoint); points.add(entity.textMidPoint) }
                is DxfLeader     -> points.addAll(entity.vertices)
                is DxfHatch, is DxfUnknown -> {}
            }
        }
        if (points.isEmpty()) return null
        return BoundingBox(
            minX = points.minOf { it.x }, minY = points.minOf { it.y },
            maxX = points.maxOf { it.x }, maxY = points.maxOf { it.y }
        )
    }
}
