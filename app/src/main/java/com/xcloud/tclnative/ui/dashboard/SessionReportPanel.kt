package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

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
    fun show(context: Context,report: SessionReport,onBack: (() -> Unit)?=null,disableAutomatic: (() -> Unit)?=null): ConsoleDialog {
        val dialog=ConsoleDialog(context,UiText.text("Diagnóstico pós-partida"))
        val accent=when { report.score==null -> DashboardTheme.muted; report.score>=75 -> 0xff54de69.toInt(); report.score>=60 -> 0xffffcd66.toInt(); else -> 0xffff826d.toInt() }
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
                paint.textSize=size*.12f; canvas.drawText(if(report.score==null) UiText.text("PARCIAL") else "/ 100",size*.5f,size*.70f,paint)
            }
        }
        hero.addView(gauge,LinearLayout.LayoutParams(dialog.px(100),dialog.px(100)))
        val intro=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dialog.px(22),0,0,0) }
        intro.addView(dialog.text(report.game,29,true))
        intro.addView(dialog.text(UiText.text("{0} • {1} conectado",UiText.nested(report.quality),UiText.nested(SessionReport.duration(report.durationMs))),23,true,accent))
        intro.addView(dialog.text(UiText.text("{0} • {1} • {2}",UiText.nested(report.resolution ?: UiText.text("Resolução n/d")),UiText.nested(report.codec ?: UiText.text("Codec n/d")),UiText.nested(report.end.label)),20,color=DashboardTheme.muted))
        intro.addView(dialog.text(report.network,19,color=DashboardTheme.muted))
        hero.addView(intro,LinearLayout.LayoutParams(0,-2,1f)); dialog.body.addView(hero)
        if(report.end==SessionEnd.FAILURE) {
            dialog.body.addView(dialog.text(UiText.join(UiText.text("MOTIVO DO ENCERRAMENTO\n"),(report.failure?.description ?: UiText.text("Não registrado nesta versão do relatório."))),20,true,0xffffcd66.toInt()).apply {
                isFocusable=true;isFocusableInTouchMode=true;DashboardTheme.customFocus(this)
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(12) })
        }
        dialog.body.addView(dialog.text(UiText.text("MÉDIA / PICO • {0} amostras • {1} observados • ↑/↓ ou deslize para detalhes",UiText.nested(report.samples),UiText.nested(SessionReport.duration(report.observedMs))),17,true,DashboardTheme.muted),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(14); bottomMargin=dialog.px(10) })
        val keys=listOf(ReportMetric.RTT,ReportMetric.LOSS,ReportMetric.FPS,ReportMetric.BITRATE,ReportMetric.JITTER,ReportMetric.DECODE,
            ReportMetric.BUFFER,ReportMetric.BUFFER_TARGET,ReportMetric.INPUT_DELAY)
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
        dialog.body.addView(dialog.text(UiText.join(UiText.text("Recebido WebRTC: {0} • Enviado: {1}\n",UiText.nested(SessionReport.bytes(report.receivedBytes)),UiText.nested(SessionReport.bytes(report.uploadedBytes))),UiText.text("Vídeo RTP: {0} • Input: {1}\n",UiText.nested(SessionReport.bytes(report.videoBytes)),UiText.nested(SessionReport.bytes(report.inputBytes))),UiText.text("Descartes observados: {0} • Sem progresso: {1} ({2} episódios)\n",UiText.nested(report.droppedFrames ?: "n/d"),UiText.nested(SessionReport.duration(report.stalledMs)),UiText.nested(report.stalledEvents)),UiText.text("Apresentação solicitada: {0} FPS • Decoder: {1}",UiText.nested(report.presentationFps),UiText.nested(report.decoder ?: "n/d")),(report.inputDetails?.let { "\n$it" } ?: "")),19,color=DashboardTheme.muted).apply { isFocusable=true; isFocusableInTouchMode=true; DashboardTheme.customFocus(this) })
        report.findings().forEach { finding -> dialog.body.addView(dialog.text("• $finding",20).apply { isFocusable=true; isFocusableInTouchMode=true; DashboardTheme.customFocus(this) },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(8) }) }
        dialog.body.addView(dialog.text(UiText.text("Índice estimado. FPS decodificados não são FPS do jogo ou da tela. Rede e pausas refletem as amostras disponíveis; enlace Wi-Fi não mede velocidade de internet."),17,color=DashboardTheme.muted),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dialog.px(12) })
        dialog.addAction(UiText.text("Copiar")) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(UiText.text("Diagnóstico xCLOUD"),report.text()))
            Toast.makeText(context,UiText.text("Diagnóstico copiado."),Toast.LENGTH_SHORT).show()
        }
        if(disableAutomatic!=null) dialog.addAction(UiText.text("Não abrir automaticamente")) { disableAutomatic() }
        val done=dialog.addAction(if(onBack==null) UiText.text("Concluir") else UiText.text("Voltar")) { onBack?.invoke() }
        if(onBack!=null) dialog.setOnCancelListener { onBack() }
        dialog.focusFirst(done)
        dialog.show(); return dialog
    }
}
