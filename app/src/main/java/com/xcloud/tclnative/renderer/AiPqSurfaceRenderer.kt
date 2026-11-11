package com.xcloud.tclnative.renderer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import com.xcloud.tclnative.settings.VideoAspect
import com.xcloud.tclnative.settings.FsrOutput
import com.xcloud.tclnative.webrtc.ClientDisplayDimensions
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import com.xcloud.tclnative.webrtc.StreamMetrics
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class AiPqSurfaceRenderer(context: Context) : FrameLayout(context) {
    private val egl = lazy { EglBase.create() }
    val eglBase: EglBase get()=egl.value
    var onFirstFrameRendered: (() -> Unit)? = null
    var onRenderError: ((Exception) -> Unit)? = null
    var onDisplayDimensionsChanged: ((ClientDisplayDimensions) -> Unit)? = null
    private var lastDisplayDimensions: ClientDisplayDimensions? = null
    private val displayManager=context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
    private var watchingDisplay=false
    private val displayListener=object: android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { if(!released && fsrEnabled && display?.displayId==id) updateOutputBuffer() }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(!watchingDisplay && !released) {
            displayManager.registerDisplayListener(displayListener,android.os.Handler(android.os.Looper.getMainLooper()))
            watchingDisplay=true
        }
        if(fsrEnabled) updateOutputBuffer()
    }
    override fun onDetachedFromWindow() {
        stopWatchingDisplay();cancelBufferVerification();super.onDetachedFromWindow()
    }
    private fun stopWatchingDisplay() { if(watchingDisplay) { displayManager.unregisterDisplayListener(displayListener);watchingDisplay=false } }
    private var currentTrack: VideoTrack? = null
    private var renderer: OutputSurfaceViewRenderer? = null
    private var aspectDrawer: AspectVideoDrawer? = null
    private var trackSink: VideoSink? = null
    @Volatile private var generation = 0
    private val lastDeliveryMs = AtomicLong(Long.MIN_VALUE)
    private val presentedToken = AtomicLong()
    private val probePending = AtomicBoolean()
    private val fsrDraws=AtomicLong()
    private val fsrPerformance=FsrPerformanceGuard()
    data class VideoHealth(val presentedToken: Long, val deliveredRecently: Boolean)

    /** One zero-scale callback per watchdog tick: no bitmap allocation or GPU pixel readback. */
    fun probeVideoHealth(): VideoHealth {
        val id = generation
        val view = renderer
        if (view != null && probePending.compareAndSet(false, true)) {
            view.addFrameListener({
                if (generation == id && !released) {
                    presentedToken.incrementAndGet()
                    probePending.set(false)
                }
            }, 0f)
        }
        val delivered = lastDeliveryMs.get()
        return VideoHealth(presentedToken.get(), delivered != Long.MIN_VALUE &&
            android.os.SystemClock.elapsedRealtime() - delivered < 2000)
    }

    /** Keep the same remote track, peer connection, audio and Xbox allocation. UI thread only. */
    fun rebuildSurface() {
        if (released) return
        val track = currentTrack
        replaceRenderer()
        if (track != null) attachTrackNow(track)
    }
    @Volatile private var released = false
    var minimalPostProcessingRequested = false
    private var frameRateHintRequested = false
    private var videoWidth = 1920
    private var videoHeight = 1080
    private var sharpness=0
    private var saturation=100
    private var brightness=0
    private var contrast=100
    private var fsrRequested=false
    private var fsrSharpness=50
    private var fsrOutput=FsrOutput.AUTO
    private var fsrFailure: String?=null
    private var requestedBuffer: VideoViewport?=null
    private var bufferVerification: Runnable?=null
    private val fsrEnabled get()=fsrRequested && !compatibility && fsrFailure==null
    var compatibility = false
    fun setFsr(requested: Boolean,sharpness: Int,output: FsrOutput=FsrOutput.AUTO) {
        fsrPerformance.reset()
        fsrFailure=null;fsrOutput=output;fsrRequested=requested;setFsrSharpness(sharpness)
        aspectDrawer?.fsrEnabled=fsrEnabled
        updateOutputBuffer()
    }
    fun setFsrSharpness(sharpness: Int) { fsrSharpness=sharpness.coerceIn(0,100);aspectDrawer?.fsrSharpness=fsrSharpness }
    fun setImageAdjustments(sharpness: Int,saturation: Int,brightness: Int=0,contrast: Int=100) {
        this.brightness=brightness.coerceIn(-50,50);this.contrast=contrast.coerceIn(50,150)
        this.sharpness=sharpness.coerceIn(0,100); this.saturation=saturation.coerceIn(0,200)
        aspectDrawer?.sharpness=this.sharpness; aspectDrawer?.saturation=this.saturation
        aspectDrawer?.brightness=this.brightness;aspectDrawer?.contrast=this.contrast
    }
    fun touchCoordinates(x: Float,y: Float): Pair<Float,Float>? = VideoScalingPolicy.touchCoordinates(
        width,height,videoWidth,videoHeight,aspect,x,y)
    fun displayDimensions(): ClientDisplayDimensions? {
        if (width <= 0 || height <= 0) return null
        val viewport = VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        val metrics = resources.displayMetrics
        return ClientDisplayDimensions.fromPixels(width,height,viewport.width,viewport.height,
            metrics.xdpi,metrics.ydpi,metrics.densityDpi.toFloat())
    }
    var aspect = VideoAspect.RATIO_16_9
        set(value) {
            field=value
            aspectDrawer?.stretch=value.stretch
            updateVideoLayout()
        }
    var nativeUpscaling = true
        set(value) {
            field = value
            updateOutputBuffer()
            updateVideoLayout()
        }

    init { setBackgroundColor(Color.BLACK); keepScreenOn = true }

    /** A new renderer has a new first-frame event for every session, including retries. */
    fun prepareForSession(expectedWidth: Int = 1920, expectedHeight: Int = 1080) {
        check(!released)
        fsrFailure=null
        videoWidth = expectedWidth; videoHeight = expectedHeight
        lastDeliveryMs.set(Long.MIN_VALUE)
        presentedToken.set(0)
        replaceRenderer()
    }
    private fun replaceRenderer() {
        detachTrack()
        cancelBufferVerification();requestedBuffer=null
        generation++
        fsrDraws.set(0);fsrPerformance.reset()
        probePending.set(false)
        frameRateHintRequested = false
        renderer?.let { removeView(it); it.release() }
        val id = generation
        val drawer=AspectVideoDrawer(aspect.stretch,{ org.webrtc.Fsr1Drawer(context) },onFsrFailure={ reason ->
            post { if(!released && generation==id) fallbackFsr(reason) }
        }).also {
            it.onFsrDraw={ if(!released && generation==id) fsrDraws.incrementAndGet();Unit }
            it.sharpness=sharpness;it.saturation=saturation;it.brightness=brightness;it.contrast=contrast;it.fsrEnabled=fsrEnabled;it.fsrSharpness=fsrSharpness;aspectDrawer=it
        }
        renderer = object : OutputSurfaceViewRenderer(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                drawer.layoutAspect=(right-left).toFloat()/(bottom-top).coerceAtLeast(1)
                super.onLayout(changed,left,top,right,bottom)
                updateOutputBuffer()
            }
        }.also { view ->
            view.onBufferFailure={ post { if(!released && generation==id) fallbackFsr("Surface recusou o tamanho solicitado") } }
            view.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    post { if (!released && generation == id) onFirstFrameRendered?.invoke() }
                }
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    post { if (!released && generation == id) {
                        videoWidth = if (rotation % 180 == 0) width else height
                        videoHeight = if (rotation % 180 == 0) height else width
                        updateVideoLayout()
                    } }
                }
            },EglBase.CONFIG_PLAIN,drawer)
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            view.setEnableHardwareScaler(nativeUpscaling && !fsrEnabled)
            if(compatibility) view.setFpsReduction(30f) else view.disableFpsReduction()
            view.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                private fun frameRate(holder: android.view.SurfaceHolder) {
                    if (!released && generation == id && android.os.Build.VERSION.SDK_INT >= 30 && holder.surface.isValid) {
                        frameRateHintRequested = runCatching {
                            holder.surface.setFrameRate(if(compatibility) 30f else 60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                        }.isSuccess
                    }
                }
                override fun surfaceCreated(holder: android.view.SurfaceHolder) { frameRate(holder);if(!released && generation==id) updateOutputBuffer() }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                    frameRate(holder)
                    if(!released && generation==id) updateOutputBuffer()
                }
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { if (generation == id) { frameRateHintRequested = false;cancelBufferVerification() } }
            })
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        updateVideoLayout()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        updateVideoLayout()
    }
    private fun updateVideoLayout() {
        displayDimensions()?.let { dimensions ->
            if (dimensions != lastDisplayDimensions) {
                lastDisplayDimensions = dimensions
                onDisplayDimensionsChanged?.invoke(dimensions)
            }
        }
        val view = renderer ?: return
        val viewport = VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        val targetWidth = viewport.width
        val targetHeight = viewport.height
        val old = view.layoutParams as? LayoutParams
        if (old == null || old.width != targetWidth || old.height != targetHeight || old.gravity != Gravity.CENTER) {
            view.layoutParams = LayoutParams(targetWidth,targetHeight,Gravity.CENTER)
        }
        updateOutputBuffer()
    }
    private fun updateOutputBuffer() {
        val view=renderer ?: return
        val mode=display?.mode
        val tv=(context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType==android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val activity=context as? android.app.Activity
        val fullScreen=activity?.let { !it.isInMultiWindowMode && !it.isInPictureInPictureMode } ?: true
        val viewport=VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        val target=if(fsrEnabled) FsrOutputPolicy.target(fsrOutput,width,height,viewport,
            mode?.physicalWidth ?: 0,mode?.physicalHeight ?: 0,tv,fullScreen) else null
        if(target!=requestedBuffer) { cancelBufferVerification();fsrPerformance.reset() }
        requestedBuffer=target
        view.setOutputBuffer(target)
        view.setEnableHardwareScaler(nativeUpscaling && !fsrEnabled)
        verifyOutputBuffer()
    }
    private fun cancelBufferVerification() { bufferVerification?.let(::removeCallbacks);bufferVerification=null }
    private fun verifyOutputBuffer() {
        val target=requestedBuffer ?: return
        val view=renderer ?: return
        val actual=view.holder.surfaceFrame
        if(actual.width()==target.width && actual.height()==target.height) { cancelBufferVerification();return }
        if(bufferVerification!=null) return
        val id=generation
        bufferVerification=Runnable {
            bufferVerification=null
            if(released || generation!=id || requestedBuffer!=target || !view.isAttachedToWindow || !view.holder.surface.isValid) return@Runnable
            val frame=view.holder.surfaceFrame
            if(frame.width()!=target.width || frame.height()!=target.height)
                fallbackFsr("Surface não confirmou ${target.width}x${target.height}; mantendo escala padrão")
        }.also { postDelayed(it,2000) }
    }
    private fun fallbackFsr(reason: String) {
        if(!fsrEnabled) return
        fsrFailure=reason
        aspectDrawer?.fsrEnabled=false
        updateOutputBuffer()
    }
    /** UI thread; fed independently of HUD visibility by the existing once-per-second stats. */
    fun observeFsrPerformance(sampledAtMs: Long,metrics: StreamMetrics,foreground: Boolean) {
        if(!fsrEnabled || aspectDrawer?.isFsrBypassed()==true) { fsrPerformance.reset();return }
        val now=android.os.SystemClock.elapsedRealtime()
        val fresh=now>=sampledAtMs && now-sampledAtMs<=500
        val slow=fsrPerformance.observe(now,fsrDraws.get(),metrics,foreground && fresh) ?: return
        val rate=String.format(java.util.Locale.US,"%.1f",slow.drawsPerSecond)
        fallbackFsr("proteção de fluidez: $rate desenhos FSR/s por ${slow.durationMs/1000}s; escala padrão nesta partida")
    }
    fun attachVideoTrack(track: VideoTrack) {
        val id = generation
        post {
            if (!released && generation == id) attachTrackNow(track)
        }
    }
    private fun attachTrackNow(track: VideoTrack) {
        if (currentTrack === track) return
        detachTrack()
        val view = renderer ?: return
        val drawer = aspectDrawer
        val id = generation
        val sink = VideoSink { frame ->
            if (generation == id && !released) {
                lastDeliveryMs.set(android.os.SystemClock.elapsedRealtime())
                drawer?.sourceAspect=frame.rotatedWidth.toFloat()/frame.rotatedHeight.coerceAtLeast(1)
                drawer?.sourceWidth=frame.rotatedWidth; drawer?.sourceHeight=frame.rotatedHeight
                view.onFrame(frame)
            }
        }
        try {
            track.addSink(sink)
            trackSink = sink
            currentTrack = track
        } catch (e: Exception) { onRenderError?.invoke(e) }
    }
    fun detachTrack() {
        trackSink?.let { sink -> currentTrack?.removeSink(sink) }
        trackSink = null
        currentTrack = null
    }
    fun clearSession() {
        if (released) return
        generation++
        cancelBufferVerification();fsrPerformance.reset()
        detachTrack()
        renderer?.clearImage()
        if(fsrRequested) {
            renderer?.let { removeView(it);it.release() }
            renderer=null;aspectDrawer=null;requestedBuffer=null
        }
        fsrRequested=false
        onFirstFrameRendered = null
        onRenderError = null
    }
    /** Android exposes surface/display geometry, but no public API reports TCL AiPQ activation. */
    fun diagnostics(): String {
        val surface = renderer?.holder?.surfaceFrame
        val size = if (surface != null && surface.width() > 0 && surface.height() > 0)
            "${surface.width()}x${surface.height()}" else "n/d"
        val mode = display?.mode
        val output = mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${kotlin.math.round(it.refreshRate).toInt()} Hz" } ?: "n/d"
        val layout=renderer?.let { "${it.width}x${it.height}" } ?: "n/d"
        val expected=VideoScalingPolicy.viewport(width,height,videoWidth,videoHeight,aspect)
        val minimalSupport=if(android.os.Build.VERSION.SDK_INT>=30) runCatching { display?.isMinimalPostProcessingSupported }.getOrNull() else null
        val minimalLabel=when(minimalSupport) { true -> "sim";false -> "não";null -> "n/d" }
        return "Surface $size → janela ${width}x$height | Saída Android: $output\n" +
            "Layout do vídeo: $layout | Área calculada: ${expected.width}x${expected.height} | Quadro do renderer: ${videoWidth}x$videoHeight\n" +
            "Escala da Surface: ${if(fsrEnabled) "renderer em resolução de saída para FSR" else if (nativeUpscaling) "HW solicitada" else "HW desativada"}; imagem ajustada à janela | Conteúdo ${if(compatibility) 30 else 60} FPS sinalizado à Surface: ${if (frameRateHintRequested) "sim" else "não confirmado"} | Pós-processamento mínimo: ${if (minimalPostProcessingRequested) "solicitado" else "padrão"}; suporte Android declarado: $minimalLabel\n" +
            "Proporção: ${aspect.label} | Nitidez padrão: $sharpness% | Saturação: $saturation% | Brilho: $brightness% | Contraste: $contrast% | Apresentação: até ${if(compatibility) 30 else 60} FPS | AiPQ: não verificável pelo app\n"+
            (if(fsrRequested) "Saída FSR solicitada: ${fsrOutput.label} | buffer solicitado: ${requestedBuffer?.let { "${it.width}x${it.height}" } ?: "escala padrão"}\n" else "")+
            (if(fsrEnabled) "Cadência local FSR: ${fsrPerformance.lastRate?.let { String.format(java.util.Locale.US,"%.1f desenhos/s",it) } ?: "aguardando amostras"} (não mede GPU nem FPS do jogo/tela)\n" else "")+
            (if(fsrFailure!=null) "AMD FSR 1.0.2: retorno à escala padrão — $fsrFailure" else if(fsrRequested && compatibility) "AMD FSR 1.0.2: inativo no modo Compatibilidade" else aspectDrawer?.fsrDiagnostics() ?: "AMD FSR 1.0.2: desligado")
    }

    fun compactDiagnostics(): String {
        val surface = renderer?.holder?.surfaceFrame
        val mode = display?.mode
        return "Surface ${surface?.width() ?: 0}x${surface?.height() ?: 0} → saída Android " +
            (mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${kotlin.math.round(it.refreshRate).toInt()}Hz" } ?: "n/d")
    }

    fun release() {
        if (released) return
        clearSession()
        released = true
        stopWatchingDisplay()
        renderer?.release()
        renderer = null
        if(egl.isInitialized()) eglBase.release()
    }
}
