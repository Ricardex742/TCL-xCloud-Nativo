package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.core.view.doOnLayout
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.*

internal data class WallpaperGalleryCatalog(val items: List<SeriesWallpaper>) {
    companion object {
        fun create(official: List<SeriesWallpaper>,recreated: List<SeriesWallpaper>)=WallpaperGalleryCatalog(
            (SeriesWallpaper.builtIns+recreated+official.mapNotNull { RecreatedXboxBackgrounds.animatedArtwork(it) }+official)
                .distinctBy { it.id })
    }
}

/** A bounded page of views and visible-only thumbnail requests, including large official collections. */
internal class SeriesWallpaperGallery(private val context: Context,items: List<SeriesWallpaper>,
    officialItems: List<SeriesWallpaper>?=null,
    backend: ArtworkBackend?=null,
    recreatedItems: List<SeriesWallpaper>?=null,
    private val catalogSource: (suspend () -> WallpaperGalleryCatalog)?=null,
    private val back: () -> Unit) {
    private val store=SeriesPersonalizationStore(context)
    private val entries=items.take(SeriesPersonalizationStore.MAX_ITEMS)
    private val suppliedRecreations=recreatedItems
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    // Gallery previews and thumbnails share one decode slot, including on normal-memory TVs.
    private val posters=PosterLoader(context,scope,backend ?: ArtworkBackend.get(context),maxConcurrent=1)
    private var catalog=if(officialItems!=null) WallpaperGalleryCatalog.create(officialItems,
        recreatedItems ?: RecreatedXboxBackgrounds.wallpapers(officialItems)) else WallpaperGalleryCatalog(SeriesWallpaper.builtIns)
    internal var catalogReady=officialItems!=null;private set
    private var closed=false
    private val dialog=ConsoleDialog(context,UiText.text("Galeria de fundos"))
    private var category=WallpaperCategory.ALL
    private var page=0
    private var query=""
    private var pages=1
    private var filteredEntries=emptyList<SeriesWallpaper>()
    private var focusedId: String?=null
    private var pendingFocusId: String?=null
    private var focusGeneration=0
    private var previewed=store.selected()
    private var previewReady=false
    private val preview=MotionArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val previewArt=FixedArtworkView(context)
    private val previewNotice=dialog.text("",19).apply { gravity=Gravity.CENTER;maxLines=2 }
    private val previewLabel=dialog.text("",21,true).apply {
        gravity=Gravity.BOTTOM or Gravity.START;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(dialog.px(14),0,dialog.px(14),dialog.px(9))
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xe0101518.toInt()))
    }
    private val help=dialog.text("",16,color=DashboardTheme.muted)
    private val categories=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
    private val pager=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private val pageLabel=dialog.text("",17).apply { gravity=Gravity.CENTER }
    private lateinit var previous: TextView
    private lateinit var next: TextView
    private lateinit var search: TextView
    private val strip=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private val scroll=HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false
        addView(strip,android.view.ViewGroup.LayoutParams(-2,-1))
    }
    private val tiles=mutableListOf<Tile>()
    private val categoryButtons=mutableMapOf<WallpaperCategory,TextView>()
    private lateinit var favoriteButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var animateButton: TextView
    private data class Tile(val entry: SeriesWallpaper,val view: FrameLayout,val image: ImageView,val label: TextView)

    fun show(): ConsoleDialog {
        val previewFrame=FrameLayout(context).apply {
            setBackgroundColor(0xff10191e.toInt())
            addView(preview,FrameLayout.LayoutParams(-1,-1));addView(previewNotice,FrameLayout.LayoutParams(-1,-1))
            addView(previewLabel,FrameLayout.LayoutParams(-1,dialog.px(43),Gravity.BOTTOM))
            animateButton=DashboardTheme.button(context,UiText.text("Animar esta arte"),dialog.scale) { chooseEffect() }.apply {
                id=View.generateViewId();setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(16).toFloat())
                setPadding(dialog.px(6),0,dialog.px(6),0)
            }
            addView(animateButton,FrameLayout.LayoutParams(dialog.px(232),dialog.px(36),Gravity.TOP or Gravity.END).apply {
                topMargin=dialog.px(8);marginEnd=dialog.px(8)
            })
        }
        dialog.body.addView(previewFrame,LinearLayout.LayoutParams(-1,dialog.px(170)))
        WallpaperCategory.entries.forEach { value ->
            val button=DashboardTheme.button(context,value.label,dialog.scale) { changeCategory(value) }.apply {
                id=View.generateViewId();setPadding(dialog.px(5),0,dialog.px(5),0)
                typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(17).toFloat())
                setOnKeyListener { _,key,event ->
                    if(key==KeyEvent.KEYCODE_DPAD_DOWN && tiles.isNotEmpty()) {
                        if(event.action==KeyEvent.ACTION_DOWN) focusPage(false);true
                    } else false
                }
            }
            categories.addView(button,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dialog.px(5) })
            categoryButtons[value]=button
        }
        dialog.body.addView(categories,LinearLayout.LayoutParams(-1,dialog.px(42)).apply { topMargin=dialog.px(8);bottomMargin=dialog.px(5) })
        previous=pageButton("‹",UiText.text("Página anterior")) { movePage(-1,true) }
        next=pageButton("›",UiText.text("Próxima página")) { movePage(1,true) }
        search=pageButton(UiText.text("Pesquisar fundos"),UiText.text("Pesquisar fundos")) {
            ConsoleDialogs.searchWallpapers(context,query) { value ->
                query=value.trim().take(120);page=0;clearPendingFocus();renderPage();focusPage(false)
            }
        }
        pager.addView(previous,LinearLayout.LayoutParams(dialog.px(48),-1))
        pager.addView(pageLabel,LinearLayout.LayoutParams(dialog.px(120),-1))
        pager.addView(next,LinearLayout.LayoutParams(dialog.px(48),-1))
        pager.addView(search,LinearLayout.LayoutParams(dialog.px(250),-1).apply { marginStart=dialog.px(14) })
        dialog.body.addView(pager,LinearLayout.LayoutParams(-1,dialog.px(34)).apply { bottomMargin=dialog.px(4) })
        dialog.body.addView(scroll,LinearLayout.LayoutParams(-1,dialog.px(119)))
        dialog.body.addView(help,LinearLayout.LayoutParams(-1,-2))
        favoriteButton=dialog.addPersistentAction(UiText.text("Favoritar fundo")) { toggleFavorite() }.apply {
            maxLines=1
            if(android.os.Build.VERSION.SDK_INT>=26) setAutoSizeTextTypeUniformWithConfiguration(
                dialog.px(13).coerceAtLeast(1),dialog.px(22).coerceAtLeast(2),1,android.util.TypedValue.COMPLEX_UNIT_PX)
            else setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(16).toFloat())
        }
        applyButton=dialog.addPersistentAction(UiText.text("Usar fundo")) { choose(previewed) }.apply { id=View.generateViewId() }
        dialog.addAction(UiText.text("Voltar"),back)
        dialog.shortcut={ event ->
            if(handleHorizontal(event)) true else if(event.keyCode==KeyEvent.KEYCODE_BUTTON_A && pendingFocusId!=null) {
                // A detached tile must never apply the previous page's image.
                true
            } else if(event.keyCode==KeyEvent.KEYCODE_BUTTON_X) {
                pendingFocusId?.let { id -> filteredEntries.firstOrNull { it.id==id }?.let(::showPreview) }
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) toggleFavorite();true
            } else false
        }
        scroll.setOnScrollChangeListener { _,_,_,_,_ -> loadVisible() }
        dialog.addCleanup { closed=true;preview.close();posters.close();scope.cancel() };dialog.setOnCancelListener { back() }
        page=(all().indexOfFirst { it.id==previewed.id }.coerceAtLeast(0))/PAGE_SIZE
        renderPage();showPreview(previewed)
        dialog.focusFirst(tiles.firstOrNull { it.entry.id==previewed.id }?.view ?: categoryButtons.getValue(category))
        dialog.show();scroll.doOnLayout { loadVisible() }
        if(!catalogReady) scope.launch {
            val loaded=withContext(Dispatchers.Default) {
                catalogSource?.invoke() ?: WallpaperGalleryCatalog.create(OfficialXboxWallpapers.read(context),
                    suppliedRecreations ?: RecreatedXboxBackgrounds.wallpapers(context))
            }
            if(closed || !dialog.isShowing) return@launch
            val focused=tiles.firstOrNull { it.view.hasFocus() }?.entry?.id
            val anchor=pendingFocusId ?: focused ?: previewed.id
            catalog=loaded;catalogReady=true
            page=collection().indexOfFirst { it.id==anchor }.coerceAtLeast(0)/PAGE_SIZE
            renderPage()
            if(focused!=null || pendingFocusId!=null) focusTile(anchor)
        }
        return dialog
    }
    private fun pageButton(label: String,description: String,action: () -> Unit)=DashboardTheme.button(context,label,dialog.scale,action).apply {
        id=View.generateViewId();contentDescription=description;setPadding(dialog.px(5),0,dialog.px(5),0)
        typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(17).toFloat())
    }
    private fun all()=(catalog.items+entries+store.favorites()+store.selected()).distinctBy { it.id }
    private fun collection()=when(category) {
        WallpaperCategory.ALL -> all();WallpaperCategory.FAVORITES -> store.favorites()
        else -> all().filter { it.category==category }
    }
    private fun clearPendingFocus() { ++focusGeneration;pendingFocusId=null;focusedId=null }
    private fun changeCategory(value: WallpaperCategory) { clearPendingFocus();category=value;query="";page=0;renderPage() }
    private fun movePage(step: Int,focus: Boolean) {
        val target=page+step;if(target !in 0 until pages) return
        page=target;renderPage();if(focus) focusPage(step<0)
    }
    private fun focusPage(last: Boolean) {
        val tile=if(last) tiles.lastOrNull() else tiles.firstOrNull()
        if(tile==null) { clearPendingFocus();search.requestFocus() } else focusTile(tile.entry.id)
    }
    private fun focusTile(id: String) {
        pendingFocusId=id;focusedId=id;val request=++focusGeneration
        fun focus(attempt: Int) {
            if(!dialog.isShowing || request!=focusGeneration) return
            val target=tiles.firstOrNull { it.entry.id==id }?.view ?: return
            if(target.width>0 && target.height>0 && target.requestFocus()) {
                pendingFocusId=null
                target.requestRectangleOnScreen(android.graphics.Rect(0,0,target.width,target.height),true)
            } else if(attempt<4) {
                scroll.doOnLayout { focus(attempt+1) };scroll.requestLayout()
            }
        }
        focus(0)
    }
    private fun handleHorizontal(event: KeyEvent): Boolean {
        if(event.keyCode!=KeyEvent.KEYCODE_DPAD_LEFT && event.keyCode!=KeyEvent.KEYCODE_DPAD_RIGHT) return false
        val current=tiles.firstOrNull { it.view.hasFocus() }
        if(current==null && pendingFocusId==null) return false
        if(event.action==KeyEvent.ACTION_DOWN) {
            val at=filteredEntries.indexOfFirst { it.id==(pendingFocusId ?: current?.entry?.id ?: focusedId) }
            val target=at+if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            if(at>=0 && target in filteredEntries.indices) {
                val id=filteredEntries[target].id
                pendingFocusId=id;focusedId=id
                if(page!=target/PAGE_SIZE) { page=target/PAGE_SIZE;renderPage() }
                focusTile(id)
            }
        }
        return true
    }
    private fun renderPage() {
        tiles.forEach { posters.cancel(it.image) };tiles.clear();strip.removeAllViews()
        val source=collection();val filtered=if(query.isBlank()) source else source.filter { it.label.contains(query,true) }
        filteredEntries=filtered
        pages=((filtered.size+PAGE_SIZE-1)/PAGE_SIZE).coerceAtLeast(1);page=page.coerceIn(0,pages-1)
        val items=filtered.drop(page*PAGE_SIZE).take(PAGE_SIZE)
        pager.visibility=if(source.size>PAGE_SIZE || query.isNotBlank()) View.VISIBLE else View.GONE
        previous.isEnabled=page>0;next.isEnabled=page<pages-1
        previous.alpha=if(previous.isEnabled) 1f else .4f;next.alpha=if(next.isEnabled) 1f else .4f
        pageLabel.text=if(catalogReady) "${page+1} / $pages" else UiText.text("Carregando catálogo…")
        search.text=UiText.text("Pesquisar fundos")
        categoryButtons.forEach { (key,button) -> button.isSelected=key==category;button.background=tilePlate(key==category) }
        if(items.isEmpty()) strip.addView(dialog.text(UiText.text("Nenhum fundo nesta categoria."),20),LinearLayout.LayoutParams(-2,-2))
        items.forEach { entry ->
            val image=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(0xff10191e.toInt()) }
            if(entry.artUrl==null || entry.recreation!=null) image.setImageDrawable(MotionWallpaperArt.create(entry).apply { lowDetail=true })
            val label=dialog.text(entry.label,16).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL;setPadding(dialog.px(5),0,dialog.px(5),0) }
            val tile=FrameLayout(context).apply {
                id=View.generateViewId();isFocusable=true;isFocusableInTouchMode=true;isClickable=true
                DashboardTheme.customFocus(this);setPadding(dialog.px(3),dialog.px(3),dialog.px(3),dialog.px(3))
                addView(image,FrameLayout.LayoutParams(-1,dialog.px(78)))
                if(entry.recreation!=null) addView(dialog.text(UiText.text("Recriação"),13,true).apply {
                    setPadding(dialog.px(4),dialog.px(2),dialog.px(4),dialog.px(2));setBackgroundColor(0xc0111715.toInt())
                },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
                addView(label,FrameLayout.LayoutParams(-1,dialog.px(28),Gravity.BOTTOM))
                setOnFocusChangeListener { _,focused ->
                    background=tilePlate(focused)
                    if(focused && (pendingFocusId==null || pendingFocusId==entry.id)) {
                        pendingFocusId=null;focusedId=entry.id;showPreview(entry)
                    }
                }
                setOnClickListener {
                    clearPendingFocus();focusedId=entry.id
                    if(previewed.id==entry.id && previewReady) choose(entry) else showPreview(entry)
                }
            }
            tile.background=tilePlate(false);tiles+=Tile(entry,tile,image,label)
            strip.addView(tile,LinearLayout.LayoutParams(dialog.px(151),dialog.px(111)).apply { marginEnd=dialog.px(10) })
        }
        tiles.forEachIndexed { i,tile ->
            tile.view.nextFocusLeftId=tiles[(i-1).coerceAtLeast(0)].view.id
            tile.view.nextFocusRightId=tiles[(i+1).coerceAtMost(tiles.lastIndex)].view.id
            tile.view.nextFocusUpId=categoryButtons.getValue(category).id;tile.view.nextFocusDownId=applyButton.id
            tile.view.setOnKeyListener { _,_,event -> handleHorizontal(event) }
        }
        updateFavorites();scroll.scrollTo(0,0);scroll.doOnLayout { loadVisible() }
    }
    private fun tilePlate(focused: Boolean)=GradientDrawable().apply {
        setColor(0xff171e22.toInt());cornerRadius=dialog.px(4).toFloat()
        if(focused) setStroke(dialog.px(2).coerceAtLeast(1),DashboardTheme.accent)
    }
    private fun choose(entry: SeriesWallpaper) {
        if(!previewReady || previewed.id!=entry.id) return
        store.select(entry);dialog.dismiss();back()
    }
    private fun showPreview(entry: SeriesWallpaper) {
        posters.cancelPending()
        posters.cancel(previewArt);preview.setBackdrop(null)
        previewed=entry;previewReady=entry.artUrl==null
        preview.setPresentationEnabled(previewReady)
        val original=if(entry.official) entry else RecreatedXboxBackgrounds.originalArtwork(entry)
        animateButton.visibility=if(original!=null) View.VISIBLE else View.GONE
        animateButton.text=if(entry.official) UiText.text("Animar esta arte") else UiText.text("Trocar efeito")
        animateButton.nextFocusDownId=categoryButtons.getValue(category).id
        preview.setBackgroundColor(0xff10191e.toInt());previewNotice.visibility=if(previewReady) View.GONE else View.VISIBLE
        previewNotice.text=UiText.text("Carregando imagem…")
        applyButton.isEnabled=previewReady;applyButton.alpha=if(previewReady) 1f else .45f
        if(entry.recreation!=null) {
            posters.cancel(preview);preview.showArt(MotionWallpaperArt.create(entry))
            entry.artUrl?.let { url -> posters.load(previewArt,url,768,432,notifyPlaceholder=false) { ready ->
                if(previewed.id==entry.id) {
                    previewReady=ready;preview.setPresentationEnabled(ready);previewNotice.visibility=if(ready) View.GONE else View.VISIBLE
                    if(ready) preview.setBackdrop(previewArt.drawable) else previewNotice.text=UiText.text("Imagem indisponível. Tente novamente.")
                    applyButton.isEnabled=ready;applyButton.alpha=if(ready) 1f else .45f
                }
            } }
        } else if(entry.artUrl!=null) posters.load(preview,entry.artUrl,768,432,notifyPlaceholder=false) { ready ->
            if(previewed.id==entry.id) {
                previewReady=ready;previewNotice.visibility=if(ready) View.GONE else View.VISIBLE
                if(!ready) previewNotice.text=UiText.text("Imagem indisponível. Tente novamente.")
                applyButton.isEnabled=ready;applyButton.alpha=if(ready) 1f else .45f
            }
        } else { posters.cancel(preview);preview.showArt(MotionWallpaperArt.create(entry)) }
        previewLabel.text=entry.label
        help.text=if(RecreatedXboxBackgrounds.originalArtwork(entry)!=null) UiText.text("Arte original com animação própria do app. Não é a animação original do console.")
            else if(entry.recreation!=null) UiText.text("Recriação própria: elementos animados pelo app; não é o arquivo original do console. A: escolher · X: favoritar · B: voltar")
            else if(entry.official) UiText.text("Oficiais Xbox: imagens estáticas. A: escolher · X: favoritar · B: voltar")
            else UiText.text("A: escolher fundo · X: favoritar · B: voltar")
        updateFavoriteButton()
        loadVisible() // Requeue visible thumbnails behind the selected preview, keeping ready bitmaps.
    }
    private fun chooseEffect() {
        val original=if(previewed.official) previewed else RecreatedXboxBackgrounds.originalArtwork(previewed) ?: return
        val effectDialog=ConsoleDialog(context,UiText.text("Efeito da recriação"))
        ArtAnimationEffect.entries.forEach { effect ->
            effectDialog.addMenuItem(effect.label) {
                RecreatedXboxBackgrounds.animatedArtwork(original,effect)?.let(::showPreview)
                animateButton.requestFocus()
            }
        }
        effectDialog.addAction(UiText.text("Voltar")) { animateButton.requestFocus() }
        effectDialog.setOnCancelListener { animateButton.requestFocus() }
        dialog.addCleanup { effectDialog.dismiss() }
        effectDialog.show()
    }
    private fun toggleFavorite() {
        store.toggleFavorite(previewed)
        if(category==WallpaperCategory.FAVORITES) { renderPage();focusPage(false) }
        updateFavorites()
    }
    private fun updateFavoriteButton() {
        if(::favoriteButton.isInitialized) favoriteButton.text=UiText.uppercase(if(store.isFavorite(previewed.id)) UiText.text("Remover dos fundos favoritos") else UiText.text("Favoritar fundo"))
    }
    private fun updateFavorites() {
        updateFavoriteButton();val favorites=store.favorites().map { it.id }.toSet()
        tiles.forEach { tile ->
            val favorite=tile.entry.id in favorites
            tile.label.text=UiText.text("{0}{1}",UiText.nested(if(favorite) "★ " else ""),UiText.nested(if(tile.entry.recreation!=null) tile.entry.name else tile.entry.label))
            tile.view.contentDescription=UiText.text("{0}{1}",UiText.nested(tile.entry.label),UiText.nested(if(favorite) UiText.text(", favorito") else ""))
        }
    }
    private fun loadVisible() {
        if(scroll.width<=0 || !dialog.isShowing) return
        tiles.forEach { tile -> if(tile.entry.artUrl!=null) {
            if(tile.view.right>=scroll.scrollX-dialog.px(151) && tile.view.left<=scroll.scrollX+scroll.width+dialog.px(151))
                posters.load(tile.image,tile.entry.previewUrl ?: tile.entry.artUrl,256,144)
            else posters.cancel(tile.image)
        } }
    }
    companion object {
        const val PAGE_SIZE=24
        fun fromGames(games: List<GameTitle>,preferred: List<GameTitle> = emptyList())=(preferred.asSequence()+games.asSequence()).filter { !it.heroUrl.isNullOrBlank() }
            .distinctBy { it.titleId }.take(SeriesPersonalizationStore.MAX_ITEMS).map {
                SeriesWallpaper("game:${it.titleId}",it.name,artUrl=it.heroUrl,category=WallpaperCategory.GAMES)
            }.toList()
    }
}
