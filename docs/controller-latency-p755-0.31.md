# Atraso de câmera na TCL P755 — 0.31

## Relato e limites das medidas

O usuário percebeu atraso leve entre mover a câmera no controle e ver a resposta em Gears of War: E-Day na TCL P755, mesmo com ping e decode baixos. Confirmou **app 0.29.0, Menor atraso, codec padrão H.264 e receptor USB**. Não guardou o diagnóstico na TV. O modelo do controle/receptor ainda não foi confirmado.

O relato antecede as mudanças de decoder/cadência e os codecs experimentais da 0.31; não é evidência de regressão por HEVC ou pela migração para WebRTC M137. A comparação deve manter H.264 padrão como referência. Receptor USB também não significa necessariamente cabo até o controle: pode existir um trecho de rádio antes do receptor.

RTT é ida e volta da conexão; decode observa o pipeline do codec. Nenhum mede sozinho o percurso controle → Android → canal de input → processamento do jogo → captura/encoder → buffer de vídeo → composição/TV. FPS decodificados também não demonstram a taxa de simulação do jogo. Não há coleta física de input-to-photon da P755 neste ambiente.

## Evidência encontrada no código

- O eixo da câmera já ignora a zona morta do driver e desperta a thread de envio quando muda. O buffer consolida posições analógicas na mais recente e não reproduz posições antigas em fila. A frequência nominal permanece 250 Hz fora do preset Compatibilidade.
- O campo antigo “Atraso local do input” era somente a diferença do agendador da thread para seu prazo. Não incluía a idade do evento ao chegar à Activity, Bluetooth/USB ou o processamento remoto.
- O Android pode agrupar `ACTION_MOVE` em `InputConsumer` e consumir lotes no ciclo da interface. Em Android 11+, `View.requestUnbufferedDispatch(SOURCE_CLASS_JOYSTICK)` permite pedir consumo imediato dessa classe de fontes. A implementação AOSP foi conferida, incluindo a rotina de consumo dos lotes em `ViewRootImpl`.
- O diagnóstico visual pós-partida mostrava RTT/decode, mas não tinha cartões para o buffer observado e seu alvo. A saída Android/pós-processamento também não era incluída no resumo persistido da sessão.

Essas evidências permitem corrigir um ponto local e melhorar a medição. Não comprovam que ele seja a causa única do relato nem que o firmware da P755 use exatamente o comportamento do AOSP examinado.

## Alterações

1. Solicitar despacho imediato **somente para joystick**, em Android 11+, no modo Menor atraso, com vídeo já iniciado, app visível e janela de gameplay focada. Menus, perda de foco, background, fim da sessão e destruição restauram o padrão. Toque/mouse não recebem essa solicitação. Android 7–10 mantém o caminho anterior.
2. Registrar média, mínimo, pico e contagem da idade do evento analógico na entrada da Activity, usando `uptimeMillis` e `MotionEvent.eventTime` na mesma base de tempo. O histórico máximo recebido e o nome limitado do dispositivo também aparecem. Não se reproduz o histórico para fazer essa medição; continuam sendo usadas as posições mais recentes.
3. Reutilizar o `InputDevice` do evento durante o mapeamento dos eixos, evitando resolver o mesmo dispositivo repetidamente.
4. Renomear o indicador antigo para **Agendador de input** e destacar **Buffer de vídeo observado**, **Alvo do buffer WebRTC** e agendador no painel pós-partida. Buffer acima de dois intervalos da apresentação solicitada gera uma observação específica, mesmo com RTT/decode baixos.
5. Persistir no relatório a saída Android, cadência, dimensões do renderer, filtros, pedido de pós-processamento mínimo e o suporte declarado pelo Android. Suporte/pedido não são confirmação de que AiPQ, MEMC ou o modo de jogo da TV estão ativos/desativados.
6. Ampliar o limite fixo dos detalhes de 1.024 para 4.096 caracteres, para preservar os dados de input, tela e codec no resumo copiado. Não há histórico ilimitado de eventos nem armazenamento de endereços Bluetooth, SSID, IP ou SDP.

A idade do evento começa no timestamp fornecido pelo Android. Ela não mede toda a viagem por rádio/cabo antes desse timestamp, e o envio aceito localmente não comprova aplicação do comando pelo servidor. A mudança não altera confiabilidade/ordenação do canal Xbox, curva da câmera, bitrate, codec, FPS do jogo ou parâmetros de sistema da TV.

## Comparação necessária no aparelho

Repetir uma cena com H.264 padrão, mesma resolução, proporção e filtros, comparando a 0.29 com a versão atual. Conferir o buffer observado e a saída Android; depois comparar Menor atraso com Adaptativo. No controle com receptor USB, comparar com cabo direto ao controle quando compatível, mantendo o restante igual. Quando possível, comparar rede cabeada ou Wi-Fi de 5 GHz para separar interferência de 2,4 GHz.

Se o jogo oferecer modos de qualidade/desempenho, comparar a resposta entre eles sem usar FPS do stream como prova dos FPS do jogo. No aplicativo nativo da TV, não presumir que opções de modo Jogo/ALLM destinadas a HDMI também atuem sobre apps internos. Os recursos de imagem disponíveis nesse app devem ser conferidos no firmware da própria TV.

O próximo relatório permite separar atraso Android observável, agendamento local e retenção de vídeo. Uma comparação com câmera de alta velocidade e referência física do comando é necessária para medir a latência total.

Referências: [API de despacho por classe](https://developer.android.com/reference/android/view/View#requestUnbufferedDispatch(int)), [View AOSP Android 14](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/view/View.java), [ViewRootImpl](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/view/ViewRootImpl.java), [InputTransport](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-14.0.0_r1/libs/input/InputTransport.cpp).

## Validação local

Passaram **316 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais lint sem problemas e `:app:assembleRelease` sem assinatura. Os testes novos verificam a chamada real da API de joystick/restauração em Android 11, ausência de chamada em Android 9, anexação da View, agregação dos timestamps, dados ausentes, preservação dos detalhes até 4.096 caracteres e destaque/alcance dos nove cartões do relatório. A captura do painel foi inspecionada.

A política de assinatura fixa continua preservada. O ganho real de resposta e o comportamento do firmware da P755 precisam de comparação física; os testes não simulam a latência do receptor nem a resposta de Gears of War: E-Day.
