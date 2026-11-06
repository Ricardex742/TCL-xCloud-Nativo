# Decoder, cadência da tela e buffer — 0.31

Base: branch `ci/apk-android10-3cc2595`, commit `777a7e3`. Continuação isolada em `perf/decoder-display-latency-0.31`, sem modificar a branch original nem a configuração publicada do ambiente.

## Relatório que motivou o ajuste

A sessão enviada pelo usuário na 0.30 durou 25min47s, com Fortnite, toque nativo, Android 16 e vídeo H.264 de 2080×992. Médias: 58,43 FPS decodificados, decode de 18,72 ms/quadro, buffer de 59,51 ms, RTT de 45,52 ms e jitter de 3,20 ms. A tela permaneceu em 90 Hz para uma apresentação solicitada de 60 FPS. Houve uma pausa observada de quatro segundos sem dados de vídeo.

O decode do WebRTC não é uma medição isolada do trabalho aritmético do chip. Na dependência fixada, o decoder Java acompanha o intervalo desde a entrada do quadro no codec até a saída. Um pipeline pode sobrepor quadros e sustentar quase 60 FPS mesmo quando esse intervalo supera 16,7 ms. O relatório não demonstra sozinho saturação do decoder, nem permite prometer decode abaixo de 10 ms.

A pausa sem recebimento de vídeo também não é resolvida por prioridade do codec ou troca de frequência da tela. Rede e continuidade do envio pelo serviço continuam sendo hipóteses a comparar no aparelho.

## Solicitações ao decoder

No modo **Menor atraso**, o wrapper H.264 solicita `MediaFormat.KEY_PRIORITY = 0`, valor público de prioridade de tempo real disponível desde Android 6. Essa solicitação não depende de o driver anunciar `FEATURE_LowLatency`; por isso também é feita em Android 7–10 e em decoders que não anunciam esse recurso.

`KEY_LOW_LATENCY = 1` continua restrito a Android 11+ e ao decoder selecionado que declara a capacidade. Não se força taxa máxima de operação, frequência de CPU/GPU, parâmetros de fabricante ou descarte de quadros H.264 de referência. Adaptativo e Compatibilidade mantêm o codec padrão.

O wrapper utiliza a API de `MediaCodecWrapper` da biblioteca `io.getstream:stream-webrtc-android:1.1.1`. Rejeições na configuração/inicialização seguem o caminho de erro reconhecido por `AndroidVideoDecoder`, permitindo liberar o codec e inicializar outro decoder padrão. Após uma rejeição, a recuperação da mesma sessão continua no padrão, sem tentar repetidamente as opções rejeitadas. Uma liberação com falha não aloca outro codec de hardware nesse wrapper.

O diagnóstico registra solicitação/rejeição; não afirma que o driver honrou a prioridade ou que houve ganho medido. O estado é incluído também no resumo pós-partida.

## Cadência da tela durante a partida

Além do pedido existente de taxa da Surface, a janela solicita um modo anunciado compatível com a apresentação:

- Para 60 FPS, mantém 60/120 Hz e seus equivalentes fracionários (59,94/119,88); se o modo atual for 90 Hz, prefere 60 Hz na mesma resolução física, quando anunciado.
- Em Compatibilidade, considera múltiplos de 30 Hz: por exemplo, 90 Hz já comporta cadência uniforme de 30 FPS.
- Não escolhe outra resolução no celular para obter a frequência desejada. Na TV com escala nativa, conserva a política existente de pedir um modo 4K60 anunciado, sem reduzir uma saída 4K120 existente.
- Ao retornar ao catálogo ou destruir a Activity, restaura tanto o ID de modo quanto a frequência de janela anteriores. Outros atributos modificados durante a partida são preservados.

São preferências da janela; o Android, economia de energia e driver decidem o modo efetivo. O diagnóstico de saída continua mostrando a frequência realmente informada pelo sistema. Não foram adicionadas geração de quadros ou novas filas de renderização.

## Buffer observado, alvo e mínimo de rede

O pedido `WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:40/` permanece igual. Não é um teto garantido para o número observado na estatística.

O coletor calcula separadamente os deltas de `jitterBufferDelay`, `jitterBufferTargetDelay` e `jitterBufferMinimumDelay`, divididos pelo delta de `jitterBufferEmittedCount`, convertidos de segundos para milissegundos. São, respectivamente, o tempo observado, o alvo acumulado informado pelo WebRTC e o mínimo estimado a partir das características da rede, sem os acréscimos externos ao alvo. O alvo WebRTC não é simplesmente a configuração de 0–40 ms do app e pode incluir sincronização A/V e outros mecanismos.

As médias de sessão desses indicadores são ponderadas pelos quadros emitidos. Campos ausentes, contadores reiniciados, mudança de stream e intervalos sem emissão não produzem um alvo inventado ou um zero saudável. Quando a versão do WebRTC não expõe um campo, ele aparece como `n/d`. Os campos novos são persistidos pelo formato de métricas já existente; relatórios antigos continuam legíveis.

O aviso de decode passa a considerar a apresentação solicitada (16,67 ms a 60 FPS ou 33,33 ms a 30 FPS) e explica a sobreposição do pipeline. Buffer e decode não devem ser somados para anunciar input-to-photon.

## Verificação e limites

Validação local concluída com Gradle 8.7, Android SDK 34 e JDK Temurin 17 completo: **277 testes JVM/Robolectric**, zero falhas/erros/ignorados, `:app:lintDebug` sem problemas e `:app:assembleRelease` concluído. Os **8 testes Python de proteção da assinatura** também passaram. O APK release gerado tem pacote `com.xcloud.tclnative`, versionCode 31 e mínimo API 24; não tem assinatura de atualização.

Comandos executados em `/workspace/TCL-xCloud-Nativo`, usando o SDK e o JDK locais e o proxy/TLS configurados:

```bash
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python3 -m unittest discover -s scripts/tests -v
```

Os testes novos exercitam a configuração real de MediaFormat com um driver simulado, rejeição de configuração/início, Android 7 sem a chave de Android 11, recuperação padrão após rejeição, restauração de preferências da janela, seleção de cadência e resolução, contadores de buffer e ponderação do relatório. São testes locais; não executam MediaCodec em um dispositivo físico.

O workflow existente também aceita a nova branch e preserva a verificação da assinatura fixa. A chave privada de atualização não está disponível nesta máquina; não foi substituída nem foi criada outra. O APK debug de atualização continua dependendo do segredo existente do GitHub Actions. Um APK release sem assinatura serve somente para validar empacotamento local e não é uma atualização instalável.

Para comparar no mesmo celular: manter jogo, perfil 1080p, rede, proporção Original, nitidez 0% e saturação 100%; testar Menor atraso e Adaptativo em trechos semelhantes. Guardar os relatórios, conferindo versão, estado do codec, frequência efetiva, FPS, descartes, buffer observado/alvo/mínimo e pausas. Separar uma mudança de cadência visual de uma redução medida de latência. Toque nativo, áudio, login e assinatura de atualização permanecem nos caminhos existentes.

Referências: [MediaFormat.KEY_PRIORITY](https://developer.android.com/reference/android/media/MediaFormat#KEY_PRIORITY), [KEY_LOW_LATENCY](https://developer.android.com/reference/android/media/MediaFormat#KEY_LOW_LATENCY), [estatísticas de jitter buffer W3C](https://www.w3.org/TR/webrtc-stats/#dom-rtcinboundrtpstreamstats-jitterbuffertargetdelay) e [taxa de quadros no Android](https://developer.android.com/media/optimize/performance/frame-rate).
