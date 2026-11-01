package com.xcloud.tclnative.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.xcloud.tclnative.auth.AuthenticationRequiredException
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.GamepadState
import com.xcloud.tclnative.webrtc.XCloudWebRtcClient
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var renderer: AiPqSurfaceRenderer
    private lateinit var hud: TextView
    private lateinit var panel: LinearLayout
    private lateinit var touchPad: LinearLayout
    private lateinit var auth: XalAuthenticator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loginJob: Job? = null
    private var catalogJob: Job? = null
    private var sessionJob: Job? = null
    private var rtc: XCloudWebRtcClient? = null
    private var currentSession: XCloudSession? = null
    private var loginDialog: AlertDialog? = null
    private var generation = 0
    private var destroyed = false
    private var gamepad = GamepadState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        auth = XalAuthenticator(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        renderer = AiPqSurfaceRenderer(this)
        root.addView(renderer, FrameLayout.LayoutParams(-1, -1))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 18, 18, 22)); setPadding(24, 24, 24, 24)
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        touchPad = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(touchPad, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        buildTouchControls()
        hud = label("xCloud nativo | Android ${Build.VERSION.RELEASE}").apply {
            textSize = 12f; setBackgroundColor(Color.argb(210, 0, 0, 0)); setPadding(12, 6, 12, 6)
        }
        root.addView(hud, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        auth.getSavedRpsTicket()?.let(::connect) ?: loginMenu()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isFocusable = true; setOnClickListener { action() }
    }
    private fun status(text: String, error: Boolean = false) {
        if (!destroyed) { hud.text = text; hud.setTextColor(if (error) Color.RED else Color.GREEN) }
    }
    private fun loginMenu() {
        loginJob?.cancel(); catalogJob?.cancel()
        panel.removeAllViews(); panel.visibility = View.VISIBLE
        panel.addView(label("XBOX CLOUD GAMING — ENTRAR"))
        panel.addView(button("Entrar via QR Code") { startDeviceLogin() })
        panel.addView(button("Entrar pelo navegador integrado") { browserLogin() })
        auth.getSavedRpsTicket()?.let { ticket -> panel.addView(button("Repetir com o login salvo") { connect(ticket) }) }
    }
    private fun startDeviceLogin() {
        loginJob?.cancel()
        panel.removeAllViews()
        val image = ImageView(this)
        panel.addView(image, LinearLayout.LayoutParams(280, 280))
        val instructions = label("Solicitando código de aprovação...")
        panel.addView(instructions)
        val approve = button("Abrir página de aprovação") {}.apply { visibility = View.GONE }
        panel.addView(approve)
        panel.addView(button("Voltar / cancelar") { loginMenu() })
        loginJob = scope.launch {
            try {
                status("AUTH_DEVICE_CODE: solicitando código...")
                val info = auth.requestDeviceCodeQr().getOrThrow()
                val approvalUrl = info.verificationUriComplete ?: info.verificationUri
                val bitmap = withContext(Dispatchers.Default) { auth.generateQrCodeBitmap(approvalUrl, 280) }
                image.setImageBitmap(bitmap)
                instructions.text = "Abra a página e aprove o código:\n${info.userCode}"
                approve.visibility = View.VISIBLE
                approve.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(approvalUrl))) }
                status("Aguardando aprovação do código...")
                val ticket = auth.pollForDeviceCodeLogin(info).getOrThrow()
                ensureActive()
                connect(ticket)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                instructions.text = e.message ?: "Não foi possível concluir o login"
                status(instructions.text.toString(), true)
                panel.addView(button("Gerar novo código") { startDeviceLogin() })
            }
        }
    }
    private fun connect(ticket: String) {
        loginJob?.cancel(); catalogJob?.cancel()
        loginJob = scope.launch {
            try {
                val session = auth.authenticateToXCloud(ticket) { progress -> runOnUiThread { status(progress) } }.getOrThrow()
                ensureActive()
                currentSession = session
                status("Login concluído; consultando catálogo...")
                dashboard(session)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A temporary network/region error must not erase a valid refresh token.
                if (e is AuthenticationRequiredException) auth.clearSession()
                loginMenu()
                status(e.message ?: "Falha de autenticação", true)
            }
        }
    }
    private fun dashboard(session: XCloudSession) {
        catalogJob?.cancel()
        panel.visibility = View.VISIBLE; panel.removeAllViews()
        panel.addView(label("Consultando catálogo da sua conta..."))
        catalogJob = scope.launch {
            try {
                val games = XCloudSignalingClient(session).fetchCatalogGames()
                ensureActive()
                panel.removeAllViews()
                panel.addView(label("CATÁLOGO NATIVO — ${games.size} JOGOS"))
                val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                games.forEach { game -> list.addView(button("▶ ${game.name}") { launchGame(session, game.titleId, game.name) }) }
                list.addView(button("Atualizar catálogo") { dashboard(session) })
                list.addView(button("Trocar conta") { auth.clearSession(); currentSession = null; loginMenu() })
                panel.addView(ScrollView(this@MainActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
                status(if (games.isEmpty()) "Nenhum jogo disponível para esta conta/região" else "Escolha um jogo para iniciar")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                panel.removeAllViews()
                panel.addView(label("Não foi possível carregar o catálogo"))
                panel.addView(button("Repetir consulta") { dashboard(session) })
                panel.addView(button("Refazer autenticação") { auth.getSavedRpsTicket()?.let(::connect) ?: loginMenu() })
                panel.addView(button("Trocar conta") { auth.clearSession(); currentSession = null; loginMenu() })
                status(e.message ?: "Falha no catálogo", true)
            }
        }
    }

    private fun launchGame(session: XCloudSession, titleId: String, name: String) {
        if (sessionJob?.isActive == true) return
        val id = ++generation
        panel.visibility = View.GONE
        gamepad = GamepadState()
        renderer.prepareForSession()
        val firstFrame = CompletableDeferred<Unit>()
        renderer.onFirstFrameRendered = { if (id == generation) firstFrame.complete(Unit) }
        var renderError: Exception? = null
        renderer.onRenderError = { if (id == generation) renderError = IOException("VIDEO: falha ao anexar renderizador", it) }
        val signaling = XCloudSignalingClient(session)
        sessionJob = scope.launch {
            var localRtc: XCloudWebRtcClient? = null
            var failed = false
            try {
                status("Solicitando sessão de $name...")
                val client = XCloudWebRtcClient(this@MainActivity, renderer.eglBase,
                    onVideoTrackReady = { track -> runOnUiThread {
                        if (!destroyed && id == generation) renderer.attachVideoTrack(track)
                    } },
                    onConnectionStateChanged = { state -> runOnUiThread {
                        if (id == generation && !firstFrame.isCompleted) status("$name | $state")
                    } },
                    onServerDialog = { title, text, reply -> runOnUiThread {
                        if (destroyed || generation != id) reply(false)
                        else AlertDialog.Builder(this@MainActivity).setTitle(title).setMessage(text)
                            .setPositiveButton("Confirmar") { _, _ -> reply(true) }
                            .setNegativeButton("Cancelar") { _, _ -> reply(false) }
                            .setOnCancelListener { reply(false) }.show()
                    } })
                localRtc = client; rtc = client
                signaling.startAndConnectWebRtcSession(titleId, client) { progress -> runOnUiThread {
                    if (id == generation && !firstFrame.isCompleted) status(progress)
                } }.getOrThrow()
                touchPad.visibility = View.VISIBLE
                var pollsWithoutFrame = 0
                var lastKeepAlive = 0L
                while (isActive) {
                    renderError?.let { throw it }
                    client.checkHealthy()
                    if (++lastKeepAlive % 8L == 0L) signaling.keepAlive()
                    val stats = client.videoStats()
                    if (firstFrame.isCompleted) {
                        status("Vídeo nativo ativo | ${stats.codec.ifBlank { "codec negociado" }} | quadros decodificados: ${stats.decoded}")
                    } else {
                        status("Aguardando vídeo | bytes: ${stats.bytes} | quadros recebidos: ${stats.frames} | decodificados: ${stats.decoded}")
                        if (++pollsWithoutFrame >= 15) throw IOException("VIDEO: nenhum quadro renderizado; recebidos=${stats.frames}, decodificados=${stats.decoded}")
                    }
                    delay(2_000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; status(e.message ?: "Falha ao iniciar o jogo", true) }
            finally {
                if (id == generation) {
                    renderer.clearSession()
                    rtc = null
                    touchPad.visibility = View.GONE
                    panel.visibility = View.VISIBLE
                }
                localRtc?.close()
                withContext(NonCancellable + Dispatchers.IO) { withTimeoutOrNull(5_000) { runCatching { signaling.stop() } } }
                if (failed && id == generation) panel.visibility = View.VISIBLE
            }
        }
    }
    private fun stopGame() {
        generation++
        renderer.clearSession() // Detach the track before disposing native handles.
        rtc?.updateGamepad(GamepadState()); rtc?.close(); rtc = null
        sessionJob?.cancel(); sessionJob = null
        touchPad.visibility = View.GONE
        panel.visibility = View.VISIBLE
        status("Sessão encerrada")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun browserLogin() {
        loginJob?.cancel()
        val dialog = AlertDialog.Builder(this).create()
        loginDialog = dialog
        val completed = AtomicBoolean(false)
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                private fun consume(url: Uri): Boolean {
                    if (url.scheme != "https" || url.host != "login.live.com" || url.path != "/oauth20_desktop.srf") return false
                    val fragment = Uri.parse("https://callback.invalid/?${url.fragment.orEmpty()}")
                    val error = fragment.getQueryParameter("error") ?: url.getQueryParameter("error")
                    if (error != null) {
                        if (completed.compareAndSet(false, true)) {
                            dialog.dismiss()
                            status("AUTH_BROWSER: " + error.replace(Regex("[^A-Za-z0-9_.-]"), "").take(80), true)
                        }
                        return true
                    }
                    val token = fragment.getQueryParameter("access_token") ?: return false
                    if (completed.compareAndSet(false, true)) {
                        val expires = fragment.getQueryParameter("expires_in")?.toLongOrNull()
                        auth.saveTokens(token, expiresInSeconds = expires, clientId = XalAuthenticator.BROWSER_CLIENT_ID)
                        dialog.dismiss()
                        connect(auth.getSavedRpsTicket()!!)
                    }
                    return true
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    request?.url?.let(::consume) ?: false
                override fun onPageFinished(view: WebView?, url: String?) { url?.let { consume(Uri.parse(it)) } }
            }
        }
        dialog.setView(FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) })
        dialog.setOnDismissListener { view.stopLoading(); view.destroy(); loginDialog = null }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .9).toInt(), (resources.displayMetrics.heightPixels * .85).toInt())
        view.loadUrl("https://login.live.com/oauth20_authorize.srf?client_id=${XalAuthenticator.BROWSER_CLIENT_ID}&response_type=token&approval_prompt=auto&scope=service::user.auth.xboxlive.com::MBI_SSL&redirect_uri=https://login.live.com/oauth20_desktop.srf")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTouchControls() {
        listOf("←" to 1024, "↑" to 256, "↓" to 512, "→" to 2048, "A" to 16, "B" to 32, "X" to 64, "Y" to 128, "☰" to 4).forEach { (text, mask) ->
            val control = Button(this).apply {
                this.text = text; alpha = .65f; isFocusable = false
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { setButton(mask, true); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { setButton(mask, false); true }
                        else -> true
                    }
                }
            }
            touchPad.addView(control, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    private fun setButton(mask: Int, down: Boolean) {
        gamepad = gamepad.copy(buttons = if (down) gamepad.buttons or mask else gamepad.buttons and mask.inv())
        rtc?.updateGamepad(gamepad)
    }
    private fun mask(key: Int): Int? = when (key) {
        KeyEvent.KEYCODE_BUTTON_MODE -> 2; KeyEvent.KEYCODE_BUTTON_START -> 4; KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> 16; KeyEvent.KEYCODE_BUTTON_B -> 32
        KeyEvent.KEYCODE_BUTTON_X -> 64; KeyEvent.KEYCODE_BUTTON_Y -> 128
        KeyEvent.KEYCODE_DPAD_UP -> 256; KeyEvent.KEYCODE_DPAD_DOWN -> 512; KeyEvent.KEYCODE_DPAD_LEFT -> 1024; KeyEvent.KEYCODE_DPAD_RIGHT -> 2048
        KeyEvent.KEYCODE_BUTTON_L1 -> 4096; KeyEvent.KEYCODE_BUTTON_R1 -> 8192
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 16384; KeyEvent.KEYCODE_BUTTON_THUMBR -> 32768
        else -> null
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && panel.visibility == View.GONE) { stopGame(); return true }
        if (rtc != null) {
            mask(keyCode)?.let { setButton(it, true); return true }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                gamepad = if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) gamepad.copy(leftTrigger = 1f) else gamepad.copy(rightTrigger = 1f)
                rtc?.updateGamepad(gamepad); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (rtc != null) {
            mask(keyCode)?.let { setButton(it, false); return true }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                gamepad = if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) gamepad.copy(leftTrigger = 0f) else gamepad.copy(rightTrigger = 0f)
                rtc?.updateGamepad(gamepad); return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (rtc != null && event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            fun axis(code: Int): Float {
                val value = event.getAxisValue(code)
                val flat = event.device?.getMotionRange(code, event.source)?.flat ?: .1f
                return if (abs(value) <= flat) 0f else value.coerceIn(-1f, 1f)
            }
            fun trigger(code: Int, fallback: Int): Float {
                fun value(axis: Int): Float {
                    val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
                    if (range.max <= range.min) return 0f
                    return ((event.getAxisValue(axis) - range.min) / (range.max - range.min)).coerceIn(0f, 1f)
                }
                return maxOf(value(code), value(fallback))
            }
            val hatMask = (if (axis(MotionEvent.AXIS_HAT_X) < -.5f) 1024 else if (axis(MotionEvent.AXIS_HAT_X) > .5f) 2048 else 0) or
                (if (axis(MotionEvent.AXIS_HAT_Y) < -.5f) 256 else if (axis(MotionEvent.AXIS_HAT_Y) > .5f) 512 else 0)
            val rightX = if (event.device?.getMotionRange(MotionEvent.AXIS_Z, event.source) != null) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
            val rightY = if (event.device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
            gamepad = gamepad.copy(leftX = axis(MotionEvent.AXIS_X), leftY = axis(MotionEvent.AXIS_Y),
                buttons = (gamepad.buttons and 3840.inv()) or hatMask,
                rightX = axis(rightX), rightY = axis(rightY),
                leftTrigger = trigger(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
                rightTrigger = trigger(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS))
            rtc?.updateGamepad(gamepad); return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun onPause() {
        gamepad = GamepadState(); rtc?.updateGamepad(gamepad)
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true
        generation++
        scope.cancel()
        loginDialog?.dismiss()
        renderer.clearSession()
        rtc?.close(); rtc = null
        renderer.release()
        super.onDestroy()
    }
}
