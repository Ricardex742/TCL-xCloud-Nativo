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

/** Native gallery with one preview and a visible-only thumbnail window, bounded independently of the catalog. */
internal class SeriesWallpaperGallery(private val context: Context,items: List<SeriesWallpaper>,private val back: () -> Unit) {
    private val store=SeriesPersonalizationStore(context)
    private val entries=items.take(SeriesPersonalizationStore.MAX_ITEMS)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val posters=PosterLoader(context,scope)
    private val dialog=ConsoleDialog(context,UiText.text("Galeria de fundos"))
    private var category=WallpaperCategory.ALL
    private var previewed=store.selected()
    private val preview=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val previewLabel=dialog.text("",21,true).apply {
        gravity=Gravity.BOTTOM or Gravity.START;setPadding(dialog.px(14),0,dialog.px(14),dialog.px(9))
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xe0101518.toInt()))
    }
    private val categories=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
    private val strip=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private val scroll=HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false
        addView(strip,android.view.ViewGroup.LayoutParams(-2,-1))
    }
    private val tiles=mutableListOf<Tile>()
    private val categoryButtons=mutableMapOf<WallpaperCategory,TextView>()
    private lateinit var favoriteButton: TextView
    private lateinit var applyButton: TextView
    private data class Tile(val entry: SeriesWallpaper,val view: FrameLayout,val image: ImageView,val label: TextView)

    fun show(): ConsoleDialog {
        val previewFrame=FrameLayout(context).apply {
            background=SeriesWallpaperArt(previewed.style)
            addView(preview,FrameLayout.LayoutParams(-1,-1))
            addView(previewLabel,FrameLayout.LayoutParams(-1,dialog.px(49),Gravity.BOTTOM))
        }
        dialog.body.addView(previewFrame,LinearLayout.LayoutParams(-1,dialog.px(192)))
        WallpaperCategory.entries.forEach { value ->
            val button=DashboardTheme.button(context,value.label,dialog.scale) { changeCategory(value) }.apply {
                id=View.generateViewId();setPadding(dialog.px(6),0,dialog.px(6),0)
                typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(17).toFloat())
                setOnKeyListener { _,key,event ->
                    if(key==KeyEvent.KEYCODE_DPAD_DOWN && tiles.isNotEmpty()) {
                        if(event.action==KeyEvent.ACTION_DOWN) tiles.first().view.requestFocus();true
                    } else false
                }
            }
            categories.addView(button,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dialog.px(6) })
            categoryButtons[value]=button
        }
        dialog.body.addView(categories,LinearLayout.LayoutParams(-1,dialog.px(42)).apply { topMargin=dialog.px(10);bottomMargin=dialog.px(7) })
        dialog.body.addView(scroll,LinearLayout.LayoutParams(-1,dialog.px(119)))
        dialog.body.addView(dialog.text(UiText.text("A: escolher fundo · X: favoritar · B: voltar"),17,color=DashboardTheme.muted),LinearLayout.LayoutParams(-1,-2))
        favoriteButton=dialog.addPersistentAction(UiText.text("Favoritar fundo")) { toggleFavorite() }
        applyButton=dialog.addAction(UiText.text("Usar fundo")) { store.select(previewed);back() }.apply { id=View.generateViewId() }
        dialog.addAction(UiText.text("Voltar"),back)
        dialog.shortcut={ event ->
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_X) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) toggleFavorite();true
            } else false
        }
        scroll.setOnScrollChangeListener { _,_,_,_,_ -> loadVisible() }
        dialog.addCleanup { posters.close();scope.cancel() }
        dialog.setOnCancelListener { back() }
        changeCategory(category);showPreview(previewed)
        dialog.focusFirst(tiles.firstOrNull { it.entry.id==previewed.id }?.view ?: categoryButtons.getValue(category))
        dialog.show();scroll.doOnLayout { loadVisible() }
        return dialog
    }
    private fun all()=(SeriesWallpaper.builtIns+entries+store.favorites()+store.selected()).distinctBy { it.id }
    private fun changeCategory(value: WallpaperCategory) {
        category=value;tiles.forEach { posters.cancel(it.image) };tiles.clear();strip.removeAllViews()
        val items=when(value) {
            WallpaperCategory.ALL -> all()
            WallpaperCategory.FAVORITES -> store.favorites()
            else -> all().filter { it.category==value }
        }
        categoryButtons.forEach { (key,button) -> button.isSelected=key==value;button.background=tilePlate(key==value) }
        if(items.isEmpty()) strip.addView(dialog.text(UiText.text("Nenhum fundo nesta categoria."),20),LinearLayout.LayoutParams(-2,-2))
        items.forEach { entry ->
            val image=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
            if(entry.artUrl==null) image.setImageDrawable(SeriesWallpaperArt(entry.style))
            val label=dialog.text(entry.label,16).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL;setPadding(dialog.px(5),0,dialog.px(5),0) }
            val tile=FrameLayout(context).apply {
                id=View.generateViewId();isFocusable=true;isFocusableInTouchMode=true;isClickable=true
                DashboardTheme.customFocus(this);setPadding(dialog.px(3),dialog.px(3),dialog.px(3),dialog.px(3))
                addView(image,FrameLayout.LayoutParams(-1,dialog.px(78)))
                addView(label,FrameLayout.LayoutParams(-1,dialog.px(28),Gravity.BOTTOM))
                setOnFocusChangeListener { _,focused ->
                    background=tilePlate(focused);if(focused) showPreview(entry)
                }
                setOnClickListener { store.select(entry);dialog.dismiss();back() }
            }
            tile.background=tilePlate(false)
            tiles+=Tile(entry,tile,image,label)
            strip.addView(tile,LinearLayout.LayoutParams(dialog.px(151),dialog.px(111)).apply { marginEnd=dialog.px(10) })
        }
        tiles.forEachIndexed { i,tile ->
            tile.view.nextFocusLeftId=tiles[(i-1).coerceAtLeast(0)].view.id
            tile.view.nextFocusRightId=tiles[(i+1).coerceAtMost(tiles.lastIndex)].view.id
            tile.view.nextFocusUpId=categoryButtons.getValue(value).id
            tile.view.nextFocusDownId=applyButton.id
            tile.view.setOnKeyListener { _,key,event ->
                if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if(event.action==KeyEvent.ACTION_DOWN) {
                        tiles[(i+if(key==KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0,tiles.lastIndex)].view.requestFocus()
                    };true
                } else false
            }
        }
        updateFavorites();scroll.scrollTo(0,0);scroll.doOnLayout { loadVisible() }
    }
    private fun tilePlate(focused: Boolean)=GradientDrawable().apply {
        setColor(0xff171e22.toInt());cornerRadius=dialog.px(4).toFloat()
        if(focused) setStroke(dialog.px(2).coerceAtLeast(1),DashboardTheme.accent)
    }
    private fun showPreview(entry: SeriesWallpaper) {
        previewed=entry
        preview.background=SeriesWallpaperArt(entry.style)
        if(entry.artUrl!=null) posters.load(preview,entry.artUrl,768,432) else {
            posters.cancel(preview);preview.setImageDrawable(SeriesWallpaperArt(entry.style))
        }
        previewLabel.text=entry.label;updateFavoriteButton()
    }
    private fun toggleFavorite() {
        store.toggleFavorite(previewed)
        if(category==WallpaperCategory.FAVORITES) {
            changeCategory(category)
            (tiles.firstOrNull()?.view ?: categoryButtons.getValue(category)).requestFocus()
        }
        updateFavorites()
    }
    private fun updateFavoriteButton() {
        if(::favoriteButton.isInitialized) favoriteButton.text=UiText.uppercase(if(store.isFavorite(previewed.id)) UiText.text("Remover dos fundos favoritos") else UiText.text("Favoritar fundo"))
    }
    private fun updateFavorites() {
        updateFavoriteButton()
        val favorites=store.favorites().map { it.id }.toSet()
        tiles.forEach { tile ->
            val favorite=tile.entry.id in favorites
            tile.label.text=UiText.text("{0}{1}",UiText.nested(if(favorite) "★ " else ""),UiText.nested(tile.entry.label))
            tile.view.contentDescription=UiText.text("{0}{1}",UiText.nested(tile.entry.label),UiText.nested(if(favorite) UiText.text(", favorito") else ""))
        }
    }
    private fun loadVisible() {
        if(scroll.width<=0 || !dialog.isShowing) return
        tiles.forEach { tile -> if(tile.entry.artUrl!=null) {
            if(tile.view.right>=scroll.scrollX-dialog.px(151) && tile.view.left<=scroll.scrollX+scroll.width+dialog.px(151))
                posters.load(tile.image,tile.entry.artUrl,256,144)
            else posters.cancel(tile.image)
        } }
    }
    companion object {
        fun fromGames(games: List<GameTitle>,preferred: List<GameTitle> = emptyList())=(preferred.asSequence()+games.asSequence()).filter { !it.heroUrl.isNullOrBlank() }
            .distinctBy { it.titleId }.take(SeriesPersonalizationStore.MAX_ITEMS).map {
                SeriesWallpaper("game:${it.titleId}",it.name,artUrl=it.heroUrl,category=WallpaperCategory.GAMES)
            }.toList()
    }
}
