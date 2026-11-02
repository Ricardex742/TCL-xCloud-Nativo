package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.content.res.ColorStateList
import com.xcloud.tclnative.auth.DeviceCodeInfo
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Login and device-code screens share the catalog's native typography, green palette and focus. */
class ConsoleLoginView(context: Context, private val signIn: () -> Unit, private val back: () -> Unit,
                       private val openApproval: (String) -> Unit) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var ticker: Job? = null
    var isQr = false
        private set
    private var info: DeviceCodeInfo? = null
    private var failed = false
    private var connecting = false
    private var geometry = 0L
    private val eyebrow = label("XBOX CLOUD GAMING",21).apply { setTextColor(DashboardTheme.accent) }
    private val headline = label("SEUS JOGOS.\nNA SUA TELA.",65).apply { maxLines=2 }
    private val description = label("Seu catálogo Xbox Cloud, com transmissão nativa e navegação pelo controle.",24,false).apply {
        maxLines=3; setTextColor(DashboardTheme.muted)
    }
    private val card = FrameLayout(context).apply { background=DashboardTheme.panel(16f) }
    private val cardTitle = label("ENTRE PARA JOGAR",34)
    private val instructions = label("Conecte sua conta Microsoft para acessar seus jogos.",22,false).apply {
        maxLines=3; setTextColor(DashboardTheme.muted)
    }
    private val qr = ImageView(context).apply {
        setBackgroundColor(Color.WHITE); scaleType=ImageView.ScaleType.FIT_CENTER
        contentDescription="QR Code oficial de login da Microsoft"
    }
    private val code = label("",44).apply { setTextColor(DashboardTheme.accent); gravity=Gravity.CENTER }
    private val status = label("Use o celular para conectar a sua conta.",20,false).apply { maxLines=3; setTextColor(DashboardTheme.muted) }
    private val validity = label("",18,false).apply { maxLines=2; setTextColor(DashboardTheme.muted) }
    private val spinner = ProgressBar(context).apply { indeterminateTintList=ColorStateList.valueOf(DashboardTheme.accent); visibility=GONE }
    private val primary = DashboardTheme.button(context,"ENTRAR COM QR CODE") {
        if (!connecting) {
            val current = info
            if (current != null && !failed) openApproval(current.verificationUriComplete ?: current.verificationUri) else signIn()
        }
    }
    private val secondary = DashboardTheme.button(context,"VOLTAR") { back() }
    private val footer = label("A  SELECIONAR       B  VOLTAR",18,false).apply { setTextColor(DashboardTheme.muted) }
    init {
        background=DashboardBackground()
        listOf(eyebrow,headline,description,card,footer).forEach(::addView)
        listOf(cardTitle,qr,code,instructions,status,validity,spinner,primary,secondary).forEach(card::addView)
        headline.text=SpannableString("SEUS JOGOS.\nNA SUA TELA.").apply {
            setSpan(ForegroundColorSpan(DashboardTheme.accent),11,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        landing()
    }
    private fun label(value: String, size: Int, heavy: Boolean = true) = TextView(context).apply {
        text=value; typeface=DashboardTheme.font(context,heavy); includeFontPadding=false
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX,size.toFloat()); maxLines=1
    }
    fun landing() {
        ticker?.cancel(); info=null; failed=false; connecting=false; isQr=false
        headline.visibility=VISIBLE; description.visibility=VISIBLE
        cardTitle.text="ENTRE PARA JOGAR"
        instructions.text="Conecte sua conta Microsoft para acessar seus jogos."
        status.text="Use o celular para conectar a sua conta."; status.setTextColor(DashboardTheme.muted)
        primary.text="ENTRAR COM QR CODE"; primary.isEnabled=true
        listOf(qr,code,secondary,validity,spinner).forEach { it.visibility=GONE }
        geometry=0; requestLayout(); post { primary.requestFocus() }
    }
    fun beginQr() {
        ticker?.cancel(); info=null; failed=false; connecting=false; isQr=true
        headline.visibility=GONE; description.visibility=GONE
        cardTitle.text="CONECTE SUA CONTA"
        instructions.text="Escaneie o QR Code com seu celular ou acesse a página de login da Microsoft."
        qr.setImageDrawable(null); qr.visibility=VISIBLE
        code.text="•••• ••••"; code.visibility=VISIBLE
        status.text="Gerando seu código de login…"; status.setTextColor(DashboardTheme.muted)
        primary.text="ABRIR PÁGINA DE LOGIN"; primary.isEnabled=false
        secondary.visibility=VISIBLE; validity.visibility=VISIBLE; validity.text=""
        spinner.visibility=VISIBLE
        geometry=0; requestLayout(); post { secondary.requestFocus() }
    }
    fun showQr(value: DeviceCodeInfo) {
        info=value; failed=false
        qr.setImageBitmap(value.qrBitmap); code.text=value.userCode
        instructions.text="Escaneie o QR Code ou acesse\n${value.verificationUri} e informe o código:"
        status.text="Aguardando sua aprovação na Microsoft…"; status.setTextColor(DashboardTheme.muted)
        primary.isEnabled=true; spinner.visibility=VISIBLE
        ticker?.cancel()
        ticker=scope.launch { while (isActive && isQr && !failed && !connecting) {
            val seconds=((value.expiresAtMs-System.currentTimeMillis())/1000).coerceAtLeast(0)
            validity.text=if (seconds > 0) "Código válido por ${seconds/60} min ${seconds%60}s." else "O código expirou. Gere um novo código para continuar."
            delay(1000)
        } }
    }
    fun showConnecting(message: String) {
        connecting=true; ticker?.cancel()
        spinner.visibility=VISIBLE; primary.isEnabled=false
        status.text=message; status.setTextColor(DashboardTheme.accent)
        validity.text=if (isQr) "Conta aprovada. Finalizando sua conexão…" else ""
    }
    fun showError(message: String) {
        connecting=false; failed=true; ticker?.cancel(); spinner.visibility=GONE
        status.text=message; status.setTextColor(0xffffa59a.toInt())
        primary.text=if (isQr) "GERAR NOVO CÓDIGO" else "TENTAR NOVAMENTE"
        primary.isEnabled=true; validity.text=""
        post { primary.requestFocus() }
    }
    fun handleKey(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) findFocus()?.performClick()
            true
        }
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) back()
            true
        }
        else -> false
    }
    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        view.layoutParams=LayoutParams(w.roundToInt(),h.roundToInt()).apply { leftMargin=x.roundToInt(); topMargin=y.roundToInt() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec); val h=MeasureSpec.getSize(heightMeasureSpec)
        val key=w.toLong() shl 32 or h.toLong()
        if (key != geometry && w > 0 && h > 0) {
            geometry=key
            val s=minOf(w/1280f,h/720f); val ox=(w-1280*s)/2; val oy=(h-720*s)/2
            fun root(view: View,x: Int,y: Int,vw: Int,vh: Int) = place(view,ox+x*s,oy+y*s,vw*s,vh*s)
            fun item(view: View,x: Int,y: Int,vw: Int,vh: Int) = place(view,x*s,y*s,vw*s,vh*s)
            root(eyebrow,80,60,1120,32); root(footer,80,660,1120,30)
            if (isQr) {
                root(card,80,130,1120,480)
                item(cardTitle,40,28,1040,48); item(qr,48,111,264,264)
                qr.setPadding((12*s).roundToInt(),(12*s).roundToInt(),(12*s).roundToInt(),(12*s).roundToInt())
                item(instructions,365,108,700,78); item(code,365,198,300,65)
                item(status,365,280,696,62); item(validity,365,346,696,30)
                item(primary,365,392,450,60); item(secondary,835,392,237,60)
                item(spinner,325,284,25,25)
            } else {
                root(headline,80,193,570,168); root(description,80,398,520,110)
                root(card,684,176,516,402)
                item(cardTitle,34,35,448,48); item(instructions,34,108,448,90)
                item(primary,29,229,458,66); item(status,34,321,448,65)
                item(spinner,460,41,25,25)
            }
            listOf(eyebrow to 21, headline to 65, description to 24, cardTitle to 34, instructions to 22,
                code to 44, status to 20, validity to 18, primary to 23, secondary to 23, footer to 18).forEach { (view,size) ->
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*s)
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun close() { scope.cancel() }
}
