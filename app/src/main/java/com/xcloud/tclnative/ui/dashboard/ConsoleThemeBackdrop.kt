package com.xcloud.tclnative.ui.dashboard

import android.animation.ValueAnimator
import android.content.*
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import com.xcloud.tclnative.settings.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.WeakHashMap

/** No timer for the seven color palettes, hidden screens or gameplay. AUTO is conservative. */
internal object BackgroundMotionPolicy {
    fun interval(mode: BackgroundMotion,scene: ConsoleScene,lowRam: Boolean,largeUi: Boolean,
                 powerSave: Boolean,animations: Boolean,presented: Boolean,independentAnimation: Boolean=false): Long? {
        if(!presented || scene==ConsoleScene.NONE || mode==BackgroundMotion.OFF) return null
        if(mode==BackgroundMotion.AUTO && (powerSave || !animations || lowRam || largeUi)) return null
        return if(powerSave) 250L else if(independentAnimation) { if(lowRam || largeUi) 100L else 33L }
            else if(lowRam || largeUi) 167L else 84L
    }
}

/** A separate render node avoids invalidating/rebinding catalogue cards for background motion. */
internal class ConsoleThemeBackdrop(context: Context): View(context) {
    private val painter=ConsoleScenePainter()
    private var chrome: ConsoleDashboardChrome?=null
    private var artwork: Bitmap?=null
    private val series=SeriesPersonalizationStore(context)
    private val opening=openingFor(context)
    private val wallpaperScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val wallpaperLoader=lazy { PosterLoader(context,wallpaperScope) }
    private val wallpaperTarget=FixedArtworkView(context)
    private var wallpaper: SeriesWallpaper?=null
    private var wallpaperArt: MotionWallpaperArt?=null
    private var galleryBitmap: Bitmap?=null
    private var wallpaperRefreshPending=false
    internal var rotationPending=false
        private set
    private val rotate=Runnable { refreshScheduling() }
    internal val hasArtwork: Boolean get()=artwork?.isRecycled==false
    internal val hasGalleryArtwork: Boolean get()=galleryBitmap?.isRecycled==false
    private val artworkPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artworkRect=RectF()
    private var explicitMotion=false
    private val prefs=context.getSharedPreferences("app_appearance",Context.MODE_PRIVATE)
    private var attached=false
    private var receiverRegistered=false
    private var focused=false
    private var aggregated=true
    private var presented=true
    private var closed=false
    private var interval: Long?=null
    internal var framePending=false
        private set
    internal var renderedTicks=0L
        private set
    private var phase=0f
    private var lastTick=0L
    private var lowRam=false
    private var powerSave=false
    private var animations=true
    private val tick=object: Runnable {
        override fun run() {
            framePending=false
            if(!explicitMotion && Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled()) { refreshScheduling();return }
            if(!canPresent()) { refreshScheduling();return }
            val step=interval ?: return
            val now=SystemClock.uptimeMillis()
            phase=(phase+(now-lastTick).coerceIn(0L,500L)/16000f)%1f;lastTick=now
            renderedTicks++;invalidate()
            framePending=postDelayed(this,step)
        }
    }
    private val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="background_motion") refreshScheduling()
        if(key?.startsWith("series_")==true && !key.startsWith("series_rotation_") && !wallpaperRefreshPending) {
            wallpaperRefreshPending=true
            post { wallpaperRefreshPending=false;if(!closed) refreshScheduling() }
        }
    }
    private val powerReceiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context?,intent: Intent?) { refreshScheduling() }
    }
    init {
        isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun hasOverlappingRendering()=false
    fun refreshAppearance() {
        painter.configure(DashboardTheme.palette,width,height)
        phase=0f;invalidate();refreshScheduling()
    }
    fun setPresentationEnabled(value: Boolean) { presented=value;refreshScheduling() }
    fun setChrome(value: ConsoleDashboardChrome) { chrome=value;invalidate() }
    fun setArtwork(value: Drawable?) { artwork=(value as? BitmapDrawable)?.bitmap;invalidate() }
    fun refreshStructure() { chrome?.invalidateSelf();invalidate() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);refreshAppearance() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val picture=if(wallpaper!=null) galleryBitmap else artwork
        val recreation=wallpaper?.recreation!=null && DashboardTheme.palette.theme.scene==ConsoleScene.XBOX_SERIES
        if(recreation) {
            if(picture!=null && !picture.isRecycled) {
                wallpaperArt?.let { it.phase=phase;it.lowDetail=lowRam;it.drawArtwork(canvas,picture,width,height) }
            }
            wallpaperArt?.let { it.hasArtwork=picture?.isRecycled==false;it.lowDetail=lowRam;it.phase=phase;it.draw(canvas) }
        } else if(picture!=null && !picture.isRecycled && DashboardTheme.palette.theme.scene==ConsoleScene.XBOX_SERIES) {
            val wave=kotlin.math.sin(phase*2*Math.PI).toFloat()
            val moving=interval!=null || phase!=0f
            val fit=maxOf(width.toFloat()/picture.width,height.toFloat()/picture.height)*(if(moving) 1.05f else 1f)
            val w=picture.width*fit;val h=picture.height*fit
            val dx=if(moving) wave*(w-width)*.43f else 0f
            artworkRect.set((width-w)/2f+dx,(height-h)/2f,(width+w)/2f+dx,(height+h)/2f)
            canvas.drawBitmap(picture,null,artworkRect,artworkPaint)
        } else if(wallpaperArt!=null && DashboardTheme.palette.theme.scene==ConsoleScene.XBOX_SERIES) {
            wallpaperArt!!.phase=phase;wallpaperArt!!.draw(canvas)
        } else painter.draw(canvas,phase)
        chrome?.let { it.motionPhase=phase;it.setBounds(0,0,width,height);it.draw(canvas) }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();attached=true;focused=hasWindowFocus()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // This is a protected system broadcast; older Android does not need a custom permission.
        val filter=IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(powerReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(powerReceiver,filter)
        receiverRegistered=true
        refreshAppearance()
    }
    override fun onDetachedFromWindow() {
        attached=false;stop();refreshWallpaper()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        if(receiverRegistered) { context.unregisterReceiver(powerReceiver);receiverRegistered=false }
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus);focused=hasWindowFocus;refreshScheduling() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility);refreshScheduling() }
    override fun onVisibilityChanged(changedView: View,visibility: Int) { super.onVisibilityChanged(changedView,visibility);if(isAttachedToWindow) refreshScheduling() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible);aggregated=isVisible;refreshScheduling() }
    private fun canPresent()=attached && !closed && presented && focused && aggregated && isShown && windowVisibility==VISIBLE && width>0 && height>0
    private fun refreshScheduling() {
        // Services/settings are sampled on lifecycle/preference changes, never on each frame.
        lowRam=DevicePerformancePolicy.read(context).constrainedMemory
        painter.lowDetail=lowRam
        powerSave=context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
        animations=if(Build.VERSION.SDK_INT>=26) ValueAnimator.areAnimatorsEnabled()
            else Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
        val mode=AppearanceSettingsStore(context).motion();explicitMotion=mode==BackgroundMotion.ON
        refreshWallpaper()
        wallpaperArt?.animationEnabled=BackgroundMotionPolicy.interval(mode,DashboardTheme.palette.theme.scene,
            lowRam,width.toLong()*height>2560L*1440L,powerSave,animations,true,true)!=null
        val next=BackgroundMotionPolicy.interval(mode,DashboardTheme.palette.theme.scene,
            lowRam,width.toLong()*height>2560L*1440L,powerSave,animations,canPresent(),wallpaper?.recreation!=null || DashboardTheme.palette.theme.scene==ConsoleScene.XBOX_ORIGINAL)
        if(next==interval && framePending) return
        stop();interval=next
        if(mode==BackgroundMotion.OFF) { phase=0f;invalidate() }
        if(next!=null) { lastTick=SystemClock.uptimeMillis();framePending=postDelayed(tick,next) }
    }
    private fun refreshWallpaper() {
        removeCallbacks(rotate);rotationPending=false
        val visible=attached && !closed && presented && aggregated && isShown && windowVisibility==VISIBLE && width>0 && height>0
        // Keep the last frame behind modal panels; stop work while their window owns focus.
        if(visible && !focused) {
            if(wallpaperLoader.isInitialized()) wallpaperLoader.value.cancel(wallpaperTarget)
            return
        }
        val active=visible && DashboardTheme.palette.theme.scene==ConsoleScene.XBOX_SERIES
        val next=if(active) series.resolve(System.currentTimeMillis(),opening) else null
        if(next==null) {
            if(wallpaperLoader.isInitialized()) wallpaperLoader.value.cancel(wallpaperTarget)
            galleryBitmap=null;wallpaper=null;wallpaperArt=null;invalidate();return
        }
        if(wallpaper?.id!=next.id || wallpaper?.artUrl!=next.artUrl) {
            if(wallpaperLoader.isInitialized()) wallpaperLoader.value.cancel(wallpaperTarget)
            galleryBitmap=null;wallpaper=next;wallpaperArt=MotionWallpaperArt.create(next)
        }
        wallpaperArt?.setBounds(0,0,width,height)
        if(next.artUrl!=null) {
            val low=lowRam
            wallpaperLoader.value.load(wallpaperTarget,next.artUrl,if(low) 640 else 1024,if(low) 360 else 576,notifyPlaceholder=false) { ready ->
                if(wallpaper?.id==next.id) {
                    galleryBitmap=if(ready) (wallpaperTarget.drawable as? BitmapDrawable)?.bitmap else null
                    invalidate()
                }
            }
        }
        series.nextChange(System.currentTimeMillis())?.let { rotationPending=postDelayed(rotate,it) }
        invalidate()
    }
    private fun stop() { removeCallbacks(tick);framePending=false;interval=null }
    fun close() {
        closed=true;artwork=null;galleryBitmap=null;wallpaper=null;wallpaperArt=null;chrome=null;stop();removeCallbacks(rotate);rotationPending=false
        if(wallpaperLoader.isInitialized()) wallpaperLoader.value.close()
        wallpaperScope.cancel()
    }
    companion object {
        private val openings=WeakHashMap<Context,String>()
        private fun openingFor(context: Context)=synchronized(openings) { openings.getOrPut(context) { UUID.randomUUID().toString() } }
    }
}
