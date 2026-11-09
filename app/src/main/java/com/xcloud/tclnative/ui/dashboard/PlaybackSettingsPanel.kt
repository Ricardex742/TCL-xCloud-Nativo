package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.widget.LinearLayout
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution

enum class SettingsPage { ROOT, VIDEO, IMAGE, NETWORK, CONTROLS, PHYSICAL, MOUSE, GYRO, RUMBLE, AUDIO, DIAGNOSTICS, ACCOUNT }

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
    private val sessionOptions: () -> Unit = {},private val controllerProfiles: () -> Unit = {},
    private val onPageEntered: (SettingsPage) -> Unit = {}
) {
    private var currentPage=SettingsPage.ROOT
    private fun enter(page: SettingsPage) { currentPage=page;onPageEntered(page) }
    fun open(page: SettingsPage): ConsoleDialog = when(page) {
        SettingsPage.ROOT -> show();SettingsPage.VIDEO -> showVideo();SettingsPage.IMAGE -> showImage()
        SettingsPage.NETWORK -> showNetwork();SettingsPage.CONTROLS -> showControls();SettingsPage.PHYSICAL -> showPhysical()
        SettingsPage.MOUSE -> showMouse();SettingsPage.GYRO -> showGyro();SettingsPage.RUMBLE -> showRumble()
        SettingsPage.AUDIO -> showAudio();SettingsPage.DIAGNOSTICS -> showDiagnostics();SettingsPage.ACCOUNT -> showAccount()
    }
    private fun category(page: SettingsPage,title: String,rows: List<ConsoleAction>,parent: SettingsPage=SettingsPage.ROOT): ConsoleDialog {
        enter(page)
        return ConsoleDialogs.menu(context,"Configurações › $title",rows,onBack={ open(parent) })
    }
    fun show(): ConsoleDialog {
        enter(SettingsPage.ROOT)
        return ConsoleDialogs.menu(context,"Configurações",listOf(
            ConsoleAction("Vídeo e transmissão") { showVideo() },
            ConsoleAction("Imagem e tela") { showImage() },
            ConsoleAction("Rede e sessão") { showNetwork() },
            ConsoleAction("Controles e toque") { showControls() },
            ConsoleAction("Áudio e microfone") { showAudio() },
            ConsoleAction("Diagnósticos") { showDiagnostics() },
            ConsoleAction("Biblioteca e conta") { showAccount() }
        ))
    }
    fun showVideo(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.VIDEO,"Vídeo e transmissão",listOf(
            ConsoleAction("Modo de vídeo: ${value.videoMode.label}") { chooseVideo() },
            ConsoleAction("Codec: ${if(value.videoMode==VideoMode.COMPATIBILITY) "H.264 leve (Compatibilidade)" else value.videoCodec.label}") { chooseCodec() },
            ConsoleAction("Resolução: ${value.forSession().resolution.label}") { chooseResolution() }
        ))
    }
    fun showImage(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.IMAGE,"Imagem e tela",listOf(
            ConsoleAction("Proporção: ${value.aspect.label}") { chooseAspect() },
            ConsoleAction("Upscaling nativo: ${if(value.nativeUpscaling) "Ativado" else "Desativado"}") { chooseUpscaling() },
            ConsoleAction("AMD FSR: ${if(value.videoMode==VideoMode.COMPATIBILITY) "Desativado (Compatibilidade)" else value.fsr.label}") { chooseFsr() },
            ConsoleAction("Saída do FSR: ${value.fsrOutput.label}") { chooseFsrOutput() },
            ConsoleAction("Ajustes da imagem — nitidez, saturação, brilho e contraste") { showImageAdjustments() },
            ConsoleAction("HDR10: verificar tela e transmissão") { showHdrStatus() }
        ))
    }
    fun showNetwork(): ConsoleDialog = category(SettingsPage.NETWORK,"Rede e sessão",listOf(
        ConsoleAction("IPv4 / IPv6: ${store.read().ipPreference.label}") { chooseNetwork() },
        ConsoleAction("Bitrate, região e idioma por jogo",sessionOptions)
    ))
    fun showDiagnostics(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.DIAGNOSTICS,"Diagnósticos",listOf(
            ConsoleAction("Diagnóstico pós-partida: ${if(value.sessionReport) "Ativado" else "Desativado"}") {
                choices("Diagnóstico pós-partida","Guarda o último resumo local. As estatísticas da partida continuam ocultas até você ativá-las.",
                    listOf("Ativado — abrir ao sair do jogo" to value.sessionReport,"Desativado — consultar nas configurações" to !value.sessionReport)) {
                    save(value.copy(sessionReport=it==0))
                }
            },
            ConsoleAction("Último diagnóstico",lastReport)
        ))
    }
    fun showAccount(): ConsoleDialog = category(SettingsPage.ACCOUNT,"Biblioteca e conta",listOf(
        ConsoleAction("Atalhos dos favoritos",homeShortcuts),ConsoleAction("Gerenciar assinatura Microsoft",subscription),
        ConsoleAction("Atualizar catálogo",refresh),ConsoleAction("Trocar conta",logout)
    ))
    fun showHdrStatus(): ConsoleDialog = ConsoleDialogs.message(context,"HDR10: tela e transmissão",
        com.xcloud.tclnative.renderer.HdrStatus.report(context),listOf(ConsoleAction("Voltar") { showImage() }),onCancel={ showImage() })
    private fun save(settings: PlaybackSettings, after: () -> Unit = { open(currentPage) }) { store.save(settings); changed(settings); after() }
    private fun choices(title: String, description: String, entries: List<Pair<String,Boolean>>,
                        onBack: () -> Unit = { open(currentPage) }, select: (Int) -> Unit): ConsoleDialog {
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
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices("Modo de vídeo","Menor atraso reduz buffers. Adaptativo permite mais margem para a rede. Compatibilidade seleciona 720p, pede até 6 Mbps no Automático, prioriza H.264 Baseline e apresenta até 30 quadros/s. A decodificação ainda depende do fluxo recebido. Aplica-se à próxima partida.",
            VideoMode.entries.map { it.label to (it==current.videoMode) }) {
            val mode=VideoMode.entries[it]
            save(if(mode==VideoMode.COMPATIBILITY) current.copy(videoMode=mode,resolution=StreamResolution.HD_720,
                nativeUpscaling=true,sharpness=0,saturation=100,videoCodec=VideoCodecMode.H264,fsr=FsrMode.OFF,brightness=0,contrast=100) else current.copy(videoMode=mode))
        }
    }
    fun chooseCodec(): ConsoleDialog {
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices("Codec de vídeo","Padrão mantém a negociação H.264 habitual. Leve oferece somente Baseline, solicita 720p e até 6 Mbps no Automático. O teto manual da partida pode substituir esse orçamento. É um perfil do H.264, sem garantia de ganho se o jogo já usava Baseline. A resolução e o bitrate recebidos dependem do serviço. Compatibilidade também usa Baseline. Aplica-se à próxima partida.",
            VideoCodecMode.entries.map { it.label to (it==current.videoCodec) }) { save(current.copy(videoCodec=VideoCodecMode.entries[it]).forSession()) }
    }
    fun chooseResolution(): ConsoleDialog {
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices("Resolução da transmissão","H.264 leve e Compatibilidade usam 720p mesmo com outra escolha salva. 720p solicita menos banda e trabalho de decodificação. A resolução recebida depende do Xbox, do jogo e da conexão. Aplica-se à próxima partida.",
            StreamResolution.entries.map { it.label to (it==current.resolution) }) { save(current.copy(resolution=StreamResolution.entries[it])) }
    }
    fun chooseUpscaling(): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices("Upscaling nativo","A imagem se ajusta à janela nos dois modos, respeitando a proporção escolhida. Ativado solicita a escala da Surface Android; desativado usa a escala do renderer. Quando o FSR está ativo, ele usa o renderer na resolução de saída. O processamento da TV é ajustado na própria TV. Aplica-se à próxima partida.",
            listOf("Ativado — escala da Surface" to current.nativeUpscaling,"Desativado — escala do renderer" to !current.nativeUpscaling)) {
            save(current.copy(nativeUpscaling=it==0))
        }
    }
    fun chooseFsr(): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices("AMD FidelityFX Super Resolution","FSR 1.0.2 amplia o vídeo com EASU e nitidez RCAS. Quando vídeo e saída têm o mesmo tamanho, aplica somente RCAS, sem ampliar. Comece com Saída do FSR em Automático e RCAS em 50%. Compare nitidez, fluidez e resposta dos comandos; 1440p e 4K exigem mais GPU. O diagnóstico distingue ampliação de RCAS nativo. O FSR assume a escala mesmo com Upscaling nativo ligado. Ajuste RCAS em Ajustes da imagem. Compatibilidade mantém FSR desligado. Aplica-se à próxima partida.",
            FsrMode.entries.map { it.label to (it==current.fsr) }) { save(current.copy(fsr=FsrMode.entries[it])) }
    }
    fun chooseFsrOutput(): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices("Saída do FSR","Define a apresentação local, sem aumentar a resolução recebida nem o consumo de rede. Automático acompanha a janela, limitado a 1080p nas TVs, priorizando a fluidez. As escolhas são limitadas pela saída física disponível e preservam a proporção. 1440p e 4K têm custo maior de GPU; não garantem 60 FPS. Requer AMD FSR ativado; Compatibilidade mantém o filtro desligado. O app retorna à escala padrão se o filtro falhar ou se a cadência ficar muito baixa por vários segundos com vídeo chegando. Aplica-se à próxima partida.",
            FsrOutput.entries.map { it.label to (it==current.fsrOutput) }) { save(current.copy(fsrOutput=FsrOutput.entries[it])) }
    }
    fun chooseAspect(): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices("Proporção da imagem","Original preserva o formato recebido e usa o maior tamanho que cabe na janela. 16:9 pode deixar barras em telas largas. Proporções fixas e Esticar podem deformar; Zoom mantém a proporção e corta as bordas. Use ↑/↓ ou deslize para ver todas as opções. Aplica-se à próxima partida.",
            VideoAspect.entries.map { it.label to (it==current.aspect) }) { save(current.copy(aspect=VideoAspect.entries[it])) }
    }
    fun showImageAdjustments(onBack: () -> Unit = { showImage() }): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val settings=store.read()
        fun backToImage() { showImageAdjustments(onBack) }
        return ConsoleDialog(context,"Ajustes da imagem").apply {
            body.addView(text("Ajustes opcionais da imagem. Nitidez pode realçar ruído e exigir mais GPU. Durante uma partida, as mudanças são imediatas.",20,color=DashboardTheme.muted))
            if(settings.fsr==FsrMode.FSR1) addMenuItem("Nitidez FSR (RCAS): ${settings.fsrSharpness}%") {
                val levels=listOf(0,25,50,75,100)
                choices("Nitidez FSR (RCAS)","RCAS ajusta a intensidade da nitidez; 50% não significa metade do custo de GPU. Comece com 50%; 0% desliga a nitidez RCAS e mantém EASU quando houver ampliação. Sem ampliação e com RCAS em 0%, usa o filtro padrão. A nitidez padrão abaixo é usada quando o FSR não está ativo.",
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
    fun showControls(): ConsoleDialog = category(SettingsPage.CONTROLS,"Controles e toque",listOf(
        ConsoleAction("Controle físico e coop local") { showPhysical() },
        ConsoleAction("Jogar com toque: ${store.read().touchMode.label}") { chooseTouch() },
        ConsoleAction("Mouse e teclado") { showMouse() },
        ConsoleAction("Giroscópio") { showGyro() },
        ConsoleAction("Vibração do controle e celular") { showRumble() }
    ))
    fun showPhysical(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.PHYSICAL,"Controles › Controle físico",listOf(
            ConsoleAction("Calibração e remapeamento por controle",controllerProfiles),
            ConsoleAction("Coop local: ${if(value.localCoOp) "Ativado — até 4 jogadores" else "Desativado"}") {
                choices("Coop local","Cada controle ocupa um jogador. Toque/teclado reservam o jogador 1. O jogo e a sessão precisam aceitar multiplayer local. Vale na próxima partida.",
                    listOf("Desativado" to !value.localCoOp,"Ativado — até 4 jogadores" to value.localCoOp)) { save(value.copy(localCoOp=it==1)) }
            }
        ),SettingsPage.CONTROLS)
    }
    fun chooseTouch(): ConsoleDialog {
        enter(SettingsPage.CONTROLS);val value=store.read()
        return choices("Jogar com toque","Na TV, o jogo usa controle físico. No celular, Automático prioriza toque nativo em jogos compatíveis e HUD nos demais. Exclusivo — controle virtual força o HUD. Vale na próxima partida.",
            TouchMode.entries.map { it.label to (it==value.touchMode) }) { save(value.copy(touchMode=TouchMode.entries[it])) }
    }
    fun showMouse(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.MOUSE,"Controles › Mouse e teclado",listOf(
            ConsoleAction("Modo: ${value.desktopInput.label}") { chooseDesktopInput() },
            ConsoleAction("Sensibilidade do mouse emulado: ${value.mouseSensitivity}%") {
                val levels=listOf(25,50,75,100,125,150,200,250,300)
                choices("Sensibilidade do mouse emulado","Ajusta o analógico direito emulado. No modo nativo, use a sensibilidade do próprio jogo. Vale na próxima partida.",
                    levels.map { "$it%" to (it==value.mouseSensitivity) }) { save(value.copy(mouseSensitivity=levels[it])) }
            }
        ),SettingsPage.CONTROLS)
    }
    fun showGyro(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.GYRO,"Controles › Giroscópio",listOf(
            ConsoleAction("Modo: ${value.gyroMode.label}") { chooseGyro() },
            ConsoleAction("Sensibilidade: ${value.gyroSensitivity}%") {
                val levels=listOf(25,50,75,100,125,150,200,250,300)
                choices("Sensibilidade do giroscópio","Vale na próxima partida.",levels.map { "$it%" to (it==value.gyroSensitivity) }) { save(value.copy(gyroSensitivity=levels[it])) }
            },
            ConsoleAction("Toque nativo Xbox: ${if(value.gyroNativeTouch) "Ativado — experimental" else "Desativado"}") { chooseNativeGyro() }
        ),SettingsPage.CONTROLS)
    }
    private fun hasGyro()=(context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)!=null
    fun chooseNativeGyro(): ConsoleDialog {
        enter(SettingsPage.GYRO);val value=store.read()
        if(!hasGyro()) return ConsoleDialogs.message(context,"Giroscópio","O Android não informou um giroscópio neste aparelho.",listOf(ConsoleAction("Voltar") { showGyro() }),onCancel={ showGyro() })
        return choices("Giroscópio com toque nativo Xbox","Teste opcional: mantém os dedos no toque nativo e envia a mira pelo analógico direito. Alguns jogos podem alternar a interface de entrada. Se o modo estiver Desativado, ativar este teste escolhe Sempre. LT/LB exigem controle físico no toque nativo. Vale na próxima partida.",
            listOf("Desativado" to !value.gyroNativeTouch,"Ativado — experimental" to value.gyroNativeTouch)) {
            save(value.copy(gyroNativeTouch=it==1,gyroMode=if(it==1 && value.gyroMode==GyroMode.OFF) GyroMode.ALWAYS else value.gyroMode))
        }
    }
    fun showRumble(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.RUMBLE,"Controles › Vibração",listOf(
            ConsoleAction("Vibração: ${if(value.rumble) "Ativada" else "Desativada"}") {
                choices("Vibração","Aciona o controle correspondente quando Android ou o teste USB oferecem suporte. Vale na próxima partida.",
                    listOf("Ativada" to value.rumble,"Desativada" to !value.rumble)) { save(value.copy(rumble=it==0)) }
            },
            ConsoleAction("Vibração do celular no toque: ${if(value.touchRumble) "Ativada" else "Desativada"}") {
                choices("Vibração do celular","Usa rumble do jogador de toque quando não há controle físico associado. Requer Vibração ativada e vibrador no celular. Vale na próxima partida.",
                    listOf("Desativada" to !value.touchRumble,"Ativada" to value.touchRumble)) { save(value.copy(touchRumble=it==1)) }
            },
            ConsoleAction("Testar vibração / verificar suporte",testRumble)
        ),SettingsPage.CONTROLS)
    }
    fun chooseNetwork(): ConsoleDialog {
        enter(SettingsPage.NETWORK)
        val current=store.read()
        return choices("Preferência de rede","Automático mantém IPv4/IPv6 disponíveis e verifica as rotas durante a partida. O WebRTC considera conectividade, recebimento e RTT, mantendo alternativas de reserva. As preferências IPv4/IPv6 dão até 750 ms iniciais à família escolhida e preservam a outra. A rota depende também do servidor; não há garantia do menor ping. Vale na próxima partida.",
            IpPreference.entries.map { it.label to (it==current.ipPreference) }) { save(current.copy(ipPreference=IpPreference.entries[it])) }
    }
    fun chooseDesktopInput(): ConsoleDialog {
        enter(SettingsPage.MOUSE)
        val current=store.read()
        return choices("Mouse e teclado","Emulação: WASD move, mouse mira, clique esquerdo RT, direito LT; Espaço A, Ctrl B, R/E X, Q Y, Shift L3, Tab View e Esc Menu. Nativo envia teclas e mouse apenas em jogos que anunciam suporte. F7 abre o menu do app; F8 captura/solta entradas. Captura relativa requer Android 8+. Vale na próxima partida.",
            DesktopInputMode.entries.map { it.label to (it==current.desktopInput) },{ showMouse() }) { save(current.copy(desktopInput=DesktopInputMode.entries[it])) { showMouse() } }
    }
    fun chooseGyro(): ConsoleDialog {
        enter(SettingsPage.GYRO)
        val current=store.read()
        val sensor=(context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
        if(sensor==null) return ConsoleDialogs.message(context,"Giroscópio","O Android não informou um giroscópio neste aparelho.",listOf(ConsoleAction("Voltar") { showGyro() }),onCancel={ showGyro() })
        return choices("Mira por movimento","Emula o analógico direito em controle/HUD. Para combinar com toque nativo Xbox, ative também o teste na categoria Giroscópio. LT/LB no toque nativo dependem de controle físico. Vale na próxima partida.",
            GyroMode.entries.map { it.label to (it==current.gyroMode) },{ showGyro() }) { save(current.copy(gyroMode=GyroMode.entries[it])) { showGyro() } }
    }
    fun showAudio(onBack: () -> Unit={ show() }): ConsoleDialog {
        enter(SettingsPage.AUDIO)
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
