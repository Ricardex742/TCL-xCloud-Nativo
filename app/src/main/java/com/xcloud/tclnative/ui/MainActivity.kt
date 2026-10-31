package com.xcloud.tclnative.ui

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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xcloud.tclnative.auth.XalAuthenticator
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var authenticator: XalAuthenticator
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        authenticator = XalAuthenticator(this)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Camada de Vídeo Nativa (Hardware Overlay 1080p -> 4K AiPQ na TV / Low-Latency no Android 10)
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
            setBackgroundColor(Color.argb(180, 0, 0, 0))
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

        // 3. Painel Central de Login (Compatível com Toque no Celular e Controle na TCL P755)
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(210, 20, 20, 20))
            setPadding(48, 48, 48, 48)
        }

        val titleView = TextView(this).apply {
            text = "XBOX CLOUD GAMING - MOTOR NATIVO"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        controlPanel.addView(titleView)

        val btnOpenBrowser = Button(this).apply {
            text = "1. Abrir Login Microsoft (Navegador)"
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(XalAuthenticator.getAuthorizationUrl()))
                startActivity(intent)
            }
        }
        controlPanel.addView(btnOpenBrowser)

        val btnPasteToken = Button(this).apply {
            text = "2. Inserir URL/Token de Autenticação"
            isFocusable = true
            setOnClickListener {
                showTokenInputDialog()
            }
        }
        controlPanel.addView(btnPasteToken)

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        rootLayout.addView(controlPanel, panelParams)

        setContentView(rootLayout)

        // Se já tiver um token salvo anteriormente, autentica sozinho ao abrir!
        authenticator.getSavedRpsTicket()?.let { savedTicket ->
            connectToXboxCloud(savedTicket)
        }
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
        // Se o usuário colou a URL inteira de redirecionamento, extrai apenas o access_token
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
                hudTextView.text = "CONECTADO! Servidor: ${session.baseUri} | UHS: ${session.userHash} | AiPQ Ready"
                hudTextView.setTextColor(Color.GREEN)
                // Esconde o painel de login para liberar a tela de vídeo/catálogo
                controlPanel.visibility = View.GONE
            }.onFailure { err ->
                hudTextView.text = "Falha na autenticação: ${err.message}"
                hudTextView.setTextColor(Color.RED)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }
}
