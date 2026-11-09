# Opções de sessão, controles e áudio — 0.31.6

## Pedido e estado

O usuário esclareceu que sua concordância anterior abrangia a lista de dez adições da análise de Better xCloud/XStreaming. A entrega 0.31.5 havia tratado apenas da retirada dos codecs experimentais e do teste USB de vibração. Esta continuação implementa os dez itens da lista, exclusivamente em `perf/decoder-display-latency-0.31`, com versão **0.31.6-session-controls-audio / versionCode 37**.

User-Agent continua fora do escopo. As escolhas experimentais H.264 High/HEVC continuam removidas. Não foram importados motores de renderização ou código dos projetos analisados. A configuração publicada do ambiente não foi alterada.

## Como acessar e o que foi implementado

| Adição | Acesso e comportamento |
| --- | --- |
| Bitrate máximo | Configurações → Bitrate, região e idioma por jogo. Automático ou tetos de 1 a 50 Mbps; padrão da conta e substituição por jogo. |
| Calibração/remapeamento | Controles e toque → Calibração e remapeamento por controle; também no menu da partida. Zonas mortas, sensibilidades esquerda/direita, curva, inversão Y, zona morta/troca de gatilhos e destino dos botões. |
| Região | Na mesma tela de opções da partida. Automático ou uma das regiões realmente recebidas no login da conta. |
| IPv6 com alternativa IPv4 | Configurações → Rede. Automático ou preferência experimental; todas as rotas válidas IPv4 continuam disponíveis. |
| Mouse/teclado | Controles e toque. Desativado, emulação de controle ou nativo para títulos que anunciam suporte e sessões que confirmam input v8. F7 abre o menu do app; F8 captura/libera. |
| Giroscópio | Controles e toque. Desativado, LT, LB ou sempre, com sensibilidade. Exige sensor Android; fica desligado no toque nativo Xbox. |
| Idioma por jogo | Opções da partida. Interface do app continua em português; o idioma é enviado em `settings.locale`. |
| Microfone/ganho | Áudio e microfone. Ganho até 300% com ajuste adaptativo de picos; disponibilidade de chat opcional, sempre iniciando mudo. Ativar/silenciar no menu da partida. |
| Brilho/contraste | Nitidez e saturação. Aplicação imediata no shader já existente, inclusive no passe final do FSR. |
| Vibração do celular | Controles e toque. Desativada por padrão; usa rumble recebido para o jogador de toque quando não há controle físico associado. |

## Opções de sessão e conta

O armazenamento de bitrate, região e idioma é separado por conta Xbox. Jogos sem substituição herdam o padrão atual; salvar uma configuração por jogo guarda os três valores juntos. Restaurar o padrão remove a substituição. O menu está disponível para o jogo selecionado no catálogo e para o jogo ativo, além do padrão geral. Mudanças de rede/idioma/bitrate valem na próxima partida.

O bitrate manual não depende da resolução: pode pedir menos banda em 1080p ou mais banda em 720p. Ele substitui o orçamento automático, inclusive o automático de até 6 Mbps de Baseline/Compatibilidade. Mínimo e início do SDP são limitados ao teto escolhido, evitando um mínimo maior que o máximo. O pedido preserva os IDs reais de perfil, áudio e reparo. O serviço decide a resolução e o bitrate efetivamente recebidos; maior teto não garante maior qualidade.

O login preserva a lista de regiões oferecidas. A seleção muda o endpoint utilizado para criar/controlar a sessão. Consultas de catálogo e histórico continuam no endpoint padrão da conta. Se uma região salva deixar de ser oferecida, o app volta ao padrão e informa isso no diagnóstico. Não há endereço de servidor digitado pelo usuário, teste de ping apresentado como latência do jogo ou alteração de User-Agent.

O idioma é uma preferência da sessão, não muda a região da conta nem obriga o jogo a oferecer uma tradução/dublagem. O menu inclui pt-BR, en-US, es-MX, es-ES, fr-FR, de-DE, it-IT e ja-JP.

## IPv6 experimental

Automático mantém o comportamento anterior. A preferência adiciona candidatos remotos IPv6 primeiro e concede uma vantagem inicial de **até 750 ms** quando ambas as famílias existem. IPv4 entra imediatamente se os candidatos IPv6 forem recusados ou a tentativa preferida falhar; também entra quando termina esse intervalo. Uma conexão IPv6 rápida não remove as alternativas IPv4.

Prioridades e endereços dos candidatos não são reescritos. A preferência não retira candidatos locais IPv4 do SDP nem obriga o WebRTC/serviço a selecionar IPv6. Uma família única não recebe espera extra. Cancelamento da sessão interrompe a tentativa; não injeta candidatos depois do fechamento. O diagnóstico informa intenção e recusas, sem publicar endereços completos.

## Perfis de controle

Os perfis usam vendor/product e o descritor estável do dispositivo Android, preservando escolhas entre reconexões e separando controles distintos. O perfil padrão mantém os valores anteriores. A normalização dos intervalos do driver continua anterior à calibração; as zonas mortas adicionais são radiais. A curva pode ser linear, precisa no centro ou rápida. São ajustes manuais, não uma medição automática do centro físico.

O remapeamento atua sobre o estado físico completo antes da mistura com o HUD, preservando solturas e fontes simultâneas. O atalho físico Menu + View continua reservado para abrir o menu. Toque, teclado emulado e os outros jogadores não recebem indevidamente o perfil de um controle. A calibração melhora a resposta escolhida pelo usuário, mas não mede ou elimina o atraso do dongle, do jogo ou da tela.

## Mouse e teclado: caminhos distintos

A emulação produz estados de controle Xbox: WASD move; mouse controla o analógico direito; clique esquerdo usa RT, direito LT e meio R3; Espaço=A, Ctrl=B, R/E=X, Q=Y, Shift=L3, Tab=View e Esc=Menu. Sensibilidade ajusta o mouse emulado. Isso continua sujeito à curva, limite de giro e assistência de mira de um controle; não é mira nativa 1:1.

O nativo é permitido somente quando o título autenticado anuncia suporte MKB/mouse/teclado e o serviço confirma **input v8**, formato que este app serializa. O cliente envia relatórios relativos de mouse e teclas Windows VKey no canal confiável já existente. Não depende de WebView, proxy de mouse, acessibilidade ou alteração de dispositivo/lobby. A primeira versão cobre teclas comuns de jogo e não implementa um IME completo de texto composto.

Teclado/mouse ativos iniciam a partida sem toque; caso um jogo não anuncie MKB nativo, o app informa e usa a entrada de toque/controle normalmente escolhida. Se o título anunciar, mas o serviço não confirmar v8, a abertura falha com mensagem explícita para escolher emulação/controle. Não finge sucesso nem envia pacotes de versão incompatível.

F7 abre o menu local; F8 captura/libera as entradas. Captura relativa do mouse usa a API Android 8+; versões anteriores têm apenas a posição/movimentos disponíveis na janela. A fila mantém bordas de botões/teclas, consolida movimento, limita pendências e envia solturas ao perder captura/foco. Teclas já reportadas também são liberadas quando uma saturação substitui a fila, mesmo se a soltura anterior ainda estava pendente. O mouse emulado volta ao centro após 30 ms sem movimento, preservando teclas e gatilhos segurados. Com coop local, teclado/mouse reservam o jogador 1 e os controles físicos ocupam os slots seguintes. Quando a emulação está desligada, o envio de controle não cria um estado de teclado a cada amostra.

## Giroscópio e vibração do celular

O giroscópio usa o sensor de velocidade angular do Android e considera a rotação da tela. Pode atuar continuamente ou somente com LT/LB lógico ativo, adicionando a mira ao analógico direito do jogador 1. Não há listener quando desativado ou sem sensor. Falta de amostras por 100 ms libera a contribuição da câmera; menu, perda de foco e segundo plano param o sensor.

Toque nativo Xbox não recebe giroscópio simultâneo nesta versão: o app informa a restrição; use controle/HUD para testar. A combinação precisa ser validada no aparelho antes de ampliar o suporte. Não é uma implementação do sensor interno do GameSir via protocolo USB.

A vibração do celular é independente da presença de motores num dongle: direciona comandos de rumble recebidos para o vibrador do aparelho somente para o jogador zero de toque, sem controle físico associado e com a opção de vibração ativada. TVs e controles remotos não são tratados como celulares. Não cria rumble em jogos que não o enviam. O teste USB/XInput anterior continua disponível separadamente.

## Microfone e áudio

Microfone desativado é o padrão. A opção “Disponível — inicia mudo” anuncia capacidade `chatStream`, sem iniciar captura. No menu da partida, Ativar solicita a permissão Android quando necessário. A captura só é liberada com opção da sessão habilitada, suporte anunciado, SDP de chat aplicado, app visível e permissão concedida.

O áudio de chat usa a trilha/sender WebRTC e a renegociação `isMediaStreamsChatRenegotiation`; mantém a mesma sessão Xbox e não volta à fila. Respostas SDP de pedidos anteriores são ignoradas quando identificadas. Rejeição de chat mantém o microfone mudo, tenta rollback local e não encerra o jogo por conta própria. A trilha de voz é criada com as codificações do sender inativas. Ativar libera captura e envio somente após a negociação; mudo/segundo plano desativam as codificações e solicitam parada do gravador, além de silenciar os dados. Se o SDK recusar o controle do encoder, o diagnóstico informa e mantém o áudio mudo. Mudo/segundo plano param a captura quando os controles são aceitos; retorno ao app não reativa o microfone automaticamente. Encerramento cancela a negociação antes de liberar o cliente. O diagnóstico distingue pedido, início da captura e bytes RTP enviados; bytes enviados não provam que outro jogador ouviu.

O ganho configurável é separado do ganho de foco de áudio. 100% preserva o nível; até 300% é opcional. O controle observa picos do PCM já renderizado, sobe gradualmente e reduz o ganho ao detectar picos/clipping. Não adiciona fila de áudio nem readback gráfico. **É reativo, não um limiter por amostra com lookahead**: o primeiro transiente ainda pode distorcer. Um formato de PCM sem medição compatível limita a amplificação a 100%. Volume, mudo e foco do Android continuam sendo respeitados.

## Imagem

Brilho neutro é 0%; contraste neutro é 100%. Saturação é aplicada antes desses ajustes. Com filtros padrão, os valores usam o desenho que já existia. Com FSR, usam o passe RCAS final, sem nova textura, fila temporal ou passe adicional. Neutros preservam a saída anterior; valores extremos podem perder detalhes em pretos/brancos. Os cabeçalhos AMD fixados permanecem intactos.

## Fontes e validação

As estruturas de mouse/teclado e a negociação de chat foram conferidas no cliente oficial público, em [web-rtc-stream.d6f6b903.chunk.js](https://assets.play.xbox.com/playxbox/static/js/web-rtc-stream.d6f6b903.chunk.js), com enums de teclado em [2091.34346f46.chunk.js](https://assets.play.xbox.com/playxbox/static/js/2091.34346f46.chunk.js) e estruturas compartilhadas em [8128.254147fc.chunk.js](https://assets.play.xbox.com/playxbox/static/js/8128.254147fc.chunk.js). Foram consultados os snapshots de Better xCloud/XStreaming já registrados na análise para comparar comportamento; não foi copiado código AGPL.

A validação automatizada cobre persistência/isolamento da conta, orçamento SDP, endpoints de região, renegociação de chat sem nova sessão, respostas antigas, calibração/remapeamento, formato binário MKB, saturação/soltura de teclas, alternativa IPv4, cancelamento, sensor parado, vibração opcional e controles da interface. Os testes GPU conferem brilho/contraste e os kernels FSR anteriores. Os resultados finais constam no contexto consolidado.

Não há TV, dongle, microfone, teclado, mouse ou sensores físicos conectados ao ambiente. Desempenho, sensibilidade, voz ouvida no jogo e compatibilidade real de cada título/conta ainda precisam de testes no aparelho. As opções novas que capturam entradas ou preferem uma família de rede começam desativadas/Automático.

Passaram 384 testes na suíte completa inicial; após a revisão, passaram grupos selecionados de 70, 22, 12 e 14 testes, sem falhas. Lint e empacotamento final foram aprovados, assim como 8 testes de assinatura, 45 verificações FSR e 9 do filtro padrão. O workflow existente executa a suíte completa do commit publicado e produz o APK com a assinatura fixa.
