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
import android.text.Editable
import android.text.TextWatcher
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.XCloudWebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var authenticator: XalAuthenticator
    private var webrtcClient: XCloudWebRtcClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameJob: Job? = null
    @Volatile private var playbackGeneration = 0
    private var gamepadState = com.xcloud.tclnative.webrtc.GamepadState()
    private var qrPollingJob: Job? = null
    private lateinit var queuePanel: LinearLayout
    private lateinit var queueText: TextView
    private var queueSnapshot: com.xcloud.tclnative.signaling.QueueStatus? = null
    private var queueTicker: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        authenticator = XalAuthenticator(this)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceRenderer = AiPqSurfaceRenderer(this)
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

        queuePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 20, 36, 20)
            setBackgroundColor(Color.rgb(20, 24, 30))
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "Fila do Xbox Cloud"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            addView(android.widget.ProgressBar(this@MainActivity), LinearLayout.LayoutParams(48, 48))
        }
        queueText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }
        queuePanel.addView(queueText)
        queuePanel.addView(Button(this).apply {
            text = "Cancelar espera"
            setOnClickListener { cancelPlayback() }
        })
        rootLayout.addView(queuePanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply { marginStart = 48; marginEnd = 48 })
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
        val signaling = XCloudSignalingClient(session, transferTokenProvider = { authenticator.getConsoleTransferToken() })

        mainScope.launch {
            val allGames = try { signaling.fetchCatalogGames() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                hudTextView.text = "Falha no catálogo: ${error.message}"
                hudTextView.setTextColor(Color.RED)
                return@launch
            }
            controlPanel.removeAllViews()
            controlPanel.visibility = View.VISIBLE

            val topBar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 10)
            }

            val header = TextView(this@MainActivity).apply {
                text = "CATÁLOGO NATIVO — ${allGames.size} JOGOS REAIS"
                setTextColor(Color.WHITE)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topBar.addView(header)

            val btnLogout = Button(this@MainActivity).apply {
                text = "Trocar Conta"
                setOnClickListener {
                    authenticator.clearSession()
                    renderLoginMenu()
                }
            }
            topBar.addView(btnLogout)
            controlPanel.addView(topBar)

            val searchInput = EditText(this@MainActivity).apply {
                hint = "Pesquisar jogo (ex: FORTNITE, FORZA, CALL OF DUTY, GTA)..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setBackgroundColor(Color.argb(255, 35, 35, 42))
                setPadding(24, 12, 24, 12)
                isSingleLine = true
            }
            controlPanel.addView(
                searchInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
            )

            val scroll = ScrollView(this@MainActivity)
            val gamesListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(gamesListLayout)
            controlPanel.addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            fun populateGames(filterQuery: String) {
                gamesListLayout.removeAllViews()
                val filtered = if (filterQuery.isBlank()) {
                    allGames.take(120)
                } else {
                    allGames.filter {
                        it.name.contains(filterQuery, ignoreCase = true) ||
                            it.titleId.contains(filterQuery, ignoreCase = true)
                    }.take(120)
                }

                for (game in filtered) {
                    val btnGame = Button(this@MainActivity).apply {
                        text = "▶ ${game.name}"
                        isFocusable = true
                        setOnClickListener {
                            launchCloudGame(signaling, game.titleId, game.name)
                        }
                    }
                    gamesListLayout.addView(btnGame)
                }
            }

            populateGames("")

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    populateGames(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            hudTextView.text = "Catálogo pronto (${allGames.size} títulos) | Selecione um jogo"
            hudTextView.setTextColor(Color.GREEN)
        }
    }

    private fun launchCloudGame(signaling: XCloudSignalingClient, titleId: String, gameName: String) {
        val previous = gameJob
        previous?.cancel()
        val generation = ++playbackGeneration
        controlPanel.visibility = View.GONE
        hudTextView.visibility = View.VISIBLE
        hudTextView.text = "PLAY: Solicitando sessão para [$gameName]. Voltar cancela."
        hudTextView.setTextColor(Color.CYAN)
        gameJob = mainScope.launch {
            previous?.join()
            if (generation != playbackGeneration) return@launch
            val firstFrame = CompletableDeferred<Unit>()
            val transportFailure = CompletableDeferred<Exception>()
            gamepadState = com.xcloud.tclnative.webrtc.GamepadState()
            surfaceRenderer.prepareForSession()
            surfaceRenderer.onFirstFrameRendered = {
                if (generation == playbackGeneration) {
                    firstFrame.complete(Unit)
                    hudTextView.text = "Vídeo nativo ativo [$gameName]"
                    hudTextView.setTextColor(Color.GREEN)
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
                } })
            webrtcClient = rtc
            try {
                signaling.startAndConnectWebRtcSession(titleId, rtc, onQueueStatus = { status -> runOnUiThread {
                    if (generation == playbackGeneration) showQueue(status)
                } }) { progress -> runOnUiThread {
                    if (generation == playbackGeneration && !firstFrame.isCompleted) {
                        hudTextView.text = progress
                        hudTextView.setTextColor(Color.YELLOW)
                    }
                } }.getOrThrow()
                if (withTimeoutOrNull(20_000) {
                    kotlinx.coroutines.selects.select<Unit> {
                        firstFrame.onAwait { }
                        transportFailure.onAwait { throw it }
                    }
                    true
                } != true) {
                    throw java.io.IOException("VIDEO: transporte conectado, mas nenhum quadro foi renderizado em 20s.")
                }
                while (isActive) {
                    withTimeoutOrNull(30_000) { transportFailure.await() }?.let { throw it }
                    signaling.keepAlive()
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
                    if (generation == playbackGeneration) showQueue(null)
                    surfaceRenderer.clearSession()
                    rtc.close()
                    if (webrtcClient === rtc) webrtcClient = null
                    if (generation == playbackGeneration) controlPanel.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showQueue(status: com.xcloud.tclnative.signaling.QueueStatus?) {
        val wasHidden = queuePanel.visibility != View.VISIBLE
        queueSnapshot = status
        queuePanel.visibility = if (status == null) View.GONE else View.VISIBLE
        if (status == null) {
            queueTicker?.cancel()
            queueTicker = null
        } else {
            if (wasHidden) queuePanel.getChildAt(queuePanel.childCount - 1).requestFocus()
            queueText.text = status.message(System.nanoTime() / 1_000_000)
            if (queueTicker?.isActive != true) queueTicker = mainScope.launch {
                while (isActive) {
                    val snapshot = queueSnapshot ?: break
                    queueText.text = snapshot.message(System.nanoTime() / 1_000_000)
                    delay(1000)
                }
            }
        }
    }

    private fun cancelPlayback() {
        ++playbackGeneration
        gameJob?.cancel()
        showQueue(null)
        hudTextView.text = "Sessão cancelada. Selecione um jogo."
        hudTextView.visibility = View.VISIBLE
        controlPanel.visibility = View.VISIBLE
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

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (controlPanel.visibility == View.GONE && queuePanel.visibility != View.VISIBLE && webrtcClient != null &&
            event.source and android.view.InputDevice.SOURCE_JOYSTICK != 0 && event.action == MotionEvent.ACTION_MOVE) {
            fun axis(id: Int): Float {
                val value = event.getAxisValue(id)
                val flat = event.device?.getMotionRange(id, event.source)?.flat ?: 0f
                return if (kotlin.math.abs(value) <= flat) 0f else value
            }
            var buttons = gamepadState.buttons and 0xF0FF
            val x = axis(MotionEvent.AXIS_HAT_X); val y = axis(MotionEvent.AXIS_HAT_Y)
            if (y < -0.5f) buttons = buttons or 256
            if (y > 0.5f) buttons = buttons or 512
            if (x < -0.5f) buttons = buttons or 1024
            if (x > 0.5f) buttons = buttons or 2048
            gamepadState = gamepadState.copy(buttons = buttons,
                leftX = axis(MotionEvent.AXIS_X), leftY = axis(MotionEvent.AXIS_Y),
                rightX = axis(MotionEvent.AXIS_Z), rightY = axis(MotionEvent.AXIS_RZ),
                leftTrigger = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)),
                rightTrigger = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)))
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
        if (mask != 0 && controlPanel.visibility == View.GONE && queuePanel.visibility != View.VISIBLE && webrtcClient != null) {
            gamepadState = gamepadState.copy(buttons = gamepadState.buttons and mask.inv())
            webrtcClient?.updateGamepad(gamepadState)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            gamepadState = com.xcloud.tclnative.webrtc.GamepadState()
            webrtcClient?.updateGamepad(gamepadState)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel.visibility == View.GONE) {
            cancelPlayback()
            return true
        }
        val mask = gamepadButton(keyCode)
        if (mask != 0 && controlPanel.visibility == View.GONE && queuePanel.visibility != View.VISIBLE && webrtcClient != null) {
            gamepadState = gamepadState.copy(buttons = gamepadState.buttons or mask)
            webrtcClient?.updateGamepad(gamepadState)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ++playbackGeneration
        mainScope.cancel()
        surfaceRenderer.clearSession()
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
