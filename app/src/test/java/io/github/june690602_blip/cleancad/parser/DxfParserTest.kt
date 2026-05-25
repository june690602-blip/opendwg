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
}
