package io.github.june690602_blip.cleancad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.june690602_blip.cleancad.parser.DxfParser
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativePipelineTest {

    @Test
    fun dwgToDxf_producesNonEmptyDxfFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cacheDir = instrumentation.targetContext.cacheDir

        // 1. Copy DWG asset to cache dir
        val dwgFile = File(cacheDir, "test_circle.dwg")
        instrumentation.context.assets.open("circle.dwg").use { input ->
            dwgFile.outputStream().use { input.copyTo(it) }
        }
        assertTrue("DWG file should exist", dwgFile.exists())

        // 2. Convert DWG → DXF
        val dxfFile = File(cacheDir, "test_circle.dxf")
        val result = NativeDwg.nativeDwgToDxf(dwgFile.absolutePath, dxfFile.absolutePath)

        // 3. Verify conversion
        assertTrue("nativeDwgToDxf should return 0 (no critical error), got $result", result == 0)
        assertTrue("DXF file should exist after conversion", dxfFile.exists())
        assertTrue("DXF file should not be empty", dxfFile.length() > 0)

        // 4. Parse DXF → Drawing and verify
        val dxfContent = dxfFile.readText()
        val drawing = DxfParser.parse(dxfContent)
        assertTrue("Drawing should have at least 1 entity", drawing.entities.isNotEmpty())
        assertNotNull("BoundingBox should not be null", drawing.extents)
    }

    @Test
    fun dwgToDxf_invalidPath_returnsError() {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val result = NativeDwg.nativeDwgToDxf(
            "/nonexistent/path/fake.dwg",
            File(cacheDir, "out.dxf").absolutePath
        )
        assertTrue("Invalid path should return non-zero error code", result != 0)
    }
}
