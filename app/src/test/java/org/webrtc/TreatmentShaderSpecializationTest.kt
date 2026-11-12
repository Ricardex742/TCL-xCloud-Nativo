package org.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TreatmentShaderSpecializationTest {
    @Test fun emittedRuntimeVariantsCanBeValidatedAgainstTheGenericShaderOnGles() {
        val c=RuntimeEnvironment.getApplication()
        val source=TreatmentShaderDrawer.asset(c,"spatial.glsl").replace("/* ADAPTIVE_SHARPEN */",TreatmentShaderDrawer.asset(c,"adaptive.glsl"))
        val folder=File("build/reports/treatment-shaders").apply { mkdirs() }
        for(mask in 0..15) {
            val options=TreatmentOptions(1,if(mask and 2!=0)25 else 0,if(mask and 1!=0)25 else 0,if(mask and 4!=0)25 else 0,if(mask and 8!=0)25 else 0,0,0)
            assertEquals(mask,TreatmentShaderDrawer.featureMask(options))
            File(folder,"spatial-$mask.glsl").writeText(TreatmentShaderDrawer.specialize(source,mask))
        }
        // Sharpening/history do not silently compile the optional spatial restoration filters.
        assertEquals(0,TreatmentShaderDrawer.featureMask(TreatmentOptions(2,0,0,0,0,50,100)))
    }
}
