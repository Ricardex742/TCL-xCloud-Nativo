package com.xcloud.tclnative.ui.dashboard

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
import java.util.Locale
import kotlin.math.roundToInt

data class ConsoleAction(val label: String, val action: () -> Unit)

/** Shared native panels for search, account menus and messages. No platform alert buttons/rows. */
class ConsoleDialog(context: Context, heading: String) : Dialog(context, R.style.ConsoleDialog) {
    private val activity = context as? Activity
    private val screenWidth = activity?.window?.decorView?.width?.takeIf { it > 0 }
        ?: context.resources.displayMetrics.widthPixels
    private val screenHeight = activity?.window?.decorView?.height?.takeIf { it > 0 }
        ?: context.resources.displayMetrics.heightPixels
    val scale = (screenWidth/1280f).coerceAtLeast(.5f)
    val panel: LinearLayout = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (screenHeight*.90f).roundToInt()
            val requested = MeasureSpec.getSize(heightMeasureSpec)
            val bound = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit else minOf(limit, requested)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(bound, MeasureSpec.AT_MOST))
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        background = DashboardTheme.panel(16*scale)
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
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        activity?.let(::setOwnerActivity)
        panel.addView(text("xCLOUD", 16, true, DashboardTheme.accent), LinearLayout.LayoutParams(-1, -2))
        panel.addView(text(heading, 35, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin=px(5); bottomMargin=px(23) })
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin=px(22) })
        setContentView(panel)
        setOnKeyListener { _, key, event ->
            when (key) {
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
    fun text(value: String, size: Int = 22, heavy: Boolean = false, color: Int = Color.WHITE) = TextView(context).apply {
        text=value; typeface=DashboardTheme.font(context, heavy); includeFontPadding=false
        setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX, size*scale)
    }
    fun focusFirst(view: View) { initialFocus = view }
    fun addAction(label: String, action: () -> Unit): TextView {
        val button = DashboardTheme.button(context, label.uppercase(Locale.getDefault()), scale) { dismiss(); action() }
        actions.addView(button, LinearLayout.LayoutParams(0, px(58), 1f).apply {
            if (actions.childCount > 1) marginStart = px(10)
        })
        if (initialFocus == null) initialFocus = button
        return button
    }
    fun addMenuItem(label: String, action: () -> Unit): TextView {
        val item = DashboardTheme.button(context, label, scale) { dismiss(); action() }.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(px(20), px(10), px(20), px(10))
        }
        body.addView(item, LinearLayout.LayoutParams(-1, px(62)).apply { bottomMargin = px(7) })
        if (initialFocus == null) initialFocus = item
        return item
    }
    override fun show() {
        super.show()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0,0,0,0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.72f)
            setGravity(Gravity.CENTER)
            setLayout((screenWidth*.82f).roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        initialFocus?.requestFocus()
    }
}

object ConsoleDialogs {
    fun search(context: Context, query: String, onSearch: (String) -> Unit): ConsoleDialog {
        val dialog = ConsoleDialog(context, "Pesquisar jogos")
        val field = EditText(dialog.context).apply {
            setText(query); hint="Nome do jogo"; isSingleLine=true
            typeface=DashboardTheme.font(context, false)
            setTextColor(Color.WHITE); setHintTextColor(DashboardTheme.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28*dialog.scale)
            setPadding(dialog.px(20), dialog.px(12), dialog.px(20), dialog.px(12))
            highlightColor=0x6654de69
            DashboardTheme.customFocus(this)
            fun outline(color: Int) = GradientDrawable().apply {
                setColor(0xff101a13.toInt()); cornerRadius=8*dialog.scale; setStroke(dialog.px(2),color)
            }
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), outline(DashboardTheme.accent))
                addState(intArrayOf(), outline(DashboardTheme.border))
            }
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { dialog.dismiss(); onSearch(text.toString()); true } else false
            }
            selectAll()
        }
        dialog.body.addView(dialog.text("Encontre um jogo pelo nome.", 21, color=DashboardTheme.muted), LinearLayout.LayoutParams(-1,-2)
            .apply { bottomMargin=dialog.px(17) })
        dialog.body.addView(field, LinearLayout.LayoutParams(-1,dialog.px(65)))
        dialog.addAction("Limpar busca") { onSearch("") }
        dialog.addAction("Voltar") {}
        dialog.addAction("Buscar") { onSearch(field.text.toString()) }
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
    fun menu(context: Context, title: String, items: List<ConsoleAction>, onBack: () -> Unit = {}): ConsoleDialog = ConsoleDialog(context,title).apply {
        items.forEach { addMenuItem(it.label, it.action) }
        addAction("Voltar",onBack)
        setOnCancelListener { onBack() }
        show()
    }
    fun message(context: Context, title: String, message: String, actions: List<ConsoleAction> = listOf(ConsoleAction("Fechar") {}),
                onCancel: (() -> Unit)? = null): ConsoleDialog = ConsoleDialog(context,title).apply {
        body.addView(text(message,22), LinearLayout.LayoutParams(-1,-2))
        actions.forEach { addAction(it.label,it.action) }
        if (onCancel != null) setOnCancelListener { onCancel() }
        show()
    }
}
