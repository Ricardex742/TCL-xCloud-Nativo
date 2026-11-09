# FSR com entrada direta e RCAS em resolução nativa — 0.31.10

Versão **0.31.10-fsr-direct / 41**, exclusivamente na branch `perf/decoder-display-latency-0.31`.

O usuário confirmou aproximadamente 13 FPS em 1440p e seis em 4K na TCL, contra perto de 60 FPS ao desligar FSR. Também relatou pouco benefício visual, enquanto FSR no XStreaming foi mais perceptível e fluido nessa mesma TV. A 0.31.9 acrescentou uma proteção; esta entrega otimiza o trabalho gráfico e permite testar nitidez sem forçar uma saída maior.

## O que mudou

O caminho anterior de ampliação tinha três passagens: preparar OES/RGB/YUV em RGB, executar EASU em outra textura e apresentar com RCAS. Entradas de hardware OES e RGB agora fazem **EASU diretamente sobre a textura recebida → RCAS na saída**, em duas passagens. As amostras usam a matriz original do WebRTC, incluindo rotação, espelhamento e recorte; os pontos são limitados ao domínio lógico antes da transformação. O kernel EASU completo e o RCAS com denoise continuam derivados do AMD FSR 1.0.2 fixado no projeto.

Quando vídeo e destino têm exatamente o mesmo tamanho e RCAS está acima de zero, o app executa **somente RCAS diretamente na saída**, em uma passagem e sem textura intermediária de trabalho. Assim, 1080p → 1080p não abandona mais toda a filtragem. Esse caso aparece como `ativo RCAS nativo (sem EASU)`: é nitidez adaptativa em resolução nativa, não uma ampliação FSR nem reconstrução de detalhes perdidos na compressão. A tela Android/TV ainda pode realizar a escala final até o painel físico.

RCAS em 0% mantém EASU se houver ampliação; sem ampliação, usa o filtro padrão. Brilho, contraste e saturação continuam na passagem final. Nenhuma alteração oculta de saturação, contraste, resolução recebida ou bitrate foi acrescentada.

### Passagens e texturas de trabalho

| Caso OES/RGB | Antes | Agora | Memória intermediária RGBA8 antes → agora |
|---|---|---|---|
| 1080p → 1080p, RCAS > 0 | Filtro pulado | RCAS, uma passagem | Nenhuma → nenhuma |
| 1080p → 1440p | Três passagens | Duas passagens | 22,0 → 14,1 MiB |
| 1080p → 4K | Três passagens | Duas passagens | 39,6 → 31,6 MiB |

Esses valores contam apenas texturas intermediárias do filtro: não incluem buffers do decoder, Surface, apresentação ou driver. Reduzir passagens e alocações não comprova uma taxa de FPS específica. A leitura repetida de OES também depende da conversão/formato e do driver da GPU.

## Compatibilidade e proteções

- YUV separado mantém preparação RGB para preservar a transformação de cores do WebRTC; usa EASU + RCAS se houver ampliação, ou apenas RCAS depois da preparação em 1:1.
- Se a tentativa direta falhar, há uma única tentativa com preparação RGB no mesmo desenho; a seleção de compatibilidade permanece naquela instância, sem recompilar indefinidamente a variante recusada. Se também falhar, volta à escala padrão.
- Dimensões/precisão/limites de GPU são verificados antes das alocações. Permanecem o limite de saída 4K e 64 MiB estimados de trabalho.
- Automático acompanha a janela, limitado a 1080p nas TVs. 1440p e 4K continuam escolhas manuais, limitadas à saída física. A política/proteção da 0.31.9 permanece: cadência FSR muito baixa de forma sustentada, em amostras válidas com vídeo chegando e perda até 1%, retorna à escala padrão na mesma partida.
- Quando o filtro é dispensado (como 1:1 com RCAS 0%), a proteção de fluidez pausa; ausência de desenhos filtrados não é registrada como sobrecarga da GPU. Ao retomar o filtro, começa uma nova janela de aquecimento.
- Não se acrescentaram leitura de pixels, espera de GPU, interpolação, contexto EGL adicional ou cópia de quadros para a CPU no app. Troca de resolução/caminho libera alvos intermediários que deixaram de ser necessários.

O diagnóstico distingue EASU + RCAS de RCAS nativo, entrada direta de preparação RGB, passagens, resoluções e memória de trabalho estimada. A medida CPU/GL continua explicitamente diferente de GPU, FPS de apresentação e latência total.

## Comparação com XStreaming e origem do código

A revisão conceitual do XStreaming no commit `06ef1c7ee01d09cf9bb2a97fc4895da85e8488a8` encontrou caminhos diretos sobre OES e filtragem em resolução nativa. Isso orienta quais custos comparar; não identifica qual shader ou tamanho de Surface foi usado no teste do usuário. Não foi incorporado código da aplicação AGPL. A implementação usa os shaders AMD MIT já existentes e adaptações próprias de amostragem, planejamento e retorno de compatibilidade.

Fontes da comparação: [FsrVideoProcessor.java](https://github.com/Geocld/XStreaming/blob/06ef1c7ee01d09cf9bb2a97fc4895da85e8488a8/android/app/src/main/java/com/xstreaming/fsr/FsrVideoProcessor.java) e [RTCFsrVideoView.java](https://github.com/Geocld/XStreaming/blob/06ef1c7ee01d09cf9bb2a97fc4895da85e8488a8/android/app/src/main/java/com/oney/WebRTCModule/RTCFsrVideoView.java). A origem e licença dos kernels estão em [FSR experimental 0.31.4](fsr1-experimental-0.31.4.md) e `third_party/amd-fsr1`.

## Validação

O teste JVM exporta as variantes de shader realmente geradas pelo app. O teste Mesa cria EGLImage e textura externa OES e executa as variantes diretas, além das referências AMD fixadas. Confere cores, preto/branco, bordas, recortes, rotação, espelhamento, origem de viewport, RCAS em intensidades diferentes, ajustes de imagem e alocação/apresentação em 1440p/4K. Um padrão em resolução nativa confirma alteração de bordas com RCAS ligado. A tolerância cobre eventual remoção do arredondamento da preparação RGBA8; as comparações diretas/preparadas da execução local tiveram diferença máxima de zero nesse driver.

Passaram **342 verificações FSR** e **nove do filtro padrão** por GPU de software. Esse resultado verifica pixels/caminhos e não mede desempenho da TCL, aparência de uma partida real ou ausência de travamentos no aparelho. Testes JVM cobrem também reutilização de planos, ausência de preparação nas entradas diretas, RCAS 1:1, YUV, mudança para RCAS 0%, limites, falha/retorno de compatibilidade e liberação de recursos. A suíte Android local exercitou 424 casos; as classes FSR passaram em 35 testes. Dois testes antigos de sinalização apresentaram falhas intermitentes de prazo em execuções diferentes e passaram isoladamente, sem alterações de rede. O contexto consolidado registra esse limite; o CI da branch reexecuta a suíte completa e verifica a assinatura do APK.

Lint sem problemas e build release local sem assinatura aprovados. Identidade conferida: `com.xcloud.tclnative`, versionCode 41 / 0.31.10-fsr-direct, minSdk 24 e target 34. A tela atualizada foi inspecionada; o APK de atualização é gerado pelo CI com o certificado fixo existente.

## Primeiro teste na TCL

1. Mantenha 1080p HQ, H.264 padrão, Menor atraso e os demais ajustes da sessão iguais ao teste sem FSR.
2. Em **Imagem e tela**, ative AMD FSR e selecione **Saída do FSR → Automático**. Em **Ajustes da imagem**, comece com **RCAS 50%**; mantenha saturação/contraste em 100%, brilho em 0%.
3. Em vídeo e saída 1920×1080, o diagnóstico deve mostrar **RCAS nativo (sem EASU)**, **uma passagem** e **textura direta**. Se a resolução recebida mudar, pode entrar EASU; observe o que foi realmente executado.
4. Compare a mesma cena parada com FSR desligado e ligado. Avalie a mira/comandos em movimento e a fluidez, além de textos e contornos. Se necessário, compare RCAS 75%, observando ruído/halos.
5. Só depois compare 1440p e 4K; continuam pesados e experimentais. Se aparecer retorno à escala padrão, registre a mensagem e o relatório da partida.

A expectativa é reduzir o custo evitável e oferecer nitidez perceptível sem exigir 4K. O ganho de FPS e o melhor ajuste da TCL continuam pendentes de teste físico.
