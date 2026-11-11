package com.xcloud.tclnative.ui

import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.*
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
import androidx.core.view.doOnPreDraw
import kotlin.coroutines.resume
import com.xcloud.tclnative.diagnostics.*
import com.xcloud.tclnative.ui.dashboard.SessionReportPanel
import com.xcloud.tclnative.ui.dashboard.SessionTransitionView
import com.xcloud.tclnative.ui.dashboard.PlaybackSettingsPanel
import com.xcloud.tclnative.ui.dashboard.SettingsPage
import com.xcloud.tclnative.catalog.CatalogCard
import com.xcloud.tclnative.catalog.OfficialGalleryCards
import com.xcloud.tclnative.catalog.CatalogCache
import java.io.File
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.ui.dashboard.ConsoleLoginView
import com.xcloud.tclnative.ui.dashboard.ConsoleDialogs
import com.xcloud.tclnative.ui.dashboard.ConsoleAction
import com.xcloud.tclnative.catalog.CatalogService
import com.xcloud.tclnative.catalog.GalleryProduct
import com.xcloud.tclnative.catalog.OfficialGalleryKind
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.launch

open class MainActivity : Activity() {
    protected open fun createAuthenticator() = XalAuthenticator(this)
    protected open fun createCatalogService(cache: CatalogCache? = null, beforeRequest: suspend () -> Unit = {}) =
        CatalogService(cache=cache,beforeRequest=beforeRequest)

    private lateinit var rootLayout: FrameLayout
    private val playbackDisplay by lazy { com.xcloud.tclnative.renderer.PlaybackDisplayPreference(window) }
    private val joystickDispatch by lazy { com.xcloud.tclnative.webrtc.JoystickDispatchPreference(window.decorView) }
    private var immediateJoystickForSession=false
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
    private var microphoneJob: Job?=null
    private var pendingMicrophoneGeneration: Int?=null
    private var sessionSettings: PlaybackSettings?=null
    private var sessionDesktopMode=DesktopInputMode.OFF
    private var desktopCaptured=false
    private var desktopFocusState: Pair<Boolean,Boolean>?=null
    private var lastMousePosition: Pair<Float,Float>?=null
    private val desktopGamepad=DesktopGamepad()
    private val auxiliaryHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseMouseMotion=Runnable { desktopGamepad.releaseMotion();if(gameplayInputEnabled()) publishGamepad(0) }
    private val controllerProfiles by lazy { ControllerProfileStore(this) }
    private val gyro by lazy { GyroController(this,{ window.decorView.display?.rotation ?: android.view.Surface.ROTATION_0 }) {
        if(gameplayInputEnabled()) publishGamepad(0)
    } }
    @Volatile private var playbackGeneration = 0
    private val controllers=ControllerRouter()
    private var gamepadState: GamepadState
        get()=controllers.physical(0)
        set(value) { controllers.update(0,value) }
    private val usbRumble by lazy { com.xcloud.tclnative.webrtc.UsbControllerRumble(this) }
    private val rumble by lazy { ControllerRumble(controllers::deviceFor,usbRumble) {
        if(InputDeviceProfile.read(this).touchDevice && currentTouchMode!=TouchMode.OFF)
            getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator else null
    } }
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
    private var galleryLaunchJob: Job? = null
    private var recentHistoryJob: Job? = null
    private var gameLibrary: GameLibrary? = null
    private var loginView: ConsoleLoginView? = null
    private var accountJob: Job? = null
    private var currentSignaling: XCloudSignalingClient? = null
    private var currentSession: XCloudSession? = null
    private lateinit var transitionPanel: SessionTransitionView
    private var startupArtJob: Job? = null
    private var catalogReturnJob: Job? = null
    private var pendingAutomaticReport: SessionReport? = null
    private var loadingArtJob: Job? = null
    private val catalogCache by lazy { CatalogCache(File(cacheDir,"catalog-art-v2.json")) }
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
        volumeControlStream=android.media.AudioManager.STREAM_MUSIC
        pendingLaunch = PendingGameLaunch(savedInstanceState?.getString("pending_game"))
        lastShortcutRequest=savedInstanceState?.getString("last_shortcut_request")
        val shortcutRequest=intent.getStringExtra("shortcut_request")
        if (savedInstanceState == null || (shortcutRequest!=null && shortcutRequest!=lastShortcutRequest)) pendingLaunch.receive(intent)
        lastShortcutRequest=shortcutRequest ?: lastShortcutRequest
        pendingHomeChannel=savedInstanceState?.getLong("pending_home_channel",-1)?.takeIf { it>=0 }
        savedInstanceState?.getString("pending_shortcut_apk")?.takeIf {
            it.matches(Regex("com[.]xcloud[.]game[.]g[0-9a-f]{24}(?:-[0-9a-f]{32})?[.]apk"))
        }?.let { pendingShortcutApk=File(cacheDir,"game-shortcuts/$it").takeIf(File::isFile) }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        authenticator = createAuthenticator()
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
        surfaceRenderer.onDisplayDimensionsChanged = { webrtcClient?.updateDisplayDimensions(it) }
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
        transitionPanel=SessionTransitionView(this)
        rootLayout.addView(transitionPanel,FrameLayout.LayoutParams(-1,-1))
        setContentView(rootLayout)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(deviceListener,null)

        val savedTicket = authenticator.getSavedRpsTicket()
        if (!savedTicket.isNullOrEmpty() && authenticator.supportsConsoleTransfer()) {
            renderSessionRestore()
            connectToXboxCloud(savedTicket)
        } else {
            renderLoginMenu()
            if(!savedTicket.isNullOrEmpty()) loginView?.showError("Atualize seu login pelo QR Code para conectar os jogos.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (pendingLaunch.receive(intent)) {
            setIntent(intent)
            lastShortcutRequest=intent.getStringExtra("shortcut_request")
            if(transitionPanel.visibility==View.VISIBLE && !transitionPanel.exiting) {
                transitionPanel.showStartup(pendingLaunch.titleId?.let { GameTitle(it,"Seu jogo") },"Preparando seu jogo…")
                loadStartupArtwork()
            }
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

    private fun launchPendingShortcut(): Boolean {
        val signaling = currentSignaling ?: return false // Login/catalog loading retain the pending game.
        if (pendingLaunch.titleId == null) return false
        val game = pendingLaunch.consume(currentGames)
        if (game == null) {
            ConsoleDialogs.message(this,"Atalho indisponível","Este jogo não está disponível no catálogo desta conta. Atualize o catálogo nas configurações.")
        } else if (activeGame?.titleId != game.titleId || gameJob?.isActive != true) {
            launchCloudGame(signaling,game)
            return true
        }
        return false
    }

    private fun configureVideoOutput(settings: PlaybackSettings, playing: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(settings.videoMode.lowLatency)
        if (!playing) return
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        val display = windowManager.defaultDisplay
        fun mode(value: android.view.Display.Mode) = com.xcloud.tclnative.renderer.OutputMode(
            value.modeId,value.physicalWidth,value.physicalHeight,value.refreshRate)
        val current=mode(display.mode)
        val supported=display.supportedModes.map(::mode)
        val tv4k = if (uiMode.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION &&
            (settings.nativeUpscaling || (settings.fsr==com.xcloud.tclnative.settings.FsrMode.FSR1 && settings.videoMode!=VideoMode.COMPATIBILITY)))
            com.xcloud.tclnative.renderer.DisplayModePolicy.preferred4k60(current,supported) else null
        val fps=if(settings.videoMode==VideoMode.COMPATIBILITY) 30 else 60
        playbackDisplay.request(tv4k ?: com.xcloud.tclnative.renderer.DisplayModePolicy.preferredCadence(current,supported,fps))
    }

    private fun renderLoginMenu() {
        galleryLaunchJob?.cancel(); galleryLaunchJob=null
        recentHistoryJob?.cancel(); recentHistoryJob=null
        accountJob?.cancel()
        qrPollingJob?.cancel()
        dashboardJob?.cancel()
        dashboard?.close(); dashboard = null
        loginView?.close()
        gameLibrary = null
        currentSignaling = null
        currentSession = null
        startupArtJob?.cancel(); startupArtJob=null
        transitionPanel.dismiss(); pendingAutomaticReport=null
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

    private fun renderSessionRestore() {
        loginView?.close(); loginView=null
        controlPanel.removeAllViews(); controlPanel.visibility=View.GONE
        val id=pendingLaunch.titleId
        transitionPanel.showStartup(id?.let { GameTitle(it,"Seu jogo") },
            if(id!=null) "Preparando seu jogo…" else "Restaurando sua sessão…")
        hudTextView.visibility=View.GONE
        loadStartupArtwork()
    }
    private fun loadStartupArtwork() {
        startupArtJob?.cancel()
        val id=pendingLaunch.titleId ?: return
        startupArtJob=mainScope.launch {
            val game=withContext(Dispatchers.IO) { catalogCache.artwork(id) }
            if(game!=null && pendingLaunch.titleId==id && transitionPanel.visibility==View.VISIBLE && !transitionPanel.exiting)
                transitionPanel.setGame(game)
        }
    }
    private fun accountProgress(message: String) {
        loginView?.showConnecting(message)
        if(transitionPanel.visibility==View.VISIBLE && !transitionPanel.exiting) transitionPanel.updateStatus(message)
    }
    private fun accountFailure(error: Throwable, retry: () -> Unit) {
        if(error is com.xcloud.tclnative.auth.AuthenticationRequiredException) {
            authenticator.clearSession(); renderLoginMenu()
            loginView?.showError("Sua sessão precisa ser renovada. Entre pelo QR Code para continuar.")
        } else if(loginView!=null) loginView?.showError("Não foi possível conectar: ${error.message}")
        else ConsoleDialogs.message(this,"Conexão não concluída","Não foi possível restaurar a conexão agora. Seu login salvo foi preservado.",
            listOf(ConsoleAction("Tentar novamente",retry),ConsoleAction("Entrar novamente") { renderLoginMenu() }))
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
        val view = loginView
        accountProgress("Conectando sua conta ao Xbox…")
        accountJob = mainScope.launch {
            authenticator.authenticateToXCloud(rpsTicket).onSuccess { session ->
                try {
                    authenticator.getConsoleTransferToken()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (loginView === view) accountFailure(error) { connectToXboxCloud(rpsTicket) }
                    return@launch
                }
                if (loginView !== view) return@launch
                currentSession=session
                accountProgress(if(pendingLaunch.titleId!=null) "Preparando o jogo do atalho…" else "Conta conectada. Carregando seu catálogo…")
                renderGameDashboard(session)
            }.onFailure { error ->
                if (loginView === view) accountFailure(error) { connectToXboxCloud(rpsTicket) }
            }
        }
    }

    private fun showExitDialog() {
        ConsoleDialogs.message(this,"Sair do aplicativo?",
            "Seu login e seus favoritos continuarão salvos neste aparelho.", listOf(
                ConsoleAction("Continuar") {}, ConsoleAction("Sair") { finish() }))
    }

    private fun renderGameDashboard(session: XCloudSession, validatedGames: List<GameTitle>? = null) {
        galleryLaunchJob?.cancel(); galleryLaunchJob=null
        dashboardJob?.cancel()
        recentHistoryJob?.cancel(); recentHistoryJob=null
        val signaling = XCloudSignalingClient(session, transferTokenProvider = { authenticator.getConsoleTransferToken() })
        dashboardJob = mainScope.launch {
            val allGames = try {
                val raw=validatedGames ?: signaling.fetchCatalogGames()
                withContext(Dispatchers.IO) { catalogCache.restore(raw) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                accountFailure(error) { renderGameDashboard(session,validatedGames) }
                return@launch
            }
            dashboard?.close()
            loginView?.close(); loginView = null
            val library = withContext(Dispatchers.IO) {
                GameLibrary(this@MainActivity, session.libraryAccountId, session.userHash)
            }
            gameLibrary = library
            currentGames=allGames; currentSignaling=signaling
            if(launchPendingShortcut()) {
                loginView?.close(); loginView=null
                return@launch // No catalog/grid or background galleries while the shortcut enters its game.
            }
            val view = GameDashboardView(this@MainActivity, library,
                onPlay = { game -> chooseLaunchMode(signaling, game) },
                onSearch = { showCatalogSearch() },
                onOptions = { showSettings(session) },
                onExit = { showExitDialog() },
                onFavoritesChanged = { syncHomeShortcuts() },
                onPlayProduct = { product -> playGalleryProduct(signaling,product) })
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
            revealDashboard(view)
            refreshCloudHistory(view,signaling,library)
            currentGames = allGames
            currentSignaling = signaling
            syncHomeShortcuts()
            launchPendingShortcut()
            val catalog = createCatalogService(catalogCache,catalogWork::await)
            val nativeMetadata = async {
                catalog.hydrate(allGames, onProgress = { progress -> withContext(Dispatchers.Main) {
                    if (dashboard === view) view.setMetadataProgress(progress)
                } }) { hydrated -> withContext(Dispatchers.Main) {
                    if (dashboard === view) {
                        val baseIds=allGames.map { it.titleId }.toSet()
                        currentGames = hydrated + currentGames.filter { it.titleId !in baseIds }
                        if(controlPanel.visibility==View.VISIBLE) view.updateMetadata(currentGames)
                        refreshLoadingArt(); syncHomeShortcuts()
                    }
                } }
            }
            OfficialGalleryKind.entries.forEach { kind -> launch {
                val gallery=try { catalog.officialGallery(kind) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { null }
                if(dashboard===view) view.setOfficialGallery(kind,gallery)
                if(gallery!=null) {
                    nativeMetadata.await()
                    val known=currentGames.filter { it.name!=it.titleId && (it.posterUrl!=null || it.tileUrl!=null) }
                        .mapNotNull { it.productId?.let(com.xcloud.tclnative.catalog.GamePassCatalog::productKey) }.toSet()
                    catalog.hydrateOfficialGallery(gallery,known) { hydrated -> withContext(Dispatchers.Main) {
                        if(dashboard===view) view.updateOfficialGallery(hydrated)
                    } }
                }
            } }
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

    private fun playGalleryProduct(signaling: XCloudSignalingClient, product: GalleryProduct) {
        val view=dashboard ?: return
        val generation=playbackGeneration
        galleryLaunchJob?.cancel()
        Toast.makeText(this,"Verificando disponibilidade do jogo no Xbox Cloud…",Toast.LENGTH_SHORT).show()
        galleryLaunchJob=mainScope.launch {
            try {
                val known=currentGames
                val resolved=withContext(Dispatchers.Default) { OfficialGalleryCards.resolve(product,known) }
                    ?: signaling.resolveGalleryProduct(product.productId)
                if(dashboard!==view || currentSignaling!==signaling || generation!=playbackGeneration || controlPanel.visibility!=View.VISIBLE) return@launch
                if(resolved==null) {
                    ConsoleDialogs.message(this@MainActivity,"Vínculo do jogo não confirmado",
                        "Não foi possível associar esta edição a um título jogável do catálogo autenticado. Isso não confirma falta de acesso na sua conta/região. Atualize o catálogo e tente novamente. Produto: ${product.productId}.")
                    return@launch
                }
                val native=currentGames.firstOrNull { it.titleId.equals(resolved.titleId,true) } ?: resolved
                val card=CatalogCard(native,product,view.isGamePassProduct(product.productId))
                currentGames=currentGames.filterNot { it.titleId.equals(native.titleId,true) }+native
                view.setGames(currentGames)
                view.bindGalleryProduct(product.productId,native)
                chooseLaunchMode(signaling,card.playableGame()!!)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) {
                if(dashboard===view && currentSignaling===signaling)
                    ConsoleDialogs.message(this@MainActivity,"Disponibilidade não confirmada",
                        "Não foi possível verificar o jogo. Tente novamente ou atualize o catálogo. Jogos comprados exigem a licença digital e as condições do serviço Xbox.")
            }
        }
    }

    private fun refreshCloudHistory(view: GameDashboardView,signaling: XCloudSignalingClient,library: GameLibrary) {
        recentHistoryJob?.cancel()
        view.beginRecentRefresh()
        recentHistoryJob=mainScope.launch {
            try {
                catalogWork.await()
                val ids=signaling.fetchRecentGames(currentGames)
                if(dashboard===view) {
                    withContext(Dispatchers.IO) { library.saveCloudRecent(ids) }
                    if(dashboard===view) view.setRecentHistory(ids)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { if(dashboard===view) view.setRecentHistory(null) }
        }
    }

    private fun showCatalogSearch() {
        val view = dashboard ?: return
        ConsoleDialogs.search(this, view.searchQuery) { query -> view.setSearch(query) }
    }

    private fun showSettings(session: XCloudSession,startPage: SettingsPage=SettingsPage.ROOT) {
        var returnPage=startPage
        fun back() { showSettings(session,returnPage) }
        PlaybackSettingsPanel(this,settingsStore,
            changed = ::applyPlaybackSettings,
            subscription = { openAccountPage("https://account.microsoft.com/services") },
            refresh = { renderGameDashboard(session) },
            logout = { authenticator.clearSession(); renderLoginMenu(); syncHomeShortcuts() },
            testRumble = { showRumbleDiagnostics(::back) },
            homeShortcuts = { showHomeShortcuts(::back) },lastReport={ showLatestReport(::back) },
            sessionOptions={ showSessionOptionsMenu(session,::back) },controllerProfiles={ showControllerProfiles(::back) },
            onPageEntered={ returnPage=it }).open(startPage)
    }
    private fun showControllerProfiles(back: () -> Unit) {
        com.xcloud.tclnative.ui.dashboard.ControllerProfilePanel(this,controllerProfiles) { if(webrtcClient!=null) (0..3).filter { controllers.deviceFor(it)!=null }.forEach(::publishGamepad) }.show(back)
    }
    private fun showSessionOptionsMenu(session: XCloudSession,onBack: () -> Unit={ if(webrtcClient!=null) showGameMenu() else showSettings(session,SettingsPage.NETWORK) }) {
        val game=activeGame ?: dashboard?.selectedGame
        val store=SessionOptionsStore(this,session.libraryAccountId)
        val actions=mutableListOf(ConsoleAction("Padrão para todas as partidas") {
            com.xcloud.tclnative.ui.dashboard.SessionOptionsPanel(this,store,session.regions,back={ showSessionOptionsMenu(session,onBack) }).show()
        })
        if(game!=null) actions.add(ConsoleAction("Este jogo: ${game.name}") {
            com.xcloud.tclnative.ui.dashboard.SessionOptionsPanel(this,store,session.regions,game.titleId,game.name) {
                showSessionOptionsMenu(session,onBack)
            }.show()
        })
        ConsoleDialogs.menu(this,"Opções das partidas",actions,onBack=onBack)
    }
    private fun showLatestReport(onBack: (() -> Unit)?=null) {
        val report=lastSessionReport ?: reportStore.read()
        if(report==null) ConsoleDialogs.message(this,"Último diagnóstico","Ainda não há uma partida com vídeo para mostrar.",listOf(ConsoleAction("Voltar") { onBack?.invoke() }),onCancel=onBack)
        else postSessionDialog=SessionReportPanel.show(this,report,onBack=onBack)
    }
    private suspend fun finishSessionReport(tracker: SessionReportTracker) {
        val report=tracker.takeReport()
        if(report==null) { if(reportTracker===tracker) reportTracker=null; return }
        lastSessionReport=report
        try { withContext(Dispatchers.IO) { reportStore.save(report) } }
        catch(error: java.io.IOException) { if(!isFinishing && !isDestroyed) Toast.makeText(this,error.message,Toast.LENGTH_LONG).show() }
        val current=reportTracker===tracker
        if(current) reportTracker=null
        if(current && activityVisible && !isFinishing && !isDestroyed && activeGame==null && settingsStore.read().sessionReport &&
            (report.durationMs>=5000 || report.end==SessionEnd.FAILURE)) {
            pendingAutomaticReport=report; showPendingReport()
        }
    }
    private fun showPendingReport() {
        if(!activityVisible || isFinishing || isDestroyed || activeGame!=null || dashboard==null ||
            controlPanel.visibility!=View.VISIBLE || transitionPanel.visibility==View.VISIBLE) return
        val report=pendingAutomaticReport ?: return
        pendingAutomaticReport=null
        if(!settingsStore.read().sessionReport) return
        postSessionDialog?.dismiss()
        postSessionDialog=SessionReportPanel.show(this,report) {
            val updated=settingsStore.read().copy(sessionReport=false)
            settingsStore.save(updated); playbackSettings=updated
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
    private fun showHomeShortcuts(onBack: (() -> Unit)?=null) {
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
                val snapshot=currentGames
                mainScope.launch(Dispatchers.IO) { catalogCache.save(snapshot) }
                val image=ImageView(this)
                shortcutPosters.load(image,game.tileUrl ?: game.posterUrl,192,192,notifyPlaceholder=false) {
                    val accepted=homeShortcuts.pin(game,(image.drawable as? BitmapDrawable)?.bitmap)
                    Toast.makeText(this,if(accepted) "Pedido enviado ao launcher. Confirme na tela inicial." else "O launcher não aceitou o pedido.",Toast.LENGTH_LONG).show()
                }
            }
        }
        if(actions.isEmpty()) ConsoleDialogs.message(this,"Atalhos dos favoritos","Adicione jogos aos favoritos com Y para criar seus atalhos.",listOf(ConsoleAction("Voltar") { onBack?.invoke() }),onCancel=onBack)
        else ConsoleDialogs.menu(this,"Atalhos dos favoritos",actions,onBack=onBack ?: {})
    }
    private fun createInstalledShortcut(game: GameTitle) {
        if(shortcutInstallJob?.isActive==true) return
        shortcutInstallJob=mainScope.launch {
            val snapshot=currentGames
            withContext(Dispatchers.IO) { catalogCache.save(snapshot) }
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
        if(surfaceRenderer.aspect!=settings.aspect) {
            // End contacts in the old coordinate space before changing the displayed video.
            touchView.releaseInputs()
            surfaceRenderer.aspect=settings.aspect
        }
        surfaceRenderer.setImageAdjustments(settings.sharpness,settings.saturation,settings.brightness,settings.contrast)
        surfaceRenderer.setFsrSharpness(settings.fsrSharpness)
        webrtcClient?.setOutputGain(settings.audioGain)
    }
    private fun showRumbleDiagnostics(back: () -> Unit) {
        val actions=mutableListOf(ConsoleAction("Testar via Android") { rumble.test();showRumbleDiagnostics(back) })
        usbRumble.candidates().forEach { candidate ->
            actions.add(ConsoleAction("Testar USB/XInput — ${candidate.label}") {
                usbRumble.enableAndTest(candidate) { if(!isFinishing && !isDestroyed) showRumbleDiagnostics(back) }
            })
        }
        actions.add(ConsoleAction("Desativar teste USB") { usbRumble.disable();showRumbleDiagnostics(back) })
        actions.add(ConsoleAction("Voltar",back))
        ConsoleDialogs.message(this,"Vibração do controle",rumble.describe()+"\n\n"+usbRumble.describe(),actions,onCancel=back)
    }
    private fun showGameMenu() {
        if (transitionPanel.visibility==View.VISIBLE || gameMenuDialog?.isShowing == true || webrtcClient == null || queuePanel.visibility == View.VISIBLE) return
        resetController()
        gameMenuDialog = com.xcloud.tclnative.ui.dashboard.GameMenuPanel.show(this,
            image={ PlaybackSettingsPanel(this,settingsStore,::applyPlaybackSettings,{}, {}, {}).showImageAdjustments(onBack={ showGameMenu() }) },
            aspect={ PlaybackSettingsPanel(this,settingsStore,::applyPlaybackSettings,{}, {}, {}).chooseAspect(onBack={ showGameMenu() }) },
            statistics={ cycleStatistics() },
            diagnostics={ showCopyableErrorDialog("Áudio e vídeo","${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} | ${Build.HARDWARE}\n${surfaceRenderer.diagnostics()}\n${currentSignaling?.profileDiagnostics() ?: ""}\n${webrtcClient?.audioDiagnostics() ?: ""}\n${streamHistory.report()}") },
            disconnect={ cancelPlayback() },
            hud={ showGameHudSettings() },
            sessionOptions={ currentSession?.let(::showSessionOptionsMenu) },
            audio={ PlaybackSettingsPanel(this,settingsStore,::applyPlaybackSettings,{}, {}, {}).showAudio { showGameMenu() } },
            microphone=::toggleMicrophone,microphoneLabel=webrtcClient?.microphoneLabel() ?: "Ativar microfone",
            controllerProfiles={ showControllerProfiles { showGameMenu() } },
            mouse=if(sessionDesktopMode==DesktopInputMode.OFF) null else ({ auxiliaryHandler.post { setDesktopCapture(!desktopCaptured) } })).also { dialog ->
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
        launchCloudGame(signaling,game)
    }

    private fun openAccountPage(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) {
            ConsoleDialogs.message(this,"Acesse pelo celular",url)
        }
    }

    private fun restoreDashboard() {
        joystickDispatch.request(false)
        playbackDisplay.restore()
        catalogWork.resume()
        if(dashboard==null) {
            currentSession?.let { renderGameDashboard(it,currentGames) }
            return
        }
        dashboard?.setGames(currentGames)
        dashboard?.resumeArtwork()
        controlPanel.visibility = View.VISIBLE
        dashboard?.let { view ->
            hudTextView.visibility=View.GONE; revealDashboard(view)
            val signaling=currentSignaling; val library=gameLibrary
            if(signaling!=null && library!=null) refreshCloudHistory(view,signaling,library)
        }
    }
    private fun revealDashboard(view: GameDashboardView) {
        view.doOnPreDraw {
            if(dashboard===view && activeGame==null && controlPanel.visibility==View.VISIBLE) {
                startupArtJob?.cancel(); startupArtJob=null
                transitionPanel.dismiss(); view.restoreFocus(); showPendingReport()
            }
        }
    }
    private fun showCatalogReturn(game: GameTitle?,wasPlaying: Boolean) {
        startupArtJob?.cancel(); startupArtJob=null
        gameMenuDialog?.dismiss()
        val fresh=currentGames.firstOrNull { it.titleId==game?.titleId } ?: game
        transitionPanel.showExit(fresh,wasPlaying)
        controlPanel.visibility=View.GONE; statsView.visibility=View.GONE; hudTextView.visibility=View.GONE
    }
    private suspend fun allowTransitionFrame() {
        if(!activityVisible || !transitionPanel.isAttachedToWindow || !transitionPanel.exiting) return
        // Let the branded screen paint before native decoder/surface cleanup can block the UI.
        withTimeoutOrNull(100) {
            suspendCancellableCoroutine<Unit> { waiting ->
                transitionPanel.postOnAnimation { transitionPanel.post { if(waiting.isActive) waiting.resume(Unit) } }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(sessionDesktopMode!=DesktopInputMode.OFF && gameplayInputEnabled() &&
            event.device?.keyboardType==InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            if(event.keyCode==KeyEvent.KEYCODE_F7) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) showGameMenu()
                return true
            }
            if(event.keyCode==KeyEvent.KEYCODE_F8) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) setDesktopCapture(!desktopCaptured)
                return true
            }
            if(desktopCaptured && event.action in setOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
                val down=event.action==KeyEvent.ACTION_DOWN
                if(sessionDesktopMode==DesktopInputMode.NATIVE) {
                    val code=DesktopKeys.windowsKey(event.keyCode)
                    if(code!=null) { if(event.repeatCount==0) webrtcClient?.desktopKey(NativeKey(code,down));return true }
                } else if(desktopGamepad.key(event.keyCode,down)) { publishGamepad(0);return true }
            }
        }
        if(transitionPanel.visibility==View.VISIBLE && event.keyCode in setOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if(!transitionPanel.exiting && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 &&
                event.keyCode in setOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B)) showExitDialog()
            return true
        }
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
        recentHistoryJob?.cancel(); recentHistoryJob=null
        catalogReturnJob?.cancel(); catalogReturnJob=null
        startupArtJob?.cancel(); startupArtJob=null
        transitionPanel.dismiss(); pendingAutomaticReport=null
        val titleId = game.titleId
        val gameName = game.name
        activeGame = game
        microphoneJob?.cancel();pendingMicrophoneGeneration=null
        val settings = playbackSettings.forSession()
        sessionSettings=settings
        val sessionOptions=currentSession?.let { SessionOptionsStore(this,it.libraryAccountId).read(titleId) } ?: SessionOptions()
        sessionDesktopMode=if(settings.desktopInput==DesktopInputMode.NATIVE && !DesktopKeys.nativeSupported(game.supportedInputTypes)) {
            Toast.makeText(this,"Este jogo não anunciou mouse e teclado nativos. Use emulação ou controle.",Toast.LENGTH_LONG).show()
            DesktopInputMode.OFF
        } else settings.desktopInput
        joystickDispatch.request(false)
        immediateJoystickForSession=settings.videoMode.lowLatency
        val compatible=settings.videoMode==com.xcloud.tclnative.settings.VideoMode.COMPATIBILITY
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.REPLACED,webrtcClient?.currentInputMetrics())
        joystickDispatch.beginSession()
        touchView.beginSession()
        touchView.immediateNativeDispatch=settings.videoMode.lowLatency
        postSessionDialog?.dismiss()
        val tracker=SessionReportTracker(gameName,"${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}",
            "${settings.resolution.label} • ${settings.videoMode.label} • ${settings.videoCodec.label}",if(compatible) 30 else 60)
        tracker.setVisible(activityVisible); reportTracker=tracker
        streamHistory.clear()
        catalogWork.pause()
        dashboard?.pauseArtwork()
        val choice=touchChoice ?: settings.touchMode
        val touchMode=if(sessionDesktopMode!=DesktopInputMode.OFF) TouchMode.OFF else DeviceInputPolicy.resolve(InputDeviceProfile.read(this),choice,game)
        currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF)
        statistics.beginSession()
        configureVideoOutput(settings, playing = true)
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
            controllers.beginSession(settings.localCoOp,touchMode!=TouchMode.OFF || sessionDesktopMode!=DesktopInputMode.OFF)
            InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter {
                it.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD || it.sources and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK
            }.sortedWith(compareBy<InputDevice> { it.id!=preferredControllerId }.thenBy { it.id }).forEach { controllers.slot(it.id) }
            rumble.enabled=settings.rumble
            rumble.touchRumble=settings.touchRumble
            surfaceRenderer.compatibility=compatible
            surfaceRenderer.setFsr(settings.fsr==com.xcloud.tclnative.settings.FsrMode.FSR1,settings.fsrSharpness,settings.fsrOutput)
            surfaceRenderer.nativeUpscaling = settings.nativeUpscaling
            surfaceRenderer.aspect = settings.aspect
            surfaceRenderer.setImageAdjustments(settings.sharpness,settings.saturation,settings.brightness,settings.contrast)
            surfaceRenderer.minimalPostProcessingRequested = settings.videoMode.lowLatency && Build.VERSION.SDK_INT >= 30
            surfaceRenderer.prepareForSession(settings.resolution.width,settings.resolution.height)
            surfaceRenderer.onFirstFrameRendered = {
                if (generation == playbackGeneration && !firstFrame.isCompleted) {
                    tracker.begin(android.os.SystemClock.elapsedRealtime(),NetworkSnapshot.read(this@MainActivity))
                    firstFrame.complete(Unit)
                    finishLoading()
                    updateJoystickDispatch()
                    currentTouchMode=touchMode
                    touchView.configure(touchMode,game,hudStore.read(titleId))
                    resumeAuxiliaryInput()
                    hudTextView.visibility = View.GONE
                    gameLibrary?.recordPlayed(titleId)
                    hudTextView.text = "Vídeo nativo ativo [$gameName]"
                    hudTextView.setTextColor(Color.GREEN)
                    statsView.visibility = if (statistics.mode != 0) View.VISIBLE else View.GONE
                }
            }
            surfaceRenderer.onRenderError = { error -> transportFailure.complete(RenderFailureException(error)) }
            var allocatedRtc: XCloudWebRtcClient?=null
            var failed=false
            val playbackEgl=surfaceRenderer.eglBase
            try {
                // Capability discovery and native factory initialization must not block the menu/UI.
                val rtc = withContext(Dispatchers.Default) { XCloudWebRtcClient(this@MainActivity, playbackEgl,
                    onVideoTrackReady = { track ->
                        runOnUiThread { if (generation == playbackGeneration) surfaceRenderer.attachVideoTrack(track) }
                    },
                    onConnectionStateChanged = { state -> runOnUiThread {
                        if (generation == playbackGeneration) {
                            if (state.contains("FAILED") || state.contains("CLOSED") || state.startsWith("CHANNELS:") || state.startsWith("VIDEO_CODEC:")) {
                                transportFailure.complete(java.io.IOException(state))
                            }
                            if (!firstFrame.isCompleted) hudTextView.text = state
                        }
                    } },
                    onStatistics = stats@ { metrics, input ->
                        if(generation==playbackGeneration) {
                            val now=android.os.SystemClock.elapsedRealtime()
                            streamHistory.add(now,metrics,input); tracker.add(now,metrics,input)
                            if(settings.fsr==com.xcloud.tclnative.settings.FsrMode.FSR1 && !compatible) runOnUiThread {
                                if(generation==playbackGeneration) surfaceRenderer.observeFsrPerformance(now,metrics,
                                    firstFrame.isCompleted && activityVisible && gameplayInputEnabled())
                            }
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
                    onRumble = { command -> if(generation==playbackGeneration) rumble.play(command) },compatibility=compatible,
                    videoCodec=settings.videoCodec,maxBitrateKbps=sessionOptions.maxBitrateKbps,
                    ipPreference=settings.ipPreference,nativeKeyboardMouse=sessionDesktopMode==DesktopInputMode.NATIVE,
                    microphoneAllowed=settings.microphone,outputGain=settings.audioGain).also { allocatedRtc=it } }
                webrtcClient = rtc
                val appVersion=packageManager.getPackageInfo(packageName,0).versionName ?: "n/d"
                tracker.inputDetails = { "xCLOUD $appVersion • Entrada inicial: ${touchMode.label} • ${rtc.inputDiagnostics()}"+
                    (when(touchMode) { TouchMode.NATIVE -> "\n${touchView.nativeDiagnostics()}";TouchMode.VIRTUAL -> "\n${touchView.hudDiagnostics()}";else -> "" })+
                    "\n${joystickDispatch.diagnostics()}\n${signaling.profileDiagnostics()}\n${signaling.keepAliveDiagnostics()}\n${surfaceRenderer.diagnostics()}\n${playbackDisplay.diagnostics(window.decorView.display)}\n${rtc.audioDiagnostics()}\n${rtc.latencyDiagnostics()}\n${rtc.iceDiagnostics()}\n${rtc.codecDiagnostics()}\n${rumble.diagnostics()}\n${gyro.status} | Mouse/teclado: ${sessionDesktopMode.label} | Entrada MKB ativa: $desktopCaptured | Captura Android: ${if(Build.VERSION.SDK_INT>=26) window.decorView.hasPointerCapture() else false}" }
                surfaceRenderer.displayDimensions()?.let(rtc::updateDisplayDimensions)
                if(touchMode!=TouchMode.NATIVE) (0..3).filter { controllers.deviceFor(it)!=null }.forEach { publishGamepad(it) }
                signaling.startAndConnectWebRtcSession(titleId, rtc, resolution = settings.resolution, nativeTouch=touchMode==TouchMode.NATIVE,options=sessionOptions,microphone=settings.microphone, onQueueStatus = { status -> runOnUiThread {
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
                kotlinx.coroutines.coroutineScope {
                    launch { signaling.maintainSession() }
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
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed=true
                tracker.recordFailure(error)
                if (generation == playbackGeneration && (!firstFrame.isCompleted || !settingsStore.read().sessionReport)) {
                    val details = error.message ?: "Falha na sessão"
                    hudTextView.text = details
                    hudTextView.setTextColor(Color.RED)
                    showCopyableErrorDialog(gameName, details)
                }
            } finally {
                // Always release the local decoder and server session together.
                withContext(NonCancellable) {
                    val rtc=allocatedRtc
                    tracker.finish(android.os.SystemClock.elapsedRealtime(),if(failed) SessionEnd.FAILURE else SessionEnd.USER,rtc?.currentInputMetrics())
                    microphoneJob?.cancelAndJoin();microphoneJob=null;pendingMicrophoneGeneration=null
                    rtc?.setMicrophoneMuted(true)
                    gyro.stop();setDesktopCapture(false)
                    if(generation==playbackGeneration) { sessionDesktopMode=DesktopInputMode.OFF;sessionSettings=null }
                    if (generation == playbackGeneration) {
                        showCatalogReturn(game,pendingLoadingGame==null)
                        finishLoading()
                        statsView.visibility = View.GONE
                    }
                    allowTransitionFrame()
                    videoRecoveryDialog?.dismiss()
                    resetController()
                    currentTouchMode=TouchMode.OFF
                    touchView.configure(TouchMode.OFF)
                    surfaceRenderer.clearSession()
                    rtc?.close()
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
        loadingArtJob?.cancel()
        if(game.posterUrl==null && game.tileUrl==null) loadingArtJob=mainScope.launch {
            // Resolve only this validated title; bulk catalog work remains paused while playing.
            createCatalogService().hydrate(listOf(game)) { enriched -> withContext(Dispatchers.Main) {
                val fresh=enriched.firstOrNull() ?: return@withContext
                if(fresh.posterUrl==null && fresh.tileUrl==null) return@withContext
                if(pendingLoadingGame?.titleId==game.titleId) {
                    currentGames=currentGames.map { if(it.titleId==game.titleId) fresh else it }
                    refreshLoadingArt()
                    val snapshot=currentGames
                    withContext(Dispatchers.IO) { catalogCache.save(snapshot) }
                }
            } }
        }
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
        currentGames.firstOrNull { it.titleId==pending.titleId }?.let { game -> pendingLoadingGame = game; queuePanel.setGame(game) }
    }
    private fun finishLoading() {
        pendingLoadingGame = null
        loadingArtJob?.cancel(); loadingArtJob=null
        queueTicker?.cancel(); queueTicker = null
        queuePanel.dismiss()
    }
    private fun showQueue(status: com.xcloud.tclnative.signaling.QueueStatus?) {
        if (pendingLoadingGame != null) queuePanel.updateQueue(status)
    }

    private fun toggleMicrophone() {
        val rtc=webrtcClient ?: return
        if(rtc.microphoneActive) { rtc.setMicrophoneMuted(true);return }
        if(microphoneJob?.isActive==true) { Toast.makeText(this,"Negociando microfone…",Toast.LENGTH_SHORT).show();return }
        if(!rtc.canUseMicrophone()) {
            ConsoleDialogs.message(this,"Microfone",rtc.microphoneUnavailableReason())
            return
        }
        if(androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            pendingMicrophoneGeneration=playbackGeneration
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),2206)
            return
        }
        val generation=playbackGeneration
        microphoneJob=mainScope.launch {
            try {
                if(!rtc.microphoneNegotiated()) {
                    withTimeout(35000) {
                        val offer=rtc.createChatOffer()
                        val answer=currentSignaling?.renegotiateChat(offer) ?: throw java.io.IOException("CHAT: sessão encerrada")
                        ensureActive()
                        if(generation!=playbackGeneration || webrtcClient!==rtc) throw CancellationException("session replaced")
                        rtc.applyChatAnswer(answer)
                    }
                }
                if(activityVisible && generation==playbackGeneration && webrtcClient===rtc) rtc.setMicrophoneMuted(false)
            } catch(cancelled: CancellationException) {
                withContext(NonCancellable) { rtc.rejectChat() };throw cancelled
            } catch(error: Exception) {
                rtc.rejectChat()
                if(generation==playbackGeneration) showCopyableErrorDialog("Microfone",error.message ?: "CHAT: não foi possível negociar o microfone")
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==2206) {
            val generation=pendingMicrophoneGeneration;pendingMicrophoneGeneration=null
            if(generation==playbackGeneration && activityVisible && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) toggleMicrophone()
        }
    }
    private fun cancelPlayback() {
        if(transitionPanel.exiting) return
        showCatalogReturn(activeGame ?: pendingLoadingGame,pendingLoadingGame==null)
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.USER,webrtcClient?.currentInputMetrics())
        val generation=++playbackGeneration
        val closing=gameJob
        closing?.cancel()
        activeGame = null
        gameMenuDialog?.dismiss()
        videoRecoveryDialog?.dismiss()
        resetController()
        currentTouchMode=TouchMode.OFF
        touchView.configure(TouchMode.OFF)
        finishLoading()
        statsView.visibility = View.GONE
        hudTextView.visibility = View.GONE
        catalogReturnJob=mainScope.launch {
            closing?.join()
            if(generation==playbackGeneration && transitionPanel.exiting) {
                reportTracker?.let { finishSessionReport(it) }
                restoreDashboard()
            }
        }
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
        transitionPanel.visibility!=View.VISIBLE && queuePanel.visibility != View.VISIBLE && videoRecoveryDialog?.isShowing != true && webrtcClient != null && !touchView.isEditing && hasWindowFocus()

    private fun updateJoystickDispatch() { joystickDispatch.request(activityVisible && immediateJoystickForSession && gameplayInputEnabled()) }

    private fun combinedInput(slot: Int,physicalOverride: GamepadState?=null): GamepadState {
        val raw=physicalOverride ?: controllers.physical(slot)
        val device=controllers.deviceFor(slot)?.let(InputDevice::getDevice)
        val physical=if(device==null) raw else controllerProfiles.read(controllerProfiles.key(device)).apply(raw)
        val base=controllers.combined(slot,physical)
        return if(slot==0) gyro.apply(if(sessionDesktopMode==DesktopInputMode.EMULATED) ControllerRouter.merge(base,desktopGamepad.state()) else base) else base
    }
    private fun publishGamepad(slot: Int) {
        val auxiliary=slot==0 && currentTouchMode==TouchMode.NATIVE && sessionSettings?.gyroNativeTouch==true && controllers.deviceFor(0)==null
        webrtcClient?.updateGamepad(combinedInput(slot),slot,auxiliary)
    }
    private fun setDesktopCapture(enabled: Boolean) {
        val active=enabled && sessionDesktopMode!=DesktopInputMode.OFF && webrtcClient!=null
        desktopCaptured=active;lastMousePosition=null
        if(!active) { desktopGamepad.reset();auxiliaryHandler.removeCallbacks(releaseMouseMotion);webrtcClient?.releaseDesktop() }
        if(Build.VERSION.SDK_INT>=26) {
            if(active) {
                if(desktopFocusState==null) desktopFocusState=window.decorView.isFocusable to window.decorView.isFocusableInTouchMode
                window.decorView.isFocusableInTouchMode=true;window.decorView.isFocusable=true
                window.decorView.setOnCapturedPointerListener { _,event -> handleDesktopMouse(event,true) }
                window.decorView.requestFocus();window.decorView.requestPointerCapture()
            } else { window.decorView.releasePointerCapture();window.decorView.setOnCapturedPointerListener(null)
                desktopFocusState?.let { window.decorView.isFocusableInTouchMode=it.second;window.decorView.isFocusable=it.first };desktopFocusState=null }
        }
        if(webrtcClient!=null) publishGamepad(0)
    }
    private fun resumeAuxiliaryInput() {
        val settings=sessionSettings ?: return
        if(!gameplayInputEnabled()) return
        gyro.start(settings.gyroMode,settings.gyroSensitivity,currentTouchMode==TouchMode.NATIVE,settings.gyroNativeTouch)
        if(sessionDesktopMode!=DesktopInputMode.OFF) setDesktopCapture(true)
    }
    private fun handleDesktopMouse(event: MotionEvent,captured: Boolean=false): Boolean {
        if(!desktopCaptured || sessionDesktopMode==DesktopInputMode.OFF || !gameplayInputEnabled()) return false
        val movement=event.actionMasked==MotionEvent.ACTION_MOVE || event.actionMasked==MotionEvent.ACTION_HOVER_MOVE
        val previous=lastMousePosition
        val dx=if(!movement) 0f else if(captured) event.x else if(previous==null) 0f else event.x-previous.first
        val dy=if(!movement) 0f else if(captured) event.y else if(previous==null) 0f else event.y-previous.second
        if(movement) lastMousePosition=event.x to event.y
        if(sessionDesktopMode==DesktopInputMode.NATIVE) {
            webrtcClient?.desktopMouse(NativeMouse(dx.toInt(),dy.toInt(),(event.getAxisValue(MotionEvent.AXIS_HSCROLL)*120).toInt(),
                (event.getAxisValue(MotionEvent.AXIS_VSCROLL)*120).toInt(),event.buttonState))
        } else {
            desktopGamepad.mouse(dx,dy,event.buttonState,sessionSettings?.mouseSensitivity ?: 100);publishGamepad(0)
            auxiliaryHandler.removeCallbacks(releaseMouseMotion);auxiliaryHandler.postDelayed(releaseMouseMotion,30)
        }
        return true
    }
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
            webrtcClient?.updateGamepad(combinedInput(0,gamepadState.copy(buttons = base or update.forwardedButtons or update.pulse)),0)
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
        gyro.stop();setDesktopCapture(false)
        shortcutFlushJob?.cancel()
        shortcutFlushJob = null
        controllerShortcut.reset()
        if(::touchView.isInitialized) touchView.releaseInputs()
        controllers.reset()
        webrtcClient?.resetInput()
        rumble.enabled=false; rumble.stop()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(event.isFromSource(InputDevice.SOURCE_MOUSE) && handleDesktopMouse(event)) return true
        if (gameplayInputEnabled() &&
            event.source and android.view.InputDevice.SOURCE_JOYSTICK != 0 && event.action == MotionEvent.ACTION_MOVE) {
            val slot=controllerSlot(event.deviceId,event.source) ?: return true
            val receivedAt=android.os.SystemClock.uptimeMillis()
            val device=event.device
            webrtcClient?.recordControllerEvent(event.eventTime,receivedAt,event.historySize,device?.name)
            val previous=controllers.physical(slot)
            fun range(id: Int): com.xcloud.tclnative.webrtc.ControllerAxisRange? =
                device?.getMotionRange(id, event.source)?.let {
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
        updateJoystickDispatch()
        if (!hasFocus) resetController()
        else {
            rumble.enabled=playbackSettings.rumble && webrtcClient!=null
            resumeAuxiliaryInput()
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(transitionPanel.visibility==View.VISIBLE && (keyCode==KeyEvent.KEYCODE_BACK || keyCode==KeyEvent.KEYCODE_BUTTON_B)) {
            if(!transitionPanel.exiting && event.repeatCount==0) showExitDialog()
            return true
        }
        if(::touchView.isInitialized && touchView.isEditing && (keyCode==KeyEvent.KEYCODE_BACK || keyCode==KeyEvent.KEYCODE_BUTTON_B)) {
            if(event.repeatCount==0) touchView.cancelEditing()
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_F10) &&
            transitionPanel.visibility!=View.VISIBLE && controlPanel.visibility == View.GONE && queuePanel.visibility != View.VISIBLE) {
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

    override fun onStart() {
        super.onStart(); activityVisible=true; reportTracker?.setVisible(true); webrtcClient?.resumeAudio();webrtcClient?.setAudioForeground(true)
        usbRumble.setForeground(true)
        rumble.enabled=playbackSettings.rumble && webrtcClient!=null
        updateJoystickDispatch()
        if(activeGame==null && dashboard!=null && controlPanel.visibility==View.VISIBLE && transitionPanel.visibility!=View.VISIBLE) {
            val signaling=currentSignaling; val library=gameLibrary
            if(signaling!=null && library!=null) refreshCloudHistory(dashboard!!,signaling,library)
        }
    }
    override fun onStop() { joystickDispatch.request(false);rumble.enabled=false;rumble.stop();usbRumble.setForeground(false);resetController();microphoneJob?.cancel();webrtcClient?.setAudioForeground(false);activityVisible=false; reportTracker?.setVisible(false); super.onStop() }

    override fun onDestroy() {
        joystickDispatch.request(false)
        playbackDisplay.restore()
        reportTracker?.finish(android.os.SystemClock.elapsedRealtime(),SessionEnd.APP_CLOSED,webrtcClient?.currentInputMetrics())
        postSessionDialog?.dismiss()
        ++playbackGeneration
        gyro.stop();setDesktopCapture(false);auxiliaryHandler.removeCallbacksAndMessages(null)
        mainScope.cancel()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(deviceListener)
        rumble.enabled=false; rumble.stop()
        usbRumble.close()
        dashboard?.close()
        shortcutPosters.close()
        gameMenuDialog?.dismiss()
        loginView?.close()
        transitionPanel.close()
        queuePanel.close()
        videoRecoveryDialog?.dismiss()
        surfaceRenderer.clearSession()
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
