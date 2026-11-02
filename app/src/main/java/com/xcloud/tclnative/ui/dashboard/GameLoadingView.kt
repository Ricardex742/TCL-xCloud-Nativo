package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val art = PosterLoader(context, scope)
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val shade = View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xf51a1820.toInt(), 0xcf15151d.toInt(), 0xa62d1838.toInt())) }
    private val eyebrow = text(19f).apply { text = "XBOX CLOUD GAMING"; setTextColor(DashboardTheme.violet) }
    private val title = text(44f).apply { maxLines = 2 }
    private val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(0xff38313f.toInt()); clipToOutline = true
    }
    private val posterFallback = text(26f).apply { gravity = Gravity.CENTER; maxLines = 4 }
    private val badges = GameBadgesView(context)
    private val panel = FrameLayout(context).apply { background = rounded(0xe622202b.toInt()) }
    private val status = text(25f)
    private val estimateLabel = text(18f, false).apply { setTextColor(0xffc7bbcF.toInt()) }
    private val estimate = text(49f)
    private val elapsedLabel = text(18f, false).apply { setTextColor(0xffc7bbcF.toInt()) }
    private val elapsed = text(33f)
    private val explanation = text(21f, false).apply { maxLines = 2 }
    private val heartbeat = text(18f, false).apply { setTextColor(0xffc7bbcf.toInt()) }
    private val freshness = text(17f, false).apply { maxLines = 2; setTextColor(0xffc7bbcf.toInt()) }
    private val spinner = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(DashboardTheme.violet) }
    private val steps = List(3) { index -> text(21f).apply { gravity = Gravity.CENTER; tag = index } }
    private val note = text(17f, false).apply { setTextColor(0xffc7bbcf.toInt()); maxLines = 2 }
    private val cancelButton = text(22f).apply {
        text = "B  CANCELAR"; gravity = Gravity.CENTER
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        val plate = FocusPlate(7f, 5f)
        background = plate
        setOnFocusChangeListener { _, focus -> plate.highlighted = focus }
        setOnClickListener { cancel() }
        contentDescription = "Cancelar espera e voltar ao catálogo"
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
        addView(eyebrow); addView(title); addView(poster); addView(posterFallback); addView(badges)
        listOf(status, estimateLabel, estimate, elapsedLabel, elapsed, explanation, heartbeat, freshness, spinner).forEach(panel::addView)
        addView(panel); steps.forEach(::addView); addView(note); addView(cancelButton)
        visibility = View.GONE
    }
    private fun text(size: Float, heavy: Boolean = true) = TextView(context).apply {
        typeface = DashboardTheme.font(context, heavy); includeFontPadding = false
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        maxLines = 1
    }
    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = 9f; setStroke(1, 0xff51405f.toInt())
    }
    fun begin(value: GameTitle, nowMs: Long = System.nanoTime()/1_000_000) {
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
        if (hero != lastBackdrop) { lastBackdrop = hero; art.load(backdrop, hero, 1920, 1080) }
        val portrait = value.posterUrl ?: value.tileUrl
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
        val snapshot = queue?.takeIf { phase == LoadingPhase.QUEUED }
        status.text = when (phase) {
            LoadingPhase.QUEUED -> "AGUARDANDO UMA VAGA"
            LoadingPhase.PREPARING -> "PREPARANDO SEU JOGO"
            LoadingPhase.CONNECTING -> "CONECTANDO A TRANSMISSÃO"
            LoadingPhase.VIDEO -> "RECEBENDO A IMAGEM"
        }
        estimateLabel.text = if (phase == LoadingPhase.QUEUED) "PREVISÃO DO XBOX" else "SUA SESSÃO"
        estimate.text = if (phase == LoadingPhase.QUEUED) snapshot?.estimate?.let {
            if (it.seconds < 60) "Menos de 1 min" else "Cerca de ${QueueStatus.duration(it.seconds)}"
        } ?: "Consultando…" else if (phase == LoadingPhase.PREPARING) "Vamos jogar" else "Quase pronto"
        elapsedLabel.text = if (snapshot != null) "NA FILA HÁ" else "TEMPO DECORRIDO"
        elapsed.text = QueueStatus.duration((nowMs-(snapshot?.enteredAtMs ?: startedAt)).coerceAtLeast(0)/1000)
        explanation.text = when (phase) {
            LoadingPhase.QUEUED -> "Seu jogo abrirá assim que o Xbox liberar uma sessão."
            LoadingPhase.PREPARING -> "O Xbox está reservando e preparando a sua sessão."
            LoadingPhase.CONNECTING -> "Conectando áudio, vídeo e controle ao Xbox."
            LoadingPhase.VIDEO -> "Conexão estabelecida. Aguardando o primeiro quadro do jogo."
        }
        heartbeat.text = snapshot?.let { "Fila confirmada há ${QueueStatus.duration((nowMs-it.confirmedAtMs).coerceAtLeast(0)/1000)}" }
            ?: "Você pode cancelar com B ou Voltar."
        val age = snapshot?.estimateAtMs?.let { (nowMs-it).coerceAtLeast(0)/1000 }
        val stale = snapshot?.estimateFailed == true || (age ?: 0) > 90
        freshness.text = when {
            snapshot == null -> ""
            snapshot.estimate == null -> "O Xbox ainda não informou uma estimativa de espera."
            stale -> "Previsão desatualizada. A busca por uma vaga continua."
            age != null -> "Estimativa atualizada há ${QueueStatus.duration(age)}. O tempo pode variar."
            else -> "A estimativa pode mudar; não é um horário garantido."
        }
        freshness.setTextColor(if (stale) DashboardTheme.yellow else 0xffc7bbcf.toInt())
        val step = when (phase) { LoadingPhase.QUEUED -> 0; LoadingPhase.PREPARING -> 1; else -> 2 }
        val labels = listOf("FILA", "PREPARAÇÃO", "CONEXÃO")
        steps.forEachIndexed { index, label ->
            label.text = "${if (index < step) "✓" else "${index+1}"}   ${labels[index]}"
            label.setTextColor(if (index <= step) Color.WHITE else 0xff93879c.toInt())
            label.background = GradientDrawable().apply {
                setColor(if (index == step) 0xb45b2d77.toInt() else 0x8824202d.toInt())
                cornerRadius = 7f; setStroke(if (index == step) 2 else 1, if (index == step) DashboardTheme.violet else 0xff4b3e55.toInt())
            }
        }
        note.text = if (snapshot != null) "A previsão pode mudar. Limite da tentativa: ${QueueStatus.duration(snapshot.limitMs/1000)}."
            else "A partida será exibida assim que o vídeo estiver pronto."
    }
    fun cancelFromController() { cancelButton.performClick() }
    fun dismiss() { visibility = View.GONE; queue = null }
    private fun currentScale() = minOf(width/1280f, height/720f).takeIf { it > 0 } ?: 1f
    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        view.layoutParams = LayoutParams(w.roundToInt(), h.roundToInt()).apply { leftMargin=x.roundToInt(); topMargin=y.roundToInt() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val key = w.toLong() shl 32 or h.toLong()
        if (key != geometry && w > 0 && h > 0) {
            geometry = key
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
    fun close() { scope.cancel(); art.close() }
}
