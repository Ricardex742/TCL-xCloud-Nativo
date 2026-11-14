package com.xcloud.tclnative.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.ImageView
import com.xcloud.tclnative.settings.*

/** One animated gallery preview; thumbnails do not each start a timer. */
internal class MotionArtworkView(context: Context): ImageView(context) {
    private var attached=false;private var focused=false;private var aggregated=true;private var closed=false
    private var presented=true
    private val television=BackgroundMotionPolicy.television(context)
    private var last=0L;private var step: Long?=null;private var phase=0f
    private var backdrop: Bitmap?=null
    internal var framePending=false;private set
    internal var ticks=0L;private set
    private val preferences=context.getSharedPreferences("app_appearance",Context.MODE_PRIVATE)
    private val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key=="background_motion") refreshMotion() }
    private val tick=object: Runnable {
        override fun run() {
            framePending=false
            if(!visibleForMotion()) { refreshMotion();return }
            val interval=step ?: return
            val now=SystemClock.uptimeMillis();phase=(phase+(now-last).coerceIn(0L,500L)/16000f)%1f;last=now
            (drawable as? MotionWallpaperArt)?.let { it.phase=phase;it.invalidateSelf() };ticks++;invalidate()
            framePending=postDelayed(this,interval)
        }
    }
    fun showArt(art: MotionWallpaperArt) { setBackdrop(null);setImageDrawable(art);phase=0f;refreshMotion() }
    fun setPresentationEnabled(value: Boolean) { presented=value;refreshMotion() }
    fun setBackdrop(value: Drawable?) {
        backdrop=(value as? BitmapDrawable)?.bitmap
        (drawable as? MotionWallpaperArt)?.hasArtwork=backdrop?.isRecycled==false
        invalidate()
    }
    override fun setImageDrawable(value: Drawable?) { super.setImageDrawable(value);if(attached) refreshMotion() }
    override fun onDraw(canvas: Canvas) {
        backdrop?.takeIf { !it.isRecycled }?.let { bitmap ->
            (drawable as? MotionWallpaperArt)?.drawArtwork(canvas,bitmap,width,height)
        }
        super.onDraw(canvas)
    }
    private fun visibleForMotion()=attached && !closed && presented && focused && aggregated && isShown && windowVisibility==VISIBLE && drawable is MotionWallpaperArt
    private fun refreshMotion() {
        removeCallbacks(tick);framePending=false
        val art=drawable as? MotionWallpaperArt ?: return
        val low=DevicePerformancePolicy.read(context).constrainedMemory;art.lowDetail=low || television
        val power=context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
        val animations=if(Build.VERSION.SDK_INT>=26) ValueAnimator.areAnimatorsEnabled()
            else Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0
        val motion=AppearanceSettingsStore(context).motion()
        art.animationEnabled=BackgroundMotionPolicy.interval(motion,ConsoleScene.XBOX_SERIES,low,false,power,animations,true,art is RecreatedWallpaperArt,television)!=null
        step=BackgroundMotionPolicy.interval(motion,ConsoleScene.XBOX_SERIES,low,false,power,animations,visibleForMotion(),art is RecreatedWallpaperArt,television)
        if(motion==BackgroundMotion.OFF) { phase=0f;art.phase=0f;invalidate() }
        step?.let { last=SystemClock.uptimeMillis();framePending=postDelayed(tick,it) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();attached=true;focused=hasWindowFocus();preferences.registerOnSharedPreferenceChangeListener(listener);refreshMotion() }
    override fun onDetachedFromWindow() { attached=false;removeCallbacks(tick);framePending=false;preferences.unregisterOnSharedPreferenceChangeListener(listener);super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus);focused=hasWindowFocus;refreshMotion() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible);aggregated=isVisible;refreshMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility);if(attached) refreshMotion() }
    fun close() { closed=true;removeCallbacks(tick);framePending=false;backdrop=null;setImageDrawable(null) }
}
