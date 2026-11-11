package org.webrtc

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FsrCopyShaderTest {
    @Test fun actualWebRtcOesRgbYuvAdaptersUseHighPrecisionAndAreExportedForPixelTests() {
        val directory=File("build/reports/dashboard").apply { mkdirs() }
        val vertex=Fsr1Drawer.copyVertexShader()
        assertTrue(vertex.contains("varying highp vec2 tc"))
        assertTrue(vertex.contains("tex_mat*in_tc"))
        File(directory,"fsr1-copy.vert").writeText(vertex)
        for(type in GlGenericDrawer.ShaderType.values()) {
            val source=Fsr1Drawer.copyFragmentShader(type)
            assertTrue(source,source.contains("precision highp float"))
            assertTrue(source.contains("varying highp vec2 tc"))
            assertFalse(source.contains("precision mediump"))
            if(type==GlGenericDrawer.ShaderType.YUV) {
                assertTrue(source.contains("uniform sampler2D y_tex"));assertTrue(source.contains("sample(tc)"))
            } else assertFalse(source.contains("sample(tc)"))
            File(directory,"fsr1-copy-${type.name.lowercase()}.frag").writeText(source)
        }
    }
    @Test fun actualDirectVariantsAreExportedForOesAndRgbPixelTests() {
        val directory=File("build/reports/dashboard").apply { mkdirs() }
        for(kind in listOf(Fsr1Drawer.Input.OES,Fsr1Drawer.Input.RGB)) for(stage in listOf("easu","rcas")) {
            val asset=File("src/main/assets/fsr1/$stage.frag").readText()
            val source=Fsr1Drawer.directShader(asset,kind)
            assertTrue(source.startsWith("#define FSR_DIRECT_INPUT 1"))
            assertEquals(kind==Fsr1Drawer.Input.OES,source.contains("#define FSR_SOURCE_OES 1"))
            File(directory,"fsr1-direct-${kind.name.lowercase()}-$stage.frag").writeText(source)
        }
    }
}
