package com.xcloud.tclnative.ui

import android.annotation.SuppressLint
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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private var qrPollingJob: Job? = null

    companion object {
        private const val OAUTH_AUTHORIZE_URL =
            "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=000000004c12ae6f" +
                "&response_type=token" +
                "&approval_prompt=auto" +
                "&scope=service::user.auth.xboxlive.com::MBI_SSL" +
                "&redirect_uri=https://login.live.com/oauth20_desktop.srf"
    }

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
            text = "xCloud Native [Android $androidVer | SoC: $socModel] | Decodificador H.264 Ativo"
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

        setContentView(rootLayout)

        val savedTicket = authenticator.getSavedRpsTicket()
        if (!savedTicket.isNullOrEmpty()) {
            connectToXboxCloud(savedTicket)
        } else {
            renderLoginMenu()
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
            text = "1. Entrar via QR Code Oficial (TV / Celular)"
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { startQrCodeLoginFlow() }
        }
        controlPanel.addView(btnQrLogin)

        val btnAutoLogin = Button(this).apply {
            text = "2. Login Direto de 1 Toque (Navegador)"
            isFocusable = true
            setOnClickListener { openAutoCaptureLoginWebView() }
        }
        controlPanel.addView(btnAutoLogin)
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
                stepsText.text = "Escaneie o QR Code ou acesse\nmicrosoft.com/link com o código:"
                codeText.text = info.userCode
                btnApprovePhone.visibility = View.VISIBLE
                btnApprovePhone.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.verificationUriComplete)))
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
            loadUrl(OAUTH_AUTHORIZE_URL)
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
                hudTextView.text = "CONECTADO! Região: ${session.baseUri.replace("https://", "")} | Sincronizando Catálogo..."
                hudTextView.setTextColor(Color.GREEN)
                renderGameDashboard(session)
            }.onFailure { err ->
                hudTextView.text = "Falha na autenticação: ${err.message}"
                hudTextView.setTextColor(Color.RED)
                authenticator.clearSession()
                renderLoginMenu()
            }
        }
    }

    private fun renderGameDashboard(session: XCloudSession) {
        val signaling = XCloudSignalingClient(session)

        mainScope.launch {
            val allGames = signaling.fetchCatalogGames()
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

            hudTextView.text = "Catálogo Oficial Pronto (${allGames.size} títulos) | H.264 Pure SDP Ativo!"
            hudTextView.setTextColor(Color.GREEN)
        }
    }

    private fun launchCloudGame(signaling: XCloudSignalingClient, titleId: String, gameName: String) {
        controlPanel.visibility = View.GONE
        hudTextView.visibility = View.VISIBLE
        hudTextView.text = "Alocando Xbox Series X e pré-aquecendo SDP H.264 para [$gameName]..."
        hudTextView.setTextColor(Color.CYAN)

        webrtcClient?.close()
        val rtc = XCloudWebRtcClient(
            context = this,
            eglBase = surfaceRenderer.eglBase,
            onVideoTrackReady = { videoTrack ->
                surfaceRenderer.attachVideoTrack(videoTrack)
                runOnUiThread {
                    hudTextView.text = "STREAM H.264 ATIVO [$gameName] | Renderizador Nativo 1080p->4K!"
                    hudTextView.setTextColor(Color.GREEN)
                    hudTextView.postDelayed({
                        if (controlPanel.visibility == View.GONE) {
                            hudTextView.visibility = View.GONE
                        }
                    }, 5000)
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
                val fullError = err.message ?: "Erro desconhecido"
                hudTextView.text = fullError.lines().firstOrNull() ?: "Falha na sessão"
                hudTextView.setTextColor(Color.RED)
                controlPanel.visibility = View.VISIBLE
                showCopyableErrorDialog(gameName, fullError)
            }
        }
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
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel.visibility == View.GONE) {
            webrtcClient?.close()
            hudTextView.visibility = View.VISIBLE
            controlPanel.visibility = View.VISIBLE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        qrPollingJob?.cancel()
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
