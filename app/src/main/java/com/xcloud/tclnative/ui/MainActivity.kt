package com.xcloud.tclnative.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.auth.XCloudSession
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.signaling.XCloudSignalingClient
import com.xcloud.tclnative.webrtc.XCloudWebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView
    private lateinit var overlayContainer: FrameLayout
    private var catalogWebView: WebView? = null

    @Volatile private var capturedGssvToken: String = ""
    @Volatile private var capturedBaseUri: String = "https://bra.gssv-play-prod.xboxlive.com"
    @Volatile private var isNativeStreamLaunching: Boolean = false

    private var webrtcClient: XCloudWebRtcClient? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        // 2. Contentor para o Catálogo Oficial xbox.com/pt-BR/play
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
            text = "xCloud Native [Android $androidVer | SoC: $socModel] | Toque em 'Jogar na nuvem' para ativar o Motor Nativo!"
            setTextColor(Color.GREEN)
            textSize = 11f
            setBackgroundColor(Color.argb(190, 0, 0, 0))
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

        // Abre direto o Catálogo Oficial com Cookies Persistentes (Sem Duplo Login!)
        openPlayXboxCatalogWebView()
    }

    /**
     * Ponte JavaScript -> Kotlin que intercepta o clique no botão "Jogar na nuvem"
     * antes que a WebView inicie o reprodutor pesado do navegador!
     */
    inner class NativeCloudBridge {
        @JavascriptInterface
        fun onCloudPlayIntercepted(playUrl: String, authHeader: String, bodyJson: String) {
            if (isNativeStreamLaunching) return
            isNativeStreamLaunching = true

            runOnUiThread {
                try {
                    if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                        capturedGssvToken = authHeader.substring(7).trim()
                    }
                    if (playUrl.startsWith("https://")) {
                        val host = android.net.Uri.parse(playUrl).host
                        if (!host.isNullOrEmpty()) {
                            capturedBaseUri = "https://$host"
                        }
                    }

                    val json = JSONObject(bodyJson)
                    val titleId = json.optString("titleId", "")

                    if (titleId.isNotEmpty() && capturedGssvToken.isNotEmpty()) {
                        CookieManager.getInstance().flush()
                        // DESTRÓI a WebView imediatamente para o jogo NÃO rodar no navegador!
                        destroyCatalogWebView()
                        overlayContainer.visibility = View.GONE

                        val session = XCloudSession(
                            gssvToken = capturedGssvToken,
                            baseUri = capturedBaseUri,
                            userHash = "native"
                        )
                        launchNativeCloudStream(session, titleId)
                    } else {
                        isNativeStreamLaunching = false
                    }
                } catch (e: Exception) {
                    isNativeStreamLaunching = false
                    hudTextView.text = "Erro ao interceptar jogo: ${e.message}"
                    hudTextView.setTextColor(Color.RED)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPlayXboxCatalogWebView() {
        isNativeStreamLaunching = false
        destroyCatalogWebView()
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.VISIBLE

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

            cookieManager.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(NativeCloudBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val req = request ?: return null
                    val url = req.url.toString()

                    // Captura automaticamente o Token GSSV e o Servidor Brasil dos cabeçalhos de rede!
                    if (url.contains(".gssv-play-prod.xboxlive.com")) {
                        val auth = req.requestHeaders["Authorization"] ?: req.requestHeaders["authorization"]
                        if (!auth.isNullOrEmpty() && auth.startsWith("Bearer ", ignoreCase = true)) {
                            capturedGssvToken = auth.substring(7).trim()
                            req.url.host?.let { capturedBaseUri = "https://$it" }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectFetchInterceptor(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    injectFetchInterceptor(view)
                }
            }

            // URL Oficial Brasileira (Evita a tela 404 "Você está perdido no espaço!")
            loadUrl("https://www.xbox.com/pt-BR/play")
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
     * Injeta o gancho no window.fetch da página para interceptar o botão "Jogar na nuvem"
     * no exato milissegundo em que ele tenta chamar /v5/sessions/cloud/play!
     */
    private fun injectFetchInterceptor(view: WebView?) {
        val jsHook = """
            (function() {
                if (window.__tclNativeHooked) return;
                window.__tclNativeHooked = true;
                const origFetch = window.fetch;
                window.fetch = async function(input, init) {
                    try {
                        const url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        if (url.includes('/sessions/cloud/play')) {
                            let bodyText = '';
                            if (init && typeof init.body === 'string') {
                                bodyText = init.body;
                            } else if (input && typeof input.clone === 'function') {
                                bodyText = await input.clone().text();
                            }
                            let auth = '';
                            if (init && init.headers) {
                                if (typeof init.headers.get === 'function') {
                                    auth = init.headers.get('Authorization') || init.headers.get('authorization') || '';
                                } else {
                                    auth = init.headers['Authorization'] || init.headers['authorization'] || '';
                                }
                            }
                            if (window.AndroidBridge && bodyText) {
                                window.AndroidBridge.onCloudPlayIntercepted(url, auth, bodyText);
                                return new Promise(function() {}); // Congela a WebView imediatamente!
                            }
                        }
                    } catch (e) {}
                    return origFetch.apply(this, arguments);
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(jsHook, null)
    }

    private fun destroyCatalogWebView() {
        catalogWebView?.let { wv ->
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                overlayContainer.removeView(wv)
                wv.destroy()
            } catch (ignored: Exception) {}
        }
        catalogWebView = null
    }

    private fun launchNativeCloudStream(session: XCloudSession, titleId: String) {
        val signaling = XCloudSignalingClient(session)
        hudTextView.text = "MOTOR NATIVO ATIVADO: Iniciando [$titleId] (WebView Destruída | Zero Delay)..."
        hudTextView.setTextColor(Color.CYAN)

        webrtcClient?.close()
        val rtc = XCloudWebRtcClient(
            context = this,
            eglBase = surfaceRenderer.eglBase,
            onVideoTrackReady = { videoTrack ->
                surfaceRenderer.attachVideoTrack(videoTrack)
                runOnUiThread {
                    hudTextView.text = "STREAM NATIVO H.264 ATIVO [$titleId] | Renderizador Hardware 1080p->4K!"
                    hudTextView.setTextColor(Color.GREEN)
                }
            },
            onConnectionStateChanged = { iceState ->
                runOnUiThread {
                    hudTextView.text = "WebRTC Nativo ICE: $iceState | Jogo: $titleId"
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
                isNativeStreamLaunching = false
                hudTextView.text = "Aviso Nativo [$titleId]: ${err.message} (Pressione Voltar para o Catálogo)"
                hudTextView.setTextColor(Color.RED)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (catalogWebView?.canGoBack() == true) {
                catalogWebView?.goBack()
                return true
            }
            if (overlayContainer.visibility == View.GONE) {
                webrtcClient?.close()
                openPlayXboxCatalogWebView()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        destroyCatalogWebView()
        webrtcClient?.close()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
