package com.xcloud.tclnative.ui.dashboard

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.xcloud.tclnative.i18n.UiText

/** App-local keyboard for USB navigation, which cannot send keys to another process's IME. */
internal object ControllerSearchKeyboard {
    fun attach(dialog: ConsoleDialog,field: EditText): View {
        var first: View?=null
        fun replace(value: String) {
            val start=minOf(field.selectionStart,field.selectionEnd).coerceAtLeast(0)
            val end=maxOf(field.selectionStart,field.selectionEnd).coerceAtLeast(start)
            field.text.replace(start,end,value);field.setSelection(start+value.length)
        }
        val keys="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ÁÀÂÃÉÊÍÓÔÕÚÇ"
        keys.chunked(12).forEach { group ->
            val row=LinearLayout(field.context)
            group.forEach { key ->
                val button=dialog.keyButton(key.toString()) { replace(key.toString()) }.apply {
                    id=View.generateViewId();setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(21).toFloat())
                    setPadding(0,0,0,0)
                }
                if(first==null) first=button
                row.addView(button,LinearLayout.LayoutParams(0,dialog.px(40),1f).apply { marginEnd=dialog.px(4) })
            }
            dialog.body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(4) })
        }
        val edit=LinearLayout(field.context)
        listOf(UiText.text("Espaço") to { replace(" ") },UiText.text("Apagar") to {
            if(field.selectionStart==field.selectionEnd && field.selectionStart>0)
                field.setSelection(Character.offsetByCodePoints(field.text,field.selectionStart,-1),field.selectionEnd)
            replace("")
        }).forEach { (label,action) -> edit.addView(dialog.keyButton(label,action),
            LinearLayout.LayoutParams(0,dialog.px(40),1f).apply { marginEnd=dialog.px(4) }) }
        dialog.body.addView(edit,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(4) })
        return first!!
    }
}
