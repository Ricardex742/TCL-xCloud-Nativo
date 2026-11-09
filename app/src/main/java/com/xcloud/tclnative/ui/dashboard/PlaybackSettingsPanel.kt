package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.widget.LinearLayout
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution

/** Explicit single-choice menus: opening a row does not change the selected value. */
class PlaybackSettingsPanel(
    private val context: Context,
    private val store: PlaybackSettingsStore,
    private val changed: (PlaybackSettings) -> Unit,
    private val subscription: () -> Unit,
    private val refresh: () -> Unit,
    private val logout: () -> Unit,
    private val testRumble: () -> Unit = {},
    private val homeShortcuts: () -> Unit = {},
    private val lastReport: () -> Unit = {},
    private val sessionOptions: () -> Unit = {},private val controllerProfiles: () -> Unit = {}
) {
    fun show(): ConsoleDialog {
        val settings=store.read()
        val dialog=ConsoleDialogs.menu(context,"Configurações",listOf(
            ConsoleAction("Vídeo: ${settings.videoMode.label}") { chooseVideo() },
            ConsoleAction("Codec: ${if(settings.videoMode==VideoMode.COMPATIBILITY) "H.264 leve (Compatibilidade)" else settings.videoCodec.label}") { chooseCodec() },
            ConsoleAction("Resolução: ${settings.resolution.label}") { chooseResolution() },
            ConsoleAction("HDR10: verificar tela e transmissão") { showHdrStatus() },
            ConsoleAction("Upscaling nativo: ${if(settings.nativeUpscaling) "Ativado" else "Desativado"}") { chooseUpscaling() },
            ConsoleAction("AMD FSR: ${if(settings.videoMode==VideoMode.COMPATIBILITY) "Desativado (Compatibilidade)" else settings.fsr.label}") { chooseFsr() },
            ConsoleAction("Proporção: ${settings.aspect.label}") { chooseAspect() },
            ConsoleAction("Nitidez e saturação") { showImageAdjustments() },
            ConsoleAction("Controles e toque") { showControls() },
            ConsoleAction("Bitrate, região e idioma por jogo",sessionOptions),
            ConsoleAction("Rede: ${settings.ipPreference.label}") { chooseNetwork() },
            ConsoleAction("Áudio e microfone") { showAudio() },
            ConsoleAction("Diagnóstico pós-partida: ${if(settings.sessionReport) "Ativado" else "Desativado"}") {
                choices("Diagnóstico pós-partida","Guarda apenas o último resumo local. O relatório reúne as amostras da sessão sem manter uma lista de toda a partida. As estatísticas durante o jogo continuam ocultas até você ativá-las.",
                    listOf("Ativado — abrir ao sair do jogo" to settings.sessionReport,"Desativado — consultar nas configurações" to !settings.sessionReport)) {
                    save(settings.copy(sessionReport=it==0))
                }
            },
            ConsoleAction("Último diagnóstico",lastReport),
            ConsoleAction("Atalhos dos favoritos",homeShortcuts),
            ConsoleAction("Gerenciar assinatura Microsoft",subscription),
            ConsoleAction("Atualizar catálogo",refresh),
            ConsoleAction("Trocar conta",logout)
        ))
        return dialog
    }
    fun showHdrStatus(): ConsoleDialog = ConsoleDialogs.message(context,"HDR10: tela e transmissão",
        com.xcloud.tclnative.renderer.HdrStatus.report(context),listOf(ConsoleAction("Voltar") { show() }),onCancel={ show() })
    private fun save(settings: PlaybackSettings, after: () -> Unit = { show() }) { store.save(settings); changed(settings); after() }
    private fun choices(title: String, description: String, entries: List<Pair<String,Boolean>>,
                        onBack: () -> Unit = { show() }, select: (Int) -> Unit): ConsoleDialog {
        val dialog=ConsoleDialog(context,title)
        dialog.body.addView(dialog.text(description,20,color=DashboardTheme.muted),LinearLayout.LayoutParams(-1,-2)
            .apply { bottomMargin=dialog.px(16) })
        entries.forEachIndexed { i,(label,selected) ->
            val row=dialog.addMenuItem("${if(selected) "✓" else "○"}  $label") { select(i) }
            row.isSelected=selected
            row.contentDescription="$label${if(selected) ", selecionado" else ""}"
            if (selected) dialog.focusFirst(row)
        }
        dialog.addAction("Voltar",onBack)
        dialog.setOnCancelListener { onBack() }
        dialog.show()
        return dialog
    }
    fun chooseVideo(): ConsoleDialog {
        val current=store.read()
        return choices("Modo de vídeo","Menor atraso reduz buffers. Adaptativo permite mais margem para a rede. Compatibilidade seleciona 720p, pede até 6 Mbps no Automático, prioriza H.264 Baseline e apresenta até 30 quadros/s. A decodificação ainda depende do fluxo recebido. Aplica-se à próxima partida.",
            VideoMode.entries.map { it.label to (it==current.videoMode) }) {
            val mode=VideoMode.entries[it]
            save(if(mode==VideoMode.COMPATIBILITY) current.copy(videoMode=mode,resolution=StreamResolution.HD_720,
                nativeUpscaling=true,sharpness=0,saturation=100,videoCodec=VideoCodecMode.H264,fsr=FsrMode.OFF,brightness=0,contrast=100) else current.copy(videoMode=mode))
        }
    }
    fun chooseCodec(): ConsoleDialog {
        val current=store.read()
        return choices("Codec de vídeo","Padrão mantém a negociação H.264 habitual. Leve oferece somente Baseline, solicita 720p e até 6 Mbps no Automático. O teto manual da partida pode substituir esse orçamento. É um perfil do H.264, sem garantia de ganho se o jogo já usava Baseline. A resolução e o bitrate recebidos dependem do serviço. Compatibilidade também usa Baseline. Aplica-se à próxima partida.",
            VideoCodecMode.entries.map { it.label to (it==current.videoCodec) }) { save(current.copy(videoCodec=VideoCodecMode.entries[it]).forSession()) }
    }
    fun chooseResolution(): ConsoleDialog {
        val current=store.read()
        return choices("Resolução da transmissão","H.264 leve e Compatibilidade usam 720p mesmo com outra escolha salva. 720p solicita menos banda e trabalho de decodificação. A resolução recebida depende do Xbox, do jogo e da conexão. Aplica-se à próxima partida.",
            StreamResolution.entries.map { it.label to (it==current.resolution) }) { save(current.copy(resolution=StreamResolution.entries[it])) }
    }
    fun chooseUpscaling(): ConsoleDialog {
        val current=store.read()
        return choices("Upscaling nativo","A imagem se ajusta à janela nos dois modos, respeitando a proporção escolhida. Ativado solicita a escala da Surface Android; desativado usa a escala do renderer. Quando o FSR está ativo, ele usa o renderer na resolução de saída. O processamento da TV é ajustado na própria TV. Aplica-se à próxima partida.",
            listOf("Ativado — escala da Surface" to current.nativeUpscaling,"Desativado — escala do renderer" to !current.nativeUpscaling)) {
            save(current.copy(nativeUpscaling=it==0))
        }
    }
    fun chooseFsr(): ConsoleDialog {
        val current=store.read()
        return choices("AMD FidelityFX Super Resolution","FSR 1.0.2 amplia o vídeo recebido com EASU e nitidez RCAS. É um teste opcional com custo extra de GPU, principalmente em 4K. Comece em 720p → 1080p e compare imagem e resposta dos comandos. Ajuste RCAS em Nitidez e saturação. Compatibilidade mantém FSR desligado. Aplica-se à próxima partida.",
            FsrMode.entries.map { it.label to (it==current.fsr) }) { save(current.copy(fsr=FsrMode.entries[it])) }
    }
    fun chooseAspect(): ConsoleDialog {
        val current=store.read()
        return choices("Proporção da imagem","Original preserva o formato recebido e usa o maior tamanho que cabe na janela. 16:9 pode deixar barras em telas largas. Proporções fixas e Esticar podem deformar; Zoom mantém a proporção e corta as bordas. Use ↑/↓ ou deslize para ver todas as opções. Aplica-se à próxima partida.",
            VideoAspect.entries.map { it.label to (it==current.aspect) }) { save(current.copy(aspect=VideoAspect.entries[it])) }
    }
    fun showImageAdjustments(onBack: () -> Unit = { show() }): ConsoleDialog {
        val settings=store.read()
        fun backToImage() { showImageAdjustments(onBack) }
        return ConsoleDialog(context,"Nitidez e saturação").apply {
            body.addView(text("Ajustes opcionais da imagem. Nitidez pode realçar ruído e exigir mais GPU. Durante uma partida, as mudanças são imediatas.",20,color=DashboardTheme.muted))
            if(settings.fsr==FsrMode.FSR1) addMenuItem("Nitidez FSR (RCAS): ${settings.fsrSharpness}%") {
                val levels=listOf(0,25,50,75,100)
                choices("Nitidez FSR (RCAS)","RCAS é o filtro de nitidez do FSR. Comece com 50%; 0% mantém somente a ampliação EASU. A nitidez padrão abaixo é usada quando o FSR não está ativo.",
                    levels.map { "$it%" to (it==settings.fsrSharpness) },::backToImage) {
                    save(settings.copy(fsrSharpness=levels[it]),::backToImage)
                }
            }
            addMenuItem("Nitidez: ${settings.sharpness}%") {
                val levels=listOf(0,25,50,75,100)
                choices("Nitidez","0% mantém a imagem original. Comece com 25% e compare o tempo de renderização.",
                    levels.map { "${it}%${if(it==0) " — desativada" else ""}" to (it==settings.sharpness) },::backToImage) {
                    save(settings.copy(sharpness=levels[it]),::backToImage)
                }
            }
            addMenuItem("Brilho: ${settings.brightness}%") {
                val levels=listOf(-50,-25,-10,0,10,25,50)
                choices("Brilho","0% preserva a imagem original. Aplicado junto aos filtros existentes, inclusive no FSR.",
                    levels.map { "$it%" to (it==settings.brightness) },::backToImage) { save(settings.copy(brightness=levels[it]),::backToImage) }
            }
            addMenuItem("Contraste: ${settings.contrast}%") {
                val levels=listOf(50,75,90,100,110,125,150)
                choices("Contraste","100% preserva o contraste original. Valores extremos podem esconder detalhes claros ou escuros.",
                    levels.map { "$it%" to (it==settings.contrast) },::backToImage) { save(settings.copy(contrast=levels[it]),::backToImage) }
            }
            addMenuItem("Saturação: ${settings.saturation}%") {
                val levels=listOf(0,75,100,110,125,150,200)
                choices("Saturação","100% mantém as cores originais. Valores maiores intensificam as cores; 0% deixa a imagem em cinza.",
                    levels.map { "${it}%${if(it==100) " — original" else ""}" to (it==settings.saturation) },::backToImage) {
                    save(settings.copy(saturation=levels[it]),::backToImage)
                }
            }
            addAction("Voltar",onBack); setOnCancelListener { onBack() }; show()
        }
    }
    fun showControls(): ConsoleDialog {
        val settings=store.read()
        return ConsoleDialogs.menu(context,"Controles e toque",listOf(
            ConsoleAction("Coop local: ${if(settings.localCoOp) "Ativado — até 4 jogadores" else "Desativado"}") {
                choices("Coop local","Cada controle ocupa um jogador. Com toque, a tela assume o jogador 1. O jogo e a sessão Xbox precisam aceitar multiplayer local. Aplica-se à próxima partida.",
                    listOf("Desativado" to !settings.localCoOp,"Ativado — até 4 jogadores" to settings.localCoOp),{ showControls() }) {
                    save(settings.copy(localCoOp=it==1)) { showControls() }
                }
            },
            ConsoleAction("Vibração: ${if(settings.rumble) "Ativada" else "Desativada"}") {
                choices("Vibração do controle","Recebe os comandos do jogo e aciona o controle correspondente. A via Android exige suporte do driver. O teste USB/XInput opcional fica em Testar vibração / verificar suporte. Aplica-se à próxima partida.",
                    listOf("Ativada" to settings.rumble,"Desativada" to !settings.rumble),{ showControls() }) {
                    save(settings.copy(rumble=it==0)) { showControls() }
                }
            },
            ConsoleAction("Jogar com toque: ${settings.touchMode.label}") {
                val modes=TouchMode.entries
                choices("Jogar com toque","Na TV, o jogo abre com controle físico, sem HUD de toque. No celular, Automático prioriza o toque nativo nos jogos compatíveis e usa HUD nos demais. Exclusivo — controle virtual força o HUD; Desativado usa controle físico. Aplica-se à próxima partida.",
                    modes.map { it.label to (it==settings.touchMode) },{ showControls() }) {
                    save(settings.copy(touchMode=modes[it])) { showControls() }
                }
            },
            ConsoleAction("Calibração e remapeamento por controle",controllerProfiles),
            ConsoleAction("Mouse e teclado: ${settings.desktopInput.label}") { chooseDesktopInput() },
            ConsoleAction("Sensibilidade do mouse: ${settings.mouseSensitivity}%") {
                val values=listOf(25,50,75,100,125,150,200,250,300)
                choices("Sensibilidade do mouse emulado","Ajusta a velocidade do analógico direito emulado. Mouse nativo usa os movimentos relativos e a sensibilidade do próprio jogo.",
                    values.map { "$it%" to (it==settings.mouseSensitivity) },{ showControls() }) { save(settings.copy(mouseSensitivity=values[it])) { showControls() } }
            },
            ConsoleAction("Giroscópio: ${settings.gyroMode.label}") { chooseGyro() },
            ConsoleAction("Sensibilidade do giroscópio: ${settings.gyroSensitivity}%") {
                val values=listOf(25,50,75,100,125,150,200,250,300)
                choices("Sensibilidade do giroscópio","Aplica-se à próxima partida.",values.map { "$it%" to (it==settings.gyroSensitivity) },{ showControls() }) {
                    save(settings.copy(gyroSensitivity=values[it])) { showControls() }
                }
            },
            ConsoleAction("Vibração do celular no toque: ${if(settings.touchRumble) "Ativada" else "Desativada"}") {
                choices("Vibração do celular","Usa os comandos de rumble recebidos do jogo para o jogador de toque quando não há controle físico associado. Requer Vibração ativada e vibrador no celular; não usa o controle remoto da TV. Aplica-se à próxima partida.",
                    listOf("Desativada" to !settings.touchRumble,"Ativada" to settings.touchRumble),{ showControls() }) { save(settings.copy(touchRumble=it==1)) { showControls() } }
            },
            ConsoleAction("Testar vibração / verificar suporte",testRumble)
        ),onBack={ show() })
    }
    fun chooseNetwork(): ConsoleDialog {
        val current=store.read()
        return choices("Preferência de rede","Automático mantém a escolha do WebRTC. O teste IPv6 adiciona candidatos IPv6 primeiro e mantém IPv4 disponível após até 750 ms. Não exige IPv6, não elimina IPv4 e não garante uma rota melhor. Vale na próxima partida.",
            IpPreference.entries.map { it.label to (it==current.ipPreference) }) { save(current.copy(ipPreference=IpPreference.entries[it])) }
    }
    fun chooseDesktopInput(): ConsoleDialog {
        val current=store.read()
        return choices("Mouse e teclado","Emulação: WASD move, mouse mira, clique esquerdo RT, direito LT; Espaço A, Ctrl B, R/E X, Q Y, Shift L3, Tab View e Esc Menu. Nativo envia teclas e mouse apenas em jogos que anunciam suporte. F7 abre o menu do app; F8 captura/solta entradas. Captura relativa requer Android 8+. Vale na próxima partida.",
            DesktopInputMode.entries.map { it.label to (it==current.desktopInput) },{ showControls() }) { save(current.copy(desktopInput=DesktopInputMode.entries[it])) { showControls() } }
    }
    fun chooseGyro(): ConsoleDialog {
        val current=store.read()
        val sensor=(context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
        if(sensor==null) return ConsoleDialogs.message(context,"Giroscópio","O Android não informou um giroscópio neste aparelho.",listOf(ConsoleAction("Voltar") { showControls() }),onCancel={ showControls() })
        return choices("Mira por movimento","Emula o analógico direito, com ou sem toque/HUD. Toque nativo Xbox mantém o giroscópio desligado nesta versão; use o HUD ou controle. Vale na próxima partida.",
            GyroMode.entries.map { it.label to (it==current.gyroMode) },{ showControls() }) { save(current.copy(gyroMode=GyroMode.entries[it])) { showControls() } }
    }
    fun showAudio(onBack: () -> Unit={ show() }): ConsoleDialog {
        val current=store.read()
        return ConsoleDialogs.menu(context,"Áudio e microfone",listOf(
            ConsoleAction("Ganho de áudio: ${current.audioGain}%") {
                val values=listOf(0,50,75,100,125,150,175,200,250,300)
                choices("Ganho de áudio","100% preserva o volume. Acima disso, o app aumenta gradualmente e reduz o ganho ao detectar picos no áudio. Transientes ainda podem distorcer. Comece em 125%. O foco e o volume do Android continuam ativos.",
                    values.map { "$it%" to (it==current.audioGain) },{ showAudio(onBack) }) { save(current.copy(audioGain=values[it])) { showAudio(onBack) } }
            },
            ConsoleAction("Microfone nas próximas partidas: ${if(current.microphone) "Disponível — inicia mudo" else "Desativado"}") {
                choices("Microfone opcional","A sessão sempre inicia muda. Com esta opção, o app consulta suporte a chat; ative a captura no menu da partida, após conceder a permissão do Android. Ir para segundo plano silencia. Alguns servidores podem não oferecer chat.",
                    listOf("Desativado" to !current.microphone,"Disponível — iniciar mudo" to current.microphone),{ showAudio(onBack) }) { save(current.copy(microphone=it==1)) { showAudio(onBack) } }
            }
        ),onBack=onBack)
    }

}
