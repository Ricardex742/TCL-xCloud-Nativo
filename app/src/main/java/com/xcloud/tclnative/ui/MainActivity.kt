package com.xcloud.tclnative.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.InputDevice
import android.hardware.input.InputManager
import android.content.pm.PackageManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xcloud.tclnative.settings.PlaybackSettings
import com.xcloud.tclnative.diagnostics.*
import com.xcloud.tclnative.ui.dashboard.SessionReportPanel
import com.xcloud.tclnative.settings.PlaybackSettingsStore
import com.xcloud.tclnative.settings.StatisticsVisibility
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.webrtc.ControllerRouter
import com.xcloud.tclnative.webrtc.ControllerRumble
import com.xcloud.tclnative.webrtc.GamepadState
import com.xcloud.tclnative.ui.dashboard.PlaybackSettingsPanel
import com.xcloud.tclnative.catalog.CatalogCache
import java.io.File
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.ui.dashboard.ConsoleLoginView
import com.xcloud.tclnative.ui.dashboard.ConsoleDialogs
import com.xcloud.tclnative.ui.dashboard.ConsoleAction
import com.xcloud.tclnative.catalog.CatalogService
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.home.GameLaunchLink
import com.xcloud.tclnative.home.PendingGameLaunch
import com.xcloud.tclnative.home.HomeShortcuts
import com.xcloud.tclnative.ui.dashboard.PosterLoader
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.XCloudWebRtcClient
import com.xcloud.tclnative.webrtc.ControllerShortcut
import com.xcloud.tclnative.webrtc.ShortcutUpdate
import com.xcloud.tclnative.webrtc.VideoRecoveryPolicy
import com.xcloud.tclnative.webrtc.VideoRecoveryAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView
    private lateinit var statsView: TextView
    private val statistics = StatisticsVisibility()
    private lateinit var settingsStore: PlaybackSettingsStore
    private var playbackSettings = PlaybackSettings()
    private lateinit var controlPanel: LinearLayout
    private lateinit var authenticator: XalAuthenticator
    private var webrtcClient: XCloudWebRtcClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameJob: Job? = null
    @Volatile private var playbackGeneration = 0
    private val controllers=ControllerRouter()
    private var gamepadState: GamepadState
        get()=controllers.physical(0)
        set(value) { controllers.update(0,value) }
    private val rumble by lazy { ControllerRumble(controllers::deviceFor) }
    private lateinit var touchView: GameTouchView
    private var currentTouchMode=TouchMode.OFF
    private val hudStore by lazy { com.xcloud.tclnative.touch.GameHudStore(this) }
    private var preferredControllerId: Int?=null
    private val deviceListener=object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) { removeController(deviceId) }
        override fun onInputDeviceRemoved(deviceId: Int) { removeController(deviceId) }
    }
    private var qrPollingJob: Job? = null
    private lateinit var queuePanel: GameLoadingView
    private var pendingLoadingGame: GameTitle? = null
    private var queueTicker: Job? = null
    private val controllerShortcut = ControllerShortcut()
    private var shortcutFlushJob: Job? = null
    private var videoRecoveryDialog: Dialog? = null
    private var dashboard: GameDashboardView? = null
    private var dashboardJob: Job? = null
    private var gameLibrary: GameLibrary? = null
    private var loginView: ConsoleLoginView? = null
    private var accountJob: Job? = null
    private var currentSignaling: XCloudSignalingClient? = null
    private var currentGames = emptyList<GameTitle>()
    private val catalogWork = com.xcloud.tclnative.catalog.CatalogWorkGate()
    private val streamHistory = com.xcloud.tclnative.webrtc.StreamDiagnosticHistory()
    private var reportTracker: SessionReportTracker?=null
    private val reportStore by lazy { SessionReportStore(applicationContext) }
    private var lastSessionReport: SessionReport?=null
    private var postSessionDialog: Dialog?=null
    @Volatile private var activityVisible=false
    private var activeGame: GameTitle? = null
    private var pendingLaunch = PendingGameLaunch()
    private val homeShortcuts by lazy { HomeShortcuts(this) }
    private val shortcutPosters by lazy { PosterLoader(this,mainScope) }
    private val homeJobs by lazy { com.xcloud.tclnative.home.HomePublicationJobs(mainScope) }
    private var shortcutInstallJob: Job? = null
    private var pendingShortcutApk: File? = null
    private var pendingHomeChannel: Long? = null
    private var lastShortcutRequest: String? = null
    private companion object {
        const val REQUEST_HOME_CHANNEL=4101
        const val REQUEST_SHORTCUT_INSTALL_PERMISSION=4102
    }
    private var gameMenuDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLaunch = PendingGameLaunch(savedInstanceState?.getString("pending_game"))
        lastShortcutRequest=savedInstanceState?.getString("last_shortcut_request")
        val shortcutRequest=intent.getStringExtra("shortcut_request")
        if (savedInstanceState == null || (shortcutRequest!=null && shortcutRequest!=lastShortcutRequest)) pendingLaunch.receive(intent)
        lastShortcutRequest=shortcutRequest ?: lastShortcutRequest
        pendingHomeChannel=savedInstanceState?.getLong("pending_home_channel",-1)?.takeIf { it>=0 }
        savedInstanceState?.getString("pending_shortcut_apk")?.takeIf {
            it.matches(Regex("com[.]xcloud[.]game[.]g[0-9a-f]{24}[.]apk"))
        }?.let { pendingShortcutApk=File(cacheDir,"game-shortcuts/$it").takeIf(File::isFile) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        authenticator = XalAuthenticator(this)
        settingsStore = PlaybackSettingsStore(this)
        playbackSettings = settingsStore.read()
        configureVideoOutput(playbackSettings)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceRenderer = AiPqSurfaceRenderer(this)
        surfaceRenderer.minimalPostProcessingRequested = playbackSettings.videoMode.lowLatency && Build.VERSION.SDK_INT >= 30
        surfaceRenderer.nativeUpscaling = playbackSettings.nativeUpscaling
        surfaceRenderer.aspect = playbackSettings.aspect
        rootLayout.addView(
            surfaceRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val androidVer = Build.VERSION.RELEASE
        val socModel = Build.HARDWARE
        hudTextView = TextView(this).apply {
            text = "xCLOUD [Android $androidVer | SoC: $socModel] | Aguardando sessão"
            setTextColor(Color.GREEN)
            textSize = 12f
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(20, 8, 20, 8)
        }

        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 12
        }
        rootLayout.addView(hudTextView, hudParams)

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 16, 16, 20))
            setPadding(36, 24, 36, 24)
        }

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            topMargin = 56
            bottomMargin = 16
            marginStart = 48
            marginEnd = 48
        }
        rootLayout.addView(controlPanel, panelParams)

        queuePanel = GameLoadingView(this) { cancelPlayback() }
        rootLayout.addView(queuePanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        statsView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(14, 10, 14, 10)
            visibility = View.GONE
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        rootLayout.addView(statsView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ).apply { topMargin = (56 * resources.displayMetrics.density).toInt(); marginStart = 12 })
        touchView=GameTouchView(this,
            onGamepad={ state -> controllers.virtual(state); if(currentTouchMode==TouchMode.VIRTUAL) publishGamepad(0) },
            onPointers={ events -> webrtcClient?.sendPointers(events) },
            coordinates=surfaceRenderer::touchCoordinates,
            onMenu={ showGameMenu() })
        touchView.onHudSaved={ settings ->
            val game=activeGame
            if(game==null) false else try { hudStore.save(game.titleId,settings); true }
            catch(error: java.io.IOException) { Toast.makeText(this,error.message,Toast.LENGTH_LONG).show(); false }
        }
        touchView.onBindingRequested={ id,action ->
            com.xcloud.tclnative.ui.dashboard.HudActionDialogs.action(this,action) { chosen ->
                if(chosen!=null) touchView.setEditedAction(id,chosen)
            }
        }
        touchView.onGesturesRequested={ id,gestures ->
            com.xcloud.tclnative.ui.dashboard.HudActionDialogs.gestures(this,gestures) { touchView.setEditedGestures(id,it) }
        }
        rootLayout.addView(touchView,FrameLayout.LayoutParams(-1,-1))
        setContentView(rootLayout)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(deviceListener,null)

        renderLoginMenu()
        val savedTicket = authenticator.getSavedRpsTicket()
        if (!savedTicket.isNullOrEmpty() && authenticator.supportsConsoleTransfer()) {
            connectToXboxCloud(savedTicket)
        } else if (!savedTicket.isNullOrEmpty()) {
            loginView?.showError("Atualize seu login pelo QR Code para conectar os jogos.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (pendingLaunch.receive(intent)) {
            setIntent(intent)
            lastShortcutRequest=intent.getStringExtra("shortcut_request")
            launchPendingShortcut()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_game",pendingLaunch.titleId)
        outState.putString("last_shortcut_request",lastShortcutRequest)
        outState.putString("pending_shortcut_apk",pendingShortcutApk?.name)
        outState.putLong("pending_home_channel",pendingHomeChannel ?: -1)
        super.onSaveInstanceState(outState)
    }

    private fun launchPendingShortcut() {
        val signaling = currentSignaling ?: return // Login/catalog loading retain the pending game.
        if (pendingLaunch.titleId == null) return
        val game = pendingLaunch.consume(currentGames)
        if (game == null) {
            ConsoleDialogs.message(this,"Atalho indisponível","Este jogo não está disponível no catálogo desta conta. Atualize o catálogo nas configurações.")
        } else if (activeGame?.titleId != game.titleId || gameJob?.isActive != true) {
            // TV shortcuts go directly to the queue; phone shortcuts use the saved input preference.
            val mode = if (playbackSettings.touchMode == TouchMode.AUTO &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) TouchMode.VIRTUAL else playbackSettings.touchMode
            launchCloudGame(signaling,game,mode)
        }
    }

    private fun configureVideoOutput(settings: PlaybackSettings) {
        if (Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(settings.videoMode.lowLatency)
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        if (uiMode.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            val display = windowManager.defaultDisplay
            fun mode(value: android.view.Display.Mode) = com.xcloud.tclnative.renderer.OutputMode(
                value.modeId,value.physicalWidth,value.physicalHeight,value.refreshRate)
            val id = if (settings.nativeUpscaling)
                com.xcloud.tclnative.renderer.DisplayModePolicy.preferred4k60(mode(display.mode),display.supportedModes.map(::mode))
            else 0
            if (id != null) window.attributes = window.attributes.apply { preferredDisplayModeId=id }
        }
    }

    private fun renderLoginMenu() {
        accountJob?.cancel()
        qrPollingJob?.cancel()
        dashboardJob?.cancel()
        dashboard?.close(); dashboard = null
        loginView?.close()
        gameLibrary = null
        currentSignaling = null
        currentGames = emptyList()
        controlPanel.removeAllViews()
        controlPanel.setPadding(0,0,0,0)
        controlPanel.setBackgroundColor(Color.TRANSPARENT)
        controlPanel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT)
        val view = ConsoleLoginView(this,
            signIn = { startQrCodeLoginFlow() },
            back = { if (loginView?.isQr == true) renderLoginMenu() else showExitDialog() },
            openApproval = { uri -> openAccountPage(uri) })
        loginView = view
        controlPanel.addView(view, LinearLayout.LayoutParams(-1,-1))
        controlPanel.visibility = View.VISIBLE
        hudTextView.visibility = View.GONE
    }

    private fun startQrCodeLoginFlow() {
        qrPollingJob?.cancel()
        val view = loginView ?: return
        view.beginQr()
        qrPollingJob = mainScope.launch {
            val qrRes = authenticator.requestDeviceCodeQr()
            qrRes.onSuccess { info ->
                if (loginView !== view) return@onSuccess
                view.showQr(info)
                authenticator.pollForDeviceCodeLogin(info).onSuccess { ticket ->
                    if (loginView === view) connectToXboxCloud(ticket)
                }.onFailure { error ->
                    if (loginView === view) view.showError("Não foi possível concluir o login. ${error.message.orEmpty()}")
                }
            }.onFailure { error ->
                if (loginView === view) view.showError("Não foi possível gerar o QR Code. ${error.message.orEmpty()}")
            }
        }
    }

    private fun connectToXboxCloud(rpsTicket: String) {
        accountJob?.cancel()
        val view = loginView ?: return
        view.showConnecting("Conectando sua conta ao Xbox…")
        accountJob = mainScope.launch {
            authenticator.authenticateToXCloud(rpsTicket).onSuccess { session ->
                try {
                    authenticator.getConsoleTransferToken()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (loginView === view) view.showError("Não foi possível autorizar os jogos: ${error.message}")
                    return@launch
                }
                if (loginView !== view) return@launch
                view.showConnecting("Conta conectada. Carregando seu catálogo…")
                renderGameDashboard(session)
            }.onFailure { error ->
                if (error is com.xcloud.tclnative.auth.AuthenticationRequiredException) authenticator.clearSession()
                if (loginView === view) view.showError("Falha na autenticação: ${error.message}")
            }
        }
    }

    private fun showExitDialog() {
        ConsoleDialogs.message(this,"Sair do aplicativo?",
            "Seu login e seus favoritos continuarão salvos neste aparelho.", listOf(
                ConsoleAction("Continuar") {}, ConsoleAction("Sair") { finish() }))
    }

    private fun renderGameDashboard(session: XCloudSession) {
        dashboardJob?.cancel()
        val signaling = XCloudSignalingClient(session, transferTokenProvider = { authenticator.getConsoleTransferToken() })
        dashboardJob = mainScope.launch {
            val allGames = try { signaling.fetchCatalogGames() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                loginView?.showError("Não foi possível carregar o catálogo. Tente novamente.")
                showCopyableErrorDialog("Catálogo", error.message ?: "Catálogo indisponível")
                return@launch
            }
            dashboard?.close()
            loginView?.close(); loginView = null
            val library = withContext(Dispatchers.IO) {
                GameLibrary(this@MainActivity, session.libraryAccountId, session.userHash)
            }
            gameLibrary = library
            val view = GameDashboardView(this@MainActivity, library,
                onPlay = { game -> chooseLaunchMode(signaling, game) },
                onSearch = { showCatalogSearch() },
                onOptions = { showSettings(session) },
                onExit = { showExitDialog() },
                onFavoritesChanged = { syncHomeShortcuts() })
            dashboard = view
            controlPanel.removeAllViews()
            controlPanel.setPadding(0, 0, 0, 0)
            controlPanel.setBackgroundColor(Color.TRANSPARENT)
            controlPanel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            controlPanel.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT))
            controlPanel.visibility = View.VISIBLE
            hudTextView.visibility = View.GONE
            view.setGames(allGames)
            view.restoreFocus()
            currentGames = allGames
            currentSignaling = signaling
            syncHomeShortcuts()
            launchPendingShortcut()
            val catalog = CatalogService(cache = CatalogCache(File(cacheDir, "catalog-art-v2.json")),beforeRequest=catalogWork::await)
            launch {
                catalog.hydrate(allGames, onProgress = { progress -> withContext(Dispatchers.Main) {
                    if (dashboard === view) view.setMetadataProgress(progress)
                } }) { hydrated -> withContext(Dispatchers.Main) {
                    if (dashboard === view) {
                        currentGames = hydrated
                        if(controlPanel.visibility==View.VISIBLE) view.setGames(hydrated)
                        refreshLoadingArt(); syncHomeShortcuts()
                    }
                } }
            }
            launch {
                val included = async { catalog.gallery(CatalogService.GAME_PASS) }
                val free = async { catalog.gallery(CatalogService.FREE_TO_PLAY) }
                val products = included.await()?.map(com.xcloud.tclnative.catalog.GamePassCatalog::productKey)?.toSet()
                val excluded = free.await()?.map(com.xcloud.tclnative.catalog.GamePassCatalog::productKey)?.toSet()
                // Product and edition lookup are both anchored to the official Game Pass gallery.
                val confirmed = if (products != null && excluded != null) products-excluded else null
                if (dashboard === view) { view.setGamePassProducts(confirmed); refreshLoadingArt() }
                if (!confirmed.isNullOrEmpty()) {
                    val known=allGames.mapNotNull { it.productId?.let(com.xcloud.tclnative.catalog.GamePassCatalog::productKey) }.toSet()
                    val resolved=signaling.resolveCatalogProducts(confirmed-known,catalogWork::await)
                    if (dashboard === view) { view.setGamePassProducts(confirmed,resolved); refreshLoadingArt() }
                }
            }
            launch {
                view.setAddedGallery(catalog.gallery(CatalogService.RECENTLY_ADDED))
            }
            launch {
                authenticator.loadXboxProfile().onSuccess { profile ->
                    if (dashboard === view) {
                        if (profile.xuid != null && (session.xuid == null || session.xuid == profile.xuid)) {
                            withContext(Dispatchers.IO) { library.bindXboxAccount(profile.xuid) }
                            view.refresh(); syncHomeShortcuts()
                        }
                        view.setProfile(profile)
                    }
                }
            }
        }
    }

    private fun showCatalogSearch() {
        val view = dashboard ?: return
        ConsoleDialogs.search(this, view.searchQuery) { query -> view.setSearch(query) }
    }

    private fun showSettings(session: XCloudSession) {
        PlaybackSettingsPanel(this,settingsStore,
            changed = ::applyPlaybackSettings,
            subscription = { openAccountPage("https://account.microsoft.com/services") },
            refresh = { renderGameDashboard(session) },
            logout = { authenticator.clearSession(); renderLoginMenu(); syncHomeShortcuts() },
            testRumble = { showRumbleDiagnostics { showSettings(session) } },
            homeShortcuts = { showHomeShortcuts() },lastReport={ showLatestReport() }).show()
    }
    private fun showLatestReport() {
        val report=lastSessionReport ?: reportStore.read()
        if(report==null) ConsoleDialogs.message(this,"Último diagnóstico","Ainda não há uma partida com vídeo para mostrar.")
        else postSessionDialog=SessionReportPanel.show(this,report)
    }
    private suspend fun finishSessionReport(tracker: SessionReportTracker) {
        val report=tracker.takeReport()
        if(report==null) { if(reportTracker===tracker) reportTracker=null; return }
        lastSessionReport=report
        try { withContext(Dispatchers.IO) { reportStore.save(report) } }
        catch(error: java.io.IOException) { if(!isFinishing && !isDestroyed) Toast.makeText(this,error.message,Toast.LENGTH_LONG).show() }
        val current=reportTracker===tracker
        if(current) reportTracker=null
        if(current && activityVisible && !isFinishing && !isDestroyed && activeGame==null &&
            controlPanel.visibility==View.VISIBLE && settingsStore.read().sessionReport &&
            (report.durationMs>=5000 || report.end==SessionEnd.FAILURE)) {
            postSessionDialog?.dismiss()
            postSessionDialog=SessionReportPanel.show(this,report) {
                val updated=settingsStore.read().copy(sessionReport=false)
                settingsStore.save(updated); playbackSettings=updated
            }
        }
    }
    private fun syncHomeShortcuts(requestVisibility: Boolean = false) {
        if (!homeShortcuts.supportsChannel || !homeShortcuts.enabled) return
        homeJobs.schedule(requestVisibility) {
            try {
                val favorites=currentGames.filter { gameLibrary?.isFavorite(it.titleId)==true }
                val channel=withContext(Dispatchers.IO) { homeShortcuts.sync(favorites) }
                if(requestVisibility && channel!=null) requestHomeChannel(channel)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                if(requestVisibility) ConsoleDialogs.message(this@MainActivity,"Canal da tela inicial",
                    "A TV não autorizou o canal de favoritos. Use Instalar atalho na TV para criar entradas individuais dos jogos.")
            }
        }
    }
    private fun requestHomeChannel(channel: Long) {
        pendingHomeChannel=channel
        val request=homeShortcuts.browsableRequest(channel) ?: return
        if(request.resolveActivity(packageManager)!=null) startActivityForResult(request,REQUEST_HOME_CHANNEL)
        else {
            homeShortcuts.requestVisible(channel)
            verifyHomeChannel(channel)
        }
    }
    private fun verifyHomeChannel(channel: Long) {
        mainScope.launch {
            val visible=withContext(Dispatchers.IO) { runCatching { homeShortcuts.isBrowsable(channel) }.getOrDefault(false) }
            pendingHomeChannel=null
            ConsoleDialogs.message(this@MainActivity,"Canal de favoritos",if(visible)
                "O sistema autorizou o canal Favoritos xCLOUD. Sua exibição depende da tela inicial da TV. Para criar um aplicativo de acesso direto, use Instalar atalho na TV."
                else "O canal não ficou visível na tela inicial. Use Instalar atalho na TV: o jogo aparecerá em Aplicativos, inclusive no Google TV.")
        }
    }
    private fun showHomeShortcuts() {
        val favorites=currentGames.filter { gameLibrary?.isFavorite(it.titleId)==true }
        val actions=mutableListOf<ConsoleAction>()
        favorites.forEach { game ->
            actions+=ConsoleAction("Instalar atalho na TV: ${game.name}") { createInstalledShortcut(game) }
        }
        if(homeShortcuts.supportsChannel) {
            actions+=ConsoleAction("Solicitar canal de favoritos na tela inicial") {
                if(favorites.isEmpty()) ConsoleDialogs.message(this,"Seus favoritos","Adicione jogos com Y antes de criar os atalhos.")
                else { homeShortcuts.enable(true); syncHomeShortcuts(requestVisibility=true) }
            }
            if(homeShortcuts.enabled) actions+=ConsoleAction("Remover canal de favoritos") {
                homeShortcuts.enable(false); homeJobs.cancel()
                homeJobs.schedule(true) { withContext(Dispatchers.IO) { runCatching { homeShortcuts.sync(emptyList()) } } }
            }
        }
        if(homeShortcuts.supportsPin) favorites.forEach { game ->
            actions+=ConsoleAction("Pedir atalho ao launcher: ${game.name}") {
                val image=ImageView(this)
                shortcutPosters.load(image,game.tileUrl ?: game.posterUrl,192,192,notifyPlaceholder=false) {
                    val accepted=homeShortcuts.pin(game,(image.drawable as? BitmapDrawable)?.bitmap)
                    Toast.makeText(this,if(accepted) "Pedido enviado ao launcher. Confirme na tela inicial." else "O launcher não aceitou o pedido.",Toast.LENGTH_LONG).show()
                }
            }
        }
        if(actions.isEmpty()) ConsoleDialogs.message(this,"Atalhos dos favoritos","Adicione jogos aos favoritos com Y para criar seus atalhos.")
        else ConsoleDialogs.menu(this,"Atalhos dos favoritos",actions)
    }
    private fun createInstalledShortcut(game: GameTitle) {
        if(shortcutInstallJob?.isActive==true) return
        shortcutInstallJob=mainScope.launch {
            val image=ImageView(this@MainActivity)
            val waiting=ConsoleDialogs.message(this@MainActivity,"Criando atalho",game.name,
                listOf(ConsoleAction("Cancelar") { shortcutInstallJob?.cancel() }),onCancel={ shortcutInstallJob?.cancel() })
            try {
                val artwork=CompletableDeferred<android.graphics.Bitmap?>()
                shortcutPosters.load(image,game.tileUrl ?: game.posterUrl,256,256,notifyPlaceholder=false) {
                    artwork.complete((image.drawable as? BitmapDrawable)?.bitmap)
                }
                val bitmap=artwork.await()
                val apk=withContext(Dispatchers.IO) { com.xcloud.tclnative.home.GameShortcutInstaller(this@MainActivity).create(game,bitmap) }
                waiting.dismiss()
                pendingShortcutApk=apk
                ConsoleDialogs.message(this@MainActivity,"Instalar atalho: ${game.name}",
                    "Será instalado um pequeno aplicativo que abre este jogo no xCLOUD. Depois, procure o jogo em Aplicativos. No Google TV, adicione-o à fileira de apps favoritos pela própria tela inicial.",
                    listOf(ConsoleAction("Instalar atalho") { installPendingShortcut() },ConsoleAction("Cancelar") { pendingShortcutApk=null }),
                    onCancel={ pendingShortcutApk=null })
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { showCopyableErrorDialog("Atalho do jogo",error.message ?: "Não foi possível criar o atalho neste aparelho.") }
            finally { shortcutPosters.cancel(image); waiting.dismiss() }
        }
    }
    private fun installPendingShortcut() {
        val apk=pendingShortcutApk?.takeIf(File::isFile) ?: return
        if(Build.VERSION.SDK_INT>=26 && !packageManager.canRequestPackageInstalls()) {
            try { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")),REQUEST_SHORTCUT_INSTALL_PERMISSION) }
            catch(_: android.content.ActivityNotFoundException) { ConsoleDialogs.message(this,"Permitir instalação","Nas configurações da TV, permita que o xCLOUD instale os atalhos dos jogos.") }
            return
        }
        val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.shortcuts",apk)
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch(_: android.content.ActivityNotFoundException) { ConsoleDialogs.message(this,"Instalador indisponível","A TV não disponibilizou um instalador de aplicativos para o atalho.") }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==REQUEST_HOME_CHANNEL) pendingHomeChannel?.let(::verifyHomeChannel)
        if(requestCode==REQUEST_SHORTCUT_INSTALL_PERMISSION) {
            if(Build.VERSION.SDK_INT<26 || packageManager.canRequestPackageInstalls()) installPendingShortcut()
            else ConsoleDialogs.message(this,"Instalação não autorizada","O atalho foi criado, mas a permissão de instalação não foi concedida pela TV.")
        }
    }
    private fun applyPlaybackSettings(settings: PlaybackSettings) {
        playbackSettings=settings
        surfaceRenderer.setImageAdjustments(settings.sharpness,settings.saturation)
    }
    private fun showRumbleDiagnostics(back: () -> Unit) {
        ConsoleDialogs.message(this,"Vibração do controle",rumble.describe(),listOf(
            ConsoleAction("Testar agora") { rumble.test(); showRumbleDiagnostics(back) },ConsoleAction("Voltar",back)),onCancel=back)
    }
    private fun showGameMenu() {
        if (gameMenuDialog?.isShowing == true || webrtcClient == null || queuePanel.visibility == View.VISIBLE) return
        resetController()
        gameMenuDialog = com.xcloud.tclnative.ui.dashboard.GameMenuPanel.show(this,
            image={ PlaybackSettingsPanel(this,settingsStore,::applyPlaybackSettings,{}, {}, {}).showImageAdjustments(onBack={ showGameMenu() }) },
            statistics={ cycleStatistics() },
            diagnostics={ showCopyableErrorDialog("Vídeo","${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} | ${Build.HARDWARE}\n${surfaceRenderer.diagnostics()}\n${streamHistory.report()}") },
            disconnect={ cancelPlayback() },
            hud={ showGameHudSettings() }).also { dialog ->
                dialog.setOnDismissListener { if(gameMenuDialog===dialog) gameMenuDialog=null }
            }
    }
    private fun enableGameHud(): Boolean {
        val game=activeGame ?: return false
        if(currentTouchMode==TouchMode.NATIVE && webrtcClient?.setNativeTouchEnabled(false)!=true) {
            Toast.makeText(this,"Aguarde a conexão dos controles para ativar o HUD.",Toast.LENGTH_LONG).show()
            return false
        }
        if(currentTouchMode!=TouchMode.VIRTUAL) {
            resetController(); currentTouchMode=TouchMode.VIRTUAL
            touchView.configure(TouchMode.VIRTUAL,game,hudStore.read(game.titleId))
            rumble.enabled=playbackSettings.rumble
        }
        return true
    }
    private fun showGameHudSettings() {
        val game=activeGame ?: return
        if(!enableGameHud()) return
        com.xcloud.tclnative.ui.dashboard.GameHudPanel(this,game,hudStore,
            changed={ touchView.applyHud(it) },edit={ touchView.beginEditing() },
            hide={ resetController(); currentTouchMode=TouchMode.OFF; touchView.configure(TouchMode.OFF,game); rumble.enabled=playbackSettings.rumble },
            page={ touchView.showPage(it) }).show()
    }
    private fun chooseLaunchMode(signaling: XCloudSignalingClient,game: GameTitle) {
        if(playbackSettings.touchMode==TouchMode.AUTO && packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            val layout=hudStore.read(game.titleId).preset ?: com.xcloud.tclnative.touch.GameHud.automatic(game.titleId,game.name)
            val choices=mutableListOf(
                ConsoleAction("Jogar com toque • HUD ${layout.label}") { launchCloudGame(signaling,game,TouchMode.VIRTUAL) },
                ConsoleAction("Jogar com controle") { launchCloudGame(signaling,game,TouchMode.OFF) }
            )
            if("NativeTouch" in game.supportedInputTypes) choices.add(ConsoleAction("Toque nativo Xbox • experimental") { launchCloudGame(signaling,game,TouchMode.NATIVE) })
            ConsoleDialogs.menu(this,game.name,choices)
        } else launchCloudGame(signaling,game)
    }

    private fun openAccountPage(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) {
            ConsoleDialogs.message(this,"Acesse pelo celular",url)
        }
    }

    private fun restoreDashboard() {
        catalogWork.resume()
        dashboard?.setGames(currentGames)
        dashboard?.resumeArtwork()
        controlPanel.visibility = View.VISIBLE
        dashboard?.let { view -> hudTextView.visibility = View.GONE; view.restoreFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (queuePanel.visibility == View.VISIBLE) {
            val key = event.keyCode
            if (key in setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) cancelPlayback()
                    else queuePanel.cancelFromController()
                }
                return true
            }
        }
        val login = loginView
        if (login != null && controlPanel.visibility == View.VISIBLE && login.handleKey(event)) return true
        val view = dashboard
        if (view != null && controlPanel.visibility == View.VISIBLE && queuePanel.visibility != View.VISIBLE) {
            if(event.action==KeyEvent.ACTION_DOWN && (event.source and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD ||
                event.source and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK)) preferredControllerId=event.deviceId
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) view.back()
                return true
            }
            if (view.handleKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun launchCloudGame(signaling: XCloudSignalingClient, game: GameTitle, touchChoice: TouchMode? = null) {
        val titleId = game.titleId
        val gameName = game.name
        activeGame = game
        val settings = playbackSettings
        val compatible=settings.videoMode==com.xcloud.tclnative.settings.VideoMode.COMPATIBILITY
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.REPLACED,webrtcClient?.currentInputMetrics())
        postSessionDialog?.dismiss()
        val tracker=SessionReportTracker(gameName,"${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}",
            "${settings.resolution.label} • ${settings.videoMode.label}",if(compatible) 30 else 60)
        tracker.setVisible(activityVisible); reportTracker=tracker
        streamHistory.clear()
        catalogWork.pause()
        dashboard?.pauseArtwork()
        val choice=touchChoice ?: settings.touchMode
        val nativeSupported="NativeTouch" in game.supportedInputTypes
        val touchMode=when(choice) {
            TouchMode.AUTO -> TouchMode.OFF
            TouchMode.NATIVE -> if(nativeSupported) TouchMode.NATIVE else TouchMode.VIRTUAL
            else -> choice
        }
        if(choice==TouchMode.NATIVE && !nativeSupported) Toast.makeText(this,"Controle virtual ativado: este jogo não informou suporte a toque nativo.",Toast.LENGTH_LONG).show()
        currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF)
        statistics.beginSession()
        configureVideoOutput(settings)
        val previous = gameJob
        previous?.cancel()
        val generation = ++playbackGeneration
        controlPanel.visibility = View.GONE
        statsView.visibility = View.GONE
        statsView.text = "Estatísticas: aguardando amostras WebRTC..."
        hudTextView.visibility = View.GONE
        beginLoading(game)
        hudTextView.text = "PLAY: Solicitando sessão para [$gameName]. Voltar cancela."
        hudTextView.setTextColor(Color.CYAN)
        gameJob = mainScope.launch {
            previous?.join()
            if (generation != playbackGeneration) return@launch
            val firstFrame = CompletableDeferred<Unit>()
            val transportFailure = CompletableDeferred<Exception>()
            resetController()
            controllers.beginSession(settings.localCoOp,touchMode!=TouchMode.OFF)
            InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter {
                it.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD || it.sources and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK
            }.sortedWith(compareBy<InputDevice> { it.id!=preferredControllerId }.thenBy { it.id }).forEach { controllers.slot(it.id) }
            rumble.enabled=settings.rumble
            surfaceRenderer.compatibility=compatible
            surfaceRenderer.nativeUpscaling = settings.nativeUpscaling
            surfaceRenderer.aspect = settings.aspect
            surfaceRenderer.setImageAdjustments(settings.sharpness,settings.saturation)
            surfaceRenderer.minimalPostProcessingRequested = settings.videoMode.lowLatency && Build.VERSION.SDK_INT >= 30
            surfaceRenderer.prepareForSession(settings.resolution.width,settings.resolution.height)
            surfaceRenderer.onFirstFrameRendered = {
                if (generation == playbackGeneration && !firstFrame.isCompleted) {
                    tracker.begin(android.os.SystemClock.elapsedRealtime(),NetworkSnapshot.read(this@MainActivity))
                    firstFrame.complete(Unit)
                    finishLoading()
                    currentTouchMode=touchMode
                    touchView.configure(touchMode,game,hudStore.read(titleId))
                    hudTextView.visibility = View.GONE
                    gameLibrary?.recordPlayed(titleId)
                    hudTextView.text = "Vídeo nativo ativo [$gameName]"
                    hudTextView.setTextColor(Color.GREEN)
                    statsView.visibility = if (statistics.mode != 0) View.VISIBLE else View.GONE
                }
            }
            surfaceRenderer.onRenderError = { error -> transportFailure.complete(error) }
            val rtc = XCloudWebRtcClient(this@MainActivity, surfaceRenderer.eglBase,
                onVideoTrackReady = { track ->
                    runOnUiThread { if (generation == playbackGeneration) surfaceRenderer.attachVideoTrack(track) }
                },
                onConnectionStateChanged = { state -> runOnUiThread {
                    if (generation == playbackGeneration) {
                        if (state.contains("FAILED") || state.contains("CLOSED") || state.startsWith("CHANNELS:")) {
                            transportFailure.complete(java.io.IOException(state))
                        }
                        if (!firstFrame.isCompleted) hudTextView.text = state
                    }
                } },
                onStatistics = stats@ { metrics, input ->
                    if(generation==playbackGeneration) {
                        val now=android.os.SystemClock.elapsedRealtime()
                        streamHistory.add(now,metrics,input); tracker.add(now,metrics,input)
                    }
                    if (statistics.mode == 0) return@stats
                    val complete = statistics.mode == 2
                    val text = if (complete) metrics.overlay(input, "",settings.resolution,compatible) else metrics.compactOverlay(input)
                    runOnUiThread {
                        if (generation == playbackGeneration) {
                            statsView.text = text + if (complete) surfaceRenderer.diagnostics() + "\n" +
                                (webrtcClient?.latencyDiagnostics() ?: "") else surfaceRenderer.compactDiagnostics()
                            statsView.visibility = if (firstFrame.isCompleted && statistics.mode != 0) View.VISIBLE else View.GONE
                        }
                    }
                }, lowLatencyVideo = settings.videoMode.lowLatency, streamResolution = settings.resolution,
                nativeTouch = touchMode==TouchMode.NATIVE,
                onRumble = { command -> if(generation==playbackGeneration) rumble.play(command) },compatibility=compatible)
            webrtcClient = rtc
            if(touchMode!=TouchMode.NATIVE) (0..3).filter { controllers.deviceFor(it)!=null }.forEach { publishGamepad(it) }
            var failed=false
            try {
                signaling.startAndConnectWebRtcSession(titleId, rtc, resolution = settings.resolution, nativeTouch=touchMode==TouchMode.NATIVE, onQueueStatus = { status -> runOnUiThread {
                    if (generation == playbackGeneration) showQueue(status)
                } }) { progress -> runOnUiThread {
                    if (generation == playbackGeneration && !firstFrame.isCompleted) {
                        queuePanel.updateProgress(progress)
                        hudTextView.text = progress
                        hudTextView.setTextColor(Color.YELLOW)
                    }
                } }.getOrThrow()
                if (touchMode == TouchMode.NATIVE) rtc.setNativeTouchEnabled(true)
                if (!firstFrame.isCompleted) queuePanel.updateProgress("VIDEO:")
                if (withTimeoutOrNull(20_000) {
                    kotlinx.coroutines.selects.select<Unit> {
                        firstFrame.onAwait { }
                        transportFailure.onAwait { throw it }
                    }
                    true
                } != true) {
                    throw java.io.IOException("VIDEO: transporte conectado, mas nenhum quadro foi renderizado em 20s.")
                }
                val recovery = VideoRecoveryPolicy(compatible)
                var lastToken = -1L
                var recovering = false
                var nextKeepAlive = android.os.SystemClock.elapsedRealtime() + 30_000
                while (isActive) {
                    withTimeoutOrNull(1000) { transportFailure.await() }?.let { throw it }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val health = surfaceRenderer.probeVideoHealth()
                    val receiving=streamHistory.receivingVideo(now)
                    val action = recovery.observe(now, health.presentedToken, health.deliveredRecently,receiving)
                    tracker.observe(now,health.presentedToken,receiving,action)
                    if (health.presentedToken != lastToken && recovering) {
                        recovering = false
                        hudTextView.visibility = View.GONE
                        videoRecoveryDialog?.dismiss()
                        hudTextView.text = "Vídeo nativo ativo [$gameName]"
                        hudTextView.setTextColor(Color.GREEN)
                    }
                    lastToken = health.presentedToken
                    when (action) {
                        VideoRecoveryAction.NONE -> Unit
                        VideoRecoveryAction.REQUEST_KEYFRAME -> {
                            recovering = true
                            hudTextView.visibility = View.VISIBLE
                            hudTextView.text = "Vídeo sem novos quadros. Solicitando atualização da imagem…"
                            hudTextView.setTextColor(Color.YELLOW)
                            rtc.requestVideoKeyframe()
                        }
                        VideoRecoveryAction.RESET_DECODER -> {
                            hudTextView.text = "Recuperando vídeo: reiniciando decoder no próximo quadro-chave…"
                            rtc.recoverVideoDecoder()
                        }
                        VideoRecoveryAction.REBUILD_SURFACE -> {
                            hudTextView.text = "Recuperando vídeo: recriando superfície de exibição…"
                            surfaceRenderer.rebuildSurface()
                            rtc.requestVideoKeyframe()
                        }
                        VideoRecoveryAction.OFFER_RETRY -> {
                            hudTextView.text = "O vídeo ainda não voltou. A sessão Xbox continua aberta."
                            resetController()
                            val dialog = ConsoleDialogs.message(this@MainActivity,"Imagem sem atualização",
                                "A imagem não voltou após 30 segundos. Recuperar o vídeo mantém a partida aberta. Voltar ao catálogo encerra a sessão.",
                                listOf(ConsoleAction("Recuperar vídeo") {
                                    recovery.retry(android.os.SystemClock.elapsedRealtime())
                                    if (surfaceRenderer.probeVideoHealth().deliveredRecently) surfaceRenderer.rebuildSurface()
                                    rtc.recoverVideoDecoder()
                                }, ConsoleAction("Voltar ao catálogo") { cancelPlayback() }),
                                onCancel = { cancelPlayback() })
                            videoRecoveryDialog = dialog
                            dialog.setOnDismissListener { if (videoRecoveryDialog === dialog) videoRecoveryDialog = null }
                        }
                    }
                    if (now >= nextKeepAlive) {
                        signaling.keepAlive()
                        nextKeepAlive = android.os.SystemClock.elapsedRealtime() + 30_000
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed=true
                if (generation == playbackGeneration && (!firstFrame.isCompleted || !settingsStore.read().sessionReport)) {
                    val details = error.message ?: "Falha na sessão"
                    hudTextView.text = details
                    hudTextView.setTextColor(Color.RED)
                    showCopyableErrorDialog(gameName, details)
                }
            } finally {
                // The server-side Xbox and the local decoder must both be released before retrying.
                withContext(NonCancellable) {
                    tracker.finish(android.os.SystemClock.elapsedRealtime(),if(failed) SessionEnd.FAILURE else SessionEnd.USER,rtc.currentInputMetrics())
                    if (generation == playbackGeneration) {
                        finishLoading()
                        statsView.visibility = View.GONE
                    }
                    videoRecoveryDialog?.dismiss()
                    resetController()
                    currentTouchMode=TouchMode.OFF
                    touchView.configure(TouchMode.OFF)
                    surfaceRenderer.clearSession()
                    rtc.close()
                    if (webrtcClient === rtc) webrtcClient = null
                    signaling.stop()
                    if (generation == playbackGeneration) { activeGame=null; restoreDashboard() }
                    finishSessionReport(tracker)
                }
            }
        }
    }

    private fun beginLoading(game: GameTitle) {
        pendingLoadingGame = game
        queuePanel.begin(game)
        queueTicker?.cancel()
        queueTicker = mainScope.launch {
            while (isActive && pendingLoadingGame != null) {
                queuePanel.render(System.nanoTime()/1_000_000)
                delay(1000)
            }
        }
    }
    private fun refreshLoadingArt() {
        val pending = pendingLoadingGame ?: return
        dashboard?.gameById(pending.titleId)?.let { game -> pendingLoadingGame = game; queuePanel.setGame(game) }
    }
    private fun finishLoading() {
        pendingLoadingGame = null
        queueTicker?.cancel(); queueTicker = null
        queuePanel.dismiss()
    }
    private fun showQueue(status: com.xcloud.tclnative.signaling.QueueStatus?) {
        if (pendingLoadingGame != null) queuePanel.updateQueue(status)
    }

    private fun cancelPlayback() {
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.USER,webrtcClient?.currentInputMetrics())
        ++playbackGeneration
        gameJob?.cancel()
        activeGame = null
        gameMenuDialog?.dismiss()
        videoRecoveryDialog?.dismiss()
        resetController()
        currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF)
        finishLoading()
        statsView.visibility = View.GONE
        hudTextView.text = "Sessão cancelada. Selecione um jogo."
        hudTextView.visibility = View.VISIBLE
        restoreDashboard()
    }

    private fun showCopyableErrorDialog(gameName: String, fullError: String) {
        ConsoleDialogs.message(this,"Diagnóstico de sessão ($gameName)",fullError,listOf(
            ConsoleAction("Copiar detalhes") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("xCloud Error", fullError))
                Toast.makeText(this,"Detalhes copiados!",Toast.LENGTH_LONG).show()
            }, ConsoleAction("Fechar") {}))
    }

    private fun gameplayInputEnabled() = controlPanel.visibility == View.GONE &&
        queuePanel.visibility != View.VISIBLE && videoRecoveryDialog?.isShowing != true && webrtcClient != null && !touchView.isEditing && hasWindowFocus()

    private fun publishGamepad(slot: Int) { webrtcClient?.updateGamepad(controllers.combined(slot),slot) }
    private fun controllerSlot(deviceId: Int,source: Int): Int? =
        if(source and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD || source and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK)
            controllers.slot(deviceId) else 0
    private fun removeController(deviceId: Int) {
        val slot=controllers.remove(deviceId) ?: return
        if(slot==0) { shortcutFlushJob?.cancel(); controllerShortcut.reset() }
        webrtcClient?.removeGamepad(slot)
        if(slot==0 && currentTouchMode==TouchMode.VIRTUAL) publishGamepad(0)
        rumble.stop()
    }

    private fun cycleStatistics() {
        statistics.cycle()
        statsView.visibility = if (statistics.mode != 0) View.VISIBLE else View.GONE
    }

    private fun applyShortcut(update: ShortcutUpdate) {
        val base = gamepadState.buttons and ControllerShortcut.BOTH.inv()
        if (update.pulse != 0) {
            webrtcClient?.updateGamepad(controllers.combined(0,gamepadState.copy(buttons = base or update.forwardedButtons or update.pulse)),0)
        }
        gamepadState = gamepadState.copy(buttons = base or update.forwardedButtons)
        publishGamepad(0)
        if (update.openMenu) showGameMenu()
    }

    private fun handleControllerShortcut(mask: Int, down: Boolean): Boolean {
        if (mask != ControllerShortcut.START && mask != ControllerShortcut.SELECT) return false
        shortcutFlushJob?.cancel()
        applyShortcut(controllerShortcut.change(mask, down, android.os.SystemClock.elapsedRealtime()))
        controllerShortcut.flushAtMs?.let { deadline ->
            shortcutFlushJob = mainScope.launch {
                delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (gameplayInputEnabled()) applyShortcut(controllerShortcut.flush(android.os.SystemClock.elapsedRealtime()))
            }
        }
        return true
    }

    private fun resetController() {
        shortcutFlushJob?.cancel()
        shortcutFlushJob = null
        controllerShortcut.reset()
        if(::touchView.isInitialized) touchView.releaseInputs()
        controllers.reset()
        webrtcClient?.resetInput()
        rumble.enabled=false; rumble.stop()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gameplayInputEnabled() &&
            event.source and android.view.InputDevice.SOURCE_JOYSTICK != 0 && event.action == MotionEvent.ACTION_MOVE) {
            val slot=controllerSlot(event.deviceId,event.source) ?: return true
            val previous=controllers.physical(slot)
            fun range(id: Int): com.xcloud.tclnative.webrtc.ControllerAxisRange? =
                event.device?.getMotionRange(id, event.source)?.let {
                    com.xcloud.tclnative.webrtc.ControllerAxisRange(it.min, it.max, it.flat)
                }
            fun stick(id: Int, hardwareDeadzone: Boolean = false) = com.xcloud.tclnative.webrtc.ControllerAxes.stick(
                event.getAxisValue(id), range(id), hardwareDeadzone)
            fun trigger(primary: Int, fallback: Int): Float {
                fun read(id: Int): Float {
                    val limits = range(id)
                    return com.xcloud.tclnative.webrtc.ControllerAxes.trigger(event.getAxisValue(id), limits)
                }
                return maxOf(read(primary), read(fallback))
            }
            var buttons = previous.buttons and 0xF0FF
            val x = stick(MotionEvent.AXIS_HAT_X); val y = stick(MotionEvent.AXIS_HAT_Y)
            if (y < -0.5f) buttons = buttons or 256
            if (y > 0.5f) buttons = buttons or 512
            if (x < -0.5f) buttons = buttons or 1024
            if (x > 0.5f) buttons = buttons or 2048
            val rightX = if (range(MotionEvent.AXIS_Z) == null && range(MotionEvent.AXIS_RX) != null) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
            val rightY = if (range(MotionEvent.AXIS_RZ) == null && range(MotionEvent.AXIS_RY) != null) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ
            controllers.update(slot,previous.copy(buttons = buttons,
                leftX = stick(MotionEvent.AXIS_X, true), leftY = stick(MotionEvent.AXIS_Y, true),
                rightX = stick(rightX), rightY = stick(rightY),
                leftTrigger = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
                rightTrigger = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)))
            publishGamepad(slot)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun gamepadButton(key: Int): Int = when (key) {
        KeyEvent.KEYCODE_BUTTON_MODE -> 2
        KeyEvent.KEYCODE_BUTTON_START -> 4
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> 16
        KeyEvent.KEYCODE_BUTTON_B -> 32
        KeyEvent.KEYCODE_BUTTON_X -> 64
        KeyEvent.KEYCODE_BUTTON_Y -> 128
        KeyEvent.KEYCODE_DPAD_UP -> 256
        KeyEvent.KEYCODE_DPAD_DOWN -> 512
        KeyEvent.KEYCODE_DPAD_LEFT -> 1024
        KeyEvent.KEYCODE_DPAD_RIGHT -> 2048
        KeyEvent.KEYCODE_BUTTON_L1 -> 4096
        KeyEvent.KEYCODE_BUTTON_R1 -> 8192
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 16384
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 32768
        else -> 0
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mask = gamepadButton(keyCode)
        if (mask != 0 && gameplayInputEnabled()) {
            val slot=controllerSlot(event.deviceId,event.source) ?: return true
            if (slot==0 && handleControllerShortcut(mask, false)) return true
            controllers.update(slot,controllers.physical(slot).copy(buttons=controllers.physical(slot).buttons and mask.inv()))
            publishGamepad(slot)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) resetController()
        else {
            rumble.enabled=playbackSettings.rumble && webrtcClient!=null
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(::touchView.isInitialized && touchView.isEditing && (keyCode==KeyEvent.KEYCODE_BACK || keyCode==KeyEvent.KEYCODE_BUTTON_B)) {
            if(event.repeatCount==0) touchView.cancelEditing()
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_F10) &&
            controlPanel.visibility == View.GONE && queuePanel.visibility != View.VISIBLE) {
            if (event.repeatCount == 0) {
                cycleStatistics()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel.visibility == View.GONE) {
            if(touchView.isEditing) { touchView.cancelEditing(); return true }
            if (queuePanel.visibility == View.VISIBLE) cancelPlayback() else if(event.repeatCount == 0) showGameMenu()
            return true
        }
        val mask = gamepadButton(keyCode)
        if (mask != 0 && gameplayInputEnabled()) {
            val slot=controllerSlot(event.deviceId,event.source) ?: return true
            if (slot==0 && handleControllerShortcut(mask, true)) return true
            controllers.update(slot,controllers.physical(slot).copy(buttons=controllers.physical(slot).buttons or mask))
            publishGamepad(slot)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() { super.onStart(); activityVisible=true; reportTracker?.setVisible(true) }
    override fun onStop() { activityVisible=false; reportTracker?.setVisible(false); super.onStop() }

    override fun onDestroy() {
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.APP_CLOSED,webrtcClient?.currentInputMetrics())
        postSessionDialog?.dismiss()
        ++playbackGeneration
        mainScope.cancel()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(deviceListener)
        rumble.enabled=false; rumble.stop()
        dashboard?.close()
        shortcutPosters.close()
        gameMenuDialog?.dismiss()
        loginView?.close()
        queuePanel.close()
        videoRecoveryDialog?.dismiss()
        surfaceRenderer.clearSession()
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
