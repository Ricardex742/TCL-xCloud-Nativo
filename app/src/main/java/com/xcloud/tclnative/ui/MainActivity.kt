package com.xcloud.tclnative.ui

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xcloud.tclnative.catalog.CatalogCache
import java.io.File
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.catalog.CatalogService
import com.xcloud.tclnative.catalog.GameLibrary
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
    @Volatile private var statisticsMode = 1 // 0 hidden, 1 compact, 2 complete
    private var lowLatencyVideo = true
    private lateinit var controlPanel: LinearLayout
    private lateinit var authenticator: XalAuthenticator
    private var webrtcClient: XCloudWebRtcClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameJob: Job? = null
    @Volatile private var playbackGeneration = 0
    private var gamepadState = com.xcloud.tclnative.webrtc.GamepadState()
    private var qrPollingJob: Job? = null
    private lateinit var queuePanel: GameLoadingView
    private var pendingLoadingGame: GameTitle? = null
    private var queueTicker: Job? = null
    private val controllerShortcut = ControllerShortcut()
    private var shortcutFlushJob: Job? = null
    private var videoRecoveryDialog: AlertDialog? = null
    private var dashboard: GameDashboardView? = null
    private var dashboardJob: Job? = null
    private var gameLibrary: GameLibrary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        authenticator = XalAuthenticator(this)
        lowLatencyVideo = getSharedPreferences("stream_preferences", Context.MODE_PRIVATE).getBoolean("low_latency_video", true)
        if (Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(lowLatencyVideo)
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        if (uiMode.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            val display = windowManager.defaultDisplay
            fun mode(value: android.view.Display.Mode) = com.xcloud.tclnative.renderer.OutputMode(
                value.modeId, value.physicalWidth, value.physicalHeight, value.refreshRate)
            com.xcloud.tclnative.renderer.DisplayModePolicy.preferred4k60(mode(display.mode), display.supportedModes.map(::mode))
                ?.let { id -> window.attributes = window.attributes.apply { preferredDisplayModeId = id } }
        }

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceRenderer = AiPqSurfaceRenderer(this)
        surfaceRenderer.minimalPostProcessingRequested = lowLatencyVideo && Build.VERSION.SDK_INT >= 30
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
            text = "xCloud Native [Android $androidVer | SoC: $socModel] | Aguardando sessão"
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
        setContentView(rootLayout)

        val savedTicket = authenticator.getSavedRpsTicket()
        if (!savedTicket.isNullOrEmpty() && authenticator.supportsConsoleTransfer()) {
            connectToXboxCloud(savedTicket)
        } else {
            renderLoginMenu()
            if (!savedTicket.isNullOrEmpty()) {
                hudTextView.text = "Atualize seu login pelo QR Code para conectar os jogos."
                hudTextView.setTextColor(Color.YELLOW)
            }
        }
    }

    private fun renderLoginMenu() {
        dashboardJob?.cancel()
        dashboard?.close()
        dashboard = null
        gameLibrary = null
        controlPanel.setPadding(36, 24, 36, 24)
        controlPanel.setBackgroundColor(Color.argb(235, 16, 16, 20))
        controlPanel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = 56; bottomMargin = 16; marginStart = 48; marginEnd = 48
        }
        hudTextView.visibility = View.VISIBLE
        qrPollingJob?.cancel()
        controlPanel.removeAllViews()
        controlPanel.visibility = View.VISIBLE

        val titleView = TextView(this).apply {
            text = "XBOX CLOUD GAMING - MOTOR NATIVO"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        controlPanel.addView(titleView)

        val btnQrLogin = Button(this).apply {
            text = "Entrar pelo QR Code Microsoft"
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { startQrCodeLoginFlow() }
        }
        controlPanel.addView(btnQrLogin)

    }

    private fun startQrCodeLoginFlow() {
        qrPollingJob?.cancel()
        controlPanel.removeAllViews()
        controlPanel.visibility = View.VISIBLE

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val qrImageView = ImageView(this).apply {
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.WHITE)
        }
        row.addView(qrImageView, LinearLayout.LayoutParams(320, 320))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 0, 0, 0)
        }

        val stepsText = TextView(this).apply {
            text = "Gerando código oficial na Microsoft..."
            setTextColor(Color.LTGRAY)
            textSize = 13f
        }
        col.addView(stepsText)

        val codeText = TextView(this).apply {
            text = "--------"
            setTextColor(Color.GREEN)
            textSize = 26f
            setPadding(0, 8, 0, 12)
        }
        col.addView(codeText)

        val btnApprovePhone = Button(this).apply {
            text = "Aprovar Neste Celular"
            visibility = View.GONE
        }
        col.addView(btnApprovePhone)

        val btnBack = Button(this).apply {
            text = "Voltar"
            setOnClickListener { renderLoginMenu() }
        }
        col.addView(btnBack)

        row.addView(col)
        controlPanel.addView(row)

        qrPollingJob = mainScope.launch {
            val qrRes = authenticator.requestDeviceCodeQr()
            qrRes.onSuccess { info ->
                qrImageView.setImageBitmap(info.qrBitmap)
                stepsText.text = "Escaneie o QR Code ou acesse\n${info.verificationUri} com o código:"
                codeText.text = info.userCode
                btnApprovePhone.visibility = View.VISIBLE
                btnApprovePhone.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.verificationUriComplete ?: info.verificationUri)))
                }
                hudTextView.text = "Aguardando aprovação do QR Code [${info.userCode}]..."
                hudTextView.setTextColor(Color.YELLOW)

                val pollRes = authenticator.pollForDeviceCodeLogin(info)
                pollRes.onSuccess { rpsTicket ->
                    connectToXboxCloud(rpsTicket)
                }.onFailure { err ->
                    hudTextView.text = "QR Code expirado: ${err.message}"
                    hudTextView.setTextColor(Color.RED)
                }
            }.onFailure { err ->
                stepsText.text = "Erro ao gerar QR: ${err.message}"
            }
        }
    }

    private fun connectToXboxCloud(rpsTicket: String) {
        hudTextView.text = "Autenticando na Xbox Live e buscando catálogo oficial do Brasil..."
        hudTextView.setTextColor(Color.YELLOW)

        mainScope.launch {
            val result = authenticator.authenticateToXCloud(rpsTicket)
            result.onSuccess { session ->
                // Validate the console grant before the user spends time in a game's queue.
                try {
                    authenticator.getConsoleTransferToken()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    renderLoginMenu()
                    hudTextView.text = "Não foi possível autorizar os jogos: ${error.message}"
                    hudTextView.setTextColor(Color.RED)
                    return@launch
                }
                hudTextView.text = "CONECTADO! Região: ${session.baseUri.replace("https://", "")} | Sincronizando Catálogo..."
                hudTextView.setTextColor(Color.GREEN)
                renderGameDashboard(session)
            }.onFailure { err ->
                hudTextView.text = "Falha na autenticação: ${err.message}"
                hudTextView.setTextColor(Color.RED)
                if (err is com.xcloud.tclnative.auth.AuthenticationRequiredException) authenticator.clearSession()
                renderLoginMenu()
            }
        }
    }

    private fun renderGameDashboard(session: XCloudSession) {
        dashboardJob?.cancel()
        val signaling = XCloudSignalingClient(session, transferTokenProvider = { authenticator.getConsoleTransferToken() })
        dashboardJob = mainScope.launch {
            val allGames = try { signaling.fetchCatalogGames() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                hudTextView.text = "Falha no catálogo: ${error.message}"
                hudTextView.setTextColor(Color.RED)
                showCopyableErrorDialog("Catálogo", error.message ?: "Catálogo indisponível")
                return@launch
            }
            dashboard?.close()
            val library = GameLibrary(this@MainActivity, session.userHash)
            gameLibrary = library
            val view = GameDashboardView(this@MainActivity, library,
                onPlay = { game -> launchCloudGame(signaling, game) },
                onSearch = { showCatalogSearch() },
                onOptions = { showCatalogOptions(session) },
                onExit = {
                    AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Sair do aplicativo?")
                        .setMessage("Seu login e seus favoritos continuarão salvos neste aparelho.")
                        .setPositiveButton("Sair") { _, _ -> finish() }
                        .setNegativeButton("Continuar", null).show()
                })
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
            val catalog = CatalogService(cache = CatalogCache(File(cacheDir, "catalog-art-v2.json")))
            launch {
                catalog.hydrate(allGames, onProgress = { progress -> withContext(Dispatchers.Main) {
                    if (dashboard === view) view.setMetadataProgress(progress)
                } }) { hydrated -> withContext(Dispatchers.Main) {
                    if (dashboard === view) { view.setGames(hydrated); refreshLoadingArt() }
                } }
            }
            launch {
                val included = async { catalog.gallery(CatalogService.GAME_PASS) }
                val free = async { catalog.gallery(CatalogService.FREE_TO_PLAY) }
                val products = included.await()?.toSet()
                val excluded = free.await()?.toSet() ?: emptySet()
                if (dashboard === view) { view.setGamePassProducts(products?.minus(excluded)); refreshLoadingArt() }
            }
            launch {
                val popular = async { catalog.gallery(CatalogService.POPULAR) }
                val added = async { catalog.gallery(CatalogService.RECENTLY_ADDED) }
                view.setGalleries(popular.await(), added.await())
            }
            launch {
                authenticator.loadXboxProfile().onSuccess { profile ->
                    if (dashboard === view) view.setProfile(profile)
                }
            }
        }
    }

    private fun showCatalogSearch() {
        val view = dashboard ?: return
        val input = EditText(this).apply {
            setText(view.searchQuery)
            hint = "Nome do jogo"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(24, 16, 24, 16)
            selectAll()
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pesquisar jogos").setView(input)
            .setPositiveButton("Buscar") { _, _ -> view.setSearch(input.text.toString()) }
            .setNeutralButton("Limpar busca") { _, _ -> view.setSearch("") }
            .setNegativeButton("Voltar", null).show()
    }

    private fun showCatalogOptions(session: XCloudSession) {
        val items = arrayOf("Pesquisar jogos", "Vídeo: " + if (lowLatencyVideo) "menor atraso" else "adaptativo",
            "Microsoft Rewards — consultar saldo", "Gerenciar assinatura Microsoft", "Atualizar catálogo", "Trocar conta")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Conta e opções").setItems(items) { _, choice -> when (choice) {
                0 -> showCatalogSearch()
                1 -> {
                    lowLatencyVideo = !lowLatencyVideo
                    if (Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(lowLatencyVideo)
                    surfaceRenderer.minimalPostProcessingRequested = lowLatencyVideo && Build.VERSION.SDK_INT >= 30
                    getSharedPreferences("stream_preferences", Context.MODE_PRIVATE).edit()
                        .putBoolean("low_latency_video", lowLatencyVideo).apply()
                    Toast.makeText(this, "Modo de vídeo aplicado à próxima partida.", Toast.LENGTH_SHORT).show()
                }
                2 -> openAccountPage("https://rewards.bing.com/")
                3 -> openAccountPage("https://account.microsoft.com/services")
                4 -> renderGameDashboard(session)
                5 -> { authenticator.clearSession(); renderLoginMenu() }
            } }.setNegativeButton("Voltar", null).show()
    }

    private fun openAccountPage(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Acesse pelo celular")
                .setMessage(url).setPositiveButton("Fechar", null).show()
        }
    }

    private fun restoreDashboard() {
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
        val view = dashboard
        if (view != null && controlPanel.visibility == View.VISIBLE && queuePanel.visibility != View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) view.back()
                return true
            }
            if (view.handleKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun launchCloudGame(signaling: XCloudSignalingClient, game: GameTitle) {
        val titleId = game.titleId
        val gameName = game.name
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
            surfaceRenderer.prepareForSession()
            surfaceRenderer.onFirstFrameRendered = {
                if (generation == playbackGeneration && !firstFrame.isCompleted) {
                    firstFrame.complete(Unit)
                    finishLoading()
                    hudTextView.visibility = View.VISIBLE
                    gameLibrary?.recordPlayed(titleId)
                    hudTextView.text = "Vídeo nativo ativo [$gameName]"
                    hudTextView.setTextColor(Color.GREEN)
                    statsView.visibility = if (statisticsMode != 0) View.VISIBLE else View.GONE
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
                onStatistics = { metrics, input ->
                    val complete = statisticsMode == 2
                    val text = if (complete) metrics.overlay(input, "") else metrics.compactOverlay(input)
                    runOnUiThread {
                        if (generation == playbackGeneration) {
                            statsView.text = text + if (complete) surfaceRenderer.diagnostics() + "\n" +
                                (webrtcClient?.latencyDiagnostics() ?: "") else surfaceRenderer.compactDiagnostics()
                            statsView.visibility = if (firstFrame.isCompleted && statisticsMode != 0) View.VISIBLE else View.GONE
                        }
                    }
                }, lowLatencyVideo = lowLatencyVideo)
            webrtcClient = rtc
            try {
                signaling.startAndConnectWebRtcSession(titleId, rtc, onQueueStatus = { status -> runOnUiThread {
                    if (generation == playbackGeneration) showQueue(status)
                } }) { progress -> runOnUiThread {
                    if (generation == playbackGeneration && !firstFrame.isCompleted) {
                        queuePanel.updateProgress(progress)
                        hudTextView.text = progress
                        hudTextView.setTextColor(Color.YELLOW)
                    }
                } }.getOrThrow()
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
                val recovery = VideoRecoveryPolicy()
                var lastToken = -1L
                var recovering = false
                var nextKeepAlive = android.os.SystemClock.elapsedRealtime() + 30_000
                while (isActive) {
                    withTimeoutOrNull(1000) { transportFailure.await() }?.let { throw it }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val health = surfaceRenderer.probeVideoHealth()
                    val action = recovery.observe(now, health.presentedToken, health.deliveredRecently)
                    if (health.presentedToken != lastToken && recovering) {
                        recovering = false
                        videoRecoveryDialog?.dismiss()
                        hudTextView.text = "Vídeo nativo ativo [$gameName]"
                        hudTextView.setTextColor(Color.GREEN)
                    }
                    lastToken = health.presentedToken
                    when (action) {
                        VideoRecoveryAction.NONE -> Unit
                        VideoRecoveryAction.REQUEST_KEYFRAME -> {
                            recovering = true
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
                            videoRecoveryDialog = AlertDialog.Builder(this@MainActivity)
                                .setTitle("Imagem sem atualização")
                                .setMessage("A imagem não voltou após 30 segundos. Tentar recuperar o vídeo mantém a partida aberta. Voltar ao catálogo encerra a sessão.")
                                .setPositiveButton("Recuperar vídeo") { _, _ ->
                                    recovery.retry(android.os.SystemClock.elapsedRealtime())
                                    if (surfaceRenderer.probeVideoHealth().deliveredRecently) surfaceRenderer.rebuildSurface()
                                    rtc.recoverVideoDecoder()
                                }
                                .setNegativeButton("Voltar ao catálogo") { _, _ -> cancelPlayback() }
                                .setOnCancelListener { cancelPlayback() }
                                .create().also { dialog ->
                                    dialog.setOnDismissListener { if (videoRecoveryDialog === dialog) videoRecoveryDialog = null }
                                    dialog.show()
                                }
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
                if (generation == playbackGeneration) {
                    val details = error.message ?: "Falha na sessão"
                    hudTextView.text = details
                    hudTextView.setTextColor(Color.RED)
                    showCopyableErrorDialog(gameName, details)
                }
            } finally {
                // The server-side Xbox and the local decoder must both be released before retrying.
                withContext(NonCancellable) {
                    signaling.stop()
                    if (generation == playbackGeneration) {
                        finishLoading()
                        statsView.visibility = View.GONE
                    }
                    videoRecoveryDialog?.dismiss()
                    resetController()
                    surfaceRenderer.clearSession()
                    rtc.close()
                    if (webrtcClient === rtc) webrtcClient = null
                    if (generation == playbackGeneration) restoreDashboard()
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
        ++playbackGeneration
        gameJob?.cancel()
        videoRecoveryDialog?.dismiss()
        resetController()
        finishLoading()
        statsView.visibility = View.GONE
        hudTextView.text = "Sessão cancelada. Selecione um jogo."
        hudTextView.visibility = View.VISIBLE
        restoreDashboard()
    }

    private fun showCopyableErrorDialog(gameName: String, fullError: String) {
        AlertDialog.Builder(this)
            .setTitle("Diagnóstico de Sessão ($gameName)")
            .setMessage(fullError)
            .setPositiveButton("Copiar Detalhes") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("xCloud Error", fullError))
                Toast.makeText(this, "Detalhes copiados!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun gameplayInputEnabled() = controlPanel.visibility == View.GONE &&
        queuePanel.visibility != View.VISIBLE && videoRecoveryDialog?.isShowing != true && webrtcClient != null

    private fun cycleStatistics() {
        statisticsMode = (statisticsMode + 1) % 3
        statsView.visibility = if (statisticsMode != 0) View.VISIBLE else View.GONE
    }

    private fun applyShortcut(update: ShortcutUpdate) {
        val base = gamepadState.buttons and ControllerShortcut.BOTH.inv()
        if (update.pulse != 0) {
            webrtcClient?.updateGamepad(gamepadState.copy(buttons = base or update.forwardedButtons or update.pulse))
        }
        gamepadState = gamepadState.copy(buttons = base or update.forwardedButtons)
        webrtcClient?.updateGamepad(gamepadState)
        if (update.toggleStatistics) cycleStatistics()
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
        gamepadState = com.xcloud.tclnative.webrtc.GamepadState()
        webrtcClient?.updateGamepad(gamepadState)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gameplayInputEnabled() &&
            event.source and android.view.InputDevice.SOURCE_JOYSTICK != 0 && event.action == MotionEvent.ACTION_MOVE) {
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
            var buttons = gamepadState.buttons and 0xF0FF
            val x = stick(MotionEvent.AXIS_HAT_X); val y = stick(MotionEvent.AXIS_HAT_Y)
            if (y < -0.5f) buttons = buttons or 256
            if (y > 0.5f) buttons = buttons or 512
            if (x < -0.5f) buttons = buttons or 1024
            if (x > 0.5f) buttons = buttons or 2048
            val rightX = if (range(MotionEvent.AXIS_Z) == null && range(MotionEvent.AXIS_RX) != null) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
            val rightY = if (range(MotionEvent.AXIS_RZ) == null && range(MotionEvent.AXIS_RY) != null) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ
            gamepadState = gamepadState.copy(buttons = buttons,
                leftX = stick(MotionEvent.AXIS_X, true), leftY = stick(MotionEvent.AXIS_Y, true),
                rightX = stick(rightX), rightY = stick(rightY),
                leftTrigger = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
                rightTrigger = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS))
            webrtcClient?.updateGamepad(gamepadState)
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
            if (handleControllerShortcut(mask, false)) return true
            gamepadState = gamepadState.copy(buttons = gamepadState.buttons and mask.inv())
            webrtcClient?.updateGamepad(gamepadState)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) resetController()
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_F10) &&
            controlPanel.visibility == View.GONE && queuePanel.visibility != View.VISIBLE) {
            if (event.repeatCount == 0) {
                cycleStatistics()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel.visibility == View.GONE) {
            cancelPlayback()
            return true
        }
        val mask = gamepadButton(keyCode)
        if (mask != 0 && gameplayInputEnabled()) {
            if (handleControllerShortcut(mask, true)) return true
            gamepadState = gamepadState.copy(buttons = gamepadState.buttons or mask)
            webrtcClient?.updateGamepad(gamepadState)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ++playbackGeneration
        mainScope.cancel()
        dashboard?.close()
        queuePanel.close()
        videoRecoveryDialog?.dismiss()
        surfaceRenderer.clearSession()
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
