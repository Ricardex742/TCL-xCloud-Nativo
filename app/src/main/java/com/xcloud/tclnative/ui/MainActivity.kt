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
    private var currentSession: XCloudSession? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        authenticator = XalAuthenticator(this)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Camada de Vídeo Nativa (Hardware Overlay 1080p -> 4K AiPQ na TCL P755 / Low-Latency no Snapdragon)
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
            text = "xCloud Native [Android $androidVer | SoC: $socModel] | Decodificador H.264 Ativo | Status: Desconectado"
            setTextColor(Color.GREEN)
            textSize = 13f
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(24, 12, 24, 12)
        }

        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 24
            marginStart = 24
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

        // Verifica se já existe login salvo
        val savedTicket = authenticator.getSavedRpsTicket()
        if (savedTicket != null) {
            connectToXboxCloud(savedTicket)
        } else {
            renderLoginMenu()
        }
    }

    private fun renderLoginMenu() {
        controlPanel.removeAllViews()

        val titleView = TextView(this).apply {
            text = "XBOX CLOUD GAMING - MOTOR NATIVO"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        controlPanel.addView(titleView)

        // Botão Novo: Faz login e captura o Token 100% sozinho!
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

    /**
     * Abre uma janela temporária apenas para capturar o token da Microsoft automaticamente
     * e depois a destrói completamente da memória RAM!
     */
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
                        // Destrói o WebView imediatamente para liberar 100% da RAM
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

        // Necessário para o teclado do celular abrir dentro do AlertDialog
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
            .setMessage("Após logar no navegador, copie o endereço completo da página em branco final e cole abaixo:")
            .setView(inputEdit)
            .setPositiveButton("Conectar ao xCloud") { _, _ ->
                val rawInput = inputEdit.text.toString().trim()
                val token = extractAccessToken(rawInput)
                if (token.isNotEmpty()) {
                    connectToXboxCloud(token)
                } else {
                    hudTextView.text = "Erro: Token inválido ou vazio."
                    hudTextView.setTextColor(Color.RED)
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
        hudTextView.text = "Autenticando na Xbox Live e buscando servidor xCloud Brasil..."
        hudTextView.setTextColor(Color.YELLOW)

        mainScope.launch {
            val result = authenticator.authenticateToXCloud(rpsTicket)
            result.onSuccess { session ->
                currentSession = session
                hudTextView.text = "CONECTADO! Região: ${session.baseUri.replace("https://", "")} | AiPQ 4K & Low-Latency Prontos"
                hudTextView.setTextColor(Color.GREEN)
                renderGameDashboard(session)
            }.onFailure { err ->
                hudTextView.text = "Falha na autenticação: ${err.message}"
                hudTextView.setTextColor(Color.RED)
                renderLoginMenu()
            }
        }
    }

    /**
     * Exibe o Painel de Jogos do xCloud após o login ser concluído com sucesso!
     */
    private fun renderGameDashboard(session: XCloudSession) {
        controlPanel.removeAllViews()
        val signaling = XCloudSignalingClient(session)

        val header = TextView(this).apply {
            text = "CATÁLOGO RÁPIDO XCLOUD (SERVIDOR BRASIL)"
            setTextColor(Color.GREEN)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        controlPanel.addView(header)

        val scroll = ScrollView(this)
        val gamesListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (game in signaling.getQuickLaunchGames()) {
            val btnGame = Button(this).apply {
                text = "▶ Iniciar: ${game.name}"
                isFocusable = true
                setOnClickListener {
                    launchCloudGame(signaling, game.titleId, game.name)
                }
            }
            gamesListLayout.addView(btnGame)
        }

        val btnLogout = Button(this).apply {
            text = "Sair da Conta / Trocar Usuário"
            setOnClickListener {
                authenticator.saveRpsTicket("")
                renderLoginMenu()
            }
        }
        gamesListLayout.addView(btnLogout)

        scroll.addView(gamesListLayout)
        controlPanel.addView(scroll, LinearLayout.LayoutParams(750, 420))
    }

    private fun launchCloudGame(signaling: XCloudSignalingClient, titleId: String, gameName: String) {
        hudTextView.text = "Alocando Xbox Series X para [$gameName] com H.264 Baseline (Zero B-Frames)..."
        hudTextView.setTextColor(Color.CYAN)
        controlPanel.visibility = View.GONE

        mainScope.launch {
            val res = signaling.startGameSession(titleId)
            res.onSuccess { statusMsg ->
                hudTextView.text = "STREAM ATIVO: $statusMsg | Renderizador Nativo 1080p->4K"
                hudTextView.setTextColor(Color.GREEN)
            }.onFailure { err ->
                hudTextView.text = "Aviso de Sessão: ${err.message}"
                hudTextView.setTextColor(Color.YELLOW)
                controlPanel.visibility = View.VISIBLE
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Se apertar o botão Voltar enquanto o painel estiver oculto, reabre o menu de jogos
        if (keyCode == KeyEvent.KEYCODE_BACK && controlPanel.visibility == View.GONE) {
            controlPanel.visibility = View.VISIBLE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
