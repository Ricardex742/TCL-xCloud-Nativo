package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.widget.LinearLayout
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.RegionBypass
import com.xcloud.tclnative.settings.RegionBypassStore

/** Available from login as well as settings, so a rejected login cannot hide the switch. */
object RegionBypassPanel {
    fun show(context: Context, back: () -> Unit): ConsoleDialog {
        val store = RegionBypassStore(context)
        val selected = store.read()
        return ConsoleDialog(context, UiText.text("Bypass de região — experimental")).apply {
            body.addView(text(UiText.text("Tenta acessar o xCloud usando um país de referência no login. Não é VPN e não altera seu IP real. O Xbox pode recusar o pedido. A mudança vale ao reconectar a conta ou reabrir o app; seu login salvo é preservado."), 20, color = DashboardTheme.muted),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) })
            RegionBypass.entries.forEach { mode ->
                val row = addMenuItem(UiText.text("{0}  {1}", UiText.nested(if (mode == selected) "✓" else "○"), UiText.nested(mode.label))) {
                    store.save(mode); back()
                }
                if (mode == selected) focusFirst(row)
            }
            addAction(UiText.text("Voltar"), back)
            setOnCancelListener { back() }
            show()
        }
    }
}
