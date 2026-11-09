# Proteção de fluidez do FSR — 0.31.9

Versão `0.31.9-fsr-fluency-guard`, versionCode **40**, na mesma branch `perf/decoder-display-latency-0.31`.

## Evidência física recebida

Na 0.31.8, o usuário testou Fortnite na TCL com RCAS 50%: aproximadamente **13 FPS em 1440p** e **6 FPS em 4K**, ainda no menu do jogo. O print confirma FSR `ativo EASU + RCAS`, fonte 1920×1080, Surface 3840×2160, RTT perto de 38 ms, perda zero e decode perto de 9,3 ms/quadro. Ao desligar o FSR, mantendo o restante, informou retorno para perto de **60 FPS**.

Isso identifica o caminho atual do FSR como gatilho da queda nesse teste. Não demonstra que o decoder ou a internet passaram a ser lentos. O pipeline local posterior pode limitar a entrega do vídeo e produzir contrapressão mesmo com tempo de decode baixo. A versão GLES atual usa preparação RGB, EASU completo e RCAS; custo/memória crescem com a saída. RCAS 50% muda a intensidade, não reduz pela metade o número de operações. Os testes Mesa da 0.31.8 comprovaram pixels e alocação, não velocidade na GPU da TCL.

O Automático de até 1440p não foi uma escolha adequada para essa TV. A 0.31.8 retornava à escala padrão em recusas/erros, mas um filtro aceito e lento continuava ativo.

## Mudanças

- **Automático conservador:** acompanha a janela, com limite de 1920×1080 nas TVs. Não amplia automaticamente uma interface de 1080p para 1440p/4K. Em fonte 1080p e alvo 1080p, o FSR indica `sem ampliação; escala padrão`; a composição Android/TV apresenta a imagem na tela física 4K. Em fontes menores pode haver FSR até o limite local, sujeito à proteção.
- **1440p/4K:** continuam como escolhas manuais para dispositivos que consigam executá-las, limitadas pela saída física. Não há promessa de 60 FPS.
- **Retorno por baixa cadência:** quando há desenhos FSR ativos, ignora dois segundos iniciais e observa a cadência local. Quatro segundos consecutivos abaixo de 24 desenhos/s, em pelo menos três amostras válidas, acionam escala padrão para o restante da partida.
- **Critérios de amostragem:** exige vídeo recebido, perda RTP conhecida até 1%, intervalo RTC entre 0,5 e 2,5 s, observação recente e partida em primeiro plano. Pausas de rede, ausência de estatísticas, segundo plano/foco de menu e mudanças de contador/intervalos reiniciam a janela. Funciona também com HUD de estatísticas oculto.
- **Restauração:** reutiliza a sessão Xbox, desativa o filtro para aquela partida e restaura o tamanho normal do buffer e a escolha de escala nativa. Não apaga a preferência manual 1440p/4K; uma nova partida pode testá-la novamente.

## O que a proteção mede

Um contador é incrementado apenas quando o caminho FSR desenha um quadro. A thread de UI consulta esse contador com as estatísticas que já chegam uma vez por segundo. A cadência reflete também esperas entre desenhos, inclusive pressão no pipeline; **não é tempo puro de GPU, FPS do jogo nem confirmação da apresentação física**. Não se adicionam `glFinish`, leitura de pixels, timers de GPU bloqueantes ou outro fluxo de vídeo.

Baixa cadência pode ter outras causas. Por isso a mensagem informa **proteção de fluidez**, e não um diagnóstico automático de defeito da GPU. Os critérios de rede/visibilidade reduzem falsos acionamentos. Um fluxo do serviço abaixo de 24 quadros/s com dados e baixa perda ainda pode provocar o retorno conservador. A proteção não identifica a causa com certeza nem garante detectar toda forma de engasgo.

O diagnóstico registra a cadência de desenho observada e, após o retorno, uma mensagem como `proteção de fluidez: 13.0 desenhos FSR/s por 4s; escala padrão nesta partida`. A Surface solicitada volta à escala padrão; dimensões do vídeo, menus e coordenadas de toque continuam separadas.

## Orientação para esta TCL

Para jogar agora, mantenha **FSR desligado e Upscaling nativo ligado**, combinação na qual o usuário relatou retorno para perto de 60 FPS. A atualização acrescenta proteção e um Automático mais conservador; não torna o FSR 1440p/4K a 60 FPS comprovado nessa TV. O algoritmo e os shaders não foram substituídos por uma aproximação de menor qualidade.

No teste da atualização, confira primeiro a cadência sem FSR. Ao testar 1440p/4K, espere a proteção atuar e veja se o diagnóstico registra o retorno à escala padrão. Compare a recuperação da fluidez na mesma partida. Não há TV conectada ao executor para confirmar esse comportamento físico.

Validação local: **416 testes JVM/Robolectric**, sem falhas/erros/ignorados; **8 testes Python de assinatura**; lint sem problemas e release sem assinatura aprovados. Os testes simulam cadências de seis/13 desenhos/s, estabilidade em 30/60, rajadas curtas, perdas/pausas de rede, segundo plano, intervalos inválidos e reset de contadores. A integração simulada da Surface usa o proprietário/guard/retorno reais para restaurar o pedido de 4K a 1080p, preservando proporção, metadados de toque e preferência manual. Pacote `com.xcloud.tclnative`, versão **40 / 0.31.9-fsr-fluency-guard**, minSdk 24 e target 34 conferidos; tela atualizada inspecionada. Os shaders não mudaram. O CI existente reexecuta a suíte e as verificações de pixels, e assina o APK com o certificado fixo. A atuação/recuperação física da nova proteção ainda precisa do teste na TCL.
