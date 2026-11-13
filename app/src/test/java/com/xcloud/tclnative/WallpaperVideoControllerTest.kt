package com.xcloud.tclnative

import com.xcloud.tclnative.ui.wallpapers.WallpaperVideoController
import com.xcloud.tclnative.ui.wallpapers.WallpaperVideoEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WallpaperVideoControllerTest {
    private class Engine: WallpaperVideoEngine {
        lateinit var ready: () -> Unit
        lateinit var frame: () -> Unit
        lateinit var failed: () -> Unit
        var starts=0;var releases=0
        override fun prepare(file: File,prepared: () -> Unit,frame: () -> Unit,failed: () -> Unit) {
            ready=prepared;this.frame=frame;this.failed=failed
        }
        override fun start() { starts++ }
        override fun release() { releases++ }
    }
    private val first=File("/cache/one.mp4")
    private val second=File("/cache/two.mp4")
    @Test fun hiddenMenusAndMissingSurfacesDoNotAllocateADecoder() {
        var allocations=0
        val playback=WallpaperVideoController({ allocations++;Engine() })
        playback.update(first,false,true);playback.update(first,true,false);playback.update(null,true,true)
        assertEquals(0,allocations);assertEquals(WallpaperVideoController.State.OFF,playback.state)
    }
    @Test fun staticPreviewStaysUntilAnActualVideoFrameAndRepeatedLayoutsDoNotRestartPlayback() {
        val engines=mutableListOf<Engine>()
        val playback=WallpaperVideoController({ Engine().also { engines+=it } })
        playback.update(first,true,true);val engine=engines.single()
        assertEquals(WallpaperVideoController.State.LOADING,playback.state)
        engine.ready();engine.ready();assertEquals(1,engine.starts)
        assertEquals(WallpaperVideoController.State.LOADING,playback.state)
        engine.frame();assertEquals(WallpaperVideoController.State.PLAYING,playback.state)
        repeat(10) { playback.update(first,true,true) };assertEquals(1,engines.size)
        playback.close();assertEquals(1,engine.releases)
    }
    @Test fun switchingBackgroundsRejectsLateCallbacksFromTheOldDecoder() {
        val engines=mutableListOf<Engine>()
        val playback=WallpaperVideoController({ Engine().also { engines+=it } })
        playback.update(first,true,true);playback.update(second,true,true)
        val old=engines[0];val current=engines[1]
        old.ready();old.frame();old.failed()
        assertEquals(0,old.starts);assertEquals(1,old.releases)
        assertEquals(WallpaperVideoController.State.LOADING,playback.state)
        current.ready();current.frame();assertEquals(WallpaperVideoController.State.PLAYING,playback.state)
        playback.close()
    }
    @Test fun enteringGameplayReleasesTheBackgroundBeforeItsPendingPreparationCanFinish() {
        val engines=mutableListOf<Engine>()
        val playback=WallpaperVideoController({ Engine().also { engines+=it } })
        playback.update(first,true,true);val pending=engines.single()
        playback.update(first,false,true);assertEquals(1,pending.releases)
        pending.ready();pending.frame();assertEquals(0,pending.starts)
        assertEquals(WallpaperVideoController.State.OFF,playback.state)
        playback.update(first,true,true);assertEquals(2,engines.size)
        playback.close()
    }
    @Test fun aDecoderFailureKeepsAStaticFallbackWithoutARecreationLoop() {
        val engines=mutableListOf<Engine>()
        val playback=WallpaperVideoController({ Engine().also { engines+=it } })
        playback.update(first,true,true);engines.single().failed()
        assertEquals(WallpaperVideoController.State.FAILED,playback.state)
        repeat(10) { playback.update(first,true,true) };assertEquals(1,engines.size)
        assertEquals(1,engines.single().releases)
        playback.update(first,false,true);playback.update(first,true,true);assertEquals(2,engines.size)
        playback.close()
    }
    @Test fun destroyIsFinalAndCannotStartANewPlayer() {
        val engine=Engine();var allocations=0
        val playback=WallpaperVideoController({ allocations++;engine })
        playback.update(first,true,true);playback.close();playback.close()
        engine.ready();engine.frame();playback.update(second,true,true)
        assertEquals(1,allocations);assertEquals(1,engine.releases);assertEquals(0,engine.starts)
        assertEquals(WallpaperVideoController.State.OFF,playback.state)
    }
}
