package com.xcloud.tclnative.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.XCloudWebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var authenticator: XalAuthenticator
    private var webrtcClient: XCloudWebRtcClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        authenticator = XalAuthenticator(this)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Camada de Vídeo Nativa (SurfaceViewRenderer + AiPQ 4K Hardware Scaler)
        surfaceRenderer = AiPqSurfaceRenderer(this)
        rootLayout.addView(
            surfaceRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 2. HUD de Telemetria no Topo
        val androidVer = Build.VERSION.RELEASE
        val socModel = Build.HARDWARE
        hudTextView = TextView(this).apply {
            text = "xCloud Native [Android $androidVer | SoC: $socModel] | Decodificador H.264 Ativo"
            setTextColor(Color.GREEN)
            textSize = 12f
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(20, 8, 20, 8)
        }

        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 16
            marginStart = 16
        }
        rootLayout.addView(hudTextView, hudParams)

        // 3. Painel Central Dinâmico
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(225, 18, 18, 18))
            setPadding(48, 36, 48, 36)
        }

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        rootLayout.addView(controlPanel, panelParams)

        setContentView(rootLayout)

        val savedTicket = authenticator.getSavedRpsTicket()
        if (!savedTicket.isNullOrEmpty()) {
            connectToXboxCloud(savedTicket)
        } else {
            renderLoginMenu()
        }
    }

    private fun renderLoginMenu() {
        controlPanel.removeAllViews()
        controlPanel.visibility = View.VISIBLE

        val titleView = TextView(this).apply {
            text = "XBOX CLOUD GAMING - MOTOR NATIVO"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        controlPanel.addView(titleView)

        val btnAutoLogin = Button(this).apply {
            text = "1. Fazer Login Automático Microsoft (Recomendado)"
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                openAutoCaptureLoginWebView()
            }
        }
        controlPanel.addView(btnAutoLogin)

        val btnPasteToken = Button(this).apply {
            text = "2. Colar URL/Token Manualmente (Para TV)"
            isFocusable = true
            setOnClickListener {
                showTokenInputDialog()
            }
        }
        controlPanel.addView(btnPasteToken)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openAutoCaptureLoginWebView() {
        val dialog = AlertDialog.Builder(this).create()
        val loginWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.contains("access_token=")) {
                        val token = extractAccessToken(url)
                        view?.destroy()
                        dialog.dismiss()
                        connectToXboxCloud(token)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.contains("access_token=")) {
                        val token = extractAccessToken(url)
                        view?.destroy()
                        dialog.dismiss()
                        connectToXboxCloud(token)
                    }
                }
            }
            loadUrl(XalAuthenticator.getAuthorizationUrl())
        }

        val container = FrameLayout(this).apply {
            addView(loginWebView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        dialog.setView(container)
        dialog.show()
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    private fun showTokenInputDialog() {
        val inputEdit = EditText(this).apply {
            hint = "Cole aqui a URL final (login.live.com/oauth20_desktop.srf#access_token=...)"
            setTextColor(Color.BLACK)
        }

        AlertDialog.Builder(this)
            .setTitle("Vincular Conta Xbox Live")
            .setMessage("Após fazer login no navegador, copie o endereço completo da página em branco final e cole abaixo:")
            .setView(inputEdit)
            .setPositiveButton("Conectar ao xCloud") { _, _ ->
                val rawInput = inputEdit.text.toString().trim()
                val token = extractAccessToken(rawInput)
                if (token.isNotEmpty()) {
                    connectToXboxCloud(token)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun extractAccessToken(input: String): String {
        if (input.contains("access_token=")) {
            val afterToken = input.substringAfter("access_token=")
            val rawToken = afterToken.substringBefore("&")
            return Uri.decode(rawToken)
        }
        return input
    }

    private fun connectToXboxCloud(rpsTicket: String) {
        hudTextView.text = "Autenticando na Xbox Live e buscando catálogo oficial do Brasil..."
        hudTextView.setTextColor(Color.YELLOW)

        mainScope.launch {
            val result = authenticator.authenticateToXCloud(rpsTicket)
            result.onSuccess { session ->
                hudTextView.text = "CONECTADO! Região: ${session.baseUri.replace("https://", "")} | Carregando Jogos..."
                hudTextView.setTextColor(Color.GREEN)
                renderGameDashboard(session)
            }.onFailure { err ->
                hudTextView.text = "Falha na autenticação: ${err.message}"
                hudTextView.setTextColor(Color.RED)
                renderLoginMenu()
            }
        }
    }

    private fun renderGameDashboard(session: XCloudSession) {
        val signaling = XCloudSignalingClient(session)

        mainScope.launch {
            val games = signaling.fetchCatalogGames()
            controlPanel.removeAllViews()
            controlPanel.visibility = View.VISIBLE

            val header = TextView(this@MainActivity).apply {
                text = "CATÁLOGO OFICIAL XCLOUD (${games.size} TÍTULOS DISPONÍVEIS)"
                setTextColor(Color.GREEN)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }
            controlPanel.addView(header)

            val scroll = ScrollView(this@MainActivity)
            val gamesListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (game in games) {
                val btnGame = Button(this@MainActivity).apply {
                    text = "▶ ${game.name}"
                    isFocusable = true
                    setOnClickListener {
                        launchCloudGame(signaling, game.titleId, game.name)
                    }
                }
                gamesListLayout.addView(btnGame)
            }

            val btnLogout = Button(this@MainActivity).apply {
                text = "Sair da Conta / Trocar Utilizador"
                setOnClickListener {
                    authenticator.saveRpsTicket("")
                    renderLoginMenu()
                }
            }
            gamesListLayout.addView(btnLogout)

            scroll.addView(gamesListLayout)
            controlPanel.addView(scroll, LinearLayout.LayoutParams(820, 440))
            hudTextView.text = "Catálogo Carregado (${games.size} jogos) | Pronto para iniciar Stream H.264!"
        }
    }

    private fun launchCloudGame(signaling: XCloudSignalingClient, titleId: String, gameName: String) {
        controlPanel.visibility = View.GONE
        hudTextView.text = "Alocando Xbox Series X para [$gameName]..."
        hudTextView.setTextColor(Color.CYAN)

        webrtcClient?.close()
        val rtc = XCloudWebRtcClient(
            context = this,
            eglBase = surfaceRenderer.eglBase,
            onVideoTrackReady = { videoTrack ->
                surfaceRenderer.attachVideoTrack(videoTrack)
                runOnUiThread {
                    hudTextView.text = "VÍDEO H.264 RECEBIDO [$gameName] | Renderizador Nativo Ativo!"
                    hudTextView.setTextColor(Color.GREEN)
                }
            },
            onConnectionStateChanged = { iceState ->
                runOnUiThread {
                    hudTextView.text = "WebRTC ICE: $iceState | Jogo: $gameName"
                }
            }
        )
        webrtcClient = rtc

        mainScope.launch {
            val res = signaling.startAndConnectWebRtcSession(titleId, rtc) { progressMsg ->
                runOnUiThread {
                    hudTextView.text = progressMsg
                    hudTextView.setTextColor(Color.YELLOW)
                }
            }
            res.onSuccess { statusMsg ->
                hudTextView.text = statusMsg
                hudTextView.setTextColor(Color.GREEN)
            }.onFailure { err ->
                hudTextView.text = "Aviso de Sessão: ${err.message}"
                hudTextView.setTextColor(Color.RED)
                controlPanel.visibility = View.VISIBLE
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel.visibility == View.GONE) {
            controlPanel.visibility = View.VISIBLE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
