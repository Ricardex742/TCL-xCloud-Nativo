package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.*

internal class SeriesPersonalizationPanel(private val context: Context,
    private val backgrounds: () -> List<SeriesWallpaper>,private val back: () -> Unit) {
    private val store=SeriesPersonalizationStore(context)
    fun show(): ConsoleDialog {
        val value=store.read()
        return ConsoleDialogs.menu(context,UiText.text("Personalizar Series X|S"),listOf(
            ConsoleAction(UiText.text("Galeria de fundos")) { gallery() },
            ConsoleAction(UiText.text("Fundo: {0}",UiText.nested(if(!value.gallery) UiText.text("Arte do jogo selecionado")
                else if(store.shuffle()!=WallpaperShuffle.OFF) store.category().label else store.selected().label))) { chooseSource() },
            ConsoleAction(UiText.text("Troca automática: {0}",UiText.nested(if(value.gallery) store.shuffle().label else WallpaperShuffle.OFF.label))) { chooseShuffle() },
            ConsoleAction(UiText.text("Guia: {0}",UiText.nested(value.guide.label))) { chooseGuide() },
            ConsoleAction(UiText.text("Biblioteca completa: {0}",UiText.nested(if(value.libraryGrid) UiText.text("Grade") else UiText.text("Faixa horizontal — padrão")))) { chooseGrid() },
            ConsoleAction(UiText.text("Selos nas capas")) { badges() }
        ),back)
    }
    fun gallery(): ConsoleDialog = SeriesWallpaperGallery(context,backgrounds(),::show).show()
    private fun choices(title: String,entries: List<Pair<String,Boolean>>,choose: (Int) -> Unit)=ConsoleDialog(context,title).apply {
        entries.forEachIndexed { i,(label,selected) ->
            val row=addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(selected) "✓" else "○"),UiText.nested(label))) { choose(i) }
            if(selected) focusFirst(row)
        }
        addAction(UiText.text("Voltar")) { show() };setOnCancelListener { show() };show()
    }
    private fun chooseSource(): ConsoleDialog=choices(UiText.text("Fundo"),listOf(
        UiText.text("Arte do jogo selecionado") to !store.read().gallery,UiText.text("Galeria de fundos") to store.read().gallery)) {
        if(it==0) { store.followGame();show() } else gallery()
    }
    private fun chooseGuide(): ConsoleDialog=choices(UiText.text("Posição do Guia"),SeriesGuideSide.entries.map { it.label to (store.read().guide==it) }) {
        store.save(store.read().copy(guide=SeriesGuideSide.entries[it]));show()
    }
    private fun chooseGrid(): ConsoleDialog=choices(UiText.text("Biblioteca completa"),listOf(
        UiText.text("Faixa horizontal — padrão") to !store.read().libraryGrid,UiText.text("Grade") to store.read().libraryGrid)) {
        store.save(store.read().copy(libraryGrid=it==1));show()
    }
    private fun badges(): ConsoleDialog {
        val value=store.read()
        return ConsoleDialogs.menu(context,UiText.text("Selos nas capas"),listOf(
            ConsoleAction(UiText.text("Game Pass: {0}",UiText.nested(if(value.gamePassBadge) UiText.text("Ativado") else UiText.text("Desativado")))) {
                store.save(store.read().copy(gamePassBadge=!value.gamePassBadge));badges()
            },
            ConsoleAction(UiText.text("Series X|S: {0}",UiText.nested(if(value.seriesBadge) UiText.text("Ativado") else UiText.text("Desativado")))) {
                store.save(store.read().copy(seriesBadge=!value.seriesBadge));badges()
            }
        ),::show)
    }
    private fun chooseShuffle(): ConsoleDialog=choices(UiText.text("Troca automática de fundos"),WallpaperShuffle.entries.map {
        it.label to ((if(store.read().gallery) store.shuffle() else WallpaperShuffle.OFF)==it)
    }) { i ->
        val mode=WallpaperShuffle.entries[i]
        if(mode==WallpaperShuffle.OFF) { store.configureShuffle(mode,store.category(),emptyList());show() }
        else chooseCollection(mode)
    }
    private fun chooseCollection(mode: WallpaperShuffle): ConsoleDialog {
        val all=(SeriesWallpaper.builtIns+backgrounds()+store.favorites()).distinctBy { it.id }
        return ConsoleDialog(context,UiText.text("Coleção para troca automática")).apply {
            body.addView(text(UiText.text("Escolha uma coleção com pelo menos dois fundos. A troca pausa durante a partida e é atualizada ao voltar ao menu."),19,color=DashboardTheme.muted))
            WallpaperCategory.entries.forEach { category ->
                val pool=(when(category) {
                    WallpaperCategory.ALL -> all
                    WallpaperCategory.FAVORITES -> store.favorites()
                    else -> all.filter { it.category==category }
                }).take(SeriesPersonalizationStore.MAX_ITEMS)
                val row=addMenuItem(UiText.text("{0} · {1}",UiText.nested(category.label),UiText.nested(pool.size))) {
                    store.configureShuffle(mode,category,pool);show()
                }
                row.isEnabled=pool.size>=2;row.isFocusable=pool.size>=2;row.alpha=if(pool.size>=2) 1f else .45f
            }
            addAction(UiText.text("Voltar")) { chooseShuffle() };setOnCancelListener { chooseShuffle() };show()
        }
    }
}
