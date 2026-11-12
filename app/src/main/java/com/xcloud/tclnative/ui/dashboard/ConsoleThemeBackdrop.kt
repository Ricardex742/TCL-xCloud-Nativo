package com.xcloud.tclnative.ui.dashboard

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.*
import android.graphics.Canvas
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import com.xcloud.tclnative.settings.*

/** No timer for the seven color palettes, hidden screens or gameplay. AUTO is conservative. */
internal object BackgroundMotionPolicy {
    fun interval(mode: BackgroundMotion,scene: ConsoleScene,lowRam: Boolean,largeUi: Boolean,
                 powerSave: Boolean,animations: Boolean,presented: Boolean): Long? {
        if(!presented || scene==ConsoleScene.NONE || mode==BackgroundMotion.OFF || powerSave || !animations) return null
        if(mode==BackgroundMotion.AUTO && (lowRam || largeUi)) return null
        return if(lowRam || largeUi) 167L else 84L
    }
}

/** A separate render node avoids invalidating/rebinding catalogue cards for background motion. */
internal class ConsoleThemeBackdrop(context: Context): View(context) {
    private val painter=ConsoleScenePainter()
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
            if(Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled()) { refreshScheduling();return }
            if(!canPresent()) { refreshScheduling();return }
            val step=interval ?: return
            val now=SystemClock.uptimeMillis()
            phase=(phase+(now-lastTick).coerceIn(0L,250L)/24000f)%1f;lastTick=now
            renderedTicks++;invalidate()
            framePending=postDelayed(this,step)
        }
    }
    private val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="background_motion") refreshScheduling()
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
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);refreshAppearance() }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas);painter.draw(canvas,phase) }
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
        attached=false;stop()
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
        lowRam=context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: true
        powerSave=context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
        animations=if(Build.VERSION.SDK_INT>=26) ValueAnimator.areAnimatorsEnabled()
            else Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
        val next=BackgroundMotionPolicy.interval(AppearanceSettingsStore(context).motion(),DashboardTheme.palette.theme.scene,
            lowRam,width.toLong()*height>2560L*1440L,powerSave,animations,canPresent())
        if(next==interval && framePending) return
        stop();interval=next
        if(next!=null) { lastTick=SystemClock.uptimeMillis();framePending=postDelayed(tick,next) }
    }
    private fun stop() { removeCallbacks(tick);framePending=false;interval=null }
    fun close() { closed=true;stop() }
}
