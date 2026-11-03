package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnNextLayout
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.GamePassCatalog
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Locale
import kotlin.math.roundToInt

/** Native TV catalog: real catalog art, recycled portrait covers and deterministic controller focus. */
class GameDashboardView(
    context: Context,
    private val library: GameLibrary,
    private val onPlay: (GameTitle) -> Unit,
    private val onSearch: () -> Unit,
    private val onOptions: () -> Unit,
    private val onExit: () -> Unit
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val posters = PosterLoader(context, scope)
    private var games = emptyList<GameTitle>()
    private var gamePassProducts: Set<String>? = null
    private var gamePassTitles = emptySet<String>()
    private var gamePassLoaded = false
    private var added: List<String>? = null
    private var galleriesLoaded = false
    private var profile = XboxProfile()
    var activeTab = LibraryTab.ALL
        private set
    var searchQuery = ""
        private set
    var selectedGame: GameTitle? = null
        private set
    private var scale = 1f
    private var coverHeight = 1
    private var coverVerticalPadding = 8f
    private var lastGeometry = 0L
    private var closed = false
    private var focusRequest = 0
    private val tabs = mutableListOf<TextView>()
    private val tabPlates = mutableListOf<FocusPlate>()
    private val headerIcon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val headerTitle = label(44f)
    private val profilePanel = FrameLayout(context).apply {
        id = generateViewId(); isFocusable = true; isClickable = true
        DashboardTheme.customFocus(this)
        setOnClickListener { onOptions() }
    }
    private val avatar = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(0xff53655a.toInt(), 1000f); clipToOutline = true
    }
    private val avatarLetter = label(28f).apply { gravity = Gravity.CENTER; text = "X" }
    private val accountName = label(26f)
    private val gamertag = label(24f).apply { setTextColor(DashboardTheme.muted) }
    private val subscription = label(20f).apply { gravity = Gravity.CENTER }
    private val settingsButton = DashboardTheme.button(context,"⚙") { onOptions() }.apply {
        id=generateViewId(); contentDescription="Configurações"
        typeface=android.graphics.Typeface.DEFAULT
        setPadding(0,0,0,0)
    }
    private val connected = View(context).apply { background = rounded(0xff22b444.toInt(), 1000f) }
    private val navigation = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val tabRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(0xff27352c.toInt(), 7f)
        clipChildren = false; clipToPadding = false
    }
    private val lb = label(24f).apply { text = "LB"; gravity = Gravity.CENTER }
    private val rb = label(24f).apply { text = "RB"; gravity = Gravity.CENTER }
    private val grid = object : RecyclerView(context) {
        override fun dispatchDraw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.clipRect(-12*scale, -12*scale, width+12*scale, height.toFloat())
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saved)
        }
    }.apply {
        id = generateViewId()
        DashboardTheme.customFocus(this)
        layoutManager = GridLayoutManager(context, COLUMNS)
        clipToPadding = false
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        setItemViewCacheSize(12)
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    private val emptyState = label(25f, false).apply {
        gravity = Gravity.CENTER; setTextColor(0xffcdd9d1.toInt()); visibility = View.GONE
        maxLines = 4
    }
    private val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val metadataNote = label(17f, false).apply { setTextColor(DashboardTheme.muted) }
    private val catalogNote = label(18f, false).apply { gravity = Gravity.END; setTextColor(DashboardTheme.muted) }
    private val adapter = CoversAdapter()

    init {
        background = DashboardBackground()
        clipChildren = false; clipToPadding = false
        headerIcon.background = rounded(0xff334139.toInt(), 8f); headerIcon.clipToOutline = true
        headerTitle.maxLines = 2; headerTitle.setLineSpacing(0f, .96f)
        addView(headerIcon); addView(headerTitle)
        profilePanel.addView(avatar); profilePanel.addView(avatarLetter)
        profilePanel.addView(connected); profilePanel.addView(accountName); profilePanel.addView(gamertag)
        profilePanel.addView(subscription); profilePanel.addView(settingsButton)
        profilePanel.setOnFocusChangeListener { _, focused ->
            profilePanel.background = if (focused) rounded(0x66325540, 9f) else null
        }
        addView(profilePanel)
        navigation.addView(tabRow); navigation.addView(lb); navigation.addView(rb)
        lb.background = rounded(0xff252527.toInt(), 6f, Color.LTGRAY)
        rb.background = rounded(0xff252527.toInt(), 6f, Color.LTGRAY)
        lb.isClickable = true; rb.isClickable = true
        lb.contentDescription = "Categoria anterior, LB"; rb.contentDescription = "Próxima categoria, RB"
        lb.setOnClickListener { cycleTab(-1) }; rb.setOnClickListener { cycleTab(1) }
        lb.isFocusable = false; rb.isFocusable = false
        LibraryTab.entries.forEach { tab ->
            val plate = FocusPlate(7f, 4f)
            val view = label(25f).apply {
                id = generateViewId(); text = tab.label; gravity = Gravity.CENTER
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true; background = plate
                DashboardTheme.customFocus(this)
                setOnClickListener { selectTab(tab); focusFirstCover() }
                setOnFocusChangeListener { _, focused -> if (focused) selectTab(tab) }
                nextFocusUpId = profilePanel.id
            }
            tabs.add(view); tabPlates.add(plate)
            tabRow.addView(view, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, when (tab) {
                LibraryTab.RECENT -> 1.65f; LibraryTab.ADDED -> 1.35f; LibraryTab.ALL -> 1.16f
                else -> 1f
            }))
        }
        addView(navigation)
        grid.adapter = adapter
        addView(grid); addView(emptyState)
        addView(footer); addView(catalogNote); addView(metadataNote)
        hint("A", "SELECIONAR") { selectedGame?.let(onPlay) }
        hint("B", "VOLTAR") { back() }
        hint("Y", "FAVORITAR") { toggleSelectedFavorite() }
        hint("X", "BUSCAR", onSearch)
        hint("☰", "CONFIGURAÇÕES", onOptions)
        renderProfile()
        updateTabs()
    }

    private fun label(size: Float, heavy: Boolean = true) = TextView(context).apply {
        typeface = DashboardTheme.font(context, heavy)
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(2, stroke)
    }
    private fun hint(button: String, text: String, click: () -> Unit) {
        footer.addView(label(22f, false).apply {
            this.text = button
            tag = button; gravity = Gravity.CENTER
            background = rounded(Color.TRANSPARENT, if (button == "☰") 6f else 100f, Color.WHITE)
            setOnClickListener { click() }
            isFocusable = false
        })
        footer.addView(label(22f, false).apply { this.text = text; setOnClickListener { click() }; isFocusable = false })
    }

    fun setGames(titles: List<GameTitle>) {
        val products=gamePassProducts?.map(GamePassCatalog::productKey)?.toSet() ?: emptySet()
        games = titles.map { game -> game.copy(gamePass = GamePassCatalog.contains(game,products,gamePassTitles)) }
        val tier = XboxProfile.subscription(titles.flatMap { it.userSubscriptions + it.userPrograms })
        profile = profile.copy(subscription = tier ?: profile.subscription)
        renderProfile(); refresh()
    }
    fun gameById(id: String): GameTitle? = games.firstOrNull { it.titleId == id }
    fun setGamePassProducts(products: Set<String>?, resolvedTitles: Set<String> = emptySet()) {
        gamePassProducts = products
        gamePassTitles = resolvedTitles.map(GamePassCatalog::productKey).toSet()
        gamePassLoaded = true
        setGames(games)
    }
    fun setMetadataProgress(progress: com.xcloud.tclnative.catalog.CatalogService.Progress) {
        metadataNote.text = when {
            !progress.finished -> "CARREGANDO INFORMAÇÕES E CAPAS: ${progress.covers}/${progress.total}"
            progress.covers == 0 -> "CAPAS INDISPONÍVEIS. START → ATUALIZAR CATÁLOGO."
            progress.covers < progress.total -> "ALGUMAS CAPAS INDISPONÍVEIS. ATUALIZE NAS CONFIGURAÇÕES."
            else -> ""
        }
    }
    fun setProfile(value: XboxProfile) {
        profile = value.copy(subscription = value.subscription ?: profile.subscription)
        renderProfile()
    }
    private fun renderProfile() {
        accountName.text = profile.displayName.uppercase(Locale.getDefault())
        gamertag.text = profile.gamertag.uppercase(Locale.getDefault())
        subscription.text = profile.subscription?.let { if (it == "FREE") "FREE" else "GAME PASS $it" } ?: "ASSINATURA NÃO INFORMADA"
        subscription.background = rounded(0xff256038.toInt(), 3f)
        avatarLetter.text = profile.displayName.take(1).uppercase(Locale.getDefault())
        connected.contentDescription = "Conta autenticada no Xbox Cloud"
        profilePanel.contentDescription = "${profile.displayName}, ${profile.gamertag}. Abrir configurações"
        posters.load(avatar, profile.avatarUrl, 256, 256) { ready -> avatarLetter.visibility = if (ready) View.GONE else View.VISIBLE }
    }
    fun setAddedGallery(added: List<String>?) {
        this.added = added; galleriesLoaded = true; refresh()
    }
    fun setSearch(query: String) { searchQuery = query.trim(); refresh(); focusFirstCover() }
    fun refresh() {
        val visible = GameLibrary.select(games, activeTab, library.favorites, library.records(), gamePassProducts?.toList(), added, searchQuery,gamePassTitles)
        adapter.submit(visible)
        emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyState.text = when {
            searchQuery.isNotBlank() -> "Nenhum jogo encontrado para “$searchQuery”.\nX para pesquisar novamente."
            activeTab == LibraryTab.FAVORITES -> "Seus favoritos aparecem aqui.\nSelecione um jogo e pressione Y para favoritar."
            activeTab == LibraryTab.RECENT -> "Sua próxima partida começa aqui.\nOs jogos iniciados neste aparelho aparecerão nesta aba."
            activeTab == LibraryTab.ADDED && !galleriesLoaded -> "Carregando adições recentes…"
            activeTab == LibraryTab.ADDED && added == null -> "Adições recentes indisponíveis no momento.\nOs jogos continuam na aba Todos os jogos."
            activeTab == LibraryTab.GAME_PASS && !gamePassLoaded -> "Carregando jogos do Game Pass…"
            activeTab == LibraryTab.GAME_PASS && gamePassProducts == null -> "Não foi possível confirmar os jogos do Game Pass.\nAtualize o catálogo nas configurações."
            activeTab == LibraryTab.GAME_PASS -> "Nenhum título do Game Pass disponível nesta lista."
            else -> "Nenhum jogo disponível nesta categoria."
        }
        catalogNote.text = when {
            searchQuery.isNotBlank() -> "BUSCA: $searchQuery  •  ${visible.size} JOGOS"
            activeTab == LibraryTab.GAME_PASS && !gamePassLoaded -> "${visible.size} JOGOS • ATUALIZANDO GAME PASS…"
            else -> "${visible.size} JOGOS"
        }
        val selected = visible.firstOrNull { it.titleId == selectedGame?.titleId } ?: visible.firstOrNull()
        updateSelected(selected)
    }
    fun selectTab(tab: LibraryTab) {
        if (activeTab != tab) {
            activeTab = tab; selectedGame = null; refresh(); grid.scrollToPosition(0)
        }
        updateTabs()
    }
    private fun updateTabs() {
        profilePanel.nextFocusDownId = tabs[activeTab.ordinal].id
        LibraryTab.entries.forEachIndexed { index, tab ->
            tabPlates[index].highlighted = activeTab == tab
            tabs[index].isSelected = activeTab == tab
            tabs[index].contentDescription = tab.label + if (activeTab == tab) ", selecionado" else ""
        }
    }
    private fun cycleTab(step: Int) {
        val next = (activeTab.ordinal + step + LibraryTab.entries.size) % LibraryTab.entries.size
        selectTab(LibraryTab.entries[next]); focusFirstCover()
    }
    private fun focusFirstCover() {
        focusCoverAt(0)
    }
    private fun focusCoverAt(position: Int) {
        val request = ++focusRequest
        if (adapter.itemCount == 0) { tabs[activeTab.ordinal].requestFocus(); return }
        grid.doOnNextLayout {
            if (!closed && request == focusRequest) {
                grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                    ?: tabs[activeTab.ordinal].requestFocus()
            }
        }
        grid.scrollToPosition(position)
    }
    fun restoreFocus() {
        refresh()
        val position = adapter.items.indexOfFirst { it.titleId == selectedGame?.titleId }.coerceAtLeast(0)
        focusCoverAt(position)
    }
    private fun updateSelected(game: GameTitle?) {
        val previous = selectedGame
        selectedGame = game
        if (previous == game && headerTitle.text.isNotEmpty()) return
        if (game == null) { headerTitle.text = "SUA BIBLIOTECA"; posters.load(headerIcon, null); return }
        val words = game.name.uppercase(Locale.getDefault()).split(Regex("\\s+"))
        val split = if (words.size <= 1) words.size else if (words[0].length >= 10) 1 else
            (1 until words.size).minByOrNull { kotlin.math.abs(words.take(it).joinToString(" ").length - game.name.length / 2) } ?: 1
        val first = words.take(split).joinToString(" ")
        val second = words.drop(split).joinToString(" ")
        val title = if (second.isEmpty()) first else "$first\n$second"
        headerTitle.text = SpannableString(title).apply {
            if (second.isNotEmpty()) setSpan(ForegroundColorSpan(DashboardTheme.accent), first.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        headerIcon.contentDescription = "Ícone de ${game.name}"
        posters.load(headerIcon, game.tileUrl ?: game.posterUrl, 512, 512)
    }
    private fun toggleSelectedFavorite() {
        val game = selectedGame ?: return
        val added = library.toggleFavorite(game.titleId)
        announceForAccessibility(if (added) "${game.name} adicionado aos favoritos" else "${game.name} removido dos favoritos")
        val position = adapter.items.indexOfFirst { it.titleId == game.titleId }
        if (activeTab == LibraryTab.FAVORITES) { refresh(); focusFirstCover() }
        else if (position >= 0) adapter.notifyItemChanged(position, "favorite")
    }
    fun back() {
        when {
            searchQuery.isNotBlank() -> setSearch("")
            grid.hasFocus() -> tabs[activeTab.ordinal].requestFocus()
            else -> onExit()
        }
    }
    /** Handle gamepad keys only while the catalog is visible, before the native gameplay route. */
    fun handleKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        val handled = key in setOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B)
        if (!handled) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (key) {
            KeyEvent.KEYCODE_BUTTON_L1 -> cycleTab(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> cycleTab(1)
            KeyEvent.KEYCODE_BUTTON_Y -> toggleSelectedFavorite()
            KeyEvent.KEYCODE_BUTTON_X -> onSearch()
            KeyEvent.KEYCODE_BUTTON_START -> onOptions()
            KeyEvent.KEYCODE_BUTTON_A -> findFocus()?.performClick()
            KeyEvent.KEYCODE_BUTTON_B -> back()
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val geometry = (w.toLong() shl 32) or h.toLong()
        if (geometry != lastGeometry && w > 0 && h > 0) {
            lastGeometry = geometry; scale = w / 1280f; layoutGeometry(w, h)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        view.layoutParams = LayoutParams(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)).apply {
            leftMargin = x.roundToInt(); topMargin = y.roundToInt()
        }
    }
    private fun textSize(view: TextView, value: Float) = view.setTextSize(TypedValue.COMPLEX_UNIT_PX, value * scale)
    private fun layoutGeometry(w: Int, h: Int) {
        val s = scale
        // Reserve the full header for the game and profile. The portrait grid scrolls below it.
        coverVerticalPadding = 7*s
        val coverWidth = (w-150*s)/COLUMNS - 20*s
        coverHeight = (coverWidth*1.52f+2*coverVerticalPadding).roundToInt().coerceAtLeast(1)
        val footerTop = h-42*s
        val headerTop = 20*s
        val headerSize = 116*s
        val navHeight = 54*s
        val navTop = headerTop+headerSize+30*s
        val top = navTop+navHeight+18*s
        place(headerIcon, 106*s, headerTop, headerSize, headerSize)
        place(headerTitle, 242*s, headerTop+8*s, 465*s, 110*s)
        textSize(headerTitle, 46f)
        if (android.os.Build.VERSION.SDK_INT >= 26) headerTitle.setAutoSizeTextTypeUniformWithConfiguration(
            (24*s).roundToInt().coerceAtLeast(1), (46*s).roundToInt().coerceAtLeast(2), 1, TypedValue.COMPLEX_UNIT_PX)
        place(profilePanel, w-492*s, headerTop+2*s, 440*s, headerSize)
        val a = 80*s
        place(avatarLetter, 10*s, 12*s, a, a); place(avatar, 10*s, 12*s, a, a)
        place(connected, 10*s+a-19*s, 12*s+a-18*s, 15*s, 15*s)
        val tx = 106*s
        place(accountName, tx, 0f, 320*s, 34*s)
        place(gamertag, tx, 30*s, 320*s, 30*s)
        place(subscription, tx, 68*s, 250*s, 32*s)
        place(settingsButton, tx+264*s, 64*s, 60*s, 42*s)
        textSize(accountName, 27f); textSize(gamertag, 24f); textSize(subscription, 20f)
        textSize(settingsButton, 28f); textSize(avatarLetter, 28f)
        place(metadataNote, 106*s, headerTop+headerSize+5*s, w-210*s, 22*s); textSize(metadataNote, 17f)
        place(navigation, 48*s, navTop, w-84*s, navHeight+3*s)
        val shoulderTop = (navHeight-29*s)/2
        place(lb, 3*s, shoulderTop, 40*s, 29*s); place(rb, w-128*s, shoulderTop, 40*s, 29*s)
        place(tabRow, 58*s, 0f, w-204*s, navHeight)
        tabs.forEach { textSize(it, 25f) }; textSize(lb, 23f); textSize(rb, 23f)
        place(grid, 96*s, top, w-150*s, (footerTop-top-10*s).coerceAtLeast(coverHeight.toFloat()))
        place(emptyState, 106*s, top, w-210*s, h*.50f)
        textSize(emptyState, 26f)
        place(footer, 60*s, footerTop, w-120*s, 34*s)
        for (i in 0 until footer.childCount step 2) {
            val glyph = footer.getChildAt(i) as TextView
            glyph.text = glyph.tag.toString(); textSize(glyph, 22f)
            glyph.layoutParams = LinearLayout.LayoutParams((28*s).roundToInt(), (28*s).roundToInt())
                .apply { marginEnd = (8*s).roundToInt() }
            val text = footer.getChildAt(i+1) as TextView
            textSize(text, 22f)
            text.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (25*s).roundToInt() }
        }
        place(catalogNote, w-380*s, footerTop+s, 320*s, 32*s); textSize(catalogNote, 18f)
        adapter.notifyDataSetChanged()
    }

    private inner class CoversAdapter : RecyclerView.Adapter<CoverHolder>() {
        var items = emptyList<GameTitle>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].titleId.fold(1125899906842597L) { hash, c -> hash*31+c.code }
        override fun getItemCount() = items.size
        fun submit(next: List<GameTitle>) {
            val before = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = before.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(old: Int, new: Int) = before[old].titleId == next[new].titleId
                override fun areContentsTheSame(old: Int, new: Int) = before[old] == next[new]
            })
            items = next; diff.dispatchUpdatesTo(this)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CoverHolder(GameCard(context))
        override fun onBindViewHolder(holder: CoverHolder, position: Int) {
            val game = items[position]
            val card = holder.card
            card.layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, coverHeight)
            card.bind(game)
            card.nextFocusUpId = if (position < COLUMNS) tabs[activeTab.ordinal].id else View.NO_ID
            card.setOnFocusChangeListener { _, focused ->
                card.focused = focused
                if (focused) updateSelected(game)
            }
            card.setOnClickListener { updateSelected(game); onPlay(game) }
        }
        override fun onViewRecycled(holder: CoverHolder) { posters.cancel(holder.card.image) }
    }
    private class CoverHolder(val card: GameCard) : RecyclerView.ViewHolder(card)
    private inner class GameCard(context: Context) : FrameLayout(context) {
        private val cover = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff3c5144.toInt(), 0xff141d17.toInt()))
                .apply { cornerRadius = 7*scale }
            clipToOutline = true
        }
        private val fallback = label(29f).apply { gravity = Gravity.CENTER; maxLines = 4; setPadding(12, 12, 12, 12) }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val badges = GameBadgesView(context)
        private val favorite = label(22f).apply {
            text = "★"; gravity = Gravity.CENTER; setTextColor(DashboardTheme.yellow)
            background = rounded(0xc0142018.toInt(), 6f)
        }
        var focused = false
            set(value) { field = value; invalidate() }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            DashboardTheme.customFocus(this)
            clipChildren = false; setWillNotDraw(false)
            cover.addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cover.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cover.addView(badges)
            addView(cover); addView(favorite)
        }
        fun bind(game: GameTitle) {
            contentDescription = game.name + if (game.titleId in library.favorites) ", favorito" else ""
            fallback.text = game.name.uppercase(Locale.getDefault()); textSize(fallback, 27f)
            favorite.visibility = if (game.titleId in library.favorites) View.VISIBLE else View.GONE
            posters.load(image, game.posterUrl ?: game.tileUrl)
            val s = scale
            badges.bind(game.gamePass, game.seriesOptimized, s)
            badges.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (52*s).roundToInt(), Gravity.BOTTOM)
            cover.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins((10*s).roundToInt(), coverVerticalPadding.roundToInt(), (10*s).roundToInt(), coverVerticalPadding.roundToInt())
            }
            favorite.layoutParams = LayoutParams((31*s).roundToInt(), (31*s).roundToInt(), Gravity.TOP or Gravity.END)
                .apply { topMargin = (16*s).roundToInt(); marginEnd = (18*s).roundToInt() }
            textSize(favorite, 23f)
        }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!focused) return
            val s = scale
            val rect = RectF(5*s, 3*s, width-5*s, height-3*s)
            outline.style = Paint.Style.STROKE
            for (i in 5 downTo 1) {
                outline.strokeWidth = i*3*s; outline.color = DashboardTheme.accent; outline.alpha = 10
                canvas.drawRoundRect(rect, 11*s, 11*s, outline)
            }
            outline.strokeWidth = 3*s; outline.color = DashboardTheme.accent; outline.alpha = 255
            canvas.drawRoundRect(rect, 10*s, 10*s, outline)
        }
    }
    fun close() { if (!closed) { closed = true; scope.cancel(); posters.close(); grid.adapter = null } }
    companion object { const val COLUMNS = 6 }
}
