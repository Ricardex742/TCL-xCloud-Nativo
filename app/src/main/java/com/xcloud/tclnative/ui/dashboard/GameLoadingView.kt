package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.DevicePerformancePolicy
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.QueueStatus
import kotlinx.coroutines.*
import kotlin.math.roundToInt

enum class LoadingPhase { QUEUED, PREPARING, CONNECTING, VIDEO }

/** Real queue/connection state, with game artwork. No fictitious percentage or countdown. */
class GameLoadingView(context: Context, private val cancel: () -> Unit) : FrameLayout(context) {
    private val devicePolicy=DevicePerformancePolicy.read(context)
    private var scene=DashboardTheme.palette.theme.scene
    private var lastRenderedAt=0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val art = PosterLoader(context, scope)
    private val backdrop = FixedArtworkView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val shade = View(context).apply { background = DashboardTheme.gradient(GradientDrawable.Orientation.LEFT_RIGHT,
        arrayOf(UiColor.QUEUE_SHADE_TOP, UiColor.QUEUE_SHADE_MID, UiColor.QUEUE_SHADE_END)) }
    private val eyebrow = text(19f).apply { text = "xCLOUD"; setTextColor(DashboardTheme.accent) }
    private val consoleLogo=ConsoleBrandView(context).apply { visibility=GONE }
    private val title = text(44f).apply { maxLines = 2 }
    private val poster = FixedArtworkView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(UiColor.QUEUE_POSTER); clipToOutline = true
    }
    private val posterFallback = text(26f).apply { gravity = Gravity.CENTER; maxLines = 4 }
    private val badges = GameBadgesView(context)
    private val panel = FrameLayout(context).apply { background = rounded(UiColor.QUEUE_PANEL) }
    private val status = text(25f)
    private val estimateLabel = text(18f, false).apply { setTextColor(DashboardTheme.color(UiColor.QUEUE_TEXT)) }
    private val estimate = text(49f)
    private val elapsedLabel = text(18f, false).apply { setTextColor(DashboardTheme.color(UiColor.QUEUE_TEXT)) }
    private val elapsed = text(33f)
    private val explanation = text(21f, false).apply { maxLines = 2 }
    private val heartbeat = text(18f, false).apply { setTextColor(DashboardTheme.color(UiColor.QUEUE_TEXT)) }
    private val freshness = text(17f, false).apply { maxLines = 2; setTextColor(DashboardTheme.color(UiColor.QUEUE_TEXT)) }
    private val spinner = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(DashboardTheme.accent) }
    private val steps = List(3) { index -> text(21f).apply { gravity = Gravity.CENTER; tag = index } }
    private val note = text(17f, false).apply { setTextColor(DashboardTheme.color(UiColor.QUEUE_TEXT)); maxLines = 2 }
    private val cancelButton = text(22f).apply {
        text = UiText.text("B  CANCELAR"); gravity = Gravity.CENTER
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        DashboardTheme.customFocus(this)
        val plate = FocusPlate(7f, 5f)
        background = plate
        setOnFocusChangeListener { _, focus -> plate.highlighted = focus }
        setOnClickListener { cancel() }
        contentDescription = UiText.text("Cancelar espera e voltar ao catálogo")
    }
    private var game: GameTitle? = null
    private var lastBackdrop: String? = null
    private var lastPoster: String? = null
    private var startedAt = 0L
    var phase = LoadingPhase.PREPARING
        private set
    private var queue: QueueStatus? = null
    private var geometry = 0L

    init {
        background = DashboardBackground()
        addView(backdrop, LayoutParams(-1, -1)); addView(shade, LayoutParams(-1, -1))
        addView(eyebrow);addView(consoleLogo); addView(title); addView(poster); addView(posterFallback); addView(badges)
        listOf(status, estimateLabel, estimate, elapsedLabel, elapsed, explanation, heartbeat, freshness, spinner).forEach(panel::addView)
        addView(panel); steps.forEach(::addView); addView(note); addView(cancelButton)
        visibility = View.GONE
        refreshAppearance()
    }
    private fun text(size: Float, heavy: Boolean = true) = com.xcloud.tclnative.i18n.LocalizedTextView(context).apply {
        typeface = DashboardTheme.font(context, heavy); includeFontPadding = false
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        maxLines = 1
    }
    private fun rounded(color: UiColor)=DashboardTheme.shape(color,9f,1,UiColor.QUEUE_BORDER)
    fun begin(value: GameTitle, nowMs: Long = System.nanoTime()/1_000_000) {
        art.resume()
        queue = null; phase = LoadingPhase.PREPARING; startedAt = nowMs
        setGame(value); visibility = View.VISIBLE
        render(nowMs)
        cancelButton.requestFocus()
    }
    fun setGame(value: GameTitle) {
        game = value
        title.text = value.name
        posterFallback.text = value.name
        val hero = value.heroUrl ?: value.tileUrl ?: value.posterUrl
        if (hero != lastBackdrop) {
            lastBackdrop = hero
            if(scene==ConsoleScene.NONE) art.load(backdrop,hero,
                if(devicePolicy.constrainedMemory) 640 else 1920,if(devicePolicy.constrainedMemory) 360 else 1080)
            else if(scene==ConsoleScene.XBOX_ONE || scene==ConsoleScene.XBOX_SERIES) {
                val modest=devicePolicy.constrainedMemory
                art.load(backdrop,hero,if(modest) 640 else 1024,if(modest) 360 else 576)
            } else art.cancel(backdrop)
        }
        val portrait = if(scene==ConsoleScene.XBOX_ONE || scene==ConsoleScene.XBOX_SERIES || scene==ConsoleScene.XBOX_360_NXE || scene==ConsoleScene.XBOX_360_METRO) value.tileUrl ?: value.posterUrl else value.posterUrl ?: value.tileUrl
        if (portrait != lastPoster) {
            lastPoster = portrait
            art.load(poster, portrait) { ready -> posterFallback.visibility = if (ready) View.GONE else View.VISIBLE }
        }
        badges.bind(value.gamePass, value.seriesOptimized, currentScale())
    }
    fun updateQueue(value: QueueStatus?, nowMs: Long = System.nanoTime()/1_000_000) {
        queue = value
        phase = if (value != null) LoadingPhase.QUEUED else LoadingPhase.PREPARING
        render(nowMs)
    }
    fun updateProgress(progress: String) {
        phase = when {
            progress.startsWith("QUEUE:") -> LoadingPhase.QUEUED
            progress.startsWith("VIDEO:") -> LoadingPhase.VIDEO
            progress.startsWith("SDP:") || progress.startsWith("ICE:") || progress.startsWith("TRANSPORT:") -> LoadingPhase.CONNECTING
            else -> LoadingPhase.PREPARING
        }
        render(System.nanoTime()/1_000_000)
    }
    fun render(nowMs: Long) {
        lastRenderedAt=nowMs
        val snapshot = queue?.takeIf { phase == LoadingPhase.QUEUED }
        status.text = when (phase) {
            LoadingPhase.QUEUED -> UiText.text("AGUARDANDO UMA VAGA")
            LoadingPhase.PREPARING -> UiText.text("PREPARANDO SEU JOGO")
            LoadingPhase.CONNECTING -> UiText.text("CONECTANDO A TRANSMISSÃO")
            LoadingPhase.VIDEO -> UiText.text("RECEBENDO A IMAGEM")
        }
        estimateLabel.text = if (phase == LoadingPhase.QUEUED) UiText.text("PREVISÃO DO XBOX") else UiText.text("SUA SESSÃO")
        estimate.text = if (phase == LoadingPhase.QUEUED) snapshot?.estimate?.let {
            if (it.seconds < 60) UiText.text("Menos de 1 min") else UiText.text("Cerca de {0}",UiText.nested(QueueStatus.duration(it.seconds)))
        } ?: UiText.text("Consultando…") else if (phase == LoadingPhase.PREPARING) UiText.text("Vamos jogar") else UiText.text("Quase pronto")
        elapsedLabel.text = if (snapshot != null) UiText.text("NA FILA HÁ") else UiText.text("TEMPO DECORRIDO")
        elapsed.text = QueueStatus.duration((nowMs-(snapshot?.enteredAtMs ?: startedAt)).coerceAtLeast(0)/1000)
        explanation.text = when (phase) {
            LoadingPhase.QUEUED -> UiText.text("Seu jogo abrirá assim que o Xbox liberar uma sessão.")
            LoadingPhase.PREPARING -> UiText.text("O Xbox está reservando e preparando a sua sessão.")
            LoadingPhase.CONNECTING -> UiText.text("Conectando áudio, vídeo e controle ao Xbox.")
            LoadingPhase.VIDEO -> UiText.text("Conexão estabelecida. Aguardando o primeiro quadro do jogo.")
        }
        heartbeat.text = snapshot?.let { UiText.text("Fila confirmada há {0}",UiText.nested(QueueStatus.duration((nowMs-it.confirmedAtMs).coerceAtLeast(0)/1000))) }
            ?: UiText.text("Você pode cancelar com B ou Voltar.")
        val age = snapshot?.estimateAtMs?.let { (nowMs-it).coerceAtLeast(0)/1000 }
        val stale = snapshot?.estimateFailed == true || (age ?: 0) > 90
        freshness.text = when {
            snapshot == null -> ""
            snapshot.estimate == null -> UiText.text("O Xbox ainda não informou uma estimativa de espera.")
            stale -> UiText.text("Previsão desatualizada. A busca por uma vaga continua.")
            age != null -> UiText.text("Estimativa atualizada há {0}. O tempo pode variar.",UiText.nested(QueueStatus.duration(age)))
            else -> UiText.text("A estimativa pode mudar; não é um horário garantido.")
        }
        freshness.setTextColor(if(stale) { if(scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_METRO) 0xff765100.toInt() else DashboardTheme.yellow } else if(scene==ConsoleScene.NONE) DashboardTheme.color(UiColor.QUEUE_TEXT) else secondaryColor())
        val step = when (phase) { LoadingPhase.QUEUED -> 0; LoadingPhase.PREPARING -> 1; else -> 2 }
        val labels = listOf(UiText.text("FILA"), UiText.text("PREPARAÇÃO"), UiText.text("CONEXÃO"))
        steps.forEachIndexed { index, label ->
            label.text=if(scene==ConsoleScene.XBOX_SERIES) labels[index] else "${if(index<step) "✓" else "${index+1}"}   ${labels[index]}"
            if(scene==ConsoleScene.NONE) {
                label.setTextColor(if(index<=step) Color.WHITE else DashboardTheme.color(UiColor.QUEUE_MUTED))
                label.background=DashboardTheme.shape(if(index==step) UiColor.QUEUE_ACTIVE else UiColor.QUEUE_INACTIVE,
                    7f,if(index==step) 2 else 1,if(index==step) UiColor.ACCENT else UiColor.QUEUE_STEP_BORDER)
            } else {
                label.setTextColor(when(scene) {
                    ConsoleScene.XBOX_360,ConsoleScene.XBOX_360_NXE,ConsoleScene.XBOX_360_METRO -> if(index==step) Color.WHITE else 0xff31431b.toInt()
                    ConsoleScene.XBOX_ORIGINAL -> if(index==step) 0xff132900.toInt() else 0xffb5dc75.toInt()
                    else -> if(index<=step) Color.WHITE else 0xffb0b7bd.toInt()
                })
                label.background=ConsoleQueueStep(scene,index,index==step,index<step)
            }
        }
        note.text = if (snapshot != null) UiText.text("A previsão pode mudar. Limite da tentativa: {0}.",UiText.nested(QueueStatus.duration(snapshot.limitMs/1000)))
            else UiText.text("A partida será exibida assim que o vídeo estiver pronto.")
    }
    fun cancelFromController() { cancelButton.performClick() }
    fun dismiss() {
        visibility = View.GONE; queue = null
        art.pause(); lastBackdrop=null; lastPoster=null
    }
    private fun secondaryColor()=if(scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_METRO) 0xff42532d.toInt() else if(scene==ConsoleScene.XBOX_ORIGINAL) 0xffb9df85.toInt() else 0xffd0d5d9.toInt()
    internal fun refreshAppearance() {
        val next=DashboardTheme.palette.theme.scene;val changed=scene!=next;scene=next
        if(scene==ConsoleScene.NONE) {
            consoleLogo.visibility=GONE;eyebrow.visibility=VISIBLE
            if(!changed) return
            background=DashboardBackground()
            shade.background=DashboardTheme.gradient(GradientDrawable.Orientation.LEFT_RIGHT,arrayOf(UiColor.QUEUE_SHADE_TOP,UiColor.QUEUE_SHADE_MID,UiColor.QUEUE_SHADE_END))
            backdrop.visibility=VISIBLE
            listOf(eyebrow,title,posterFallback,status,estimate,elapsed,cancelButton).forEach { it.typeface=DashboardTheme.font(context);it.setTextColor(Color.WHITE) }
            listOf(estimateLabel,elapsedLabel,heartbeat,freshness,note).forEach { it.typeface=DashboardTheme.font(context,false);it.setTextColor(DashboardTheme.color(UiColor.QUEUE_TEXT)) }
            explanation.typeface=DashboardTheme.font(context,false);explanation.setTextColor(Color.WHITE)
            eyebrow.text="xCLOUD";eyebrow.setTextColor(DashboardTheme.accent)
            panel.background=rounded(UiColor.QUEUE_PANEL);poster.background=rounded(UiColor.QUEUE_POSTER)
            val focus=FocusPlate(7f,5f);cancelButton.background=focus;focus.highlighted=cancelButton.hasFocus()
            cancelButton.setOnFocusChangeListener { _,value -> focus.highlighted=value }
            steps.forEach { it.gravity=Gravity.CENTER;it.setPadding(0,0,0,0);it.typeface=DashboardTheme.font(context) }
            spinner.indeterminateTintList=ColorStateList.valueOf(DashboardTheme.accent)
            geometry=0L;lastBackdrop=null;lastPoster=null;requestLayout();game?.let(::setGame)
            if(game!=null) render(lastRenderedAt)
            return
        }
        steps.forEach { it.gravity=Gravity.CENTER;it.setPadding(0,0,0,0) }
        consoleLogo.refresh();consoleLogo.visibility=VISIBLE;eyebrow.visibility=GONE
        background=DashboardBackground(consoleArtwork=scene!=ConsoleScene.XBOX_360)
        shade.background=ConsoleQueueChrome(scene)
        backdrop.visibility=if(scene==ConsoleScene.XBOX_ONE || scene==ConsoleScene.XBOX_SERIES) VISIBLE else GONE
        val ink=if(scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_METRO) 0xff233415.toInt() else Color.WHITE
        listOf(eyebrow,title,posterFallback,status,estimateLabel,estimate,elapsedLabel,elapsed,explanation,heartbeat,freshness,note,cancelButton).forEach { v ->
            v.typeface=Typeface.create(if(scene==ConsoleScene.XBOX_ORIGINAL) "sans-serif-condensed" else "sans-serif-medium",Typeface.NORMAL)
            v.setTextColor(ink)
        }
        listOf(estimateLabel,elapsedLabel,explanation,heartbeat,freshness,note).forEach { it.typeface=Typeface.create("sans-serif",Typeface.NORMAL);it.setTextColor(secondaryColor()) }
        eyebrow.text=when(scene) { ConsoleScene.XBOX_360,ConsoleScene.XBOX_360_NXE,ConsoleScene.XBOX_360_METRO -> "XBOX 360";ConsoleScene.XBOX_ONE -> "Xbox One";ConsoleScene.XBOX_SERIES -> "Xbox Series X|S";else -> "XBOX" }
        eyebrow.setTextColor(if(scene==ConsoleScene.XBOX_ORIGINAL) 0xffb8ee4c.toInt() else ink)
        panel.background=when(scene) {
            ConsoleScene.XBOX_360 -> GradientDrawable().apply { setColor(0x70f6f8e9);cornerRadius=5f;setStroke(1,0xff8da469.toInt()) }
            ConsoleScene.XBOX_ONE -> GradientDrawable().apply { setColor(0xff161616.toInt()) }
            ConsoleScene.XBOX_360_NXE -> GradientDrawable().apply { setColor(0xdf263324.toInt());cornerRadius=5f;setStroke(1,0xffa3b39b.toInt()) }
            ConsoleScene.XBOX_360_METRO -> GradientDrawable().apply { setColor(0xffeff1ec.toInt()) }
            ConsoleScene.XBOX_SERIES -> null
            else -> GradientDrawable().apply { setColor(0xc60b1d03.toInt());cornerRadius=12f;setStroke(1,0xff6d952c.toInt()) }
        }
        cancelButton.background=ConsoleControlPlate(scene,ConsoleControl.ACTION,true)
        cancelButton.setTextColor(if(scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_ORIGINAL) 0xff142700.toInt() else Color.WHITE)
        steps.forEach { it.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        spinner.indeterminateTintList=ColorStateList.valueOf(if(scene==ConsoleScene.XBOX_360) 0xff4d761e.toInt() else 0xff8bdc42.toInt())
        if(scene==ConsoleScene.XBOX_360_NXE) note.setTextColor(0xff263024.toInt())
        geometry=0L;requestLayout()
        if(changed) { lastBackdrop=null;lastPoster=null;game?.let(::setGame) }
        if(game!=null) render(lastRenderedAt)
    }
    private fun currentScale() = minOf(width/1280f, height/720f).takeIf { it > 0 } ?: 1f
    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        view.layoutParams = LayoutParams(w.roundToInt(), h.roundToInt()).apply { leftMargin=x.roundToInt(); topMargin=y.roundToInt() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val key = w.toLong() shl 32 or h.toLong()
        if (key != geometry && w > 0 && h > 0) {
            geometry = key
            if(scene!=ConsoleScene.NONE) { layoutConsole(w,h);super.onMeasure(widthMeasureSpec,heightMeasureSpec);return }
            val s = minOf(w/1280f, h/720f); val ox = (w-1280*s)/2; val oy = (h-720*s)/2
            fun root(view: View, x: Int, y: Int, vw: Int, vh: Int) = place(view, ox+x*s, oy+y*s, vw*s, vh*s)
            fun item(view: View, x: Int, y: Int, vw: Int, vh: Int) = place(view, x*s, y*s, vw*s, vh*s)
            root(eyebrow, 80, 42, 1110, 28); root(title, 80, 79, 1120, 104)
            root(poster, 80, 214, 202, 304); root(posterFallback, 90, 222, 182, 288)
            root(badges, 80, 466, 202, 52); game?.let { badges.bind(it.gamePass, it.seriesOptimized, s) }
            root(panel, 330, 214, 870, 338)
            item(status, 26, 23, 765, 36); item(spinner, 803, 25, 32, 32)
            item(estimateLabel, 26, 75, 470, 25); item(estimate, 26, 106, 525, 67)
            item(elapsedLabel, 586, 75, 250, 25); item(elapsed, 586, 112, 250, 48)
            item(explanation, 26, 189, 808, 58); item(heartbeat, 26, 256, 808, 25)
            item(freshness, 26, 289, 808, 46)
            steps.forEachIndexed { index, label -> root(label, 330+index*296, 572, 278, 48) }
            root(cancelButton, 80, 630, 202, 50); root(note, 330, 638, 870, 46)
            listOf(eyebrow to 19, title to 44, posterFallback to 26, status to 25, estimateLabel to 18,
                estimate to 49, elapsedLabel to 18, elapsed to 33, explanation to 21, heartbeat to 18,
                freshness to 17, note to 17, cancelButton to 22).forEach { (view, size) ->
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size*s)
            }
            steps.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21*s) }
            if (android.os.Build.VERSION.SDK_INT >= 26) title.setAutoSizeTextTypeUniformWithConfiguration(
                (28*s).roundToInt().coerceAtLeast(1), (44*s).roundToInt().coerceAtLeast(2), 1, TypedValue.COMPLEX_UNIT_PX)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun layoutConsole(w: Int,h: Int) {
        val s=minOf(w/1280f,h/720f);val ox=(w-1280*s)/2f;val oy=(h-720*s)/2f
        fun root(v: View,x: Int,y: Int,vw: Int,vh: Int)=place(v,ox+x*s,oy+y*s,vw*s,vh*s)
        fun item(v: View,x: Int,y: Int,vw: Int,vh: Int)=place(v,x*s,y*s,vw*s,vh*s)
        title.maxLines=2;title.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        if(android.os.Build.VERSION.SDK_INT>=26) title.setAutoSizeTextTypeUniformWithConfiguration((25*s).roundToInt().coerceAtLeast(1),(38*s).roundToInt().coerceAtLeast(2),1,TypedValue.COMPLEX_UNIT_PX)
        when(scene) {
            ConsoleScene.XBOX_360_NXE -> {
                root(eyebrow,64,38,1100,35);root(title,64,88,1100,79)
                root(poster,76,207,294,294);root(posterFallback,88,219,270,270);root(badges,76,449,294,52)
                root(panel,414,197,790,338)
                steps.forEachIndexed { i,v -> root(v,414+i*269,565,253,54) }
                root(cancelButton,76,565,294,54);root(note,414,641,790,39)
            }
            ConsoleScene.XBOX_360_METRO -> {
                root(eyebrow,64,38,1100,35);root(title,64,88,1100,79)
                root(poster,64,204,306,306);root(posterFallback,76,216,282,282);root(badges,64,458,306,52)
                root(panel,406,204,798,330)
                steps.forEachIndexed { i,v -> root(v,406+i*271,555,255,57) }
                root(cancelButton,64,555,306,57);root(note,406,636,798,39)
            }
            ConsoleScene.XBOX_360 -> {
                root(eyebrow,98,47,1080,33);root(title,98,93,1080,67)
                root(poster,102,202,218,322);root(posterFallback,110,211,202,298);root(badges,102,472,218,52)
                root(panel,366,202,810,322)
                steps.forEachIndexed { i,v ->root(v,366+i*275,550,260,52) }
                root(cancelButton,102,552,218,52);root(note,366,616,810,36)
            }
            ConsoleScene.XBOX_ONE -> {
                root(eyebrow,65,38,1145,33);root(title,65,86,1145,78)
                root(poster,65,195,328,328);root(posterFallback,76,206,306,306);root(badges,65,471,328,52)
                root(panel,427,195,788,328)
                steps.forEachIndexed { i,v ->root(v,427+i*268,551,252,54) }
                root(cancelButton,65,551,328,54);root(note,427,637,788,43)
            }
            ConsoleScene.XBOX_SERIES -> {
                root(eyebrow,98,42,1090,32);root(title,98,91,1090,75)
                root(poster,100,222,282,282);root(posterFallback,112,234,258,258);root(badges,100,452,282,52)
                root(panel,432,214,760,320)
                steps.forEachIndexed { i,v ->root(v,100+i*373,553,352,54);v.gravity=Gravity.CENTER_VERTICAL or Gravity.START;v.setPadding((51*s).roundToInt(),0,0,0) }
                root(cancelButton,482,636,310,51);root(note,100,606,1090,29)
            }
            ConsoleScene.XBOX_ORIGINAL -> {
                root(eyebrow,90,600,430,58);root(title,67,538,512,49)
                root(poster,234,238,192,249);root(posterFallback,244,249,172,226);root(badges,234,437,192,50)
                root(panel,646,149,554,400)
                steps.forEachIndexed { i,v ->root(v,646+i*188,571,176,50) }
                root(cancelButton,646,640,554,49);root(note,646,98,554,41)
            }
            else -> Unit
        }
        val narrow=scene==ConsoleScene.XBOX_ORIGINAL
        if(narrow) root(consoleLogo,112,590,442,68) else root(consoleLogo,65,24,230,59)
        val panelWidth=panel.layoutParams.width/s
        item(status,24,20,(panelWidth-84).roundToInt(),36);item(spinner,(panelWidth-54).roundToInt(),24,28,28)
        if(narrow) {
            item(estimateLabel,24,80,500,26);item(estimate,24,112,500,62)
            item(elapsedLabel,24,191,245,25);item(elapsed,297,184,230,41)
            item(explanation,24,244,500,55);item(heartbeat,24,307,500,28);item(freshness,24,340,500,52)
        } else {
            val clockX=(panelWidth-227).roundToInt()
            item(estimateLabel,24,77,clockX-38,26);item(estimate,24,111,clockX-35,67)
            item(elapsedLabel,clockX,77,200,26);item(elapsed,clockX,117,200,45)
            item(explanation,24,196,(panelWidth-48).roundToInt(),53)
            item(heartbeat,24,252,(panelWidth-48).roundToInt(),28);item(freshness,24,282,(panelWidth-48).roundToInt(),38)
        }
        listOf(eyebrow to if(narrow) 48 else 22,title to 38,posterFallback to 24,status to 23,estimateLabel to 16,
            estimate to if(narrow) 40 else 46,elapsedLabel to 16,elapsed to 29,explanation to 20,heartbeat to 17,
            freshness to 15,note to 16,cancelButton to 21).forEach { (v,size) ->v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*s) }
        steps.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX,18*s) }
        poster.background=GradientDrawable().apply { setColor(0xff151915.toInt());cornerRadius=if(scene==ConsoleScene.XBOX_SERIES) 7*s else 0f }
        game?.let { badges.bind(it.gamePass,it.seriesOptimized,s) }
    }
    fun close() { scope.cancel(); art.close() }
}
