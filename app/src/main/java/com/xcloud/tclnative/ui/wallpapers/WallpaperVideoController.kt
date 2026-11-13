package com.xcloud.tclnative.ui.wallpapers

import java.io.File

internal interface WallpaperVideoEngine {
    fun prepare(file: File, prepared: () -> Unit, frame: () -> Unit, failed: () -> Unit)
    fun start()
    fun release()
}

/** One menu-only decoder. Late callbacks cannot restart a hidden or replaced background. */
internal class WallpaperVideoController(private val factory: () -> WallpaperVideoEngine,
                                       private val changed: (State) -> Unit = {}) {
    enum class State { OFF, LOADING, PLAYING, FAILED }
    var state=State.OFF
        private set
    private var engine: WallpaperVideoEngine?=null
    private var source: File?=null
    private var failedSource: File?=null
    private var generation=0L
    private var closed=false
    private var started=false

    fun update(file: File?, enabled: Boolean, surfaceReady: Boolean) {
        if(closed) return
        if(!enabled || !surfaceReady || file==null) {
            release();source=null;failedSource=null;publish(State.OFF);return
        }
        if(source==file && engine!=null) return
        if(failedSource==file) { publish(State.FAILED);return }
        release();source=file;failedSource=null
        val id=generation
        try {
            val next=factory();engine=next;publish(State.LOADING)
            fun current()=!closed && generation==id && engine===next
            fun failed() {
                if(current()) { failedSource=file;release();publish(State.FAILED) }
            }
            next.prepare(file,prepared={
                if(current() && !started) try { started=true;next.start() } catch(_: Exception) { failed() }
            },frame={ if(current() && started) publish(State.PLAYING) },failed=::failed)
        } catch(_: Exception) { failedSource=file;release();publish(State.FAILED) }
    }
    private fun release() {
        ++generation;started=false
        val previous=engine;engine=null
        try { previous?.release() } catch(_: Exception) { /* Static preview remains available. */ }
    }
    private fun publish(value: State) { if(state!=value) { state=value;changed(value) } }
    fun close() { if(!closed) { closed=true;release();source=null;publish(State.OFF) } }
}
