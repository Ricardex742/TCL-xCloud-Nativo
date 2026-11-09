# AMD FidelityFX Super Resolution experimental — 0.31.4

## Escolha e fontes

O usuário pediu a melhor versão possível de AMD FSR para testar no app. Foi escolhida a implementação espacial **FSR 1.0.2 completa, EASU + RCAS**, adaptada a fragment shaders OpenGL ES. O cliente recebe quadros de vídeo já prontos; não recebe profundidade, vetores de movimento, exposição ou jitter de renderização do motor do jogo. Não é uma integração temporal FSR 2/3/4, nem geração de quadros. A documentação oficial do [FSR 2](https://github.com/GPUOpen-Effects/FidelityFX-FSR2#required-input-resources) explica os recursos exigidos por esse caminho temporal.

A fonte usada é o release oficial AMD **v1.0.2**, commit [`a21ffb8f6c13233ba336352bdff293894c706575`](https://github.com/GPUOpen-Effects/FidelityFX-FSR/tree/a21ffb8f6c13233ba336352bdff293894c706575). Os dois cabeçalhos originais estão em `third_party/amd-fsr1`, com licença MIT e checksums em [SOURCE.md](../third_party/amd-fsr1/SOURCE.md). O APK inclui a licença integral em `assets/fsr1/LICENSE.txt`. Não há código ou binários incorporados dos projetos Nuvem-TV/OnlyTCL.

## Pipeline

O FSR fica **desligado por padrão**, sem criar shaders, FBOs ou texturas adicionais nesse estado. Ao ativá-lo, cada quadro usa três passagens de GPU na mesma thread EGL do renderer:

1. **Preparação RGB:** aplica a matriz de textura, rotação, espelhamento, crop/proporção e conversão OES/RGB/YUV do WebRTC a uma textura RGBA8, usando highp também nessa passagem. A resolução de preparação segue os pixels úteis do quadro; se uma proporção reduzir um eixo, essa redução ocorre aqui.
2. **EASU:** ampliação espacial com os 12 taps, direção de borda, anisotropia, pesos polinomiais e limitação aos quatro vizinhos centrais do algoritmo AMD. Lê RGB com amostras pontuais, substituindo textureGather para compatibilidade com GLES 2.
3. **RCAS:** nitidez com o kernel/limitador AMD e o caminho de redução de ruído habilitado para vídeo comprimido. A saturação existente é aplicada nesta passagem. Não há uma segunda aplicação do unsharp padrão sobre o RCAS.

Os shaders usam `highp`, recíprocos e inversa de raiz exatos em lugar das aproximações de bits do cabeçalho e guardas para denominadores zero. A capacidade highp de pelo menos 23 bits é verificada no fragment shader antes de alocar as texturas. Essas adaptações estão identificadas no código e são comparadas com os cabeçalhos oficiais nos testes de pixels.

Não há cópia de pixels para CPU, leitura da tela ou fila temporal no caminho Android. O processamento é espacial sobre o quadro atual; as passagens extras podem aumentar o trabalho de GPU e o atraso de apresentação. Os kernels não mudam o decoder, codec, banda, sessão Xbox, coordenadas do toque ou FPS fornecidos pelo jogo/serviço.

## Configurações e salvaguardas

- **Configurações → AMD FSR → AMD FSR 1.0.2 — experimental.** A escolha é salva e vale na próxima partida.
- **Nitidez e saturação → Nitidez FSR (RCAS):** padrão 50%, opções 0/25/50/75/100%. Pode ser ajustada durante a partida. 0% desativa a nitidez RCAS, mantendo a ampliação EASU e a passagem de apresentação/saturação.
- O preset **Compatibilidade** desliga FSR; uma preferência experimental não substitui essa regra. Os demais modos permitem o experimento. Preferência ausente/desconhecida usa OFF.
- Com FSR solicitado, a Surface usa o renderer na resolução de saída, para não executar EASU apenas na superfície pequena do decoder e ampliar depois pelo Android. A preferência anterior de escala da Surface é preservada para quando o experimento estiver desligado.
- A saída tem orçamento de até **8.294.400 pixels**, equivalente a 3840×2160, condicionado aos limites reais de textura/renderbuffer. As duas texturas RGBA8 têm limite de **64 MiB estimados**; 1080p → 4K usa cerca de **39,6 MiB** adicionais. Isso não mede toda a memória do decoder/driver.
- Sem ampliação, o renderer usa a escala padrão. Ausência de precisão, tamanho inválido/excessivo ou limites da GPU impedem a alocação. Erros de shader/FBO/GPU liberam os recursos e retornam à imagem padrão; a mesma falha não provoca recompilação em todos os quadros. Outra sessão/renderer cria um novo contexto de tentativa.
- Mudanças de resolução recalculam/reutilizam os alvos. O framebuffer original, viewport, blend/scissor e unidade ativa de textura são restaurados após as passagens, inclusive no caminho de erro. Recursos são liberados na thread GL ao encerrar/reconstruir o renderer.
- O diagnóstico diferencia desligado, solicitado/aguardando vídeo, ativo, sem ampliação, Compatibilidade e motivo de retorno. Informa resoluções do filtro, força RCAS e memória estimada. O tempo de submissão CPU/chamadas GL **não mede tempo de GPU nem atraso da tela** e pode incluir a compilação inicial.

## Validação

O script `scripts/verify_fsr1.py` compila os shaders reais do app em Mesa e compara a saída com shaders gerados dos cabeçalhos AMD fixados. Uma referência usa as aproximações originais; outra substitui apenas as aproximações por recíprocos exatos para conferir a adaptação. Os cabeçalhos no repositório não são alterados; três zeros unsigned são explicitados somente na fonte de referência gerada para GLSL ES.

As verificações cobrem cores uniformes, preto/branco, ruído, gradientes, diagonais, várias razões de ampliação, rotação, espelhamento, bordas, posição do viewport e saturação. O readback existe somente no teste de software. As diferenças frente à referência original aproximada são registradas no JSON; não representam uma medição perceptual de qualidade.

Os testes JVM cobrem default OFF sem criar upscaler, persistência, escolha explícita na UI, ajuste RCAS, prioridade de Compatibilidade, rotas OES/RGB/YUV, matrizes de textura, ausência de unsharp duplicado, reutilização/limites de recursos, recuperação após mudança de tamanho, liberação idempotente e restauração do alvo após falhas em cada estágio.

Em 9/11/2026, a suíte completa passou em **367 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, além dos **8 testes Python da proteção de assinatura**. Na revisão final que mantém highp também na cópia de entrada, foram aprovados **36 testes selecionados**, incluindo um novo caso que exporta os shaders OES/RGB/YUV efetivamente gerados pelo adaptador WebRTC. Lint terminou com `No issues found` e `assembleRelease` concluiu nas duas validações.

O teste de GPU final passou em **42 verificações de pixels/referência**, com compilação dos shaders EASU/RCAS e das três cópias reais OES/RGB/YUV; executa também a cópia YUV antes dos filtros. Nas fixtures testadas houve erro máximo zero em valores RGBA8 em relação à referência AMD configurada com recíprocos exatos. A maior diferença média registrada contra a referência com aproximações originais foi 1,4444 níveis de 8 bits por componente, incluindo alfa. A alteração matemática de recíprocos é declarada, não escondida como identidade binária ao caminho aproximado AMD. Os cinco checks anteriores do filtro padrão também passaram.

As capturas do seletor e do ajuste RCAS foram inspecionadas. O APK local foi conferido com pacote `com.xcloud.tclnative`, versionCode 35, versão `0.31.4-fsr1`, shaders e licença incluídos; os cabeçalhos de referência não são empacotados. O build local é release sem assinatura. O CI existente executa novamente os testes completos, a verificação dos shaders e a geração do APK com o certificado fixo. Não foi criada chave substituta nem alterada a configuração publicada do ambiente.

Os testes de GPU por software não comprovam desempenho no LG LM-K200 ou TCL P755. Não há um aparelho conectado nem uma sessão física autenticada nesta máquina.

## Teste no aparelho

Começar com uma transmissão 720p exibida em uma saída maior, preferencialmente 1080p, H.264 padrão, proporção Original e RCAS 50%. Manter jogo, cena, conexão e modo de vídeo iguais e comparar FSR OFF/ON em partidas separadas. Conferir primeiro se o relatório mostra **ativo**, as dimensões recebidas e a saída real do filtro; a resolução solicitada ao Xbox pode não ser a recebida.

Comparar bordas de texto/objetos, halos, ruído de compressão, fluidez e resposta da câmera. FPS do decoder, ping e decode não incluem todo o custo do novo renderer; um decoder a 60 FPS não comprova apresentação a 60 FPS. O teste em 4K exige mais GPU e deve ser feito depois da comparação em 1080p. Se houver perda de fluidez/resposta, desativar FSR e reabrir a partida com a mesma configuração de transmissão.

Versão desta entrega: **0.31.4-fsr1**, versionCode **35**, na mesma branch `perf/decoder-display-latency-0.31`.
