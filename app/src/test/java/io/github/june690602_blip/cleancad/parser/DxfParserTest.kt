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
}
