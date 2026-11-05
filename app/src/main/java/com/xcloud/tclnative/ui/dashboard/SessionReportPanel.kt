package com.xcloud.tclnative.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.xcloud.tclnative.diagnostics.*

/** Native post-match view, built only after playback ends. */
object SessionReportPanel {
    fun show(context: Context,report: SessionReport,disableAutomatic: (() -> Unit)?=null): ConsoleDialog {
        val dialog=ConsoleDialog(context,"Diagnóstico pós-partida")
        val accent=when { report.score==null -> DashboardTheme.muted; report.score>=75 -> DashboardTheme.accent; report.score>=60 -> 0xffffcd66.toInt(); else -> 0xffff826d.toInt() }
        val hero=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val gauge=object: View(context) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER; typeface=DashboardTheme.font(context) }
            override fun onDraw(canvas: Canvas) {
                val size=minOf(width,height).toFloat(); val inset=size*.08f
                paint.style=Paint.Style.STROKE; paint.strokeWidth=size*.06f; paint.strokeCap=Paint.Cap.ROUND
                paint.color=DashboardTheme.border; canvas.drawArc(inset,inset,size-inset,size-inset,140f,260f,false,paint)
                paint.color=accent; canvas.drawArc(inset,inset,size-inset,size-inset,140f,260f*(report.score ?: 0)/100,false,paint)
                paint.style=Paint.Style.FILL; paint.textSize=size*.34f
                canvas.drawText(report.score?.toString() ?: "—",size*.5f,size*.51f,paint)
                paint.textSize=size*.12f; canvas.drawText(if(report.score==null) "PARCIAL" else "/ 100",size*.5f,size*.70f,paint)
            }
        }
        hero.addView(gauge,LinearLayout.LayoutParams(dialog.px(100),dialog.px(100)))
        val intro=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dialog.px(22),0,0,0) }
        intro.addView(dialog.text(report.game,29,true))
        intro.addView(dialog.text("${report.quality} • ${SessionReport.duration(report.durationMs)} conectado",23,true,accent))
        intro.addView(dialog.text("${report.resolution ?: "Resolução n/d"} • ${report.codec ?: "Codec n/d"} • ${report.end.label}",20,color=DashboardTheme.muted))
        intro.addView(dialog.text(report.network,19,color=DashboardTheme.muted))
        hero.addView(intro,LinearLayout.LayoutParams(0,-2,1f)); dialog.body.addView(hero)
        dialog.body.addView(dialog.text("MÉDIA / PICO • ${report.samples} amostras • ${SessionReport.duration(report.observedMs)} observados • ↑/↓ ou deslize para detalhes",17,true,DashboardTheme.muted),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(14); bottomMargin=dialog.px(10) })
        val keys=listOf(ReportMetric.RTT,ReportMetric.LOSS,ReportMetric.FPS,ReportMetric.BITRATE,ReportMetric.JITTER,ReportMetric.DECODE)
        keys.chunked(3).forEach { row ->
            val line=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
            row.forEachIndexed { index,key ->
                val metric=report.metrics[key]
                val card=LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL
                    val plate=FocusPlate(8*dialog.scale,1*dialog.scale); background=plate
                    setPadding(dialog.px(15),dialog.px(10),dialog.px(15),dialog.px(10))
                    isFocusable=true; isFocusableInTouchMode=true; DashboardTheme.customFocus(this)
                    setOnFocusChangeListener { _,focused -> plate.highlighted=focused }
                }
                card.addView(dialog.text(key.label.uppercase(),17,true,DashboardTheme.muted))
                card.addView(dialog.text("${SessionReport.number(metric?.average)} / ${SessionReport.number(metric?.peak)} ${key.unit}",26,true))
                line.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { if(index>0) marginStart=dialog.px(12) })
            }
            dialog.body.addView(line,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dialog.px(9) })
        }
        dialog.body.addView(dialog.text("Recebido WebRTC: ${SessionReport.bytes(report.receivedBytes)} • Enviado: ${SessionReport.bytes(report.uploadedBytes)}\n"+
            "Vídeo RTP: ${SessionReport.bytes(report.videoBytes)} • Input: ${SessionReport.bytes(report.inputBytes)}\n"+
            "Descartes observados: ${report.droppedFrames ?: "n/d"} • Sem progresso: ${SessionReport.duration(report.stalledMs)} (${report.stalledEvents} episódios)\n"+
            "Apresentação solicitada: ${report.presentationFps} FPS • Decoder: ${report.decoder ?: "n/d"}"+
            (report.inputDetails?.let { "\n$it" } ?: ""),19,color=DashboardTheme.muted).apply { isFocusable=true; isFocusableInTouchMode=true; DashboardTheme.customFocus(this) })
        report.findings().forEach { finding -> dialog.body.addView(dialog.text("• $finding",20).apply { isFocusable=true; isFocusableInTouchMode=true; DashboardTheme.customFocus(this) },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(8) }) }
        dialog.body.addView(dialog.text("Índice estimado. FPS decodificados não são FPS do jogo ou da tela. Rede e pausas refletem as amostras disponíveis; enlace Wi-Fi não mede velocidade de internet.",17,color=DashboardTheme.muted),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(12) })
        dialog.addAction("Copiar") {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Diagnóstico xCLOUD",report.text()))
            Toast.makeText(context,"Diagnóstico copiado.",Toast.LENGTH_SHORT).show()
        }
        if(disableAutomatic!=null) dialog.addAction("Não abrir automaticamente") { disableAutomatic() }
        val done=dialog.addAction("Concluir") {}
        dialog.focusFirst(done)
        dialog.show(); return dialog
    }
}
