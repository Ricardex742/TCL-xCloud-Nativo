# Saída FSR Automático, 1440p e 4K — 0.31.8

Versão `0.31.8-fsr-output`, versionCode **39**, na branch `perf/decoder-display-latency-0.31`.

## Problema confirmado no teste da TV

O TXT do usuário registrou TCL/Android 12, Fortnite, transmissão 1920×1080, Surface/janela 1920×1080 e saída Android 3840×2160@60. O diagnóstico do filtro foi `sem ampliação; escala padrão | RCAS 100%`. A opção FSR estava selecionada, mas EASU/RCAS não rodavam: entrada e alvo tinham o mesmo tamanho. O percentual RCAS era uma preferência, não confirmação de processamento.

O FSR existente já aceitava 1080p → 1440p/4K, dentro dos limites de precisão/textura e memória. O que faltava era solicitar um buffer de apresentação maior que a janela lógica de 1080p. O WebRTC M137 reaplica `SurfaceHolder.setSizeFromLayout()` em mudanças de layout/resolução; uma chamada isolada a `setFixedSize()` seria sobrescrita.

## Acesso e escolhas

Em **Configurações → Imagem e tela**:

1. Ative **AMD FSR 1.0.2 — experimental**.
2. Abra **Saída do FSR** e escolha uma das três opções.

| Escolha | Comportamento |
| --- | --- |
| Automático | Padrão das instalações existentes. Nas TVs usa até 2560×1440, respeitando a saída física; nos celulares acompanha a janela. É uma escolha de geometria conservadora, não um benchmark ou ajuste dinâmico por FPS. |
| 1440p — 2560×1440 | Solicita um buffer local até essa resolução, preservando a proporção e o espaço disponível na tela. |
| 4K — 3840×2160 (experimental) | Solicita até 4K. Não excede o limite físico disponível; tem maior custo de GPU/memória e precisa de teste no aparelho. |

As opções valem na próxima partida e ficam salvas. Escolher uma saída não ativa sozinho o FSR. Compatibilidade continua sem FSR. Resoluções de aspecto diferente de 16:9 são ajustadas proporcionalmente dentro desses limites; uma escolha 4K em TV Full HD fica limitada ao espaço Full HD.

Numa TV 4K em tela cheia, com janela 1920×1080 e fonte 1920×1080, Automático/1440p pedem buffer 2560×1440; 4K pede 3840×2160. Em 1440p a composição Android/TV ainda completa a apresentação na tela física 4K. A resolução solicitada ao Xbox, bitrate, codec, tamanho dos menus e coordenadas do controle/toque continuam independentes dessa escolha.

## Surface e processamento

O app mantém `SurfaceViewRenderer`, EGL, decoder e shaders existentes. Um adaptador de `SurfaceHolder` preserva o tamanho explícito durante as solicitações de tamanho feitas pelo WebRTC, sem reflexão ou API Android privada no aplicativo. A janela continua no tamanho lógico do Android; o buffer EGL pode ser maior. No modo normal, o controle de tamanho volta ao WebRTC.

FSR tem prioridade sobre a escala de hardware da Surface; ligar Upscaling nativo não soma uma segunda passagem FSR. Desligar o filtro ou retornar à escala padrão restaura a escolha de escala nativa. A apresentação continua solicitando até 60 FPS (Compatibilidade, 30), sem alterar o conteúdo recebido.

O plano FSR conserva o limite de 8,3 MP, precisão highp e até 64 MiB estimados das texturas de trabalho. 1080p → 4K usa cerca de 39,6 MiB nessas texturas, além dos buffers de apresentação, vídeo e memória do driver. O custo de 4K não é representado pelo tempo de decodificação nem pelo RTT.

## Falhas e confirmação

Recusa de tamanho pela Surface, ausência de confirmação do tamanho solicitado ou falha persistente do filtro/GPU fazem o app voltar à escala padrão na mesma sessão. Não há nova fila Xbox nem troca de codec para essa recuperação. Recusas de precisão/textura/memória e erros de shader não são repetidos a cada quadro; uma nova partida pode tentar novamente.

O diagnóstico separa **saída solicitada**, **buffer solicitado**, **Surface real**, **janela** e **saída física Android**. O filtro só indica `ativo EASU + RCAS` quando executa seu caminho de ampliação, com dimensões de entrada/saída. Uma escolha 4K salva não é apresentada como prova de processamento 4K. Sem ampliação ou ao retornar à escala padrão, esse estado aparece explicitamente.

## Verificação e teste físico

Os testes cobrem política de tamanho, limites físicos, aspectos/rotação, persistência, três escolhas de interface, callbacks reais de layout/resolução do WebRTC conservando o buffer 4K numa view 1080p, retorno ao tamanho nativo e falhas sem realocação contínua. A validação Mesa mantém as comparações dos shaders com AMD e acrescenta alocações/desenhos reais 1080p → 1440p e 1080p → 4K, sem ler quadros no aplicativo Android.

Na TV, comece em 1080p + FSR + Automático/1440p e RCAS 50%. Confira `ativo EASU + RCAS` e `1920x1080 → 2560x1440`. Depois compare 4K, procurando Surface 3840×2160 e ampliação 1920×1080 → 3840×2160. Compare fluidez e resposta do controle com FSR desligado; diagnóstico de decode baixo sozinho não garante margem de GPU.

Não há TV física conectada ao executor. Aceitação do buffer pelo driver da TCL, qualidade visual e custo de GPU/latência continuam sujeitos ao teste do usuário.

Fonte do contrato de tamanho conferida na versão usada pelo app: [SurfaceViewRenderer M137/7151](https://webrtc.googlesource.com/src/+/refs/branch-heads/7151/sdk/android/api/org/webrtc/SurfaceViewRenderer.java).

Validação local concluída: **410 testes JVM/Robolectric**, sem falhas/erros/ignorados; **8 testes Python de assinatura**; lint sem problemas e release sem assinatura aprovados. A GPU Mesa aprovou **47 verificações FSR** (incluindo os alvos completos 1080p → 1440p/4K) e **9 do filtro padrão**. A tela das três escolhas foi inspecionada. Pacote `com.xcloud.tclnative`, versão **39 / 0.31.8-fsr-output**, minSdk 24 e target 34 conferidos. O workflow existente repete a suíte completa e gera o APK com o certificado fixo. O teste do holder usa callbacks reais do WebRTC e um holder Android simulado; a aceitação física na TCL continua pendente.
