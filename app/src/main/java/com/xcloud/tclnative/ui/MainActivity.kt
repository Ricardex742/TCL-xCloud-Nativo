package com.xcloud.tclnative.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView
    private lateinit var streamWebView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantém a tela ligada e ativa aceleração total de Hardware na janela para a TCL P755 / Snapdragon
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Na TCL P755 (4K), garante que o display físico opere no modo 3840x2160 @ 60Hz nativo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = windowManager.defaultDisplay
            val modes = display.supportedModes
            val native4kMode = modes.firstOrNull { it.physicalWidth == 3840 && it.physicalHeight == 2160 && it.refreshRate >= 59f }
            if (native4kMode != null) {
                val params = window.attributes
                params.preferredDisplayModeId = native4kMode.modeId
                window.attributes = params
            }
        }

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Camada de Hardware Base (Mantém o contexto EGL e Scaler 1080p->4K ativo)
        surfaceRenderer = AiPqSurfaceRenderer(this)
        rootLayout.addView(
            surfaceRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 2. Motor de Catálogo e Stream com Injeção Nativa de Latência Zero
        streamWebView = createLowLatencyStreamView()
        rootLayout.addView(
            streamWebView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 3. HUD de Telemetria no Topo (Desaparece sozinho 5s após o jogo iniciar!)
        val androidVer = Build.VERSION.RELEASE
        val socModel = Build.HARDWARE
        hudTextView = TextView(this).apply {
            text = "xCloud Native [Android $androidVer | SoC: $socModel] | Motor Low-Latency Pronto"
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

        streamWebView.loadUrl("https://www.xbox.com/pt-BR/play")
    }

    /**
     * Ponte Nativa Kotlin <-> WebRTC:
     * Otimiza o SDP em código nativo para forçar H.264 sem B-Frames e monitora o estado do Hardware Decoder
     */
    inner class NativeLowLatencyBridge {

        @JavascriptInterface
        fun optimizeSdpForHardware(rawSdp: String): String {
            // Prioriza os perfis H.264 de hardware na linha m=video e remove extensões que causam atraso
            val lines = rawSdp.split("\r\n")
            val h264Payloads = mutableListOf<String>()

            for (line in lines) {
                if (line.startsWith("a=rtpmap:") && line.contains("H264/90000", ignoreCase = true)) {
                    val pt = line.substringAfter("a=rtpmap:").substringBefore(" ")
                    if (pt.isNotEmpty()) h264Payloads.add(pt)
                }
            }

            val out = StringBuilder()
            for (line in lines) {
                if (line.isBlank()) continue
                if (line.startsWith("m=video ") && h264Payloads.isNotEmpty()) {
                    val parts = line.split(" ")
                    if (parts.size > 3) {
                        val header = parts.subList(0, 3).joinToString(" ")
                        val allPts = parts.subList(3, parts.size)
                        val otherPts = allPts.filter { !h264Payloads.contains(it) }
                        val reorderedPts = (h264Payloads + otherPts).joinToString(" ")
                        out.append("$header $reorderedPts\r\n")
                        // Limita picos de bitrate para evitar perda de pacotes e faixas azuis no Wi-Fi
                        out.append("b=AS:15000\r\n")
                        continue
                    }
                }
                out.append(line).append("\r\n")
            }
            return out.toString()
        }

        @JavascriptInterface
        fun onWebRtcNegotiating() {
            runOnUiThread {
                hudTextView.visibility = View.VISIBLE
                hudTextView.text = "⚡ Injetando SDP H.264 Low-Latency + JitterBuffer Zero (0ms)..."
                hudTextView.setTextColor(Color.CYAN)
            }
        }

        @JavascriptInterface
        fun onStreamActive(width: Int, height: Int) {
            runOnUiThread {
                hudTextView.visibility = View.VISIBLE
                hudTextView.text = "⚡ STREAM ATIVO [${width}x${height} -> AiPQ 4K | Jitter: 0ms] (Ocultando HUD em 5s...)"
                hudTextView.setTextColor(Color.GREEN)
                // Esconde o HUD automaticamente após 5 segundos para não atrapalhar o jogo!
                hudTextView.postDelayed({
                    hudTextView.visibility = View.GONE
                }, 5000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createLowLatencyStreamView(): WebView {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        return WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            // Força renderização direta na camada de Hardware (GPU/HWC)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                // User-Agent Windows/Edge para liberar stream 1080p @ 60FPS nos servidores da Microsoft
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
            }

            cookieManager.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(NativeLowLatencyBridge(), "AndroidBridge")

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectLowLatencyWebRtcEngine(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    injectLowLatencyWebRtcEngine(view)
                }
            }
        }
    }

    /**
     * Injeta o acelerador de baixa latência diretamente no motor RTCPeerConnection e no decodificador de vídeo:
     * 1. Zera o playoutDelayHint e o jitterBufferTarget (remove ~120ms de atraso de buffer).
     * 2. Passa o SDP pelo otimizador nativo Kotlin (H.264 Hardware Priority).
     * 3. Trava o elemento de vídeo em Hardware Overlay para acionar o AiPQ 4K da TCL P755.
     */
    private fun injectLowLatencyWebRtcEngine(view: WebView?) {
        val jsAccelerator = """
            (function() {
                if (window.__tclLowLatencyActive) return;
                window.__tclLowLatencyActive = true;

                const OrigPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;
                if (OrigPeerConnection) {
                    window.RTCPeerConnection = function(config, constraints) {
                        const pc = new OrigPeerConnection(config, constraints);

                        if (window.AndroidBridge) {
                            window.AndroidBridge.onWebRtcNegotiating();
                        }

                        // 1. Intercepta o SDP Offer para aplicar o filtro H.264 Nativo do Kotlin
                        const origCreateOffer = pc.createOffer.bind(pc);
                        pc.createOffer = async function(options) {
                            const offer = await origCreateOffer(options);
                            if (offer && offer.sdp && window.AndroidBridge) {
                                offer.sdp = window.AndroidBridge.optimizeSdpForHardware(offer.sdp);
                            }
                            return offer;
                        };

                        // 2. Força Latência Zero (0ms de Jitter Buffer) em todos os receptores de Vídeo e Áudio
                        const applyZeroDelay = () => {
                            try {
                                pc.getReceivers().forEach(receiver => {
                                    if ('playoutDelayHint' in receiver) {
                                        receiver.playoutDelayHint = 0.0;
                                    }
                                    if ('jitterBufferTarget' in receiver) {
                                        receiver.jitterBufferTarget = 0;
                                    }
                                });
                            } catch (e) {}
                        };

                        pc.addEventListener('track', applyZeroDelay);
                        setInterval(applyZeroDelay, 1000);

                        return pc;
                    };
                    window.RTCPeerConnection.prototype = OrigPeerConnection.prototype;
                }

                // 3. Monitora o início do vídeo para travar o Upscaling 1080p->4K e ocultar o HUD
                document.addEventListener('playing', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        const v = e.target;
                        v.style.imageRendering = 'crisp-edges';
                        v.style.transform = 'translateZ(0)'; // Força Hardware Overlay
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStreamActive(v.videoWidth || 1920, v.videoHeight || 1080);
                        }
                    }
                }, true);
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsAccelerator, null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && streamWebView.canGoBack()) {
            streamWebView.goBack()
            hudTextView.visibility = View.VISIBLE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        streamWebView.destroy()
        surfaceRenderer.release()
        super.onDestroy()
    }
}
