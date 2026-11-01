package com.xcloud.tclnative.ui

import android.annotation.SuppressLint
import android.app.Activity
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var overlayContainer: FrameLayout
    private var catalogWebView: WebView? = null

    private lateinit var authenticator: XalAuthenticator
    private var currentSession: XCloudSession? = null
    private var webrtcClient: XCloudWebRtcClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private var qrPollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        authenticator = XalAuthenticator(this)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Camada de Vídeo Nativa (Hardware Overlay 1080p -> 4K AiPQ na TV)
        surfaceRenderer = AiPqSurfaceRenderer(this)
        rootLayout.addView(
            surfaceRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 2. Contentor para a Tela de QR Code ou Catálogo play.xbox.com
        overlayContainer = FrameLayout(this)
        rootLayout.addView(
            overlayContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 3. HUD de Telemetria no Topo
        val androidVer = Build.VERSION.RELEASE
        val socModel = Build.HARDWARE
        hudTextView = TextView(this).apply {
            text = "xCloud Native [Android $androidVer | SoC: $socModel] | AiPQ 4K & Low-Latency"
            setTextColor(Color.GREEN)
            textSize = 11f
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(18, 6, 18, 6)
        }

        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 12
            marginStart = 12
        }
        rootLayout.addView(hudTextView, hudParams)

        setContentView(rootLayout)

        // Se já tiver login salvo, conecta direto; senão, inicia o Login por QR Code!
        val savedTicket = authenticator.getSavedRpsTicket()
        if (!savedTicket.isNullOrEmpty()) {
            authenticateAndOpenPlayXboxCatalog(savedTicket)
        } else {
            startQrCodeLoginFlow()
        }
    }

    /**
     * Exibe o QR Code Oficial na tela (Estilo Xbox / Smart TV) e aguarda aprovação automática
     */
    private fun startQrCodeLoginFlow() {
        qrPollingJob?.cancel()
        destroyCatalogWebView()
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.VISIBLE

        val qrPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(240, 15, 15, 20))
            setPadding(48, 36, 48, 36)
        }

        val qrImageView = ImageView(this).apply {
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
        }
        qrPanel.addView(qrImageView, LinearLayout.LayoutParams(420, 420))

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 0, 0, 0)
        }

        val titleText = TextView(this).apply {
            text = "ENTRAR COM QR CODE XBOX"
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        infoColumn.addView(titleText)

        val stepsText = TextView(this).apply {
            text = "Gerando código oficial na Microsoft..."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }
        infoColumn.addView(stepsText)

        val codeText = TextView(this).apply {
            text = "--------"
            setTextColor(Color.GREEN)
            textSize = 28f
            setPadding(0, 4, 0, 16)
        }
        infoColumn.addView(codeText)

        val btnOpenOnThisPhone = Button(this).apply {
            text = "Aprovar Neste Celular (Abrir Link Direto)"
            visibility = View.GONE
        }
        infoColumn.addView(btnOpenOnThisPhone)

        val btnRefreshQr = Button(this).apply {
            text = "Gerar Novo QR Code"
            setOnClickListener { startQrCodeLoginFlow() }
        }
        infoColumn.addView(btnRefreshQr)

        qrPanel.addView(infoColumn)
        overlayContainer.addView(
            qrPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        qrPollingJob = mainScope.launch {
            hudTextView.text = "Solicitando QR Code OAuth2 na Microsoft..."
            val qrRes = authenticator.requestDeviceCodeQr()
            qrRes.onSuccess { info ->
                qrImageView.setImageBitmap(info.qrBitmap)
                stepsText.text = "1. Escaneie o QR Code com a câmera do celular\n   ou acesse: microsoft.com/link\n2. Digite o código abaixo:"
                codeText.text = info.userCode

                btnOpenOnThisPhone.visibility = View.VISIBLE
                btnOpenOnThisPhone.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.verificationUriComplete))
                    startActivity(intent)
                }

                hudTextView.text = "Aguardando aprovação do QR Code [${info.userCode}]..."
                hudTextView.setTextColor(Color.YELLOW)

                val pollRes = authenticator.pollForDeviceCodeLogin(info)
                pollRes.onSuccess { rpsTicket ->
                    hudTextView.text = "QR Code Aprovado! Conectando ao xCloud Brasil..."
                    hudTextView.setTextColor(Color.GREEN)
                    authenticateAndOpenPlayXboxCatalog(rpsTicket)
                }.onFailure { err ->
                    stepsText.text = "Aviso: ${err.message}"
                    hudTextView.text = "QR Code expirado. Clique em Gerar Novo QR Code."
                    hudTextView.setTextColor(Color.RED)
                }
            }.onFailure { err ->
                stepsText.text = "Falha de rede: ${err.message}"
                hudTextView.setTextColor(Color.RED)
            }
        }
    }

    private fun authenticateAndOpenPlayXboxCatalog(rpsTicket: String) {
        hudTextView.text = "Autenticando na Xbox Live e preparando catálogo play.xbox.com..."
        hudTextView.setTextColor(Color.YELLOW)

        mainScope.launch {
            val result = authenticator.authenticateToXCloud(rpsTicket)
            result.onSuccess { session ->
                currentSession = session
                hudTextView.text = "Catálogo play.xbox.com Ativo | Ao clicar em JOGAR, o Motor Nativo assumirá!"
                hudTextView.setTextColor(Color.GREEN)
                openPlayXboxCatalogWebView(session)
            }.onFailure { err ->
                hudTextView.text = "Sessão expirada (${err.message}). Iniciando QR Code..."
                hudTextView.setTextColor(Color.RED)
                authenticator.clearSession()
                startQrCodeLoginFlow()
            }
        }
    }

    /**
     * Carrega a interface visual oficial https://play.xbox.com, mas INTERCEPTA o clique de lançamento
     * (/launch/{titleId}) para rodar o jogo no nosso Renderizador Nativo AiPQ 4K (destruindo a WebView)!
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun openPlayXboxCatalogWebView(session: XCloudSession) {
        destroyCatalogWebView()
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.VISIBLE

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // User-Agent otimizado para exibir a interface de TV/Desktop do play.xbox.com
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (interceptPlayXboxLaunchUrl(url, session)) {
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null) {
                        interceptPlayXboxLaunchUrl(url, session)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // Captura mudanças de rota Single-Page Application (React Router do play.xbox.com)
                    if (url != null) {
                        interceptPlayXboxLaunchUrl(url, session)
                    }
                }
            }
            loadUrl("https://play.xbox.com/pt-BR")
        }

        catalogWebView = webView
        overlayContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * Detecta quando o usuário clica em "Jogar" dentro do play.xbox.com (ex: /launch/FORTNITE ou /launch/9N...)
     * e transfere imediatamente a execução para o Motor WebRTC Nativo!
     */
    private fun interceptPlayXboxLaunchUrl(url: String, session: XCloudSession): Boolean {
        if (url.contains("/launch/", ignoreCase = true)) {
            val afterLaunch = url.substringAfter("/launch/", "")
            val extractedTitleId = afterLaunch.substringBefore("/").substringBefore("?").trim()
            if (extractedTitleId.isNotEmpty()) {
                // DESTRÓI a WebView do catálogo imediatamente para liberar 100% da RAM (2GB na TCL P755)!
                destroyCatalogWebView()
                overlayContainer.visibility = View.GONE
                launchNativeCloudStream(session, extractedTitleId)
                return true
            }
        }
        return false
    }

    private fun destroyCatalogWebView() {
        catalogWebView?.let { wv ->
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                overlayContainer.removeView(wv)
                wv.destroy()
            } catch (ignored: Exception) {}
        }
        catalogWebView = null
    }

    private fun launchNativeCloudStream(session: XCloudSession, titleId: String) {
        val signaling = XCloudSignalingClient(session)
        hudTextView.text = "HANDOFF NATIVO: Iniciando [$titleId] no Motor H.264 AiPQ 4K (WebView Destruída)..."
        hudTextView.setTextColor(Color.CYAN)

        webrtcClient?.close()
        val rtc = XCloudWebRtcClient(
            context = this,
            eglBase = surfaceRenderer.eglBase,
            onVideoTrackReady = { videoTrack ->
                surfaceRenderer.attachVideoTrack(videoTrack)
                runOnUiThread {
                    hudTextView.text = "STREAM NATIVO ATIVO [$titleId] | 1080p -> AiPQ 4K"
                    hudTextView.setTextColor(Color.GREEN)
                }
            },
            onConnectionStateChanged = { iceState ->
                runOnUiThread {
                    hudTextView.text = "WebRTC ICE: $iceState | Jogo: $titleId"
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
                hudTextView.text = "Erro ao iniciar [$titleId]: ${err.message} (Pressione Voltar para o Catálogo)"
                hudTextView.setTextColor(Color.RED)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Se apertar Voltar durante o jogo ou erro, recarrega o catálogo play.xbox.com
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (catalogWebView?.canGoBack() == true) {
                catalogWebView?.goBack()
                return true
            }
            val session = currentSession
            if (overlayContainer.visibility == View.GONE && session != null) {
                webrtcClient?.close()
                openPlayXboxCatalogWebView(session)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        qrPollingJob?.cancel()
        destroyCatalogWebView()
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
