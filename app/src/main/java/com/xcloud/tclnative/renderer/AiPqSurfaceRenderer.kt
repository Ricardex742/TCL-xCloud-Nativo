package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import com.xcloud.tclnative.settings.VideoAspect
import com.xcloud.tclnative.settings.DevicePerformancePolicy
import com.xcloud.tclnative.settings.FsrOutput
import com.xcloud.tclnative.settings.FsrMode
import com.xcloud.tclnative.settings.ImageTreatmentSettings
import com.xcloud.tclnative.settings.PresentationPacing
import com.xcloud.tclnative.settings.ReconstructionSettings
import com.xcloud.tclnative.settings.ImageComparison
import com.xcloud.tclnative.webrtc.ClientDisplayDimensions
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import com.xcloud.tclnative.webrtc.StreamMetrics
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class AiPqSurfaceRenderer(context: Context) : FrameLayout(context) {
    private val devicePolicy=DevicePerformancePolicy.read(context)
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
    private var pacedSink: FramePacedVideoSink?=null
    private var pacingMode=PresentationPacing.OFF
    fun setPresentationPacing(mode: PresentationPacing) { pacingMode=mode }
    @Volatile private var generation = 0
    private val lastDeliveryMs = AtomicLong(Long.MIN_VALUE)
    private val presentedToken = AtomicLong()
    private val probePending = AtomicBoolean()
    private val fsrDraws=AtomicLong()
    private val allDraws=AtomicLong()
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
    private var fsrMode=FsrMode.OFF
    private var reconstructionSettings=ReconstructionSettings()
    private val reconstructionController=ReconstructionController()
    private var requestedTreatment=ImageTreatmentSettings()
    private var treatmentBudget=TreatmentBudget(ImageTreatmentSettings(),FsrOutput.AUTO,false)
    private var treatmentCuts=mutableListOf<String>()
    private var treatmentFailure: String?=null
    private var fsrSharpness=50
    private var fsrOutput=FsrOutput.AUTO
    private var fsrFailure: String?=null
    private var requestedBuffer: VideoViewport?=null
    private var bufferVerification: Runnable?=null
    private val fsrEnabled get()=fsrRequested && treatmentBudget.scaling && !compatibility && fsrFailure==null
    var compatibility = false
    fun setFsr(requested: Boolean,sharpness: Int,output: FsrOutput=FsrOutput.AUTO) {
        setFsr(if(requested) FsrMode.FSR1 else FsrMode.OFF,sharpness,output)
    }
    fun setFsr(mode: FsrMode,sharpness: Int,output: FsrOutput=FsrOutput.AUTO) {
        if(fsrMode==mode && fsrOutput==output && fsrRequested==(mode!=FsrMode.OFF)) { setFsrSharpness(sharpness);return }
        fsrPerformance.reset()
        fsrMode=mode;fsrFailure=null;fsrOutput=output;fsrRequested=mode!=FsrMode.OFF;setFsrSharpness(sharpness)
        resetTreatmentBudget()
        updateOutputBuffer()
    }
    fun setImageTreatment(value: ImageTreatmentSettings) {
        if(requestedTreatment==value.bounded()) return
        requestedTreatment=value.bounded();treatmentFailure=null;resetTreatmentBudget();updateOutputBuffer()
    }
    fun setReconstruction(value: ReconstructionSettings) {
        val bounded=value.bounded()
        if(reconstructionSettings==bounded) return
        val qualityChanged=bounded.quality!=reconstructionSettings.quality
        reconstructionSettings=bounded
        if(qualityChanged) resetReconstructionController()
        fsrPerformance.reset();applyTreatmentBudget()
    }
    private fun resetReconstructionController() {
        reconstructionController.reset(reconstructionSettings.quality,devicePolicy.constrainedMemory)
    }
    private fun resetTreatmentBudget() {
        treatmentCuts.clear();treatmentBudget=TreatmentBudget(if(compatibility) ImageTreatmentSettings() else requestedTreatment,fsrOutput,fsrRequested)
        resetReconstructionController()
        fsrPerformance.reset();applyTreatmentBudget()
    }
    private fun applyTreatmentBudget() {
        aspectDrawer?.let { drawer ->
            drawer.treatment=if(compatibility || treatmentFailure!=null) ImageTreatmentSettings() else treatmentBudget.image
            drawer.fsrEnabled=fsrEnabled && fsrMode==FsrMode.FSR1
            drawer.sgsrEnabled=fsrEnabled && fsrMode==FsrMode.SGSR1
            drawer.reconstructionLevel=if(fsrEnabled && fsrMode==FsrMode.RECONSTRUCTION) reconstructionController.level else 0
            drawer.reconstructionStrength=reconstructionSettings.strength
            drawer.imageComparison=reconstructionSettings.comparison.ordinal
        }
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
        fsrFailure=null;treatmentFailure=null;resetTreatmentBudget()
        videoWidth = expectedWidth; videoHeight = expectedHeight
        lastDeliveryMs.set(Long.MIN_VALUE)
        presentedToken.set(0)
        replaceRenderer()
    }
    private fun replaceRenderer() {
        detachTrack()
        cancelBufferVerification();requestedBuffer=null
        generation++
        fsrDraws.set(0);allDraws.set(0);fsrPerformance.reset()
        probePending.set(false)
        frameRateHintRequested = false
        renderer?.let { removeView(it); it.release() }
        val id = generation
        val drawer=AspectVideoDrawer(aspect.stretch,{ org.webrtc.Fsr1Drawer(context) },onFsrFailure={ reason ->
            post { if(!released && generation==id) fallbackFsr(reason) }
        },treatmentFactory={ org.webrtc.ImageTreatmentPipeline(context) },onTreatmentFailure={ reason ->
            post { if(!released && generation==id) {
                treatmentFailure=reason;applyTreatmentBudget()
                if(fsrMode==FsrMode.SGSR1 || fsrMode==FsrMode.RECONSTRUCTION) fallbackFsr(reason)
            } }
        }).also {
            it.onFsrDraw={ if(!released && generation==id) fsrDraws.incrementAndGet();Unit }
            it.onDraw={ if(!released && generation==id) allDraws.incrementAndGet();Unit }
            it.sharpness=sharpness;it.saturation=saturation;it.brightness=brightness;it.contrast=contrast;it.fsrSharpness=fsrSharpness;aspectDrawer=it
            applyTreatmentBudget()
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
        val target=if(fsrEnabled) FsrOutputPolicy.target(treatmentBudget.output,width,height,viewport,
            mode?.physicalWidth ?: 0,mode?.physicalHeight ?: 0,tv,fullScreen)?.let { selected ->
            val scale=(treatmentBudget.maxOutputHeight.toDouble()/minOf(selected.width,selected.height)).coerceAtMost(1.0)
            VideoViewport((selected.width*scale).toInt().coerceAtLeast(1),(selected.height*scale).toInt().coerceAtLeast(1))
        } else null
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
                fallbackFsr(UiText.text("Surface não confirmou {0}x{1}; mantendo escala padrão",UiText.nested(target.width),UiText.nested(target.height)))
        }.also { postDelayed(it,2000) }
    }
    private fun fallbackFsr(reason: String) {
        if(!fsrEnabled) return
        fsrFailure=reason
        applyTreatmentBudget()
        updateOutputBuffer()
    }
    /** UI thread; fed independently of HUD visibility by the existing once-per-second stats. */
    fun observeFsrPerformance(sampledAtMs: Long,metrics: StreamMetrics,foreground: Boolean) {
        val now=android.os.SystemClock.elapsedRealtime()
        val fresh=now>=sampledAtMs && now-sampledAtMs<=500
        if(fsrEnabled && fsrMode==FsrMode.RECONSTRUCTION) {
            val eligible=foreground && fresh && requestedTreatment.autoProtect && reconstructionSettings.strength>0 &&
                reconstructionSettings.comparison==ImageComparison.PROCESSED
            if(reconstructionController.observe(now,allDraws.get(),metrics,eligible)) {
                applyTreatmentBudget();fsrPerformance.reset()
            }
            if(reconstructionSettings.comparison!=ImageComparison.PROCESSED) { fsrPerformance.reset();return }
        }
        if(!requestedTreatment.autoProtect || compatibility || (!fsrEnabled && !treatmentBudget.image.active) ||
            (fsrMode==FsrMode.FSR1 && aspectDrawer?.isFsrBypassed()==true && !treatmentBudget.image.active)) { fsrPerformance.reset();return }
        val slow=fsrPerformance.observe(now,allDraws.get(),metrics,foreground && fresh) ?: return
        val rate=String.format(java.util.Locale.US,"%.1f",slow.drawsPerSecond)
        if(fsrEnabled && fsrMode==FsrMode.RECONSTRUCTION && reconstructionController.reduce()) {
            treatmentCuts.add("reconstruction level ${reconstructionController.level} ($rate draws/s)")
            applyTreatmentBudget();fsrPerformance.reset();return
        }
        val cut=treatmentBudget.reduce(requestedBuffer?.let { minOf(it.width,it.height) } ?: minOf(width,height))
        if(cut!=null) {
            treatmentBudget=cut.effective;treatmentCuts.add("${cut.stage} ($rate draws/s)")
            applyTreatmentBudget();updateOutputBuffer();fsrPerformance.reset()
        } else fallbackFsr(UiText.text("proteção de fluidez: {0} desenhos FSR/s por {1}s; escala padrão nesta partida",UiText.nested(rate),UiText.nested(slow.durationMs/1000)))
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
        val delivery=VideoSink { frame ->
            if (generation == id && !released) {
                drawer?.sourceAspect=frame.rotatedWidth.toFloat()/frame.rotatedHeight.coerceAtLeast(1)
                drawer?.sourceWidth=frame.rotatedWidth; drawer?.sourceHeight=frame.rotatedHeight
                view.onFrame(frame)
            }
        }
        val paced=if(pacingMode==PresentationPacing.SMOOTH && !compatibility) FramePacedVideoSink(delivery,failed={ error ->
            post { if(generation==id && !released) onRenderError?.invoke(error) }
        }) else null
        pacedSink=paced
        val sink = VideoSink { frame ->
            if(generation==id && !released) {
                lastDeliveryMs.set(android.os.SystemClock.elapsedRealtime())
                (paced ?: delivery).onFrame(frame)
            }
        }
        try {
            track.addSink(sink)
            trackSink = sink
            currentTrack = track
        } catch (e: Exception) { paced?.close();pacedSink=null;onRenderError?.invoke(e) }
    }
    fun detachTrack() {
        trackSink?.let { sink -> currentTrack?.removeSink(sink) }
        pacedSink?.close();pacedSink=null
        trackSink = null
        currentTrack = null
    }
    fun clearSession() {
        if (released) return
        generation++
        cancelBufferVerification();fsrPerformance.reset()
        detachTrack()
        renderer?.clearImage()
        if(fsrRequested || requestedTreatment.active) {
            renderer?.let { removeView(it);it.release() }
            renderer=null;aspectDrawer=null;requestedBuffer=null
        }
        fsrRequested=false
        onFirstFrameRendered = null
        onRenderError = null
    }
    private fun imageDiagnostics(): String {
        val active=if(compatibility || treatmentFailure!=null) ImageTreatmentSettings() else treatmentBudget.image.copy(
            neural=aspectDrawer?.effectiveNeural() ?: 0,temporal=aspectDrawer?.effectiveTemporal() ?: 0)
        fun describe(value: ImageTreatmentSettings,applied: Boolean=false)="${if(applied && fsrEnabled && fsrMode==FsrMode.RECONSTRUCTION) "XCLOUD_RECONSTRUCTION" else if(applied && fsrEnabled && fsrMode==FsrMode.SGSR1) "SGSR1" else value.sharpening.name}; deband=${value.deband}%; cleanup=${value.cleanup}%; chroma=${value.chroma}%; AA=${value.antialias}%; temporal=${value.temporal}%; neural=${value.neural}%"
        return UiText.join(UiText.text("Tratamento solicitado: {0}",UiText.nested(describe(requestedTreatment))),"\n",
            UiText.text("Tratamento aplicado: {0}",UiText.nested(describe(active,true))),"\n",
            "Cuts: ${treatmentCuts.joinToString()}${treatmentFailure?.let { "; $it" } ?: ""}\n",
            aspectDrawer?.treatmentDiagnostics() ?: "Treatment: off","\n",
            if(fsrMode==FsrMode.RECONSTRUCTION) "Reconstruction requested=${reconstructionSettings.quality}; applied level=${if(fsrEnabled) reconstructionController.level else 0}; ${reconstructionController.decision}\n" else "",
            pacedSink?.diagnostics() ?: "Pacing: immediate")
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
        val minimalLabel=when(minimalSupport) { true -> UiText.text("sim");false -> UiText.text("não");null -> "n/d" }
        return UiText.join(UiText.text("Surface {0} → janela {1}x{2} | Saída Android: {3}\n",UiText.nested(size),UiText.nested(width),UiText.nested(height),UiText.nested(output)),UiText.text("Layout do vídeo: {0} | Área calculada: {1}x{2} | Quadro do renderer: {3}x{4}\n",UiText.nested(layout),UiText.nested(expected.width),UiText.nested(expected.height),UiText.nested(videoWidth),UiText.nested(videoHeight)),UiText.text("Escala da Surface: {0}; imagem ajustada à janela | Conteúdo {1} FPS sinalizado à Surface: {2} | Pós-processamento mínimo: {3}; suporte Android declarado: {4}\n",UiText.nested(if(fsrEnabled) UiText.text("renderer na resolução de saída do escalador") else if (nativeUpscaling) "HW solicitada" else "HW desativada"),UiText.nested(if(compatibility) 30 else 60),UiText.nested(if (frameRateHintRequested) UiText.text("sim") else UiText.text("não confirmado")),UiText.nested(if (minimalPostProcessingRequested) "solicitado" else UiText.text("padrão")),UiText.nested(minimalLabel)),UiText.text("Proporção: {0} | Nitidez padrão: {1}% | Saturação: {2}% | Brilho: {3}% | Contraste: {4}% | Apresentação: até {5} FPS | AiPQ: não verificável pelo app\n",UiText.nested(aspect.label),UiText.nested(sharpness),UiText.nested(saturation),UiText.nested(brightness),UiText.nested(contrast),UiText.nested(if(compatibility) 30 else 60)),(if(fsrRequested) UiText.text("Saída do escalador solicitada: {0} | buffer solicitado: {1}\n",UiText.nested(fsrOutput.label),UiText.nested(requestedBuffer?.let { "${it.width}x${it.height}" } ?: UiText.text("escala padrão"))) else ""),(if(fsrEnabled) UiText.text("Cadência local do renderer: {0} (não mede GPU nem FPS do jogo/tela)\n",UiText.nested(fsrPerformance.lastRate?.let { String.format(java.util.Locale.US,"%.1f desenhos/s",it) } ?: UiText.text("aguardando amostras"))) else ""),(if(fsrFailure!=null) UiText.text("Escalador: retorno à escala padrão — {0}",UiText.nested(fsrFailure)) else if(fsrRequested && compatibility) UiText.text("AMD FSR 1.0.2: inativo no modo Compatibilidade") else aspectDrawer?.fsrDiagnostics() ?: UiText.text("AMD FSR 1.0.2: desligado"))) + "\n" + imageDiagnostics()
    }

    fun compactDiagnostics(): String {
        val surface = renderer?.holder?.surfaceFrame
        val mode = display?.mode
        return UiText.join(UiText.text("Surface {0}x{1} → saída Android ",UiText.nested(surface?.width() ?: 0),UiText.nested(surface?.height() ?: 0)),(mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${kotlin.math.round(it.refreshRate).toInt()}Hz" } ?: "n/d"))
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
