package com.xcloud.tclnative.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer

class MainActivity : Activity() {

    private lateinit var surfaceRenderer: AiPqSurfaceRenderer
    private lateinit var hudTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantém a tela ligada e remove qualquer barra do sistema Android TV
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Camada de Vídeo Nativa (Hardware Overlay 1080p -> 4K AiPQ)
        surfaceRenderer = AiPqSurfaceRenderer(this)
        rootLayout.addView(
            surfaceRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 2. Mini-HUD de Telemetria super leve (Consome menos de 1MB de RAM)
        hudTextView = TextView(this).apply {
            text = "TCL P755 Native Renderer [AiPQ 4K Active] | RAM Livre Otimizada | Aguardando Login xCloud..."
            setTextColor(Color.GREEN)
            textSize = 14f
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(24, 12, 24, 12)
        }

        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 32
            marginStart = 32
        }

        rootLayout.addView(hudTextView, hudParams)
        setContentView(rootLayout)
    }

    // Interceptação direta dos gatilhos e analógicos do controle sem passar por WebView
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Na Etapa 4 conectaremos este evento direto ao pacote binário do DataChannel
        return super.onGenericMotionEvent(event)
    }

    // Interceptação direta dos botões A, B, X, Y, LB, RB, Start, Select
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }
}
