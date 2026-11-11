package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.settings.FsrOutput
import com.xcloud.tclnative.settings.ImageTreatmentSettings

/** Per-session cuts are separate from the user's saved settings; each cut is observable. */
data class TreatmentBudget(val image: ImageTreatmentSettings,val output: FsrOutput,val scaling: Boolean,
                           val maxOutputHeight: Int=2160) {
    data class Cut(val effective: TreatmentBudget,val stage: String)
    fun reduce(outputHeight: Int): Cut? = when {
        image.temporal>0 -> Cut(copy(image=image.copy(temporal=0)),"temporal")
        scaling && outputHeight>1440 && maxOutputHeight>1440 -> Cut(copy(output=FsrOutput.QHD_1440,maxOutputHeight=1440),"output 1440p")
        scaling && outputHeight>1080 && maxOutputHeight>1080 -> Cut(copy(output=FsrOutput.AUTO,maxOutputHeight=1080),"output native")
        image.antialias>0 -> Cut(copy(image=image.copy(antialias=0)),"antialias")
        image.neural>0 -> Cut(copy(image=image.copy(neural=0)),"neural")
        image.deband>25 -> Cut(copy(image=image.copy(deband=25)),"deband 25%")
        image.deband>0 -> Cut(copy(image=image.copy(deband=0)),"deband off")
        image.cleanup>0 -> Cut(copy(image=image.copy(cleanup=0)),"cleanup")
        image.chroma>0 -> Cut(copy(image=image.copy(chroma=0)),"chroma")
        scaling -> Cut(copy(scaling=false),"scaling off")
        image.active -> Cut(copy(image=ImageTreatmentSettings()),"image treatment off")
        else -> null
    }
}
