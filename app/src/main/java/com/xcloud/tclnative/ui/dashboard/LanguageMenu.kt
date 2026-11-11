package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.content.Context
import android.view.View
import com.xcloud.tclnative.i18n.*

object LanguageMenu {
    fun show(context: Context,back: () -> Unit): ConsoleDialog {
        val store=AppLanguageStore(context)
        val selected=store.read()
        val host=context
        return ConsoleDialog(context,UiText.text("Idioma do aplicativo")).apply {
            body.addView(text(UiText.text("Escolha o idioma dos menus. A mudança é imediata e fica salva neste aparelho. O idioma dos jogos é definido separadamente nas opções da sessão."),20,color=DashboardTheme.muted))
            AppLanguage.entries.forEach { language ->
                val row=addMenuItem("${if(language==selected) "✓" else "○"}  ${language.nativeName}") {
                    store.save(language)
                    UiText.apply(host,language)
                    (host as? Activity)?.findViewById<View>(android.R.id.content)?.let(UiText::refresh)
                    back()
                }
                row.isSelected=language==selected
                row.contentDescription=language.nativeName
                if(language==selected) focusFirst(row)
            }
            addAction(UiText.text("Voltar"),back)
            setOnCancelListener { back() };show()
        }
    }
}
