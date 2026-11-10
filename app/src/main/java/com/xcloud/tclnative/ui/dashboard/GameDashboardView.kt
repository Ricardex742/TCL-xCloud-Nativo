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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnNextLayout
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.GamePassCatalog
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.catalog.CatalogCard
import com.xcloud.tclnative.catalog.GalleryProduct
import com.xcloud.tclnative.catalog.OfficialGallery
import com.xcloud.tclnative.catalog.OfficialGalleryKind
import com.xcloud.tclnative.catalog.CatalogInput
import com.xcloud.tclnative.catalog.CatalogPresentation
import com.xcloud.tclnative.catalog.CatalogChanges
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Native TV catalog: real catalog art, recycled portrait covers and deterministic controller focus. */
class GameDashboardView(
    context: Context,
    private val library: GameLibrary,
    private val onPlay: (GameTitle) -> Unit,
    private val onSearch: () -> Unit,
    private val onOptions: () -> Unit,
    private val onExit: () -> Unit,
    private val onFavoritesChanged: () -> Unit = {},
    private val onPlayProduct: (GalleryProduct) -> Unit = {}
) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val posters = PosterLoader(context, scope,frameBound=true)
    private var games = emptyList<GameTitle>()
    private var gamePassProducts: Set<String>? = null
    private var gamePassTitles = emptySet<String>()
    private var gamePassLoaded = false
    private val officialGalleries = mutableMapOf<OfficialGalleryKind, OfficialGallery?>()
    private val pendingGalleries = mutableMapOf<OfficialGalleryKind, OfficialGallery>()
    private val galleryBindings = mutableMapOf<String, GameTitle>()
    private var artworkPaused = false
    private var metadataStatus = ""
    private var added: List<String>? = null
    private var galleriesLoaded = false
    private var recent=library.cloudRecent()
    private var recentLoading=true
    private var recentFailed=false
    private var profile = XboxProfile()
    var activeTab = LibraryTab.RECENT
        private set
    var searchQuery = ""
        private set
    private var selectedCard: CatalogCard? = null
    val selectedGame: GameTitle? get() = selectedCard?.playableGame()
    private var scale = 1f
    private var coverHeight = 1
    private var coverArtSize = ArtworkSize(192,320)
    private var headerArtJob: Job? = null
    private var metadataJob: Job? = null
    private var pendingMetadata: List<GameTitle>? = null
    private var catalogJob: Job? = null
    private var pendingCatalog: CatalogInput? = null
    private var catalogRevision = 0L
    private var renderedTab = activeTab
    private var renderedQuery = searchQuery
    private var positions = emptyMap<String, Int>()
    private var pendingFocus: Pair<String?, Int>? = null
    internal val isCatalogUpdating: Boolean get() = catalogJob?.isActive == true || metadataJob?.isActive == true
    private var coverVerticalPadding = 8f
    private var lastGeometry = 0L
    private var closed = false
    private var focusRequest = 0
    private val tabs = mutableListOf<TextView>()
    private val tabPlates = mutableListOf<FocusPlate>()
    private val headerIcon = FixedArtworkView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; cropToPadding=true }
    private val headerTitle = label(44f)
    private val profilePanel = FrameLayout(context).apply {
        id = generateViewId(); isFocusable = true; isClickable = true
        DashboardTheme.customFocus(this)
        setOnClickListener { onOptions() }
    }
    private val avatar = FixedArtworkView(context).apply {
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
    private val searchButton = SearchButton(context,onSearch)
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
        addView(searchButton)
        profilePanel.addView(avatar); profilePanel.addView(avatarLetter)
        profilePanel.addView(connected); profilePanel.addView(accountName); profilePanel.addView(gamertag)
        profilePanel.addView(subscription); profilePanel.addView(settingsButton)
        profilePanel.setOnFocusChangeListener { _, focused ->
            profilePanel.background = if (focused) rounded(0x66325540, 9f) else null
        }
        addView(profilePanel)
        searchButton.nextFocusRightId=profilePanel.id
        profilePanel.nextFocusLeftId=searchButton.id
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
                LibraryTab.RECENT -> 1.65f; LibraryTab.ADDED -> 1.35f; LibraryTab.ALL -> 1.16f; LibraryTab.PURCHASED -> 1.95f
                else -> 1f
            }))
        }
        addView(navigation)
        searchButton.nextFocusDownId=tabs.first().id
        grid.adapter = adapter
        addView(grid); addView(emptyState)
        addView(footer); addView(catalogNote); addView(metadataNote)
        hint("A", "SELECIONAR") { selectedCard?.let(::playCard) }
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
        metadataJob?.cancel(); metadataJob=null; pendingMetadata=null
        officialGalleries.putAll(pendingGalleries); pendingGalleries.clear()
        games=titles
        refresh()
    }
    fun updateMetadata(titles: List<GameTitle>) {
        pendingMetadata=titles
        scheduleMetadata()
    }
    private fun scheduleMetadata() {
        if(artworkPaused || metadataJob?.isActive==true) return
        metadataJob=scope.launch {
            delay(400)
            val latest=pendingMetadata
            pendingMetadata=null; metadataJob=null
            if(!closed) {
                officialGalleries.putAll(pendingGalleries); pendingGalleries.clear()
                if(latest!=null) games=latest
                refresh()
            }
        }
    }
    fun gameById(id: String): GameTitle? = games.firstOrNull { it.titleId == id }
    fun pauseArtwork() {
        artworkPaused=true
        catalogRevision++; pendingCatalog=null; pendingFocus=null; focusRequest++
        metadataJob?.cancel(); metadataJob=null; pendingMetadata=null
        headerArtJob?.cancel(); posters.pause()
    }
    fun resumeArtwork() {
        artworkPaused=false
        officialGalleries.putAll(pendingGalleries); pendingGalleries.clear()
        refresh()
        posters.resume(); renderProfile()
        selectedCard?.let { loadHeaderArtwork(it) }
        adapter.notifyItemRangeChanged(0,adapter.itemCount,"artwork")
    }
    fun setGamePassProducts(products: Set<String>?, resolvedTitles: Set<String> = emptySet()) {
        gamePassProducts = products
        gamePassTitles = resolvedTitles
        gamePassLoaded = true
        setGames(games)
    }
    fun setOfficialGallery(kind: OfficialGalleryKind, gallery: OfficialGallery?) {
        require(gallery==null || gallery.kind==kind)
        pendingGalleries.remove(kind)
        officialGalleries[kind]=gallery
        if(kind==OfficialGalleryKind.GAME_PASS) {
            gamePassTitles=emptySet(); gamePassLoaded=true
        }
        refresh()
    }
    fun updateOfficialGallery(gallery: OfficialGallery) {
        pendingGalleries[gallery.kind]=gallery
        scheduleMetadata()
    }
    fun bindGalleryProduct(productId: String, game: GameTitle) {
        galleryBindings[GamePassCatalog.productKey(productId)]=game
        refresh()
    }
    fun isGamePassProduct(productId: String) = GamePassCatalog.productKey(productId) in (gamePassProducts ?: emptySet())
    fun setMetadataProgress(progress: com.xcloud.tclnative.catalog.CatalogService.Progress) {
        metadataStatus = when {
            !progress.finished -> "CARREGANDO INFORMAÇÕES E CAPAS: ${progress.covers}/${progress.total}"
            progress.covers == 0 -> "CAPAS INDISPONÍVEIS. START → ATUALIZAR CATÁLOGO."
            progress.covers < progress.total -> "ALGUMAS CAPAS INDISPONÍVEIS. ATUALIZE NAS CONFIGURAÇÕES."
            else -> ""
        }
        renderMetadataNote()
    }
    fun setProfile(value: XboxProfile) {
        val updated=value.copy(subscription=value.subscription ?: profile.subscription)
        if(updated!=profile) { profile=updated; renderProfile() }
    }
    private fun renderProfile() {
        accountName.text = profile.displayName.uppercase(Locale.getDefault())
        gamertag.text = profile.gamertag.uppercase(Locale.getDefault())
        subscription.text = profile.subscription?.let { if (it == "FREE") "FREE" else "GAME PASS $it" } ?: "ASSINATURA NÃO INFORMADA"
        subscription.background = rounded(0xff256038.toInt(), 3f)
        avatarLetter.text = profile.displayName.take(1).uppercase(Locale.getDefault())
        connected.contentDescription = "Conta autenticada no Xbox Cloud"
        profilePanel.contentDescription = "${profile.displayName}, ${profile.gamertag}. Abrir configurações"
        val size=ArtworkSize.forDisplay((80*scale).roundToInt(),(80*scale).roundToInt())
        posters.load(avatar, profile.avatarUrl, size.width, size.height) { ready -> avatarLetter.visibility = if (ready) View.GONE else View.VISIBLE }
    }
    fun setAddedGallery(added: List<String>?) {
        this.added = added; galleriesLoaded = true; refresh()
    }
    fun beginRecentRefresh() { recentLoading=true; recentFailed=false; refresh() }
    fun setRecentHistory(ids: List<String>?) {
        recentLoading=false; recentFailed=ids==null
        if(ids!=null) recent=ids
        refresh()
    }
    fun setSearch(query: String) {
        searchQuery=query.trim()
        // The main search must also find games outside the account's recent history.
        if(searchQuery.isNotBlank() && activeTab!=LibraryTab.ALL) selectTab(LibraryTab.ALL) else refresh()
        focusFirstCover()
    }
    fun refresh() {
        if(closed) return
        catalogRevision++
        if(artworkPaused) return
        if(renderedTab!=activeTab || renderedQuery!=searchQuery) {
            // Do not leave clickable cards from the previous category while preparing the next.
            renderedTab=activeTab; renderedQuery=searchQuery
            adapter.submit(emptyList(),CatalogChanges(true,emptyList()))
            positions=emptyMap(); updateSelected(null)
            renderCatalogState(0,preparing=true)
        }
        pendingCatalog=CatalogInput(games,officialGalleries.toMap(),galleryBindings.toMap(),
            gamePassProducts,gamePassTitles,activeTab,library.favorites,added,recent,searchQuery)
        if(catalogJob?.isActive==true) return
        // One worker, one pending snapshot: bursts cannot build an unbounded CPU queue.
        catalogJob=scope.launch {
            while(!closed && !artworkPaused) {
                val input=pendingCatalog ?: break
                pendingCatalog=null
                val revision=catalogRevision
                val before=adapter.items
                val (presentation,changes)=withContext(Dispatchers.Default) {
                    val result=CatalogPresentation.build(input)
                    result to CatalogChanges.between(before,result.cards)
                }
                if(!closed && !artworkPaused && revision==catalogRevision) applyCatalog(presentation,changes)
            }
        }
    }
    private fun applyCatalog(presentation: CatalogPresentation, changes: CatalogChanges) {
        val selectedKey=selectedCard?.key
        val focused=grid.hasFocus()
        val manager=grid.layoutManager as GridLayoutManager
        val anchor=manager.findFirstVisibleItemPosition()
        val anchorKey=adapter.items.getOrNull(anchor)?.key
        val anchorOffset=manager.findViewByPosition(anchor)?.top?.minus(grid.paddingTop) ?: 0
        positions=presentation.positions
        gamePassProducts=presentation.gamePassProducts
        val updated=profile.copy(subscription=presentation.subscription ?: profile.subscription)
        if(updated!=profile) { profile=updated; renderProfile() }
        adapter.submit(presentation.cards,changes)
        val selectedPosition=positions[selectedKey]
        updateSelected(selectedPosition?.let { presentation.cards[it] } ?: presentation.cards.firstOrNull())
        renderCatalogState(presentation.cards.size)
        val requested=pendingFocus
        pendingFocus=null
        if(requested!=null) focusCoverNow(positions[requested.first] ?: requested.second)
        else if(changes.replace && focused) focusCoverNow(selectedPosition ?: 0)
        else if(changes.replace && anchorKey!=null) positions[anchorKey]?.let { manager.scrollToPositionWithOffset(it,anchorOffset) }
    }
    private fun renderCatalogState(count: Int, preparing: Boolean = false) {
        val kind=when(activeTab) { LibraryTab.GAME_PASS -> OfficialGalleryKind.GAME_PASS; LibraryTab.PURCHASED -> OfficialGalleryKind.PURCHASED; else -> null }
        val gallery=kind?.let { officialGalleries[it] }
        emptyState.visibility = if (count==0) View.VISIBLE else View.GONE
        emptyState.text = when {
            preparing -> "Carregando catálogo…"
            searchQuery.isNotBlank() -> "Nenhum jogo encontrado para “$searchQuery”.\nX para pesquisar novamente."
            activeTab == LibraryTab.FAVORITES -> "Seus favoritos aparecem aqui.\nSelecione um jogo e pressione Y para favoritar."
            activeTab == LibraryTab.RECENT && recentLoading -> "Consultando seu histórico do Xbox Cloud…"
            activeTab == LibraryTab.RECENT && recentFailed -> "Não foi possível consultar o histórico do Xbox.\nAtualize o catálogo nas configurações para tentar novamente."
            activeTab == LibraryTab.RECENT -> "Seus jogos recentes do Xbox Cloud Gaming aparecerão aqui."
            activeTab == LibraryTab.ADDED && !galleriesLoaded -> "Carregando adições recentes…"
            activeTab == LibraryTab.ADDED && added == null -> "Adições recentes indisponíveis no momento.\nOs jogos continuam na aba Todos os jogos."
            activeTab == LibraryTab.GAME_PASS && !gamePassLoaded -> "Carregando jogos do Game Pass…"
            activeTab == LibraryTab.GAME_PASS && gamePassProducts == null -> "Não foi possível confirmar os jogos do Game Pass.\nAtualize o catálogo nas configurações."
            activeTab == LibraryTab.GAME_PASS -> "Nenhum título do Game Pass disponível nesta lista."
            activeTab == LibraryTab.PURCHASED && !officialGalleries.containsKey(OfficialGalleryKind.PURCHASED) -> "Carregando a lista oficial de jogos comprados compatíveis…"
            activeTab == LibraryTab.PURCHASED && gallery == null -> "Não foi possível consultar a lista oficial.\nAtualize o catálogo nas configurações."
            activeTab == LibraryTab.PURCHASED -> "A galeria oficial não retornou jogos nesta região."
            else -> "Nenhum jogo disponível nesta categoria."
        }
        catalogNote.text = when {
            preparing -> "CARREGANDO CATÁLOGO…"
            searchQuery.isNotBlank() -> "BUSCA: $searchQuery  •  $count JOGOS"
            activeTab == LibraryTab.GAME_PASS && !gamePassLoaded -> "$count JOGOS • ATUALIZANDO GAME PASS…"
            activeTab == LibraryTab.RECENT && recentLoading -> "$count JOGOS • ATUALIZANDO HISTÓRICO…"
            activeTab == LibraryTab.RECENT && recentFailed && recent!=null -> "$count JOGOS • HISTÓRICO SALVO • ATUALIZAÇÃO INDISPONÍVEL"
            kind!=null && officialGalleries.containsKey(kind) && gallery==null -> "LISTA OFICIAL INDISPONÍVEL"
            gallery!=null && !gallery.metadataComplete -> "$count PRODUTOS • ATUALIZANDO"
            gallery?.metadataFailed==true -> "$count PRODUTOS • DADOS PARCIAIS"
            gallery!=null -> "$count JOGOS • LISTA OFICIAL"
            else -> "$count JOGOS"
        }
        renderMetadataNote()
    }
    private fun renderMetadataNote() {
        val kind=when(activeTab) { LibraryTab.GAME_PASS -> OfficialGalleryKind.GAME_PASS; LibraryTab.PURCHASED -> OfficialGalleryKind.PURCHASED; else -> null }
        val gallery=kind?.let { officialGalleries[it] }
        metadataNote.text=when {
            activeTab==LibraryTab.PURCHASED && gallery?.metadataFailed==true -> "Algumas informações indisponíveis. A lista não confirma a propriedade; exige compra digital e condições do serviço."
            activeTab==LibraryTab.PURCHASED && gallery!=null && !gallery.metadataComplete -> "Carregando informações da lista oficial. Não confirma a propriedade; exige compra digital e condições do serviço."
            activeTab==LibraryTab.PURCHASED -> "Lista oficial de jogos compatíveis; exige compra digital e condições do serviço. Não confirma a propriedade na conta."
            gallery?.metadataFailed==true -> "Lista oficial completa • Algumas informações/capas indisponíveis; atualize o catálogo nas configurações."
            gallery!=null && !gallery.metadataComplete -> "Lista oficial completa • Carregando informações e capas…"
            gallery!=null -> "Galeria oficial do Xbox Cloud Gaming • Inclui opções gratuitas; acesso depende da conta e das condições do serviço."
            else -> metadataStatus
        }
    }
    fun selectTab(tab: LibraryTab) {
        if (activeTab != tab) {
            pendingFocus=null; focusRequest++
            activeTab = tab; selectedCard = null; refresh(); grid.scrollToPosition(0)
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
        if(isCatalogUpdating) { pendingFocus=selectedCard?.key to position; return }
        focusCoverNow(position)
    }
    private fun focusCoverNow(position: Int) {
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
        val position = positions[selectedCard?.key] ?: 0
        focusCoverAt(position)
    }
    private fun updateSelected(game: CatalogCard?) {
        val previous = selectedCard
        selectedCard = game
        if (previous?.key==game?.key && previous?.name==game?.name && previous?.tileUrl==game?.tileUrl &&
            previous?.posterUrl==game?.posterUrl && headerTitle.text.isNotEmpty()) return
        headerArtJob?.cancel()
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
        // Fast D-pad navigation updates text immediately and loads art only after focus settles.
        headerArtJob=scope.launch { delay(120); if(selectedCard?.key==game.key) loadHeaderArtwork(game) }
    }
    private fun loadHeaderArtwork(game: CatalogCard) {
        val size=ArtworkSize.forDisplay((116*scale).roundToInt(),(116*scale).roundToInt())
        posters.load(headerIcon,game.tileUrl ?: game.posterUrl,size.width,size.height)
    }
    private fun toggleSelectedFavorite() {
        val game = selectedGame ?: run {
            if(selectedCard?.product!=null) android.widget.Toast.makeText(context,"Selecione o jogo para verificar sua disponibilidade antes de favoritar.",android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val added = try { library.toggleFavorite(game.titleId) } catch (error: java.io.IOException) {
            android.widget.Toast.makeText(context,error.message,android.widget.Toast.LENGTH_LONG).show()
            return
        }
        onFavoritesChanged()
        announceForAccessibility(if (added) "${game.name} adicionado aos favoritos" else "${game.name} removido dos favoritos")
        if (activeTab == LibraryTab.FAVORITES) { refresh(); focusFirstCover() }
        else adapter.items.indices.filter { adapter.items[it].game?.titleId==game.titleId }
            .forEach { adapter.notifyItemChanged(it,"favorite") }
    }
    private fun playCard(card: CatalogCard) {
        val game=card.playableGame()
        if(game!=null) onPlay(game) else card.product?.let(onPlayProduct)
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
        coverArtSize=ArtworkSize.forDisplay(coverWidth.roundToInt(),(coverWidth*1.52f).roundToInt())
        val footerTop = h-42*s
        val headerTop = 20*s
        val headerSize = 116*s
        val navHeight = 54*s
        val navTop = headerTop+headerSize+30*s
        val top = navTop+navHeight+18*s
        place(headerIcon, 106*s, headerTop, headerSize, headerSize)
        selectedCard?.let(::loadHeaderArtwork)
        place(searchButton, 8*s, headerTop+14*s, 88*s, 88*s)
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
        tabs.forEach { textSize(it, 22f) }; textSize(lb, 23f); textSize(rb, 23f)
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
        renderProfile()
        adapter.notifyDataSetChanged()
    }

    private inner class CoversAdapter : RecyclerView.Adapter<CoverHolder>() {
        var items = emptyList<CatalogCard>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].key.fold(1125899906842597L) { hash, c -> hash*31+c.code }
        override fun getItemCount() = items.size
        fun submit(next: List<CatalogCard>, changes: CatalogChanges) {
            items=next
            if(changes.replace) notifyDataSetChanged()
            else changes.ranges.forEach { notifyItemRangeChanged(it.first,it.last-it.first+1,"metadata") }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CoverHolder(GameCard(context))
        override fun onBindViewHolder(holder: CoverHolder, position: Int) {
            val game = items[position]
            val card = holder.card
            if(card.layoutParams?.height!=coverHeight) card.layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, coverHeight)
            card.bind(game)
            card.nextFocusUpId = if (position < COLUMNS) tabs[activeTab.ordinal].id else View.NO_ID
            card.setOnFocusChangeListener { _, focused ->
                card.focused = focused
                if (focused) items.getOrNull(holder.bindingAdapterPosition)?.let(::updateSelected)
            }
            // Holders from the previous snapshot can still exist until the next layout.
            // Resolve the current position/binding instead of launching the captured old card.
            card.setOnClickListener {
                items.getOrNull(holder.bindingAdapterPosition)?.let { current -> updateSelected(current); playCard(current) }
            }
        }
        override fun onBindViewHolder(holder: CoverHolder, position: Int, payloads: MutableList<Any>) {
            when {
                payloads.isNotEmpty() && payloads.all { it=="favorite" } -> holder.card.bindFavorite(items[position])
                payloads.isNotEmpty() && payloads.all { it=="artwork" } -> if(holder.card.isAttachedToWindow) holder.card.bindArtwork(items[position])
                else -> onBindViewHolder(holder,position)
            }
        }
        override fun onViewAttachedToWindow(holder: CoverHolder) {
            val position=holder.bindingAdapterPosition
            if(position!=RecyclerView.NO_POSITION) holder.card.bindArtwork(items[position])
        }
        override fun onViewDetachedFromWindow(holder: CoverHolder) { posters.cancel(holder.card.image) }
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
        val image = FixedArtworkView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val badges = GameBadgesView(context)
        private val favorite = label(22f).apply {
            text = "★"; gravity = Gravity.CENTER; setTextColor(DashboardTheme.yellow)
            background = rounded(0xc0142018.toInt(), 6f)
        }
        var focused = false
            set(value) { field = value; invalidate() }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        private var geometryScale=-1f
        init {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            DashboardTheme.customFocus(this)
            clipChildren = false; setWillNotDraw(false)
            cover.addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cover.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cover.addView(badges)
            addView(cover); addView(favorite)
        }
        fun bind(game: CatalogCard) {
            bindFavorite(game)
            val name=game.name.uppercase(Locale.getDefault())
            if(fallback.text.toString()!=name) fallback.text=name
            if(isAttachedToWindow) bindArtwork(game)
            val s = scale
            badges.bind(game.gamePass, game.seriesOptimized, s)
            if(geometryScale==s) return
            geometryScale=s
            textSize(fallback,27f)
            badges.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (52*s).roundToInt(), Gravity.BOTTOM)
            cover.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins((10*s).roundToInt(), coverVerticalPadding.roundToInt(), (10*s).roundToInt(), coverVerticalPadding.roundToInt())
            }
            favorite.layoutParams = LayoutParams((31*s).roundToInt(), (31*s).roundToInt(), Gravity.TOP or Gravity.END)
                .apply { topMargin = (16*s).roundToInt(); marginEnd = (18*s).roundToInt() }
            textSize(favorite, 23f)
        }
        fun bindFavorite(game: CatalogCard) {
            val starred=game.game?.let { library.isFavorite(it.titleId) } == true
            contentDescription = game.name + if(starred) ", favorito" else ""
            favorite.visibility=if(starred) View.VISIBLE else View.GONE
        }
        fun bindArtwork(game: CatalogCard) {
            posters.load(image,game.posterUrl ?: game.tileUrl,coverArtSize.width,coverArtSize.height)
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
