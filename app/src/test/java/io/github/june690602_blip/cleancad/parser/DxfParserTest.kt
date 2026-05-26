package io.github.june690602_blip.cleancad.parser

import io.github.june690602_blip.cleancad.model.*
import org.junit.Assert.*
import org.junit.Test

class DxfParserTest {

    // DXF 텍스트 내 ENTITIES 섹션을 감싸는 헬퍼
    private fun withEntities(block: String) = """
  0
SECTION
  2
ENTITIES
$block
  0
ENDSEC
  0
EOF""".trimIndent()

    // ---- LINE ----

    @Test
    fun parseLine_returnsCorrectCoordinates() {
        val dxf = withEntities("""
  0
LINE
  8
walls
 10
0.0
 20
0.0
 11
100.0
 21
50.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)

        assertEquals(1, drawing.entities.size)
        val line = drawing.entities[0] as DxfLine
        assertEquals("walls", line.layer)
        assertEquals(Vec2(0.0, 0.0), line.start)
        assertEquals(Vec2(100.0, 50.0), line.end)
    }

    @Test
    fun parseLine_defaultLayerIsZero() {
        val dxf = withEntities("""
  0
LINE
 10
1.0
 20
2.0
 11
3.0
 21
4.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        val line = drawing.entities[0] as DxfLine
        assertEquals("0", line.layer)
    }

    // ---- CIRCLE ----

    @Test
    fun parseCircle_returnsCorrectCenterAndRadius() {
        val dxf = withEntities("""
  0
CIRCLE
  8
pipes
 10
50.0
 20
75.0
 40
25.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)

        assertEquals(1, drawing.entities.size)
        val circle = drawing.entities[0] as DxfCircle
        assertEquals("pipes", circle.layer)
        assertEquals(Vec2(50.0, 75.0), circle.center)
        assertEquals(25.0, circle.radius, 1e-9)
    }

    // ---- ARC ----

    @Test
    fun parseArc_returnsAngles() {
        val dxf = withEntities("""
  0
ARC
  8
0
 10
0.0
 20
0.0
 40
10.0
 50
30.0
 51
120.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)

        assertEquals(1, drawing.entities.size)
        val arc = drawing.entities[0] as DxfArc
        assertEquals(Vec2(0.0, 0.0), arc.center)
        assertEquals(10.0, arc.radius, 1e-9)
        assertEquals(30.0, arc.startAngleDeg, 1e-9)
        assertEquals(120.0, arc.endAngleDeg, 1e-9)
    }

    // ---- 여러 엔티티 ----

    @Test
    fun parseMultipleEntities_allExtracted() {
        val dxf = withEntities("""
  0
LINE
 10
0.0
 20
0.0
 11
1.0
 21
1.0
  0
CIRCLE
 10
5.0
 20
5.0
 40
2.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        assertEquals(2, drawing.entities.size)
        assertTrue(drawing.entities[0] is DxfLine)
        assertTrue(drawing.entities[1] is DxfCircle)
    }

    // ---- 미지원 엔티티 ----

    @Test
    fun unknownEntity_returnedAsDxfUnknown() {
        val dxf = withEntities("""
  0
XLINE
  8
0
 10
0.0
 20
0.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        assertEquals(1, drawing.entities.size)
        assertTrue(drawing.entities[0] is DxfUnknown)
        assertEquals("XLINE", (drawing.entities[0] as DxfUnknown).type)
    }

    // ---- LWPOLYLINE ----

    @Test
    fun parseLwPolyline_returnsVertices() {
        val dxf = withEntities("""
  0
LWPOLYLINE
  8
0
 90
3
 70
0
 10
0.0
 20
0.0
 10
100.0
 20
0.0
 10
50.0
 20
50.0""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        val poly = drawing.entities[0] as DxfLwPolyline
        assertEquals(3, poly.vertices.size)
        assertEquals(Vec2(0.0, 0.0), poly.vertices[0])
        assertEquals(Vec2(100.0, 0.0), poly.vertices[1])
        assertEquals(Vec2(50.0, 50.0), poly.vertices[2])
        assertFalse(poly.closed)
    }

    @Test
    fun parseLwPolyline_closedFlag() {
        val dxf = withEntities("""
  0
LWPOLYLINE
 90
2
 70
1
 10
0.0
 20
0.0
 10
10.0
 20
0.0""".trimIndent())

        val poly = DxfParser.parse(dxf).entities[0] as DxfLwPolyline
        assertTrue(poly.closed)
    }

    // ---- ELLIPSE ----

    @Test
    fun parseEllipse_returnsCenterAndRatio() {
        val dxf = withEntities("""
  0
ELLIPSE
  8
0
 10
10.0
 20
20.0
 11
5.0
 21
0.0
 40
0.5
 41
0.0
 42
6.283185307179586""".trimIndent())

        val ellipse = DxfParser.parse(dxf).entities[0] as DxfEllipse
        assertEquals(Vec2(10.0, 20.0), ellipse.center)
        assertEquals(Vec2(5.0, 0.0), ellipse.majorAxis)
        assertEquals(0.5, ellipse.minorRatio, 1e-9)
    }

    // ---- SPLINE ----

    @Test
    fun parseSpline_returnsControlPoints() {
        val dxf = withEntities("""
  0
SPLINE
  8
0
 71
3
 73
3
 10
0.0
 20
0.0
 30
0.0
 10
5.0
 20
10.0
 30
0.0
 10
10.0
 20
0.0
 30
0.0""".trimIndent())

        val spline = DxfParser.parse(dxf).entities[0] as DxfSpline
        assertEquals(3, spline.degree)
        assertEquals(3, spline.controlPoints.size)
        assertEquals(Vec2(0.0, 0.0), spline.controlPoints[0])
        assertEquals(Vec2(5.0, 10.0), spline.controlPoints[1])
    }

    // ---- TEXT ----

    @Test
    fun parseText_returnsString() {
        val dxf = withEntities("""
  0
TEXT
  8
text-layer
 10
10.0
 20
20.0
 40
2.5
  1
Hello World
 50
45.0""".trimIndent())

        val text = DxfParser.parse(dxf).entities[0] as DxfText
        assertEquals("text-layer", text.layer)
        assertEquals(Vec2(10.0, 20.0), text.insertionPoint)
        assertEquals(2.5, text.height, 1e-9)
        assertEquals("Hello World", text.text)
        assertEquals(45.0, text.rotationDeg, 1e-9)
    }

    // ---- MTEXT ----

    @Test
    fun parseMText_concatenatesChunks() {
        val dxf = withEntities("""
  0
MTEXT
  8
0
 10
0.0
 20
0.0
 40
3.0
  3
First part
  1
Second part""".trimIndent())

        val mtext = DxfParser.parse(dxf).entities[0] as DxfMText
        assertEquals("First partSecond part", mtext.text)
    }

    // ---- INSERT ----

    @Test
    fun parseInsert_returnsBlockNameAndPoint() {
        val dxf = withEntities("""
  0
INSERT
  8
0
  2
DOOR
 10
100.0
 20
200.0
 41
2.0
 42
2.0
 50
90.0""".trimIndent())

        val insert = DxfParser.parse(dxf).entities[0] as DxfInsert
        assertEquals("DOOR", insert.blockName)
        assertEquals(Vec2(100.0, 200.0), insert.insertionPoint)
        assertEquals(2.0, insert.scaleX, 1e-9)
        assertEquals(90.0, insert.rotationDeg, 1e-9)
    }

    // ---- LAYER 테이블 ----

    @Test
    fun parseLayers_extractedFromTablesSection() {
        val dxf = """
  0
SECTION
  2
TABLES
  0
TABLE
  2
LAYER
 70
2
  0
LAYER
  2
walls
 62
3
  0
LAYER
  2
pipes
 62
1
  0
ENDTAB
  0
ENDSEC
  0
SECTION
  2
ENTITIES
  0
ENDSEC
  0
EOF""".trimIndent()

        val drawing = DxfParser.parse(dxf)
        assertEquals(2, drawing.layers.size)
        assertEquals("walls", drawing.layers[0].name)
        assertEquals(3, drawing.layers[0].colorIndex)
        assertEquals("pipes", drawing.layers[1].name)
        assertEquals(1, drawing.layers[1].colorIndex)
    }

    // ---- DIMENSION ----

    @Test
    fun parseDimension_returnsPoints() {
        val dxf = withEntities("""
  0
DIMENSION
  8
dim-layer
 10
100.0
 20
200.0
 11
150.0
 21
210.0
 70
1
  1
override""".trimIndent())

        val dim = DxfParser.parse(dxf).entities[0] as DxfDimension
        assertEquals("dim-layer", dim.layer)
        assertEquals(Vec2(100.0, 200.0), dim.definitionPoint)
        assertEquals(Vec2(150.0, 210.0), dim.textMidPoint)
        assertEquals(1, dim.dimType)
        assertEquals("override", dim.textOverride)
    }

    // ---- HATCH ----

    @Test
    fun parseHatch_solidFlag() {
        val dxf = withEntities("""
  0
HATCH
  8
0
 10
0.0
 20
0.0
 70
1""".trimIndent())

        val hatch = DxfParser.parse(dxf).entities[0] as DxfHatch
        assertTrue(hatch.isSolid)
    }

    // ---- LEADER ----

    @Test
    fun parseLeader_returnsVertices() {
        val dxf = withEntities("""
  0
LEADER
  8
0
 10
0.0
 20
0.0
 10
50.0
 20
50.0
 10
100.0
 20
25.0""".trimIndent())

        val leader = DxfParser.parse(dxf).entities[0] as DxfLeader
        assertEquals(3, leader.vertices.size)
        assertEquals(Vec2(0.0, 0.0), leader.vertices[0])
    }

    // ---- BoundingBox ----

    @Test
    fun boundingBox_calculatedFromLineEndpoints() {
        val dxf = withEntities("""
  0
LINE
 10
-10.0
 20
-20.0
 11
30.0
 21
40.0""".trimIndent())

        val box = DxfParser.parse(dxf).extents!!
        assertEquals(-10.0, box.minX, 1e-9)
        assertEquals(-20.0, box.minY, 1e-9)
        assertEquals(30.0, box.maxX, 1e-9)
        assertEquals(40.0, box.maxY, 1e-9)
    }

    @Test
    fun boundingBox_nullWhenNoEntities() {
        // withEntities("") 사용 시 빈 줄이 생기므로 직접 DXF 문자열 사용
        val dxf = "  0\nSECTION\n  2\nENTITIES\n  0\nENDSEC\n  0\nEOF"
        assertNull(DxfParser.parse(dxf).extents)
    }

    // ---- %% 이스케이프 코드 ----

    @Test
    fun parseText_percentEscapeDegree() {
        val dxf = withEntities("  0\nTEXT\n 10\n0.0\n 20\n0.0\n 40\n2.5\n  1\n45%%D")
        val text = DxfParser.parse(dxf).entities[0] as DxfText
        assertEquals("45°", text.text)
    }

    @Test
    fun parseText_percentEscapeDiameter() {
        val dxf = withEntities("  0\nTEXT\n 10\n0.0\n 20\n0.0\n 40\n2.5\n  1\n%%C100")
        val text = DxfParser.parse(dxf).entities[0] as DxfText
        assertEquals("⌀100", text.text)
    }

    @Test
    fun parseText_percentEscapePlusMinus() {
        val dxf = withEntities("  0\nTEXT\n 10\n0.0\n 20\n0.0\n 40\n2.5\n  1\n%%P0.5")
        val text = DxfParser.parse(dxf).entities[0] as DxfText
        assertEquals("±0.5", text.text)
    }

    @Test
    fun parseText_percentEscapeFormatting() {
        val dxf = withEntities("  0\nTEXT\n 10\n0.0\n 20\n0.0\n 40\n2.5\n  1\n%%Uhello%%U")
        val text = DxfParser.parse(dxf).entities[0] as DxfText
        assertEquals("hello", text.text)
    }

    // ---- POLYLINE (구형식) ----

    @Test
    fun parsePolyline_extractsVerticesUntilSeqEnd() {
        val dxf = withEntities("""
  0
POLYLINE
  8
walls
 66
1
 70
0
  0
VERTEX
  8
walls
 10
0.0
 20
0.0
  0
VERTEX
  8
walls
 10
10.0
 20
0.0
  0
VERTEX
  8
walls
 10
10.0
 20
5.0
  0
SEQEND""".trimIndent())

        val drawing = DxfParser.parse(dxf)
        assertEquals(1, drawing.entities.size)
        val poly = drawing.entities[0] as DxfPolyline
        assertEquals("walls", poly.layer)
        assertEquals(3, poly.vertices.size)
        assertEquals(Vec2(0.0, 0.0), poly.vertices[0])
        assertEquals(Vec2(10.0, 0.0), poly.vertices[1])
        assertEquals(Vec2(10.0, 5.0), poly.vertices[2])
        assertFalse(poly.closed)
    }

    @Test
    fun parsePolyline_closedFlag() {
        val dxf = withEntities("""
  0
POLYLINE
 70
1
  0
VERTEX
 10
0.0
 20
0.0
  0
VERTEX
 10
1.0
 20
0.0
  0
SEQEND""".trimIndent())
        val poly = DxfParser.parse(dxf).entities[0] as DxfPolyline
        assertTrue(poly.closed)
    }

    // ---- 3DFACE ----

    @Test
    fun parse3dFace_returnsFourCorners() {
        val dxf = withEntities("""
  0
3DFACE
  8
faces
 10
0.0
 20
0.0
 30
0.0
 11
10.0
 21
0.0
 31
0.0
 12
10.0
 22
10.0
 32
0.0
 13
0.0
 23
10.0
 33
0.0""".trimIndent())

        val face = DxfParser.parse(dxf).entities[0] as Dxf3DFace
        assertEquals("faces", face.layer)
        assertEquals(Vec2(0.0, 0.0),   face.corner1)
        assertEquals(Vec2(10.0, 0.0),  face.corner2)
        assertEquals(Vec2(10.0, 10.0), face.corner3)
        assertEquals(Vec2(0.0, 10.0),  face.corner4)
    }

    // ---- displayExtents (아웃라이어 필터링) ----

    @Test
    fun trimmedBoundingBox_ignoresOutliers() {
        // 정상 범위(0~10) 점 여러 개 + 아웃라이어(9999) 1개
        val lines = buildString {
            for (i in 0 until 20) {
                appendLine("  0\nLINE\n 10\n${i * 0.5}\n 20\n${i * 0.5}\n 11\n${i * 0.5 + 0.1}\n 21\n${i * 0.5 + 0.1}")
            }
            appendLine("  0\nLINE\n 10\n9999.0\n 20\n9999.0\n 11\n9999.1\n 21\n9999.1")
        }
        val drawing = DxfParser.parse(withEntities(lines.trimEnd()))
        val box = drawing.displayExtents!!
        assertTrue("maxX should be < 100 but was ${box.maxX}", box.maxX < 100.0)
        assertTrue("maxY should be < 100 but was ${box.maxY}", box.maxY < 100.0)
    }
}
