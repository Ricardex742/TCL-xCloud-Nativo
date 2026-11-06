# Toque nativo intermitente no LG LM-K200 e revisão 0.31.3

## Teste recebido

O usuário compartilhou uma sessão de Fortnite na **0.31.1-codec-negotiation**, LG/LGE LM-K200, Android 10/Qualcomm, Wi-Fi 2,4 GHz (enlace informado de 72 Mbps). Usou **720p, Menor atraso, H.264 padrão, toque nativo Xbox**, proporção Original, nitidez 0% e saturação 100%. Informou botões que às vezes não respondiam e confirmou que isso ocorria **também com um dedo só**.

| Medida | Resultado |
| --- | --- |
| Tempo conectado / observado | 26min14s / 26min09s, 1.569 amostras |
| Codec e decoder recebidos | H.264 Baseline 42e01f / OMX.qcom.video.decoder.avc |
| Resolução recebida no trecho final | 1360×672, depois 1392×656 |
| FPS decodificados médios | 57,39 |
| Decode médio / pico | 10,37 / 29,80 ms por quadro |
| Buffer observado médio / pico | 4,62 / 280,50 ms |
| Alvo WebRTC médio | 24,96 ms |
| RTT médio / pico | 45,82 / 102 ms |
| Jitter médio / pico | 3,13 / 38 ms |
| Agendador de envio médio / pico | 4,43 / 53,20 ms |
| Perda RTP de vídeo informada | 0% |
| Eventos de ponteiro aceitos pelo canal | 55.704, incluindo movimentos |
| Esperas de input / consolidações de gamepad | 0 / 0 |
| Quadros descartados | 1.707 |
| Sem progresso observado | 3s, em três episódios; 2s sem dados e 1s com dados |

O vídeo não demonstra sobrecarga contínua: a decodificação média fica abaixo do intervalo de 16,67 ms, mas há descartes e picos. A sequência final mostra interrupção e troca de dimensões seguida de recuperação para cerca de 60 quadros/s. O log não permite correlacionar exatamente essas pausas com os botões relatados.

O índice local 90/100 não avalia se cada botão foi reconhecido. “0 esperas” informa somente que o limite de bytes do canal não foi atingido; “0 transições consolidadas” era uma medida do buffer de gamepad, não das filas de toque. O total de ponteiros não separava down/up/move. Aceitação pelo DataChannel não é confirmação do jogo nem medição input-to-photon; perda RTP de vídeo não mede todas as retransmissões/esperas do canal de input. O enlace Wi-Fi anunciado também não mede capacidade útil nem ausência de interferência.

A saída física já estava em 60 Hz. Em Android 10, a API de sinalização de FPS da Surface e o pedido genérico de joystick por classe de dispositivo usados em Android 11+ não estão disponíveis; os textos correspondentes não comprovam uma falha de toque. `Escala HW desativada` é a opção da Surface e não transforma o decoder Qualcomm informado em decodificação por software.

O servidor anunciou espaço de input 1920×1080, separado do perfil 720p e das dimensões do vídeo recebido. A revisão conserva essa escala de metadados, o mapeamento da área de vídeo e o formato binário já conferido contra o serializador oficial. Não se substituem as dimensões de input pelas dimensões do vídeo.

Esta sessão é anterior à correção ICE da 0.31.2 e já havia enviado/aplicado SDP com sucesso; não valida a recuperação de uma coleta ICE vazia. A rede/aparelho desse teste de toque não foram automaticamente atribuídos à ocorrência ICE anterior.

## Revisão do fluxo e mudanças

O HUD virtual de shooters já chamava `View.requestUnbufferedDispatch(MotionEvent)`, mas o modo nativo não. Essa API por gesto existe desde Android 5.0 e pode ser usada no Android 10. Também faltava ao nativo o pedido para o pai não interceptar o gesto. São fragilidades do caminho local; sem captura dos eventos Android/SCTP no aparelho, não constituem prova da causa única da falha relatada.

Na **0.31.3-native-touch, versionCode 34**:

1. No modo **Menor atraso**, o início de cada gesto nativo solicita despacho sem agrupamento pelo vsync. Adaptativo e Compatibilidade mantêm o agrupamento padrão do Android. O gesto nativo também pede que o pai não o intercepte. É um pedido público ao Android, não garantia de reconhecimento pelo servidor ou aumento de frequência física do touchscreen.
2. Um novo ACTION_DOWN libera contatos que tenham ficado pendentes de um gesto interrompido antes de reutilizar IDs Android. Cancelamento, menu, ocultação e soltura fora da área do vídeo continuam liberando os contatos.
3. A consolidação de lotes de movimento mantém a posição mais recente de **cada ID**. Antes, dois lotes somente de movimento eram substituídos por inteiro, podendo perder a atualização de um dedo quando os conjuntos fossem diferentes. Down/up não são consolidados com movimentos nem removidos pelo caminho normal. A fila continua limitada e usa ressincronização em sobrecarga.
4. O relatório inclui ações Android down/up/move/cancel, contatos down/up gerados, solturas pelo app, inícios fora do vídeo, contatos ignorados, uso do menu, pedidos de despacho imediato e idade média/pico dos eventos ao entrar no View. Esses agregados têm memória constante e não guardam coordenadas ou uma lista de eventos.
5. O buffer informa contatos recebidos e aceitos pelo canal por tipo, movimentos antigos consolidados, lotes pendentes/pico e ressincronizações. A contagem de aceitação só avança depois de `DataChannel.send` retornar sucesso. A idade de entrada, envio local e resposta visível do jogo continuam sendo medidas distintas.
6. Os contadores da UI são limpos ao iniciar outra sessão, depois de congelar o relatório anterior. Ocultar o HUD ou voltar ao menu não apaga a evidência da partida. O limite persistido de 4.096 caracteres permanece.
7. H.264, HEVC/High, resolução, frequência de envio e protocolo permanecem conforme as escolhas existentes. A recuperação ICE da 0.31.2 está incluída. Pacote e certificado de atualização são preservados; somente a branch experimental é atualizada.

Contagens de ações Android referem-se aos MotionEvents recebidos; um MOVE pode carregar vários contatos. “Entrada” do buffer inclui solturas geradas pelo app. Ressincronizações podem gerar up/down adicionais aceitos pelo canal; os totais não devem ser comparados como simples prova de perda quando esse contador for diferente de zero. Uma diferença transitória também pode ser trabalho pendente/em envio. Nem a nova telemetria confirma que o jogo consumiu um comando.

## Validação

A suíte inclui reprodução local de toques rápidos de um dedo através do pump/serializador, preservação de down/up, coalescência por ID, fila limitada e falha de envio sem falsa contagem de aceitação. Os testes de UI verificam captura do gesto no Android 10 diante de um pai que interceptaria MOVE, limpeza de um contato interrompido, soltura e preservação dos diagnósticos ao ocultar a UI.

Em 6/11/2026, a suíte completa passou em **354 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python de proteção da assinatura**. Após a revisão final que limita o despacho imediato ao modo Menor atraso e esclarece o texto das métricas, foram reexecutados **67 testes** de toque, protocolo, pump, multiplayer, compatibilidade, início de sessão e relatório, também sem falhas, erros ou testes ignorados.

`lintDebug` terminou com `No issues found` e `assembleRelease` concluiu tanto na validação completa quanto na final. O pacote foi conferido como `com.xcloud.tclnative`, versionCode 34 e versionName `0.31.3-native-touch`. O build local é release sem assinatura; o APK de atualização assinado é gerado pelo workflow já existente, usando o certificado fixo.

A confirmação da melhora no LG/Android 10 continua dependendo de teste físico; não há aparelho conectado nem gameplay autenticado nesta máquina. Não se mediu ganho de latência nem se provou a causa completa da falha com um dedo só.

## Próximo teste

Comparar na 0.31.3 com a mesma configuração H.264 padrão/720p/Menor atraso, Original e filtros desativados. Testar apertos isolados e, depois, direcional/câmera junto com botões. Ao perceber a falha, copiar o relatório completo, informando o botão e se houve reação visual ou ação atrasada. Os novos contadores ajudam a localizar o problema; não é necessário alterar codecs para esse teste.
