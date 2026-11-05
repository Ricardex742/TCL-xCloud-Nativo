package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.xcloud.tclnative.R
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** A bounded artwork request and native views, shown only between catalog/playback states. */
class SessionTransitionView(context: Context) : FrameLayout(context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val art=PosterLoader(context,scope)
    private val banner=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val shade=View(context).apply {
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x66121b15,0x33121b15,0xf5101b14.toInt()))
    }
    private val logo=ImageView(context).apply {
        scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription="Logo xCLOUD"
        background=GradientDrawable().apply { setColor(0xff08140d.toInt()); cornerRadius=26f }
        clipToOutline=true
    }
    private val eyebrow=label(23,true).apply { text="xCLOUD"; setTextColor(DashboardTheme.accent) }
    private val title=label(48,true).apply { maxLines=2 }
    private val status=label(27,false).apply {
        maxLines=2; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val detail=label(20,false).apply { maxLines=2; setTextColor(DashboardTheme.muted) }
    private val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate=true; indeterminateTintList=ColorStateList.valueOf(DashboardTheme.accent)
        contentDescription="Aguarde"
    }
    private var logoJob: Job?=null
    private var url: String?=null
    private var game: GameTitle?=null
    private var geometry=0L
    var exiting=false
        private set

    init {
        background=DashboardBackground()
        addView(banner,LayoutParams(-1,-1)); addView(shade,LayoutParams(-1,-1))
        listOf(logo,eyebrow,title,status,detail,progress).forEach(::addView)
        isClickable=true; isFocusable=true; isFocusableInTouchMode=true
        DashboardTheme.customFocus(this)
        visibility=GONE
    }
    private fun label(size: Int,heavy: Boolean)=TextView(context).apply {
        typeface=DashboardTheme.font(context,heavy); includeFontPadding=false
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX,size.toFloat())
    }
    fun showStartup(value: GameTitle?,message: String) {
        exiting=false; show(value,message,"Seus jogos. Na sua tela.")
    }
    fun showExit(value: GameTitle?,wasPlaying: Boolean=true) {
        exiting=true
        show(value,if(wasPlaying) "Saindo do jogo…" else "Voltando ao catálogo…",
            "Preparando seu catálogo.")
    }
    private fun show(value: GameTitle?,message: String,description: String) {
        visibility=VISIBLE; detail.text=description; setGame(value); updateStatus(message)
        if(logo.drawable==null && logoJob?.isActive!=true) logoJob=scope.launch {
            // A small local icon, decoded away from the UI; never retain the full 1254px source.
            val bitmap=withContext(Dispatchers.IO) {
                BitmapFactory.decodeResource(resources,R.drawable.brand_logo,BitmapFactory.Options().apply {
                    inSampleSize=2; inScaled=false
                })
            }
            if(isActive && visibility==VISIBLE && bitmap!=null) logo.setImageDrawable(
                RoundedBitmapDrawableFactory.create(resources,bitmap).apply {
                    cornerRadius=bitmap.width*.125f; setAntiAlias(true)
                })
        }
        requestFocus()
    }
    fun updateStatus(message: String) { status.text=message }
    fun setGame(value: GameTitle?) {
        val changed=(game==null)!=(value==null)
        game=value; title.text=value?.name ?: "xCLOUD"
        banner.contentDescription=value?.let { "Banner de ${it.name}" }
        val next=value?.let { it.heroUrl ?: it.tileUrl ?: it.posterUrl }
        if(next!=url) { url=next; art.load(banner,next,1280,720) { updateBrandVisibility() } }
        updateBrandVisibility()
        if(changed) { geometry=0; requestLayout() }
    }
    private fun updateBrandVisibility() { logo.visibility=if(game==null || banner.drawable==null) VISIBLE else GONE }
    fun dismiss() {
        visibility=GONE; exiting=false; logoJob?.cancel(); logoJob=null
        art.cancel(banner); url=null; logo.setImageDrawable(null)
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec); val h=MeasureSpec.getSize(heightMeasureSpec)
        val key=w.toLong() shl 32 or h.toLong()
        if(key!=geometry && w>0 && h>0) {
            geometry=key
            val s=minOf(w/1280f,h/720f); val ox=(w-1280*s)/2; val oy=(h-720*s)/2
            (logo.background as GradientDrawable).cornerRadius=26*s
            fun place(view: View,x: Int,y: Int,vw: Int,vh: Int) {
                view.layoutParams=LayoutParams((vw*s).roundToInt(),(vh*s).roundToInt()).apply {
                    leftMargin=(ox+x*s).roundToInt(); topMargin=(oy+y*s).roundToInt()
                }
            }
            val branded=game==null
            updateBrandVisibility()
            eyebrow.visibility=if(branded) GONE else VISIBLE
            if(branded) {
                place(logo,536,128,208,208); place(title,160,362,960,67)
                place(status,160,446,960,65); place(detail,160,566,960,42)
                place(progress,480,528,320,5)
            } else {
                place(logo,536,138,208,208)
                place(eyebrow,80,54,1120,40); place(title,80,390,1120,116)
                place(status,80,519,1120,66); place(detail,80,638,1120,40)
                place(progress,80,603,340,5)
            }
            listOf(title to 48,status to 27,detail to 20,eyebrow to 23).forEach { (v,size) ->
                v.gravity=if(branded) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
                v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*s)
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun close() { dismiss(); scope.cancel(); art.close() }
}
