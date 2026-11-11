package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.widget.LinearLayout
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution

enum class SettingsPage { ROOT, VIDEO, IMAGE, APPEARANCE, NETWORK, CONTROLS, PHYSICAL, MOUSE, GYRO, RUMBLE, AUDIO, DIAGNOSTICS, ACCOUNT }

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
        SettingsPage.ROOT -> show();SettingsPage.VIDEO -> showVideo();SettingsPage.IMAGE -> showImage();SettingsPage.APPEARANCE -> showAppearance()
        SettingsPage.NETWORK -> showNetwork();SettingsPage.CONTROLS -> showControls();SettingsPage.PHYSICAL -> showPhysical()
        SettingsPage.MOUSE -> showMouse();SettingsPage.GYRO -> showGyro();SettingsPage.RUMBLE -> showRumble()
        SettingsPage.AUDIO -> showAudio();SettingsPage.DIAGNOSTICS -> showDiagnostics();SettingsPage.ACCOUNT -> showAccount()
    }
    private fun category(page: SettingsPage,title: String,rows: List<ConsoleAction>,parent: SettingsPage=SettingsPage.ROOT): ConsoleDialog {
        enter(page)
        return ConsoleDialogs.menu(context,UiText.text("Configurações › {0}",UiText.nested(title)),rows,onBack={ open(parent) })
    }
    fun show(): ConsoleDialog {
        enter(SettingsPage.ROOT)
        return ConsoleDialogs.menu(context,UiText.text("Configurações"),listOf(
            ConsoleAction(UiText.text("Vídeo e transmissão")) { showVideo() },
            ConsoleAction(UiText.text("Imagem e tela")) { showImage() },
            ConsoleAction(UiText.text("Aparência e temas")) { showAppearance() },
            ConsoleAction(UiText.text("Rede e sessão")) { showNetwork() },
            ConsoleAction(UiText.text("Controles e toque")) { showControls() },
            ConsoleAction(UiText.text("Áudio e microfone")) { showAudio() },
            ConsoleAction(UiText.text("Diagnósticos")) { showDiagnostics() },
            ConsoleAction(UiText.text("Biblioteca e conta")) { showAccount() }
        ))
    }
    fun showVideo(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.VIDEO,UiText.text("Vídeo e transmissão"),listOf(
            ConsoleAction(UiText.text("Modo de vídeo: {0}",UiText.nested(value.videoMode.label))) { chooseVideo() },
            ConsoleAction(UiText.text("Codec: {0}",UiText.nested(if(value.videoMode==VideoMode.COMPATIBILITY) UiText.text("H.264 leve (Compatibilidade)") else value.videoCodec.label))) { chooseCodec() },
            ConsoleAction(UiText.text("Resolução: {0}",UiText.nested(value.forSession().resolution.label))) { chooseResolution() }
        ))
    }
    fun showAppearance(): ConsoleDialog = category(SettingsPage.APPEARANCE,UiText.text("Aparência e temas"),listOf(
        ConsoleAction(UiText.text("Tema do aplicativo: {0}",UiText.nested(AppearanceSettingsStore(context).read().label))) { chooseTheme() },
        ConsoleAction(UiText.text("Idioma do aplicativo: {0}",UiText.nested(com.xcloud.tclnative.i18n.AppLanguageStore(context).read().nativeName))) {
            LanguageMenu.show(context) { showAppearance() }
        }
    ))
    fun chooseTheme(): ConsoleDialog {
        enter(SettingsPage.APPEARANCE)
        val preferences=AppearanceSettingsStore(context)
        val selected=preferences.read()
        val host=context
        return ConsoleDialog(context,UiText.text("Tema do aplicativo")).apply {
            body.addView(text(UiText.text("Escolha as cores dos menus, do catálogo e das janelas. Verde é o padrão. A escolha fica salva neste aparelho, é aplicada na hora e não altera as cores do jogo."),20,color=DashboardTheme.muted),
                LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(16) })
            UiTheme.entries.forEach { theme ->
                val row=addMenuItem(UiText.text("{0}  ■  {1}",UiText.nested(if(theme==selected) "✓" else "○"),UiText.nested(theme.label))) {
                    preferences.save(theme)
                    val root=(host as? android.app.Activity)?.findViewById<android.view.View>(android.R.id.content)
                    DashboardTheme.apply(host,theme,root)
                    showAppearance()
                }
                row.text=android.text.SpannableString(row.text).apply {
                    setSpan(android.text.style.ForegroundColorSpan(theme.accent),3,4,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                row.isSelected=theme==selected
                row.contentDescription=UiText.text("{0}{1}",UiText.nested(theme.label),UiText.nested(if(theme==selected) UiText.text(", selecionado") else ""))
                if(theme==selected) focusFirst(row)
            }
            addAction(UiText.text("Voltar")) { showAppearance() };setOnCancelListener { showAppearance() };show()
        }
    }
    fun showImage(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.IMAGE,UiText.text("Imagem e tela"),listOf(
            ConsoleAction(UiText.text("Proporção: {0}",UiText.nested(value.aspect.label))) { chooseAspect() },
            ConsoleAction(UiText.text("Upscaling nativo: {0}",UiText.nested(if(value.nativeUpscaling) UiText.text("Ativado") else UiText.text("Desativado")))) { chooseUpscaling() },
            ConsoleAction(UiText.text("AMD FSR: {0}",UiText.nested(if(value.videoMode==VideoMode.COMPATIBILITY) UiText.text("Desativado (Compatibilidade)") else value.fsr.label))) { chooseFsr() },
            ConsoleAction(UiText.text("Saída do FSR: {0}",UiText.nested(value.fsrOutput.label))) { chooseFsrOutput() },
            ConsoleAction(UiText.text("Ajustes da imagem — nitidez, saturação, brilho e contraste")) { showImageAdjustments() },
            ConsoleAction(UiText.text("HDR10: verificar tela e transmissão")) { showHdrStatus() }
        ))
    }
    fun showNetwork(): ConsoleDialog = category(SettingsPage.NETWORK,UiText.text("Rede e sessão"),listOf(
        ConsoleAction(UiText.text("IPv4 / IPv6: {0}",UiText.nested(store.read().ipPreference.label))) { chooseNetwork() },
        ConsoleAction(UiText.text("Bitrate, região e idioma por jogo"),sessionOptions)
    ))
    fun showDiagnostics(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.DIAGNOSTICS,UiText.text("Diagnósticos"),listOf(
            ConsoleAction(UiText.text("Diagnóstico pós-partida: {0}",UiText.nested(if(value.sessionReport) UiText.text("Ativado") else UiText.text("Desativado")))) {
                choices(UiText.text("Diagnóstico pós-partida"),UiText.text("Guarda o último resumo local. As estatísticas da partida continuam ocultas até você ativá-las."),
                    listOf(UiText.text("Ativado — abrir ao sair do jogo") to value.sessionReport,UiText.text("Desativado — consultar nas configurações") to !value.sessionReport)) {
                    save(value.copy(sessionReport=it==0))
                }
            },
            ConsoleAction(UiText.text("Último diagnóstico"),lastReport)
        ))
    }
    fun showAccount(): ConsoleDialog = category(SettingsPage.ACCOUNT,UiText.text("Biblioteca e conta"),listOf(
        ConsoleAction(UiText.text("Atalhos dos favoritos"),homeShortcuts),ConsoleAction(UiText.text("Gerenciar assinatura Microsoft"),subscription),
        ConsoleAction(UiText.text("Atualizar catálogo"),refresh),ConsoleAction(UiText.text("Trocar conta"),logout)
    ))
    fun showHdrStatus(): ConsoleDialog = ConsoleDialogs.message(context,UiText.text("HDR10: tela e transmissão"),
        com.xcloud.tclnative.renderer.HdrStatus.report(context),listOf(ConsoleAction(UiText.text("Voltar")) { showImage() }),onCancel={ showImage() })
    private fun save(settings: PlaybackSettings, after: () -> Unit = { open(currentPage) }) { store.save(settings); changed(settings); after() }
    private fun choices(title: String, description: String, entries: List<Pair<String,Boolean>>,
                        onBack: () -> Unit = { open(currentPage) }, select: (Int) -> Unit): ConsoleDialog {
        val dialog=ConsoleDialog(context,title)
        dialog.body.addView(dialog.text(description,20,color=DashboardTheme.muted),LinearLayout.LayoutParams(-1,-2)
            .apply { bottomMargin=dialog.px(16) })
        entries.forEachIndexed { i,(label,selected) ->
            val row=dialog.addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(selected) "✓" else "○"),UiText.nested(label))) { select(i) }
            row.isSelected=selected
            row.contentDescription=UiText.text("{0}{1}",UiText.nested(label),UiText.nested(if(selected) UiText.text(", selecionado") else ""))
            if (selected) dialog.focusFirst(row)
        }
        dialog.addAction(UiText.text("Voltar"),onBack)
        dialog.setOnCancelListener { onBack() }
        dialog.show()
        return dialog
    }
    fun chooseVideo(): ConsoleDialog {
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices(UiText.text("Modo de vídeo"),UiText.text("Menor atraso reduz buffers. Adaptativo permite mais margem para a rede. Compatibilidade seleciona 720p, pede até 6 Mbps no Automático, prioriza H.264 Baseline e apresenta até 30 quadros/s. A decodificação ainda depende do fluxo recebido. Aplica-se à próxima partida."),
            VideoMode.entries.map { it.label to (it==current.videoMode) }) {
            val mode=VideoMode.entries[it]
            save(if(mode==VideoMode.COMPATIBILITY) current.copy(videoMode=mode,resolution=StreamResolution.HD_720,
                nativeUpscaling=true,sharpness=0,saturation=100,videoCodec=VideoCodecMode.H264,fsr=FsrMode.OFF,brightness=0,contrast=100) else current.copy(videoMode=mode))
        }
    }
    fun chooseCodec(): ConsoleDialog {
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices(UiText.text("Codec de vídeo"),UiText.text("Padrão mantém a negociação H.264 habitual. Leve oferece somente Baseline, solicita 720p e até 6 Mbps no Automático. O teto manual da partida pode substituir esse orçamento. É um perfil do H.264, sem garantia de ganho se o jogo já usava Baseline. A resolução e o bitrate recebidos dependem do serviço. Compatibilidade também usa Baseline. Aplica-se à próxima partida."),
            VideoCodecMode.entries.map { it.label to (it==current.videoCodec) }) { save(current.copy(videoCodec=VideoCodecMode.entries[it]).forSession()) }
    }
    fun chooseResolution(): ConsoleDialog {
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices(UiText.text("Resolução da transmissão"),UiText.join(
            UiText.text("H.264 leve e Compatibilidade usam 720p mesmo com outra escolha salva. 720p solicita menos banda e trabalho de decodificação. A resolução recebida depende do Xbox, do jogo e da conexão. Aplica-se à próxima partida."),"\n\n",
            UiText.text("1440p solicita vídeo em até 2560×1440 pelo perfil de qualidade máxima do Xbox. A disponibilidade depende da assinatura, do jogo e do serviço. Exige mais rede e decodificação; confira a resolução realmente recebida no diagnóstico.")),
            StreamResolution.entries.map { it.label to (it==current.resolution) }) { save(current.copy(resolution=StreamResolution.entries[it])) }
    }
    fun chooseUpscaling(): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices(UiText.text("Upscaling nativo"),UiText.text("A imagem se ajusta à janela nos dois modos, respeitando a proporção escolhida. Ativado solicita a escala da Surface Android; desativado usa a escala do renderer. Quando o FSR está ativo, ele usa o renderer na resolução de saída. O processamento da TV é ajustado na própria TV. Aplica-se à próxima partida."),
            listOf(UiText.text("Ativado — escala da Surface") to current.nativeUpscaling,UiText.text("Desativado — escala do renderer") to !current.nativeUpscaling)) {
            save(current.copy(nativeUpscaling=it==0))
        }
    }
    fun chooseFsr(): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices(UiText.text("AMD FidelityFX Super Resolution"),UiText.text("FSR 1.0.2 amplia o vídeo com EASU e nitidez RCAS. Quando vídeo e saída têm o mesmo tamanho, aplica somente RCAS, sem ampliar. Comece com Saída do FSR em Automático e RCAS em 50%. Compare nitidez, fluidez e resposta dos comandos; 1440p e 4K exigem mais GPU. O diagnóstico distingue ampliação de RCAS nativo. O FSR assume a escala mesmo com Upscaling nativo ligado. Ajuste RCAS em Ajustes da imagem. Compatibilidade mantém FSR desligado. Aplica-se à próxima partida."),
            FsrMode.entries.map { it.label to (it==current.fsr) }) { save(current.copy(fsr=FsrMode.entries[it])) }
    }
    fun chooseFsrOutput(): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices(UiText.text("Saída do FSR"),UiText.text("Define a apresentação local, sem aumentar a resolução recebida nem o consumo de rede. Automático acompanha a janela, limitado a 1080p nas TVs, priorizando a fluidez. As escolhas são limitadas pela saída física disponível e preservam a proporção. 1440p e 4K têm custo maior de GPU; não garantem 60 FPS. Requer AMD FSR ativado; Compatibilidade mantém o filtro desligado. O app retorna à escala padrão se o filtro falhar ou se a cadência ficar muito baixa por vários segundos com vídeo chegando. Aplica-se à próxima partida."),
            FsrOutput.entries.map { it.label to (it==current.fsrOutput) }) { save(current.copy(fsrOutput=FsrOutput.entries[it])) }
    }
    fun chooseAspect(onBack: () -> Unit = { showImage() }): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices(UiText.text("Proporção da imagem"),UiText.text("Original preserva o formato recebido e usa o maior tamanho que cabe na janela. Um vídeo 16:9 pode deixar barras laterais no celular; alguns jogos com toque nativo oferecem vídeo mais largo. Esticar preenche sem cortar, mas deforma a imagem. Zoom preenche sem deformar, cortando as bordas. Proporções fixas também podem deformar. Use ↑/↓ ou deslize para ver todas as opções. A mudança é imediata durante a partida e fica salva para os próximos jogos."),
            VideoAspect.entries.map { it.label to (it==current.aspect) },onBack=onBack) {
            save(current.copy(aspect=VideoAspect.entries[it]),onBack)
        }
    }
    fun showImageAdjustments(onBack: () -> Unit = { showImage() }): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val settings=store.read()
        fun backToImage() { showImageAdjustments(onBack) }
        return ConsoleDialog(context,UiText.text("Ajustes da imagem")).apply {
            body.addView(text(UiText.text("Ajustes opcionais da imagem. Nitidez pode realçar ruído e exigir mais GPU. Durante uma partida, as mudanças são imediatas."),20,color=DashboardTheme.muted))
            if(settings.fsr==FsrMode.FSR1) addMenuItem(UiText.text("Nitidez FSR (RCAS): {0}%",UiText.nested(settings.fsrSharpness))) {
                val levels=listOf(0,25,50,75,100)
                choices(UiText.text("Nitidez FSR (RCAS)"),UiText.text("RCAS ajusta a intensidade da nitidez; 50% não significa metade do custo de GPU. Comece com 50%; 0% desliga a nitidez RCAS e mantém EASU quando houver ampliação. Sem ampliação e com RCAS em 0%, usa o filtro padrão. A nitidez padrão abaixo é usada quando o FSR não está ativo."),
                    levels.map { "$it%" to (it==settings.fsrSharpness) },::backToImage) {
                    save(settings.copy(fsrSharpness=levels[it]),::backToImage)
                }
            }
            addMenuItem(UiText.text("Nitidez: {0}%",UiText.nested(settings.sharpness))) {
                val levels=listOf(0,25,50,75,100)
                choices(UiText.text("Nitidez"),UiText.text("0% mantém a imagem original. Comece com 25% e compare o tempo de renderização."),
                    levels.map { "${it}%${if(it==0) UiText.text(" — desativada") else ""}" to (it==settings.sharpness) },::backToImage) {
                    save(settings.copy(sharpness=levels[it]),::backToImage)
                }
            }
            addMenuItem(UiText.text("Brilho: {0}%",UiText.nested(settings.brightness))) {
                val levels=listOf(-50,-25,-10,0,10,25,50)
                choices(UiText.text("Brilho"),UiText.text("0% preserva a imagem original. Aplicado junto aos filtros existentes, inclusive no FSR."),
                    levels.map { "$it%" to (it==settings.brightness) },::backToImage) { save(settings.copy(brightness=levels[it]),::backToImage) }
            }
            addMenuItem(UiText.text("Contraste: {0}%",UiText.nested(settings.contrast))) {
                val levels=listOf(50,75,90,100,110,125,150)
                choices(UiText.text("Contraste"),UiText.text("100% preserva o contraste original. Valores extremos podem esconder detalhes claros ou escuros."),
                    levels.map { "$it%" to (it==settings.contrast) },::backToImage) { save(settings.copy(contrast=levels[it]),::backToImage) }
            }
            addMenuItem(UiText.text("Saturação: {0}%",UiText.nested(settings.saturation))) {
                val levels=listOf(0,75,100,110,125,150,200)
                choices(UiText.text("Saturação"),UiText.text("100% mantém as cores originais. Valores maiores intensificam as cores; 0% deixa a imagem em cinza."),
                    levels.map { UiText.text("{0}%{1}",UiText.nested(it),UiText.nested(if(it==100) UiText.text(" — original") else "")) to (it==settings.saturation) },::backToImage) {
                    save(settings.copy(saturation=levels[it]),::backToImage)
                }
            }
            addAction(UiText.text("Voltar"),onBack); setOnCancelListener { onBack() }; show()
        }
    }
    fun showControls(): ConsoleDialog = category(SettingsPage.CONTROLS,UiText.text("Controles e toque"),listOf(
        ConsoleAction(UiText.text("Controle físico e coop local")) { showPhysical() },
        ConsoleAction(UiText.text("Jogar com toque: {0}",UiText.nested(store.read().touchMode.label))) { chooseTouch() },
        ConsoleAction(UiText.text("Mouse e teclado")) { showMouse() },
        ConsoleAction(UiText.text("Giroscópio")) { showGyro() },
        ConsoleAction(UiText.text("Vibração do controle e celular")) { showRumble() }
    ))
    fun showPhysical(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.PHYSICAL,UiText.text("Controles › Controle físico"),listOf(
            ConsoleAction(UiText.text("Calibração e remapeamento por controle"),controllerProfiles),
            ConsoleAction(UiText.text("Coop local: {0}",UiText.nested(if(value.localCoOp) UiText.text("Ativado — até 4 jogadores") else UiText.text("Desativado")))) {
                choices(UiText.text("Coop local"),UiText.text("Cada controle ocupa um jogador. Toque/teclado reservam o jogador 1. O jogo e a sessão precisam aceitar multiplayer local. Vale na próxima partida."),
                    listOf(UiText.text("Desativado") to !value.localCoOp,UiText.text("Ativado — até 4 jogadores") to value.localCoOp)) { save(value.copy(localCoOp=it==1)) }
            }
        ),SettingsPage.CONTROLS)
    }
    fun chooseTouch(): ConsoleDialog {
        enter(SettingsPage.CONTROLS);val value=store.read()
        return choices(UiText.text("Jogar com toque"),UiText.text("Na TV, o jogo usa controle físico. No celular, Automático prioriza toque nativo em jogos compatíveis e HUD nos demais. Exclusivo — controle virtual força o HUD. Vale na próxima partida."),
            TouchMode.entries.map { it.label to (it==value.touchMode) }) { save(value.copy(touchMode=TouchMode.entries[it])) }
    }
    fun showMouse(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.MOUSE,UiText.text("Controles › Mouse e teclado"),listOf(
            ConsoleAction(UiText.text("Modo: {0}",UiText.nested(value.desktopInput.label))) { chooseDesktopInput() },
            ConsoleAction(UiText.text("Sensibilidade do mouse emulado: {0}%",UiText.nested(value.mouseSensitivity))) {
                val levels=listOf(25,50,75,100,125,150,200,250,300)
                choices(UiText.text("Sensibilidade do mouse emulado"),UiText.text("Ajusta o analógico direito emulado. No modo nativo, use a sensibilidade do próprio jogo. Vale na próxima partida."),
                    levels.map { "$it%" to (it==value.mouseSensitivity) }) { save(value.copy(mouseSensitivity=levels[it])) }
            }
        ),SettingsPage.CONTROLS)
    }
    fun showGyro(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.GYRO,UiText.text("Controles › Giroscópio"),listOf(
            ConsoleAction(UiText.text("Modo: {0}",UiText.nested(value.gyroMode.label))) { chooseGyro() },
            ConsoleAction(UiText.text("Sensibilidade: {0}%",UiText.nested(value.gyroSensitivity))) {
                val levels=listOf(25,50,75,100,125,150,200,250,300)
                choices(UiText.text("Sensibilidade do giroscópio"),UiText.text("Vale na próxima partida."),levels.map { "$it%" to (it==value.gyroSensitivity) }) { save(value.copy(gyroSensitivity=levels[it])) }
            },
            ConsoleAction(UiText.text("Toque nativo Xbox: {0}",UiText.nested(if(value.gyroNativeTouch) UiText.text("Ativado — experimental") else UiText.text("Desativado")))) { chooseNativeGyro() }
        ),SettingsPage.CONTROLS)
    }
    private fun hasGyro()=(context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)!=null
    fun chooseNativeGyro(): ConsoleDialog {
        enter(SettingsPage.GYRO);val value=store.read()
        if(!hasGyro()) return ConsoleDialogs.message(context,UiText.text("Giroscópio"),UiText.text("O Android não informou um giroscópio neste aparelho."),listOf(ConsoleAction(UiText.text("Voltar")) { showGyro() }),onCancel={ showGyro() })
        return choices(UiText.text("Giroscópio com toque nativo Xbox"),UiText.text("Teste opcional: mantém os dedos no toque nativo e envia a mira pelo analógico direito. Alguns jogos podem alternar a interface de entrada. Se o modo estiver Desativado, ativar este teste escolhe Sempre. LT/LB exigem controle físico no toque nativo. Vale na próxima partida."),
            listOf(UiText.text("Desativado") to !value.gyroNativeTouch,UiText.text("Ativado — experimental") to value.gyroNativeTouch)) {
            save(value.copy(gyroNativeTouch=it==1,gyroMode=if(it==1 && value.gyroMode==GyroMode.OFF) GyroMode.ALWAYS else value.gyroMode))
        }
    }
    fun showRumble(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.RUMBLE,UiText.text("Controles › Vibração"),listOf(
            ConsoleAction(UiText.text("Vibração: {0}",UiText.nested(if(value.rumble) UiText.text("Ativada") else UiText.text("Desativada")))) {
                choices(UiText.text("Vibração"),UiText.text("Aciona o controle correspondente quando Android ou o teste USB oferecem suporte. Vale na próxima partida."),
                    listOf(UiText.text("Ativada") to value.rumble,UiText.text("Desativada") to !value.rumble)) { save(value.copy(rumble=it==0)) }
            },
            ConsoleAction(UiText.text("Vibração do celular no toque: {0}",UiText.nested(if(value.touchRumble) UiText.text("Ativada") else UiText.text("Desativada")))) {
                choices(UiText.text("Vibração do celular"),UiText.text("Usa rumble do jogador de toque quando não há controle físico associado. Requer Vibração ativada e vibrador no celular. Vale na próxima partida."),
                    listOf(UiText.text("Desativada") to !value.touchRumble,UiText.text("Ativada") to value.touchRumble)) { save(value.copy(touchRumble=it==1)) }
            },
            ConsoleAction(UiText.text("Testar vibração / verificar suporte"),testRumble)
        ),SettingsPage.CONTROLS)
    }
    fun chooseNetwork(): ConsoleDialog {
        enter(SettingsPage.NETWORK)
        val current=store.read()
        return choices(UiText.text("Preferência de rede"),UiText.text("Automático mantém IPv4/IPv6 disponíveis e verifica as rotas durante a partida. O WebRTC considera conectividade, recebimento e RTT, mantendo alternativas de reserva. As preferências IPv4/IPv6 dão até 750 ms iniciais à família escolhida e preservam a outra. A rota depende também do servidor; não há garantia do menor ping. Vale na próxima partida."),
            IpPreference.entries.map { it.label to (it==current.ipPreference) }) { save(current.copy(ipPreference=IpPreference.entries[it])) }
    }
    fun chooseDesktopInput(): ConsoleDialog {
        enter(SettingsPage.MOUSE)
        val current=store.read()
        return choices(UiText.text("Mouse e teclado"),UiText.text("Emulação: WASD move, mouse mira, clique esquerdo RT, direito LT; Espaço A, Ctrl B, R/E X, Q Y, Shift L3, Tab View e Esc Menu. Nativo envia teclas e mouse apenas em jogos que anunciam suporte. F7 abre o menu do app; F8 captura/solta entradas. Captura relativa requer Android 8+. Vale na próxima partida."),
            DesktopInputMode.entries.map { it.label to (it==current.desktopInput) },{ showMouse() }) { save(current.copy(desktopInput=DesktopInputMode.entries[it])) { showMouse() } }
    }
    fun chooseGyro(): ConsoleDialog {
        enter(SettingsPage.GYRO)
        val current=store.read()
        val sensor=(context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager)?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
        if(sensor==null) return ConsoleDialogs.message(context,UiText.text("Giroscópio"),UiText.text("O Android não informou um giroscópio neste aparelho."),listOf(ConsoleAction(UiText.text("Voltar")) { showGyro() }),onCancel={ showGyro() })
        return choices(UiText.text("Mira por movimento"),UiText.text("Emula o analógico direito em controle/HUD. Para combinar com toque nativo Xbox, ative também o teste na categoria Giroscópio. LT/LB no toque nativo dependem de controle físico. Vale na próxima partida."),
            GyroMode.entries.map { it.label to (it==current.gyroMode) },{ showGyro() }) { save(current.copy(gyroMode=GyroMode.entries[it])) { showGyro() } }
    }
    fun showAudio(onBack: () -> Unit={ show() }): ConsoleDialog {
        enter(SettingsPage.AUDIO)
        val current=store.read()
        return ConsoleDialogs.menu(context,UiText.text("Áudio e microfone"),listOf(
            ConsoleAction(UiText.text("Ganho de áudio: {0}%",UiText.nested(current.audioGain))) {
                val values=listOf(0,50,75,100,125,150,175,200,250,300)
                choices(UiText.text("Ganho de áudio"),UiText.text("100% preserva o volume. Acima disso, o app aumenta gradualmente e reduz o ganho ao detectar picos no áudio. Transientes ainda podem distorcer. Comece em 125%. O foco e o volume do Android continuam ativos."),
                    values.map { "$it%" to (it==current.audioGain) },{ showAudio(onBack) }) { save(current.copy(audioGain=values[it])) { showAudio(onBack) } }
            },
            ConsoleAction(UiText.text("Microfone nas próximas partidas: {0}",UiText.nested(if(current.microphone) UiText.text("Disponível — inicia mudo") else UiText.text("Desativado")))) {
                choices(UiText.text("Microfone opcional"),UiText.text("A sessão sempre inicia muda. Com esta opção, o app consulta suporte a chat; ative a captura no menu da partida, após conceder a permissão do Android. Ir para segundo plano silencia. Alguns servidores podem não oferecer chat."),
                    listOf(UiText.text("Desativado") to !current.microphone,UiText.text("Disponível — iniciar mudo") to current.microphone),{ showAudio(onBack) }) { save(current.copy(microphone=it==1)) { showAudio(onBack) } }
            }
        ),onBack=onBack)
    }

}
