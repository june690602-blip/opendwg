package io.github.june690602_blip.cleancad

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDwgTest {
    @Test
    fun libredwgVersion_isNotBlank() {
        val version = NativeDwg.nativeLibredwgVersion()
        assertTrue("version was blank: '$version'", version.isNotBlank())
    }
}
