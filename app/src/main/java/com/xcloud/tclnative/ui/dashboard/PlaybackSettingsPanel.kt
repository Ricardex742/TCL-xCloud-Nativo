package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.widget.LinearLayout
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution

enum class SettingsPage { ROOT, VIDEO, IMAGE, APPEARANCE, NETWORK, CONTROLS, PHYSICAL, MOUSE, GYRO, RUMBLE, AUDIO, DIAGNOSTICS, ACCOUNT, TREATMENT, RECONSTRUCTION, UPSCALING }

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
    private var upscalingBack: () -> Unit = { showImage() }
    private fun enter(page: SettingsPage) { currentPage=page;onPageEntered(page) }
    fun open(page: SettingsPage): ConsoleDialog = when(page) {
        SettingsPage.ROOT -> show();SettingsPage.VIDEO -> showVideo();SettingsPage.IMAGE -> showImage();SettingsPage.APPEARANCE -> showAppearance()
        SettingsPage.NETWORK -> showNetwork();SettingsPage.CONTROLS -> showControls();SettingsPage.PHYSICAL -> showPhysical()
        SettingsPage.MOUSE -> showMouse();SettingsPage.GYRO -> showGyro();SettingsPage.RUMBLE -> showRumble()
        SettingsPage.AUDIO -> showAudio();SettingsPage.DIAGNOSTICS -> showDiagnostics();SettingsPage.ACCOUNT -> showAccount()
        SettingsPage.TREATMENT -> showTreatment()
        SettingsPage.RECONSTRUCTION -> showReconstruction()
        SettingsPage.UPSCALING -> showUpscaling()
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
            ConsoleAction(UiText.text("Resolução: {0}",UiText.nested(value.forSession().resolution.label))) { chooseResolution() },
            ConsoleAction(UiText.text("Fluidez: {0}",UiText.nested(value.forSession().presentationPacing.label))) { choosePacing() }
        ))
    }
    fun showAppearance(): ConsoleDialog {
        val appearance=AppearanceSettingsStore(context)
        val rows=mutableListOf(
            ConsoleAction(UiText.text("Tema do aplicativo: {0}",UiText.nested(appearance.read().label))) { chooseTheme() }
        )
        if(appearance.read().scene!=ConsoleScene.NONE) rows+=ConsoleAction(UiText.text("Fundo dinâmico: {0}",UiText.nested(appearance.motion().label))) { chooseBackgroundMotion() }
        rows+=ConsoleAction(UiText.text("Idioma do aplicativo: {0}",UiText.nested(com.xcloud.tclnative.i18n.AppLanguageStore(context).read().nativeName))) {
            LanguageMenu.show(context) { showAppearance() }
        }
        return category(SettingsPage.APPEARANCE,UiText.text("Aparência e temas"),rows)
    }
    private fun chooseBackgroundMotion(): ConsoleDialog {
        enter(SettingsPage.APPEARANCE)
        val preferences=AppearanceSettingsStore(context)
        return ConsoleDialog(context,UiText.text("Fundo dinâmico")).apply {
            body.addView(text(UiText.text("Anima somente o catálogo. Automático mantém o fundo estático em aparelhos de pouca memória ou interfaces acima de 1440p. Economia de energia, animações desativadas no sistema e partidas sempre pausam o movimento."),20,color=DashboardTheme.muted))
            BackgroundMotion.entries.forEach { mode ->
                val row=addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(mode==preferences.motion()) "✓" else "○"),UiText.nested(mode.label))) {
                    preferences.saveMotion(mode);showAppearance()
                }
                if(mode==preferences.motion()) focusFirst(row)
            }
            addAction(UiText.text("Voltar")) { showAppearance() };setOnCancelListener { showAppearance() };show()
        }
    }
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
            ConsoleAction(UiText.text("Upscaling e tratamento: {0}",UiText.nested(methodName(value.forSession().fsr)))) { showUpscaling(onBack={ showImage() }) }
        ))
    }
    private fun methodName(mode: FsrMode): String = when(mode) {
        FsrMode.OFF -> UiText.text("Padrão do aparelho")
        FsrMode.FSR1 -> "AMD FSR 1.0.2"
        FsrMode.SGSR1 -> "SGSR 1"
        FsrMode.RECONSTRUCTION -> UiText.text("Reconstrução xCloud")
    }
    private fun methodDescription(mode: FsrMode): String = when(mode) {
        FsrMode.OFF -> UiText.text("Usa a escala padrão do aparelho. A nitidez e os filtros adicionais abaixo são opcionais.")
        FsrMode.FSR1 -> UiText.text("FSR amplia a imagem com EASU e aplica a nitidez escolhida abaixo. Em tamanho nativo, aplica somente a nitidez. Método experimental.")
        FsrMode.SGSR1 -> UiText.text("SGSR combina ampliação e realce próprio. A nitidez abaixo é do SGSR; os métodos RCAS e USM não se aplicam a ele. Método experimental.")
        FsrMode.RECONSTRUCTION -> UiText.text("A reconstrução xCloud prioriza definição, inclusive sem ampliar. Qualidade, intensidade e comparação abaixo pertencem a este método. Método experimental.")
    }
    /** One entry point; only controls consumed by the selected method are presented. */
    fun showUpscaling(onBack: (() -> Unit)? = null): ConsoleDialog {
        if(onBack!=null) upscalingBack=onBack
        val returnToParent=upscalingBack
        enter(SettingsPage.UPSCALING)
        val value=store.read();val mode=value.fsr
        fun back() { showUpscaling() }
        return ConsoleDialog(context,UiText.text("Upscaling e tratamento")).apply {
            if(value.videoMode==VideoMode.COMPATIBILITY) {
                body.addView(text(UiText.text("Compatibilidade está ativa e desativa os métodos e filtros experimentais. Mude o modo em Vídeo e transmissão para usá-los."),20,color=DashboardTheme.muted))
                addMenuItem(UiText.text("Modo de vídeo: {0}",UiText.nested(value.videoMode.label))) { chooseVideo() }
            } else {
                body.addView(text(methodDescription(mode),20,color=DashboardTheme.muted))
                addMenuItem(UiText.text("Método: {0}",UiText.nested(methodName(mode)))) { chooseFsr() }
                if(mode==FsrMode.OFF) {
                    addMenuItem(UiText.text("Upscaling nativo: {0}",UiText.nested(if(value.nativeUpscaling) UiText.text("Ativado") else UiText.text("Desativado")))) { chooseUpscaling() }
                } else addMenuItem(UiText.text("Saída do escalador: {0}",UiText.nested(value.fsrOutput.label))) { chooseFsrOutput() }
                when(mode) {
                    FsrMode.OFF,FsrMode.FSR1,FsrMode.SGSR1 -> {
                        val amount=if(mode==FsrMode.OFF) value.sharpness else value.fsrSharpness
                        addMenuItem(UiText.text("Nitidez: {0}%",UiText.nested(amount))) { chooseSharpeningStrength() }
                        if(mode!=FsrMode.SGSR1) addMenuItem(UiText.text("Método de nitidez: {0}",UiText.nested(sharpeningName(value)))) { chooseSharpeningMethod() }
                    }
                    FsrMode.RECONSTRUCTION -> {
                        addMenuItem(UiText.text("Qualidade do processamento: {0}",UiText.nested(value.reconstruction.quality.label))) { chooseReconstructionQuality() }
                        addMenuItem(UiText.text("Intensidade da reconstrução: {0}%",UiText.nested(value.reconstruction.strength))) { chooseReconstructionStrength() }
                        addMenuItem(UiText.text("Comparação: {0}",UiText.nested(value.reconstruction.comparison.label))) { chooseImageComparison() }
                    }
                }
                val filters=value.imageTreatment.let { it.spatial || it.temporal>0 || it.neural>0 }
                addMenuItem(UiText.text("Filtros adicionais: {0}",UiText.nested(if(filters) UiText.text("Ativados") else UiText.text("Desativados")))) { showTreatment() }
                addMenuItem(UiText.text("Cores da imagem")) { showImageAdjustments(onBack=::back) }
                addMenuItem(UiText.text("Proteção de desempenho: {0}",UiText.nested(if(value.imageTreatment.autoProtect) UiText.text("Ativada") else UiText.text("Desativada")))) { choosePerformanceProtection() }
                addMenuItem(UiText.text("Restaurar imagem original")) {
                    save(value.copy(nativeUpscaling=true,fsr=FsrMode.OFF,fsrOutput=FsrOutput.AUTO,fsrSharpness=50,
                        reconstruction=ReconstructionSettings(),imageTreatment=ImageTreatmentSettings(),sharpness=0,saturation=100,brightness=0,contrast=100),::back)
                }
            }
            addAction(UiText.text("Voltar"),returnToParent);setOnCancelListener { returnToParent() };show()
        }
    }
    private fun sharpeningName(value: PlaybackSettings): String = when(value.imageTreatment.sharpening) {
        ImageSharpening.LEGACY -> if(value.fsr==FsrMode.FSR1) UiText.text("RCAS original") else UiText.text("Nitidez simples")
        else -> value.imageTreatment.sharpening.label
    }
    private fun chooseSharpeningStrength(): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read();val levels=listOf(0,25,50,75,100)
        val amount=if(value.fsr==FsrMode.OFF) value.sharpness else value.fsrSharpness
        return choices(UiText.text("{0} — Nitidez",UiText.nested(methodName(value.fsr))),UiText.text("0% desativa somente a nitidez deste método. A ampliação e os filtros adicionais continuam independentes. Comece com 25%."),
            levels.map { "$it%" to (it==amount) }) {
            save(if(value.fsr==FsrMode.OFF) value.copy(sharpness=levels[it]) else value.copy(fsrSharpness=levels[it]))
        }
    }
    private fun chooseSharpeningMethod(): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read()
        return choices(UiText.text("Método de nitidez"),UiText.text("Este ajuste pertence ao método selecionado. RCAS adaptativo e USM trabalham na luminância para limitar ruído e halos."),
            ImageSharpening.entries.map { sharpeningName(value.copy(imageTreatment=value.imageTreatment.copy(sharpening=it))) to (it==value.imageTreatment.sharpening) }) {
            save(value.copy(imageTreatment=value.imageTreatment.copy(sharpening=ImageSharpening.entries[it])))
        }
    }
    private fun choosePerformanceProtection(): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read();val image=value.imageTreatment
        return choices(UiText.text("Proteção de desempenho"),UiText.text("Reduz temporal, saída, antialiasing, neural e filtros quando a cadência desenhada fica abaixo da recebida. As escolhas salvas são preservadas. Os cortes valem nesta partida e aparecem no diagnóstico."),
            listOf(UiText.text("Ativada") to image.autoProtect,UiText.text("Desativada") to !image.autoProtect)) { save(value.copy(imageTreatment=image.copy(autoProtect=it==0))) }
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
        return choices(UiText.text("Codec de vídeo"),UiText.join(UiText.text("Padrão mantém a negociação H.264 habitual. Leve oferece somente Baseline, solicita 720p e até 6 Mbps no Automático. O teto manual da partida pode substituir esse orçamento. É um perfil do H.264, sem garantia de ganho se o jogo já usava Baseline. A resolução e o bitrate recebidos dependem do serviço. Compatibilidade também usa Baseline. Aplica-se à próxima partida."),"\n\n",
            UiText.text("Main é experimental: só é oferecido quando o decoder de hardware declara esse perfil e suporta a resolução a 60 FPS. Baseline continua disponível na negociação. O Xbox escolhe o perfil recebido; confirme o resultado no diagnóstico.")),
            VideoCodecMode.entries.map { it.label to (it==current.videoCodec) }) {
            val selected=current.copy(videoCodec=VideoCodecMode.entries[it])
            save(if(selected.videoCodec==VideoCodecMode.H264_BASELINE) selected.copy(resolution=StreamResolution.HD_720) else selected)
        }
    }
    fun choosePacing(): ConsoleDialog {
        enter(SettingsPage.VIDEO);val value=store.read()
        return choices(UiText.text("Fluidez da apresentação"),UiText.text("Suave agenda os quadros com uma pequena espera e pode aumentar o atraso do controle. Mantém apenas o quadro pendente mais recente; não gera novos quadros. Só atua em Adaptativo. Menor atraso e Compatibilidade mantêm a apresentação imediata. Vale na próxima partida."),
            PresentationPacing.entries.map { it.label to (it==value.presentationPacing) }) { save(value.copy(presentationPacing=PresentationPacing.entries[it])) }
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
        enter(SettingsPage.UPSCALING)
        val current=store.read()
        return choices(UiText.text("Upscaling nativo"),UiText.text("A imagem se ajusta à janela nos dois modos, respeitando a proporção escolhida. Ativado solicita a escala da Surface Android; desativado usa a escala do renderer. Quando o FSR está ativo, ele usa o renderer na resolução de saída. O processamento da TV é ajustado na própria TV. Aplica-se à próxima partida."),
            listOf(UiText.text("Ativado — escala da Surface") to current.nativeUpscaling,UiText.text("Desativado — escala do renderer") to !current.nativeUpscaling)) {
            save(current.copy(nativeUpscaling=it==0))
        }
    }
    fun chooseFsr(): ConsoleDialog {
        enter(SettingsPage.UPSCALING)
        val current=store.read()
        return choices(UiText.text("Escolher método de imagem"),UiText.text("Escolha apenas um método. Padrão usa a escala do aparelho. FSR e SGSR ampliam e realçam a imagem. Reconstrução xCloud prioriza definição. Os três métodos alternativos são experimentais. Ao escolher, os ajustes correspondentes aparecem na mesma tela."),
            FsrMode.entries.map { methodName(it) to (it==current.fsr) }) { save(current.copy(fsr=FsrMode.entries[it])) }
    }
    fun chooseFsrOutput(): ConsoleDialog {
        enter(SettingsPage.UPSCALING)
        val current=store.read()
        return choices(UiText.text("Saída do escalador"),UiText.text("A saída é local e não altera o stream recebido. Automático prioriza a fluidez e limita TVs a 1080p. Ative um escalador para usar esta opção. As escolhas preservam a proporção e respeitam a saída física. A proteção reduz a saída se os desenhos não acompanharem os quadros recebidos. 1440p e 4K não garantem 60 FPS."),
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
        return ConsoleDialog(context,UiText.text("Cores da imagem")).apply {
            body.addView(text(UiText.text("Brilho, contraste e saturação são ajustes de cor compartilhados. A nitidez fica em Upscaling e tratamento, junto ao método escolhido."),20,color=DashboardTheme.muted))
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
    // Legacy page IDs and entry points reopen the single image-processing hub.
    fun showReconstruction(): ConsoleDialog = showUpscaling()
    private fun chooseReconstructionQuality(): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read();val options=value.reconstruction
        return choices(UiText.text("Qualidade do processamento"),UiText.text("Automático começa Leve e aumenta após observar cadência estável. Equilibrado e Qualidade usam mais amostras para reconstrução de detalhes. Se houver queda sustentada, a proteção reduz o nível e evita repetir a tentativa nesta partida. Sem proteção de desempenho, Automático permanece Leve. A cadência não é uma medição de tempo de GPU. Compatibilidade desativa o modo."),
            ReconstructionQuality.entries.map { it.label to (it==options.quality) }) { save(value.copy(reconstruction=options.copy(quality=ReconstructionQuality.entries[it]))) }
    }
    private fun chooseReconstructionStrength(): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read();val options=value.reconstruction;val levels=listOf(0,25,50,75,100)
        return choices(UiText.text("Intensidade da reconstrução"),UiText.text("0% desativa a reconstrução de detalhes. Valores maiores aumentam o realce com limites de ruído e halos. A intensidade não altera a resolução; 50% não significa metade do custo da GPU."),
            levels.map { "$it%" to (it==options.strength) }) { save(value.copy(reconstruction=options.copy(strength=levels[it]))) }
    }
    private fun chooseImageComparison(): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read();val options=value.reconstruction
        return choices(UiText.text("Comparação da imagem"),UiText.text("Original mostra o quadro recebido sem os filtros do app. Dividida mostra esse mesmo quadro original à esquerda e tratado à direita. Só atua na reconstrução adaptativa; não altera o toque nem o enquadramento. A adaptação automática fica pausada durante a comparação. O desempenho em meia tela não representa o custo em tela inteira."),
            ImageComparison.entries.map { it.label to (it==options.comparison) }) { save(value.copy(reconstruction=options.copy(comparison=ImageComparison.entries[it]))) }
    }
    fun showTreatment(): ConsoleDialog {
        if(store.read().videoMode==VideoMode.COMPATIBILITY) return showUpscaling()
        enter(SettingsPage.TREATMENT)
        val value=store.read();val image=value.imageTreatment
        fun level(title: String,current: Int,max: Int=100,change: (ImageTreatmentSettings,Int)->ImageTreatmentSettings) {
            val levels=listOf(0,25,50,75,100).filter { it<=max }
            choices(title,UiText.text("Filtros opcionais. Use intensidades baixas e compare com a imagem original. Temporal pode deixar rastros. A rede neural exige renderização half-float e fonte de até 1080p; o diagnóstico informa quando não há suporte. Compatibilidade desativa o tratamento."),
                levels.map { "$it%" to (it==current) },{ showTreatment() }) { index ->
                val now=store.read();save(now.copy(imageTreatment=change(now.imageTreatment,levels[index])))
            }
        }
        return ConsoleDialog(context,UiText.text("Filtros adicionais — compartilhados")).apply {
            body.addView(text(UiText.text("Método: {0}",UiText.nested(methodName(value.fsr))),20,color=DashboardTheme.muted))
            body.addView(text(UiText.text("Estes filtros são aplicados junto ao método escolhido, seja Padrão, FSR, SGSR ou Reconstrução xCloud. Os perfis também ajustam a nitidez desse método. Não é necessário ativar outro escalador."),20,color=DashboardTheme.muted))
            addMenuItem(UiText.text("Perfil leve")) { save(filterPreset(value,natural=false)) }
            addMenuItem(UiText.text("Perfil natural")) { save(filterPreset(value,natural=true)) }
            addMenuItem(UiText.text("Degradês: {0}%",UiText.nested(image.deband))) { level(UiText.text("Degradês"),image.deband) { s,n -> s.copy(deband=n) } }
            addMenuItem(UiText.text("Limpeza de compressão: {0}%",UiText.nested(image.cleanup))) { level(UiText.text("Limpeza de compressão"),image.cleanup) { s,n -> s.copy(cleanup=n) } }
            addMenuItem(UiText.text("Cor nas bordas: {0}%",UiText.nested(image.chroma))) { level(UiText.text("Cor nas bordas"),image.chroma) { s,n -> s.copy(chroma=n) } }
            addMenuItem(UiText.text("Antialiasing: {0}%",UiText.nested(image.antialias))) { level("Antialiasing",image.antialias) { s,n -> s.copy(antialias=n) } }
            addMenuItem(UiText.text("Estabilidade temporal: {0}%",UiText.nested(image.temporal))) { level(UiText.text("Estabilidade temporal"),image.temporal,50) { s,n -> s.copy(temporal=n) } }
            addMenuItem(UiText.text("Reconstrução neural: {0}%",UiText.nested(image.neural))) { level(UiText.text("Reconstrução neural"),image.neural) { s,n -> s.copy(neural=n) } }
            addAction(UiText.text("Voltar")) { showUpscaling() };setOnCancelListener { showUpscaling() };show()
        }
    }
    private fun filterPreset(value: PlaybackSettings,natural: Boolean): PlaybackSettings {
        val selectable=value.fsr==FsrMode.OFF || value.fsr==FsrMode.FSR1
        val image=ImageTreatmentSettings(sharpening=if(selectable) {
            if(natural) ImageSharpening.RCAS_LUMA else ImageSharpening.USM_LUMA
        } else value.imageTreatment.sharpening,deband=if(natural) 25 else 0,chroma=if(natural) 50 else 25)
        val next=value.copy(imageTreatment=image)
        return when(value.fsr) {
            FsrMode.OFF -> next.copy(sharpness=25)
            FsrMode.FSR1,FsrMode.SGSR1 -> next.copy(fsrSharpness=25)
            FsrMode.RECONSTRUCTION -> next.copy(reconstruction=value.reconstruction.copy(strength=if(natural) 50 else 25,comparison=ImageComparison.PROCESSED))
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
