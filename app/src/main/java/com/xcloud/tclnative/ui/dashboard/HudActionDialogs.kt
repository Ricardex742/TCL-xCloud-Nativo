package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import com.xcloud.tclnative.touch.*

/** Edits a draft only. Nothing here starts input or writes per-game preferences. */
object HudActionDialogs {
    fun action(context: Context,current: HudAction?,title: String=UiText.text("Combinar botões"),allowClear: Boolean=false,
               select: (HudAction?) -> Unit) {
        choose(context,title,current?.keys ?: emptySet(),allowClear,select)
    }
    private fun choose(context: Context,title: String,keys: Set<HudBinding>,allowClear: Boolean,select: (HudAction?) -> Unit) {
        val dialog=ConsoleDialog(context,title)
        dialog.body.addView(dialog.text(UiText.text("Marque os botões que devem ser pressionados juntos. Aplicar confirma a combinação no editor."),19,color=DashboardTheme.muted))
        HudBinding.entries.forEach { key ->
            val row=dialog.addMenuItem(UiText.text("{0} {1}",UiText.nested(if(key in keys) "✓" else "○"),UiText.nested(key.label))) {
                choose(context,title,if(key in keys) keys-key else keys+key,allowClear,select)
            }
            if(key in keys) dialog.focusFirst(row)
        }
        if(allowClear) dialog.addMenuItem(UiText.text("Remover este gesto")) { select(null) }
        dialog.addAction(UiText.text("Cancelar")) {}
        if(keys.isNotEmpty()) dialog.addAction(UiText.text("Aplicar {0}",UiText.nested(HudAction(keys).label))) { select(HudAction(keys)) }
        dialog.show()
    }
    fun gestures(context: Context,current: Map<HudSwipe,HudGesture>,changed: (Map<HudSwipe,HudGesture>) -> Unit) {
        ConsoleDialogs.menu(context,UiText.text("Gestos do botão"),HudSwipe.entries.map { direction ->
            val saved=current[direction]
            ConsoleAction(UiText.text("{0}: {1}",UiText.nested(direction.label),UiText.nested(saved?.action?.label ?: UiText.text("Sem gesto")))) {
                action(context,saved?.action,UiText.text("Gesto • {0}",UiText.nested(direction.label)),true) { action ->
                    val next=if(action==null) current-direction else current+(direction to HudGesture(action.label,action))
                    changed(next); gestures(context,next,changed)
                }
            }
        })
    }
}
