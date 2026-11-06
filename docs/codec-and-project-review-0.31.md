# Análise de codecs, Nuvem-TV e OnlyTCL

Consulta em **6/11/2026**. Branch do aplicativo: `perf/decoder-display-latency-0.31`. Esta entrega renomeia a aba para **TRANSMITA SEU JOGO** e registra a análise; não altera o codec negociado nem substitui o motor de reprodução.

Esta é a análise histórica anterior à implementação. O trabalho posterior com opções experimentais, a migração da biblioteca nativa e seus limites está em [experimental-video-codecs-0.31.md](experimental-video-codecs-0.31.md).

## Resultado

É tecnicamente viável experimentar codecs mais eficientes em dispositivos adequados. **Não é possível garantir imagem melhor sem perda de desempenho em todas as TVs.** O requisito é a interseção entre capacidade do decoder físico, suporte real do WebRTC, formato aceito pelo servidor e desempenho medido no aparelho. Exibir H.265/AV1 no menu não satisfaz esses requisitos.

H.265/HEVC é o candidato mais fundamentado para um experimento: além do suporte Java das bibliotecas analisadas, o cliente público oficial Xbox contém um caminho `videoConfiguration.preferH265` que prioriza payloads `H265/90000` já presentes no SDP. Isso demonstra implementação no cliente, **não uma sessão HEVC bem-sucedida em um Android TV independente**. Não houve autenticação ou negociação de partida nesta análise.

Antes de um novo codec, vale auditar também o anúncio de **H.264 High** no hardware real. A biblioteca usada pelo app tem uma lista restrita de fabricantes no helper que anuncia esse perfil por hardware. Essa descoberta não comprova ausência de High em toda a fábrica combinada ou na sessão atual; indica uma hipótese concreta a verificar por capacidades, oferta/resposta SDP e perfil recebido.

## Snapshots examinados

| Projeto | Commit | Escopo |
| --- | --- | --- |
| [LeuAndrade/Nuvem-TV](https://github.com/LeuAndrade/Nuvem-TV/tree/3d3efd9b84d15a7197babe24e544526e2884e6cb) | `3d3efd9b84d15a7197babe24e544526e2884e6cb` | NativePlayer, fábrica de decoders, player WebView, configuração, licença, notas de validação e recursos de biblioteca |
| [Isllanrx/OnlyTCL](https://github.com/Isllanrx/OnlyTCL/tree/567cde16feef2f2991915470271b10a1b177f60a) | `567cde16feef2f2991915470271b10a1b177f60a` | engine Rust, sink MediaCodec, ponte JNI, input, shim de estatísticas, seleção regional, documentação e licença |

A inspeção foi estática. Os aplicativos externos não foram instalados/executados e os números de seus documentos não foram medidos novamente aqui. Nenhum código desses projetos foi incorporado ao app nesta mudança.

## O que o Nuvem-TV realmente implementa

O projeto se apresenta como BXbox Cloud TV 0.7.0 e usa `io.github.webrtc-sdk:android:125.6422.07`. O menu oferece H264, VP8, VP9, H265 e AV1, mas o modo Native filtra o resultado de `HardwareVideoDecoderFactory` pelo nome escolhido. Se não encontra um codec correspondente, interrompe a inicialização e orienta escolher H264. Não utiliza fallback por software nesse caminho.

A fábrica procura MediaCodec de hardware e solicita `KEY_LOW_LATENCY` somente em Android 11+ com suporte declarado. Isso é uma boa regra de seleção, não um benchmark de capacidade em 1080p60. Só testar o MIME não verifica perfil, nível, resolução, taxa sustentada ou latência. O modo WebView possui uma verificação separada via `RTCRtpReceiver.getCapabilities`; a capacidade do navegador não determina a capacidade do motor nativo.

O AAR exato foi obtido do Maven Central e seu SHA-256 conferido antes da inspeção. A enumeração Java e a fábrica de decoders incluem **VP8, VP9, H264, AV1 e H265**. Portanto, as duas últimas opções não são apenas textos sem uma representação Java na dependência. Continuam dependendo das capacidades efetivamente anunciadas no aparelho e da negociação com o serviço.

As notas `docs/VALIDACAO.md` do próprio projeto informam que não houve TV, conta Xbox autenticada ou medição física de FPS/latência/nitidez. Os testes do player simulam a conexão. Não demonstram partidas reais em cada codec, nem garantem desempenho em aparelhos de 1 GB.

### O que merece ser aproveitado

- **Seleção somente de hardware para codecs opcionais.** Evita que AV1/VP9 por software sobrecarreguem uma TV modesta. Para o nosso app, o caminho atual H.264 permanece como referência de compatibilidade; falhar um decoder AV1 não permite decodificar seus pacotes com H.264 sem renegociar o formato.
- **Inventário de decoders por aparelho.** MIME, nome, hardware/software, perfis/níveis, suporte de baixa latência e tamanho/taxa devem aparecer separados daquilo que o servidor negociou. O projeto já tem uma tela de inventário; ampliar a verificação para dimensões e taxa seria necessário.
- **Perfil leve explícito.** Motor nativo, H.264, 720p e limite de bitrate são uma referência útil para Wi-Fi limitado. Nosso modo Compatibilidade já faz uma seleção semelhante, com até 6 Mbps solicitados e apresentação limitada a 30 FPS. Não há necessidade de substituir o renderer para obter essa classe de preset.
- **Biblioteca com custos limitados.** O Nuvem-TV usa duas requisições de capas, fila limitada e cache de 3 MiB. Nosso app já possui miniaturas dimensionadas, concorrência reduzida em aparelhos modestos, pausa durante a partida e orçamento por quadro. Os números podem ser comparados em medição, sem trocar automaticamente o cache adaptativo por um valor fixo.
- **Propriedade separada de acesso.** `Entitlements.java` distingue comprado, acesso por outro direito, não comprado e desconhecido. É uma ideia útil para um futuro filtro “meus jogos”, mas sua integração autenticada precisa de validação própria. A aba pública Transmita seu jogo não deve virar prova de compra.

Licença do Nuvem-TV: **AGPL-3.0**, com adaptações e créditos descritos em `NOTICE.md`. Uma futura incorporação literal de código exige preservar esses termos e analisar a licença do conjunto distribuído. A análise de comportamento e o uso das APIs públicas não implicam copiar seus arquivos.

## Situação do nosso app

A dependência continua sendo `io.getstream:stream-webrtc-android:1.1.1`. A inspeção do artefato efetivamente utilizado mostrou os cinco nomes na enumeração Java e na fábrica de decoders de MediaCodec. Isso evita concluir, incorretamente, que uma troca de biblioteca é obrigatória só para reconhecer H265/AV1. A parte nativa/RTP e as capacidades em runtime ainda precisam ser verificadas para cada formato.

O app restringe a preferência a H.264 em `XCloudWebRtcClient.createOfferSdp`. Seus perfis de bitrate/SDP e a configuração de prioridade/recuperação também foram implementados e testados com esse caminho. Habilitar outra opção não é apenas retirar um filtro: exige aplicar a política ao codec correto, manter RTX/feedback compatíveis, validar a resposta e testar recuperação/telemetria.

O helper `MediaCodecVideoDecoderFactory.isH264HighProfileSupported` do artefato atual reconhece `OMX.qcom.*` e `OMX.Exynos.*` (com condição de API). Isso justifica verificar decoders modernos `c2.*`, MediaTek e Realtek por suas capacidades reais, sem anunciar um `profile-level-id` inventado. O app já prefere High quando anunciado no perfil de qualidade e Baseline em 720p/Compatibilidade.

## Qual codec priorizar

| Codec | Possível benefício | Limites e recomendação |
| --- | --- | --- |
| H.264 Baseline/Main/High | Compatibilidade ampla; um perfil High aceito pode melhorar eficiência sem mudar a família do protocolo | Auditar primeiro o que cada aparelho anuncia e recebe. High não é sempre mais leve; manter Baseline para o preset modesto. Não forçar um perfil ausente. |
| H.265/HEVC | Pode preservar mais detalhe com o mesmo bitrate, ou qualidade semelhante usando menos rede | Melhor candidato experimental neste levantamento, pois existe caminho de preferência no cliente Xbox. Exigir hardware, perfil/nível corretos, suporte WebRTC/RTP e resposta real HEVC. |
| VP9 | Pode oferecer melhor eficiência que H.264, dependendo do encoder e do conteúdo | Muitos aparelhos têm decodificação de arquivos VP9, mas isso não prova streaming Xbox nesse formato nem baixa latência a 60 FPS. Experimento posterior, condicionado à negociação. |
| AV1 | Pode reduzir bitrate necessário para uma qualidade semelhante | Somente hardware adequado e taxa sustentada medida. Em TVs/boxes antigos, fallback por CPU pode ser inviável. Não foi demonstrada uma sessão Xbox AV1 nesta análise. |
| VP8 | Compatibilidade WebRTC e possível alternativa de transporte | Não é prioridade como aumento de qualidade sobre um H.264 High bem configurado. Não há razão para usá-lo como upgrade automático de imagem. |

Eficiência de compressão não é uma escala fixa de qualidade. Bitrate real, encoder do serviço, perfil, GOP, quantização e movimento da cena influenciam o resultado. Nenhum desses codecs acrescenta sozinho HDR, 4K, detalhe que o servidor não enviou ou interpolação de quadros.

### Condições para preservar desempenho

1. Consultar o decoder físico por MIME e perfil/nível; em APIs compatíveis, verificar `isHardwareAccelerated`, `isSoftwareOnly`, `VideoCapabilities.areSizeAndRateSupported` e performance points quando fornecidos. Em APIs antigas, o nome do fabricante é apenas uma heurística.
2. Conferir as dimensões realmente usadas. O relatório do usuário tem **2080×992**, não exatamente 1920×1080; um teste limitado a 1920×1080 não valida esse formato mais largo.
3. Conferir `getRtpReceiverCapabilities`, oferta, resposta e codec realmente recebido. A disponibilidade em MediaCodec ou no navegador isoladamente não basta.
4. Manter H.264 disponível na política de fallback. Após um stream iniciar em outro formato, uma mudança de família exige negociação válida ou reinício controlado; não se entrega o bitstream de um codec ao decoder de outro.
5. Comparar no mesmo jogo/cena, resolução, rede e filtros: qualidade visual, bitrate, FPS apresentados/decodificados, tempo de decode, buffer, descartes, pausas, CPU/GPU, memória, temperatura e estabilidade prolongada. As capacidades declaradas não são um teste de baixo atraso.
6. Só considerar um codec adicional elegível para aparelhos modestos após validar hardware e ausência de regressão. Um modo experimental por aparelho é mais adequado que oferecer cinco opções indiscriminadas ou ativar AV1 automaticamente por ser mais recente.

Não há TV/celular conectado nem sessão Xbox autenticada neste ambiente para concluir essa medição. A recomendação é **H.264 padrão, auditoria de H.264 High e experimento HEVC condicionado**. AV1/VP9 ficam atrás da confirmação de transporte e serviço.

## O que o OnlyTCL oferece de interessante

### Prioridade: saída direta de vídeo como experimento separado

O código `VideoSink.java` configura MediaCodec para uma Surface e tenta túnel de reprodução quando há sessão/relógio de áudio utilizáveis. Há watchdog para sair do túnel se não houver quadros renderizados e seleção de decoder com suporte declarado ao recurso.

Nosso caminho usa MediaCodec do WebRTC com SurfaceTexture e apresentação GLES pelo SurfaceViewRenderer. Uma saída direta pode reduzir trabalho de composição em algumas TVs, especialmente com filtros desativados. **Ter SurfaceView não garante composição DEVICE, e saída direta não é sinônimo de `FEATURE_TunneledPlayback`.** A decisão do compositor depende de driver, camadas, transformações e aparelho. O túnel de áudio/vídeo também precisa do relógio/AudioTrack correto.

Esse experimento exige integrar decoder, sincronização de áudio, recuperação, proporção/rotação, dimensões de toque, ciclo de vida e contagem de apresentação. Nitidez e saturação atuais dependem do shader GLES; uma rota que o contorne não pode continuar prometendo aplicar esses filtros. Não é uma troca isolada de widget.

O sink do snapshot fixa H.264 (`video/avc`) e um orçamento máximo de 1920×1080. O engine Rust também registra somente H.264 para vídeo e tem teste que descarta VP9 da preferência. Portanto, ele **não fornece um motor H265/AV1 pronto** e esse limite não cobre automaticamente o fluxo móvel 2080×992 já recebido pelo nosso app.

### Outras ideias úteis

| Ideia do projeto | Aplicação ao nosso app |
| --- | --- |
| Watchdog do túnel e retorno à Surface comum | Bom padrão para um renderer experimental: medir progresso real e ter recuperação controlada. Já temos watchdog de vídeo/decoder/Surface; aproveitar a regra de progressão, não copiar resets cegos. |
| Filas limitadas e solicitação de keyframe após perda | Manter memória e recuperação previsíveis. A política de descartar quadros codificados precisa respeitar referências H.264 e evitar tempestade de PLI; nosso WebRTC já trata essa camada. |
| Isolar navegação de input de gameplay | Princípio útil, já presente no app nativo. A ponte W3C/DOM do OnlyTCL resolve um problema do seu WebView que não temos no motor nativo. |
| Medição por SurfaceFlinger e pelo aparelho real | Útil para descobrir se o gargalo está na composição, em vez de inferir tudo pelo campo decode. Requer coleta no modelo/firmware alvo e comparação equivalente. |
| Região próxima com alternativas de fallback | Pode ser uma futura opção baseada nas regiões reais anunciadas para a conta e na medição. Não importar o endereço BrazilSouth inventado pelo shim quando a região não foi retornada. |

### Limites importantes encontrados no código

- **Estatísticas não equivalentes:** `xstream.js._report` fixa `jitterBufferDelay: 0` e preenche alguns campos ausentes com zero. Esse buffer não demonstra latência de buffer nula. No ramo tunneled, o listener de quadro renderizado incrementa frames, mas não atualiza `decodeNanos` nesse callback; uma comparação de médias exige instrumentação correspondente. Os valores publicados de <4 ms/21 ms não são garantias para nosso app.
- **Não há pipeline inteiro sem alocações:** a ponte Rust usa `byte_array_from_slice` para vídeo; o sink cria `Frame`/tarefas; `getFastJson` chama `NewStringUTF`. O buffer de C reutilizado não torna todas essas etapas zero-copy ou sem alocação.
- **nice não é escalonamento hard real-time:** o código tenta nice -20 e depois -10. A permissão pode ser negada e essa chamada não muda o algoritmo para SCHED_FIFO nem acelera GPU/decoder automaticamente.
- **Não copiar o shim de qualidade:** `tv-shim.js` força `osName="android"`. No nosso app, Android corresponde ao pedido 720p; Windows/Tizen são os seletores de 1080p/HQ já corrigidos anteriormente. Portar essa lógica pode desfazer a preservação do perfil escolhido.
- **Escopo de hardware:** alegações para BeyondTV4/Realtek não comprovam capacidades de toda TCL, de uma P755 ou do Samsung do relatório. Suporte real a AV1/HEVC deve ser lido na unidade em teste.
- **Build e validação:** o manifesto Rust herda campos de workspace; o workspace correspondente não aparece na raiz deste snapshot. Não foi assumido que o fragmento seja um substituto compilável isolado. Não executei seus binários nem scripts de debloat/acessibilidade, que afetam o sistema inteiro e não são necessários para esta análise do app.

Licença principal do OnlyTCL: **MIT**. Uma incorporação de arquivos próprios deve manter aviso/licença e distinguir os ativos e componentes de terceiros incluídos no repositório. Nesta entrega não houve incorporação.

## Referências e verificações

- [Fábrica Nuvem-TV](https://github.com/LeuAndrade/Nuvem-TV/blob/3d3efd9b84d15a7197babe24e544526e2884e6cb/app/src/main/java/org/webrtc/LowLatencyDecoderFactory.java) e [limites de validação](https://github.com/LeuAndrade/Nuvem-TV/blob/3d3efd9b84d15a7197babe24e544526e2884e6cb/docs/VALIDACAO.md).
- [OnlyTCL VideoSink](https://github.com/Isllanrx/OnlyTCL/blob/567cde16feef2f2991915470271b10a1b177f60a/apps/xbox-tv/src/bridge/gamepass/fire/tv/VideoSink.java), [codecs Rust](https://github.com/Isllanrx/OnlyTCL/blob/567cde16feef2f2991915470271b10a1b177f60a/apps/xbox-tv/src/xstream/src/codecs.rs) e [shim de métricas](https://github.com/Isllanrx/OnlyTCL/blob/567cde16feef2f2991915470271b10a1b177f60a/apps/xbox-tv/src/www/xstream.js).
- [Android VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities) e [documentação de túnel AOSP](https://source.android.com/docs/devices/tv/multimedia-tunneling).
- [Codecs WebRTC](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/WebRTC_codecs): suporte do navegador é diferente de suporte do serviço e do decoder Android nativo.
- [Chunk público Xbox WebRTC consultado](https://assets.play.xbox.com/playxbox/static/js/web-rtc-stream.d6f6b903.chunk.js): SHA-256 `0e1d97750113681c7c2d1edfc7910c1b2915055450a7cfde8fc7a0a54fef73d2`; contém `preferH265`.
- AAR Maven `io.github.webrtc-sdk:android:125.6422.07`: SHA-256 `a0ae793a4539f0da6fc9b71806ace876e50b008c30c66fd2ec5eee38ee030474`, validado contra o checksum publicado antes da inspeção. O artefato local GetStream 1.1.1 também foi inspecionado diretamente; a enum/fábrica Java contém os cinco nomes.

Validação da alteração aplicada nesta entrega: **2 testes de UI selecionados**, sem falhas/erros/ignorados, cobrindo a aba em uma linha sem reticências nos tamanhos telefone/TV/4K; `:app:lintDebug` sem problemas; `:app:assembleRelease` concluído sem assinatura de atualização. A suíte completa de 286 testes da entrega anterior não foi reapresentada como uma nova execução nesta revisão.
