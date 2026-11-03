package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.touch.*

/** Edits a draft only. Nothing here starts input or writes per-game preferences. */
object HudActionDialogs {
    fun action(context: Context,current: HudAction?,title: String="Combinar botões",allowClear: Boolean=false,
               select: (HudAction?) -> Unit) {
        choose(context,title,current?.keys ?: emptySet(),allowClear,select)
    }
    private fun choose(context: Context,title: String,keys: Set<HudBinding>,allowClear: Boolean,select: (HudAction?) -> Unit) {
        val dialog=ConsoleDialog(context,title)
        dialog.body.addView(dialog.text("Marque os botões que devem ser pressionados juntos. Aplicar confirma a combinação no editor.",19,color=DashboardTheme.muted))
        HudBinding.entries.forEach { key ->
            val row=dialog.addMenuItem("${if(key in keys) "✓" else "○"} ${key.label}") {
                choose(context,title,if(key in keys) keys-key else keys+key,allowClear,select)
            }
            if(key in keys) dialog.focusFirst(row)
        }
        if(allowClear) dialog.addMenuItem("Remover este gesto") { select(null) }
        dialog.addAction("Cancelar") {}
        if(keys.isNotEmpty()) dialog.addAction("Aplicar ${HudAction(keys).label}") { select(HudAction(keys)) }
        dialog.show()
    }
    fun gestures(context: Context,current: Map<HudSwipe,HudGesture>,changed: (Map<HudSwipe,HudGesture>) -> Unit) {
        ConsoleDialogs.menu(context,"Gestos do botão",HudSwipe.entries.map { direction ->
            val saved=current[direction]
            ConsoleAction("${direction.label}: ${saved?.action?.label ?: "Sem gesto"}") {
                action(context,saved?.action,"Gesto • ${direction.label}",true) { action ->
                    val next=if(action==null) current-direction else current+(direction to HudGesture(action.label,action))
                    changed(next); gestures(context,next,changed)
                }
            }
        })
    }
}
