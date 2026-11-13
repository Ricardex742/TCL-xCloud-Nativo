package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import com.xcloud.tclnative.R
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.SeriesGuideSide
import com.xcloud.tclnative.settings.SeriesPersonalizationStore
import java.util.Locale
import kotlin.math.roundToInt

data class ConsoleAction(val label: String, val action: () -> Unit)
enum class ConsolePanelPurpose { GENERAL, SEARCH, SETTINGS_HOME, GUIDE }

/** Shared native panels for search, account menus and messages. No platform alert buttons/rows. */
class ConsoleDialog(context: Context, heading: String,private val purpose: ConsolePanelPurpose=ConsolePanelPurpose.GENERAL) : Dialog(context, R.style.ConsoleDialog) {
    init { DashboardTheme.applyOverlay(this.context) }
    private val activity = context as? Activity
    private val skin=ConsolePanelSkin()
    private val screenWidth = activity?.window?.decorView?.width?.takeIf { it > 0 }
        ?: context.resources.displayMetrics.widthPixels
    private val screenHeight = activity?.window?.decorView?.height?.takeIf { it > 0 }
        ?: context.resources.displayMetrics.heightPixels
    val scale = (if(skin.enabled) minOf(screenWidth/1280f,screenHeight/720f) else screenWidth/1280f).coerceAtLeast(.5f)
    val panel: LinearLayout = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (screenHeight*.90f).roundToInt()
            val requested = MeasureSpec.getSize(heightMeasureSpec)
            val bound = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit else minOf(limit, requested)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(bound, MeasureSpec.AT_MOST))
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        background = if(skin.enabled) skin.background() else DashboardTheme.panel(16*scale)
        setPadding(px(32), px(26), px(32), px(26))
    }
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        DashboardTheme.customFocus(this)
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
    private var initialFocus: View? = null
    private var menuItems=0
    private var tileRow: LinearLayout?=null
    var shortcut: ((KeyEvent) -> Boolean)?=null
    private val cleanups=mutableListOf<() -> Unit>()
    fun addCleanup(action: () -> Unit) { cleanups+=action }
    override fun onStop() {
        val pending=cleanups.toList();cleanups.clear();pending.forEach { it() }
        super.onStop()
    }
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        activity?.let(::setOwnerActivity)
        if(purpose==ConsolePanelPurpose.GUIDE) {
            val brand=ConsoleBrandView(context)
            panel.addView(brand,LinearLayout.LayoutParams(px(176),px(42)))
            panel.addView(text(heading,27,true),LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(16);bottomMargin=px(16) })
            panel.minimumHeight=(screenHeight*.88f).roundToInt()
        } else if(skin.enabled) {
            val header=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
            val brand=ConsoleBrandView(context)
            header.addView(brand,LinearLayout.LayoutParams(px(if(skin.scene==ConsoleScene.XBOX_ORIGINAL) 204 else 176),px(62)))
            val title=text(heading,35,true).apply { maxLines=2;gravity=Gravity.CENTER_VERTICAL }
            header.addView(title,LinearLayout.LayoutParams(0,px(82),1f).apply { marginStart=px(22);marginEnd=px(14) })
            val icon=object: View(context) {
                private val icons=ConsoleIcons()
                override fun onDraw(canvas: android.graphics.Canvas) {
                    icons.draw(canvas,if(purpose==ConsolePanelPurpose.SEARCH) ConsoleGlyph.SEARCH else ConsoleGlyph.SETTINGS,0f,0f,minOf(width,height).toFloat(),skin.ink)
                }
            }
            header.addView(icon,LinearLayout.LayoutParams(px(32),px(32)))
            panel.addView(header,LinearLayout.LayoutParams(-1,px(94)).apply { bottomMargin=px(14) })
        } else {
            panel.addView(text("xCLOUD", 16, true, DashboardTheme.accent), LinearLayout.LayoutParams(-1, -2))
            panel.addView(text(heading, 35, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin=px(5); bottomMargin=px(23) })
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin=px(22) })
        setContentView(panel)
        setOnKeyListener { _, key, event ->
            if(shortcut?.invoke(event)==true) true else when (key) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) cancel()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) window?.currentFocus?.performClick()
                    true
                }
                else -> false
            }
        }
    }
    fun px(value: Int) = (value*scale).roundToInt()
    fun text(value: String, size: Int = 22, heavy: Boolean = false, color: Int = Color.WHITE) = com.xcloud.tclnative.i18n.LocalizedTextView(context).apply {
        text=value; typeface=if(skin.enabled) skin.font(heavy) else DashboardTheme.font(context, heavy); includeFontPadding=false
        setTextColor(if(skin.enabled) skin.textColor(color) else color); setTextSize(TypedValue.COMPLEX_UNIT_PX, size*scale)
    }
    fun focusFirst(view: View) { initialFocus = view }
    fun addAction(label: String, action: () -> Unit): TextView = actionButton(label,true,action)
    fun addPersistentAction(label: String, action: () -> Unit): TextView = actionButton(label,false,action)
    private fun actionButton(label: String,dismissOnClick: Boolean,action: () -> Unit): TextView {
        val button = DashboardTheme.button(context, UiText.uppercase(label), scale) { if(dismissOnClick) dismiss(); action() }
        if(skin.enabled) button.apply { background=skin.row();setTextColor(skin.textColors());typeface=skin.font(true) }
        actions.addView(button, LinearLayout.LayoutParams(0, px(58), 1f).apply {
            if (actions.childCount > 1) marginStart = px(10)
        })
        if (initialFocus == null) initialFocus = button
        return button
    }
    fun addMenuItem(label: String, action: () -> Unit): TextView {
        val tile=skin.enabled && purpose==ConsolePanelPurpose.SETTINGS_HOME && skin.columns>1
        val item = DashboardTheme.button(context, label, scale) { dismiss(); action() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(px(20), px(10), px(20), px(10))
            if(android.os.Build.VERSION.SDK_INT>=26) setAutoSizeTextTypeUniformWithConfiguration(
                px(13).coerceAtLeast(1),px(23).coerceAtLeast(2),1,TypedValue.COMPLEX_UNIT_PX)
            if(skin.enabled) {
                background=skin.row(tile,menuItems);typeface=skin.font();setTextColor(skin.textColors(tile))
                if(skin.scene==ConsoleScene.XBOX_ORIGINAL) { setPadding(px(38),px(10),px(20),px(10));letterSpacing=.04f }
                if(tile) { gravity=Gravity.BOTTOM or Gravity.START;setPadding(px(20),px(46),px(15),px(13)) }
            }
        }
        if(tile) {
            if(menuItems%skin.columns==0) {
                tileRow=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
                body.addView(tileRow,LinearLayout.LayoutParams(-1,px(88)).apply { bottomMargin=px(7) })
            }
            tileRow!!.addView(item,LinearLayout.LayoutParams(0,-1,1f).apply { if(menuItems%skin.columns!=0) marginStart=px(10) })
        } else body.addView(item, LinearLayout.LayoutParams(-1, px(62)).apply { bottomMargin = px(7) })
        menuItems++
        if (initialFocus == null) initialFocus = item
        return item
    }
    override fun show() {
        super.show()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0,0,0,0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if(skin.enabled) .48f else .72f)
            if(purpose==ConsolePanelPurpose.GUIDE) {
                val side=SeriesPersonalizationStore(context).read().guide
                setGravity(Gravity.CENTER_VERTICAL or if(side==SeriesGuideSide.RIGHT) Gravity.RIGHT else Gravity.LEFT)
                attributes=attributes.apply { x=px(12) }
                setLayout(minOf(px(410),(screenWidth*.90f).roundToInt()),(screenHeight*.90f).roundToInt())
            } else {
                setGravity(Gravity.CENTER)
                setLayout((screenWidth*(if(skin.enabled) .90f else .82f)).roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        initialFocus?.requestFocus()
    }
}

object ConsoleDialogs {
    fun search(context: Context, query: String, onSearch: (String) -> Unit): ConsoleDialog {
        val dialog = ConsoleDialog(context, UiText.text("Pesquisar jogos"),ConsolePanelPurpose.SEARCH)
        val skin=ConsolePanelSkin()
        val field = EditText(dialog.context).apply {
            setText(query); hint=UiText.text("Nome do jogo"); isSingleLine=true
            typeface=if(skin.enabled) skin.font() else DashboardTheme.font(context, false)
            setTextColor(if(skin.enabled) skin.ink else Color.WHITE); setHintTextColor(if(skin.enabled) skin.muted else DashboardTheme.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28*dialog.scale)
            setPadding(dialog.px(20), dialog.px(12), dialog.px(20), dialog.px(12))
            highlightColor=DashboardTheme.color(UiColor.EDIT_SELECTION)
            DashboardTheme.customFocus(this)
            fun outline(color: UiColor)=DashboardTheme.shape(UiColor.EDIT_FILL,8*dialog.scale,dialog.px(2),color)
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), outline(UiColor.ACCENT))
                addState(intArrayOf(), outline(UiColor.BORDER))
            }
            if(skin.enabled) background=skin.input(dialog.scale)
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { dialog.dismiss(); onSearch(text.toString()); true } else false
            }
            selectAll()
        }
        dialog.body.addView(dialog.text(UiText.text("Encontre um jogo pelo nome."), 21, color=DashboardTheme.muted), LinearLayout.LayoutParams(-1,-2)
            .apply { bottomMargin=dialog.px(17) })
        dialog.body.addView(field, LinearLayout.LayoutParams(-1,dialog.px(65)))
        dialog.addAction(UiText.text("Limpar busca")) { onSearch("") }
        dialog.addAction(UiText.text("Voltar")) {}
        dialog.addAction(UiText.text("Buscar")) { onSearch(field.text.toString()) }
        dialog.focusFirst(field)
        dialog.show()
        if(context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            field.post {
                if(dialog.isShowing) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return dialog
    }
    fun menu(context: Context, title: String, items: List<ConsoleAction>, onBack: () -> Unit = {}): ConsoleDialog = ConsoleDialog(context,title,
        if(title==UiText.text("Configurações")) ConsolePanelPurpose.SETTINGS_HOME else ConsolePanelPurpose.GENERAL).apply {
        items.forEach { addMenuItem(it.label, it.action) }
        addAction(UiText.text("Voltar"),onBack)
        setOnCancelListener { onBack() }
        show()
    }
    fun message(context: Context, title: String, message: String, actions: List<ConsoleAction> = listOf(ConsoleAction(UiText.text("Fechar")) {}),
                onCancel: (() -> Unit)? = null): ConsoleDialog = ConsoleDialog(context,title).apply {
        body.addView(text(message,22), LinearLayout.LayoutParams(-1,-2))
        actions.forEach { addAction(it.label,it.action) }
        if (onCancel != null) setOnCancelListener { onCancel() }
        show()
    }
}
