# Contexto completo das modificações desta branch

## Estado e objetivo

O trabalho está exclusivamente na branch **`perf/decoder-display-latency-0.31`**, criada a partir de `ci/apk-android10-3cc2595`, commit **`777a7e3487c230fc515a8b33665a08815bd1eeb0`**. A branch original foi preservada. Após o usuário perguntar pela branch, ela chegou a ser enviada ao GitHub e foi removida de lá a pedido dele. A cópia local foi mantida. Posteriormente, o usuário autorizou novamente a publicação desta branch no GitHub, incluindo as correções e os experimentos existentes naquela etapa. As entregas posteriores continuam na mesma branch. A configuração publicada do ambiente não é alterada.

Estado atual do código: **0.31.9-fsr-fluency-guard, versionCode 40**. As seções abaixo registram as entregas em ordem; a sétima substitui a política inicial de oferecer H.264 junto com os experimentos, a oitava trata a coleta ICE vazia, a nona revisa a entrega de toque nativo a décima adiciona FSR espacial experimental e a décima primeira substitui os codecs experimentais por Baseline leve e acrescenta o teste USB de vibração; a décima segunda entrega os dez itens de sessão, entradas, áudio e imagem aprovados pelo usuário; a décima terceira organiza categorias e amplia rede automática/giroscópio nativo; a décima quarta permite saídas FSR Automático/1440p/4K em buffers independentes do tamanho da interface; a décima quinta corrige o padrão Automático e acrescenta proteção de fluidez após teste físico na TCL.

A base já continha o xCLOUD 0.30 com login Microsoft/Xbox, catálogo, histórico remoto, favoritos, atalhos, HUDs, toque nativo, áudio, WebRTC/H.264 e diagnósticos. Esses recursos vieram do trabalho anterior documentado em `docs`; não foram todos implementados novamente nesta conversa.

O link de conversa compartilhada não forneceu o histórico ao executor. A retomada utilizou o arquivo `contexto 2.0.txt`, a documentação da branch e o código real. O ponto interrompido era a análise de uma partida longa de Fortnite na 0.30 e a possibilidade de melhorar decode, buffer e cadência da tela.

## Preparação do ambiente feita anteriormente

Na primeira etapa da conversa, o checkout inicial estava numa versão antiga da branch principal. Foram instalados e verificados JDK 17 completo, Gradle 8.9 e Android SDK 34 fora do repositório, com ferramentas sob `/workspace/.local/android-dev`. Foram salvos `install_script` e `start_skill` de preparação. O usuário posteriormente publicou o ambiente.

Ao retomar a branch correta, foi preparado Gradle 8.7, conforme o repositório/CI, e utilizada a confiança TLS do sistema para as conexões do Gradle pelo proxy. Não se desativaram verificações TLS ou checksums. A compilação local desta branch usa os plugins já fixados no projeto: AGP 8.5.2 e Kotlin 1.9.24. Os scripts antigos de instalação não foram reaplicados à configuração publicada.

A chave privada de atualização não está disponível na máquina. O projeto continua exigindo seu certificado fixo para o APK debug de atualização. Não foi criada uma chave substituta. O CI existente possui o fluxo de segredo e validação; o build local de empacotamento usa release sem assinatura, que não é um APK de atualização instalável.

## Primeira mudança: vídeo e diagnósticos

Registrada no commit **`cc1467f`**, `Request realtime decoding and align playback display cadence`.

### Evidência usada

A sessão recebida do usuário durou 25min47s: Fortnite, Samsung SM-A155M, Android 16, toque nativo, H.264 de 2080×992, média de 58,43 FPS decodificados, decode de 18,72 ms/quadro, buffer de 59,51 ms e RTT de 45,52 ms. A saída Android estava em 90 Hz para uma apresentação solicitada de 60 FPS. Houve uma pausa observada de quatro segundos sem dados de vídeo.

Esses valores não são input-to-photon. Um pipeline de hardware pode sobrepor quadros; decode acima de 16,7 ms não demonstra sozinho incapacidade de sustentar 60 FPS. A interrupção sem dados também não é necessariamente um problema do decoder.

### Alterações realizadas

1. **Decoder:** `KEY_PRIORITY=0` no modo Menor atraso, inclusive em Android 7–10, sem depender de `FEATURE_LowLatency`. `KEY_LOW_LATENCY=1` permanece restrito a Android 11+ e suporte declarado pelo decoder. Não foram adicionados parâmetros de fabricante, clock máximo ou descarte de quadros de referência.
2. **Fallback e recuperação:** rejeições de configuração/início seguem o caminho de erro reconhecido pelo WebRTC 1.1.1. O decoder padrão é usado após rejeição e mantido nas recuperações da mesma sessão. Uma liberação com falha não aloca sucessivamente novos codecs nesse wrapper.
3. **Tela:** durante a partida, preferência por uma frequência compatível com 60 FPS, como 60/120 Hz, em vez de 90 Hz quando o aparelho oferece essa alternativa. A resolução física do celular é preservada. Compatibilidade considera múltiplos de 30 Hz; TVs mantêm a política de 4K60 suportado sem reduzir uma saída 4K120 existente. As preferências de janela anteriores são restauradas ao voltar ao catálogo/destruir a Activity.
4. **Buffer:** observado, alvo WebRTC e mínimo estimado da rede são calculados separadamente pelos contadores cumulativos e quadros emitidos. As médias de sessão usam ponderação por emissão. Ausência/reset de contadores aparece como `n/d`, não como zero saudável.
5. **Relatório:** registra o estado das solicitações ao codec e explica os limites das medidas. O aviso de decode respeita a apresentação de 60 ou 30 FPS. O pedido de playout 0–40 ms foi mantido; ele não é um teto garantido para a estatística observada.
6. **Versão/CI:** versão de desenvolvimento `0.31.0-decoder-display-latency`, versionCode 31; workflow habilitado também para esta branch, com a assinatura fixa preservada.

A primeira entrega passou em **277 testes JVM/Robolectric**, **8 testes Python de assinatura**, lint sem problemas e build release sem assinatura. O ganho real de atraso não foi medido em um dispositivo físico. Detalhes em [decoder-display-latency-0.31.md](decoder-display-latency-0.31.md).

## Segunda mudança: Game Pass e jogos comprados

### Pesquisa direta nas fontes oficiais

Foram consultadas as páginas oficiais Game Pass e “Transmita seu próprio jogo”, os scripts referenciados e as APIs públicas usadas pelo cliente. O contrato atual é **SIGL v3**, mercado BR, idioma pt-BR e plataforma **`Cloud:XGPUWEB`**.

Em **6/11/2026**, os resultados foram:

| Lista oficial | Produtos retornados | Metadados e oferta XGPUWEB confirmados |
| --- | ---: | ---: |
| Game Pass | 560 | 560 |
| Transmita seu próprio jogo | 3.781 | 3.781 |

A auditoria verificou nomes e ofertas cloud de **todos os 3.845 produtos distintos** da união, não apenas uma primeira página. Há **496 produtos em ambas as galerias**. Os totais podem mudar no serviço e não são constantes da aplicação.

A galeria Game Pass atual inclui opções gratuitas, como Fortnite. O app não subtrai mais essa categoria da lista oficial, nem interpreta o programa genérico `GPULTIMATE` como inclusão na assinatura. Disponibilidade na galeria, compatibilidade cloud, propriedade e direito de iniciar uma partida são informações diferentes.

### Alterações no aplicativo

1. **Aba Game Pass atualizada:** consulta a galeria oficial atual (`af206485-e87d-4624-9007-cb7f6d0cc42e`) pelo contrato v3. A galeria antiga v2 deixa de ser a fonte de produção dessa aba.
2. **Nova aba JOGUE SEU JOGO COMPRADO:** consulta `e78d9a61-5ef4-43af-b400-edba1250b18e`, a fonte da rota oficial `buy-and-stream`. A lista não é apresentada como inventário de compras do usuário.
3. **Catálogo completo por produto:** produtos/edições oficiais continuam visíveis mesmo quando ainda não têm um vínculo no catálogo nativo. Não há limite artificial de itens, lista estática de títulos ou IDs cloud inventados.
4. **Modelo separado:** `GalleryProduct`/`CatalogCard` representam a navegação pública; `GameTitle` mantém o ID real de lançamento obtido de uma resposta autenticada. Cartas de edições distintas não colidem por compartilhar o mesmo título cloud.
5. **Verificação ao selecionar:** produtos sem vínculo consultam o serviço autenticado por ID de produto. Somente uma resposta válida e única fornece um ID para o fluxo de partida. Falhas, respostas vazias, ambíguas ou não filtradas não iniciam jogos. Resoluções atrasadas não atravessam uma troca de conta/tela ou o início de outra sessão.
6. **Capacidades preservadas:** o início da partida utiliza as capacidades reais de input e a sessão existente. Um produto público não inventa NativeTouch, propriedade, assinatura ou licença. O serviço continua autorizando o provisionamento.
7. **Nomes, capas e oferta cloud:** consulta pública `RemoteLowJade0` em lotes de até 50, com a correlação `MS-CV` exigida pelo endpoint. Metadados completos já obtidos no catálogo nativo são reutilizados. `XCloudOfferings.XGPUWEB.Programs` informa suporte cloud; não define a categoria Game Pass. Dados incompletos geram aviso de parcialidade.
8. **Desempenho:** imagens continuam limitadas às views visíveis/anexadas e ao tamanho exibido. Novas consultas respeitam a pausa do catálogo durante a partida; atualizações visuais são agrupadas. Não é feito download das capas dos milhares de jogos antecipadamente.
9. **Interface e pesquisa:** seis abas com navegação circular por LB/RB; o nome novo ocupa duas linhas. A pesquisa global também encontra produtos públicos ainda não associados ao motor nativo. Favoritos existentes mantêm seus IDs reais; uma carta sem vínculo precisa ser verificada antes de favoritar.
10. **Estado de falha:** falha de consulta não vira “lista vazia confirmada”. Totais e mensagens distinguem atualização, dados parciais, indisponibilidade e lista oficial carregada.

As respostas completas dos SIGLs foram preservadas como fixtures de teste, não como catálogo embutido em produção. Os testes cobrem contagens completas, sobreposição, gratuitos oficiais, formato HTTP, metadados, pausa durante partidas, resolução autenticada, pesquisa, seleção e layout em telefone/TV/4K. O documento [official-catalog-galleries-0.31.md](official-catalog-galleries-0.31.md) contém as URLs, o contrato e os checksums da pesquisa.

## Estado de validação e próximos passos

A validação final passou em **286 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python da proteção de assinatura**. `:app:lintDebug` concluiu sem problemas e `:app:assembleRelease` gerou o pacote sem assinatura. A captura de UI da nova aba foi inspecionada; os testes conferiram duas linhas sem reticências e navegação por controle em telefone, TV e 4K.

Comandos executados em `/workspace/TCL-xCloud-Nativo`, com o SDK/JDK locais e proxy/TLS configurados:

```bash
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python3 -m unittest discover -s scripts/tests -v
```

A auditoria pública consultou todos os produtos dos dois SIGLs e todos os lotes de metadados; nenhum ID retornado ficou sem nome ou sem confirmação da oferta XGPUWEB nesta coleta. A planilha de conferência contém 3.845 produtos únicos, com nome oficial e marcação independente das duas galerias. Essa auditoria não usou a conta do usuário nem confirmou propriedade.

Continua necessária uma validação no aparelho com conta/licença reais: abrir as duas galerias, comparar com a região BR do site, selecionar uma edição com e sem vínculo prévio e testar um jogo comprado que a conta possua. Também continua pendente a comparação de latência da primeira mudança, mantendo jogo, perfil 1080p, rede, proporção Original e filtros iguais. Não há neste ambiente uma sessão autenticada de gameplay físico que comprove esses resultados.

O APK de atualização assinado depende do CI e do segredo existente. Não foram publicados ambiente, branch remota ou APK nesta etapa.

## Terceira mudança: nome da aba e revisão de codecs/projetos

A aba de compra foi renomeada para **TRANSMITA SEU JOGO**, em uma linha, mantendo o ID de categoria, a galeria oficial, o fluxo de licença e a navegação. A alteração foi validada com os dois testes de UI selecionados, lint sem problemas e empacotamento release sem assinatura.

Foram analisados Nuvem-TV (`3d3efd9`) e OnlyTCL (`567cde1`), suas fábricas de decoders, dependências, fluxo de vídeo, estatísticas, testes e licenças. A biblioteca Java atual já representa cinco codecs, mas nosso app continua negociando H.264. O cliente público oficial Xbox possui uma preferência condicional HEVC; isso fundamenta um experimento futuro, sem demonstrar uma partida em HEVC no nosso app. A recomendação é manter H.264 padrão, auditar High no decoder físico e testar HEVC somente com capacidades, negociação e desempenho confirmados. AV1/VP9 precisam também de confirmação do serviço e do aparelho.

O caminho MediaCodec→Surface do OnlyTCL merece um experimento separado, especialmente em TVs limitadas, mas exige sincronização/recuperação e não equivale automaticamente a hardware tunneling. Seu motor do snapshot é H.264, e seu shim sintetiza algumas métricas, incluindo buffer zero; os números não são uma comparação direta com nosso diagnóstico. Não foram incorporados engines, código AGPL/MIT, scripts de sistema ou binários dos projetos externos, nem foi alterado o codec ativo.

A análise completa, com fontes imutáveis, limites e condições para não comprometer dispositivos modestos, está em [codec-and-project-review-0.31.md](codec-and-project-review-0.31.md).

## Quarta mudança: travamentos nas galerias em celular modesto

O usuário informou ANRs e travamentos ao alternar Game Pass e Transmita seu jogo em um **LG K10, app 0.31.0**. A expansão anterior mantinha projeção e comparação de listas grandes na thread da interface, incluindo detecção de movimentos durante reordenações causadas pela chegada dos nomes.

A preparação e a comparação do catálogo foram transferidas para um único processamento em segundo plano, com uma solicitação pendente substituída pela mais recente e descarte de resultados antigos. Mudanças de ordem passam a aplicar uma substituição com IDs estáveis; metadados sem reordenação usam notificações em intervalos limitados. Foram reduzidas as alocações de strings, compartilhado o índice nativo na pesquisa e ampliado para 400 ms o agrupamento de metadados. A seleção/foco seguem o produto após a ordenação, e a pausa/fechamento impedem resultados tardios.

As listas oficiais completas e a verificação de lançamento foram mantidas. Foram acrescentados testes com as duas fixtures completas, incluindo uma tarefa de catálogo deliberadamente bloqueada para comprovar que eventos da interface continuam sendo atendidos e que trocas rápidas não aplicam uma busca antiga. O comportamento físico do LG K10 ainda precisa ser confirmado; não houve acesso a trace de ANR desse aparelho. Detalhes em [catalog-menu-responsiveness-0.31.md](catalog-menu-responsiveness-0.31.md).

A suíte completa desta correção passou em **289 testes JVM/Robolectric**, sem falhas ou testes ignorados, além dos **8 testes Python de assinatura**, lint e empacotamento release sem assinatura. Na revisão final, cliques e foco passaram a consultar a posição atual do holder para impedir a ativação de cartões antigos enquanto uma nova lista é preparada.

Depois dessa proteção final, **23 testes selecionados** de galerias/dashboard/imagens/atalhos foram reexecutados com sucesso, junto com lint e empacotamento release. Nenhum push, workflow remoto ou publicação do ambiente foi executado nesta correção.

## Quinta mudança: H.264 High e H.265/HEVC experimentais

Foram adicionadas opções explícitas em **Configurações → Codec** para H.264 padrão, H.264 High experimental e H.265/HEVC experimental. O padrão continua H.264; o preset Compatibilidade mantém a política leve. Os experimentos exigem decoder de hardware com perfil, tamanho e taxa declarados, verificam também as dimensões reais do fluxo e não recorrem a HEVC por software.

A dependência passou para **WebRTC SDK Android 137.7151.05**, com parser/despacotizador RTP HEVC incluídos e conferidos nas quatro ABIs. O anúncio é baseado nas capacidades RTP e no hardware real, sem falsificar perfil/nível. A inicialização e a descoberta de capacidades ficam fora da interface. Prioridade/baixa latência e recuperação continuam condicionadas ao suporte do decoder.

Na implementação inicial 0.31.0, H.264 permanecia na oferta como alternativa; a sétima mudança abaixo substitui essa política nos experimentos. Falhas do experimento antes do primeiro quadro permitem uma única nova sessão em H.264, após liberar a anterior. Depois de uma partida começar a exibir vídeo, o jogador decide se quer reabri-la, com aviso sobre fila/progresso. A configuração salva não é alterada pela tentativa de retorno.

O diagnóstico distingue solicitado, anunciado, resposta SDP e codec RTP recebido, com perfil quando informado nas estatísticas. O caminho permanece SDR de 8 bits; Main10/HDR, VP9 e AV1 não são habilitados. Detalhes e limites em [experimental-video-codecs-0.31.md](experimental-video-codecs-0.31.md). O desempenho e a aceitação efetiva pelo Xbox ainda precisam de validação em aparelho com conta real.

A validação completa passou em **308 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python de assinatura**. Lint sem problemas e empacotamento release sem assinatura concluído. A captura do menu dos codecs foi inspecionada. O workflow existente continua responsável pela geração do APK debug com o certificado fixo.

A branch desta entrega no GitHub é [perf/decoder-display-latency-0.31](https://github.com/Ricardex742/TCL-xCloud-Nativo/tree/perf%2Fdecoder-display-latency-0.31). A publicação foi solicitada expressamente pelo usuário e inclui os commits anteriores de galerias, diagnóstico, nome da aba e responsividade. O envio aciona o workflow de APK já configurado para essa branch; o estado remoto da compilação pode ser acompanhado em [GitHub Actions](https://github.com/Ricardex742/TCL-xCloud-Nativo/actions/workflows/build-apk.yml). A branch original e a configuração publicada do ambiente permanecem preservadas.

## Sexta mudança: resposta do controle na TCL P755

Após a publicação dos codecs, o usuário relatou atraso de câmera em Gears of War: E-Day na TCL P755, com ping e decode baixos. Confirmou que a sessão problemática era no **app 0.29.0, Menor atraso, H.264 padrão e GameSir T4 Nova Lite via dongle USB**, sem guardar o diagnóstico. O episódio antecede os codecs experimentais; não foi atribuído ao HEVC/M137 nem a uma causa única do aparelho/jogo/controle.

A análise confirmou que a câmera já desperta a thread de envio e usa o estado mais recente, mas a entrega anterior dos eventos pelo Android podia aguardar lotes da interface. Foi preparado o pedido público de despacho imediato do joystick no modo Menor atraso em Android 11+, limitado a gameplay visível/focado e restaurado nos menus/background. O mapeamento dos eixos e o contrato Xbox foram preservados.

O relatório passa a registrar a idade dos eventos analógicos ao chegar à Activity e as informações da saída Android, distinguindo isso do atraso do agendador. Buffer observado/alvo ganham destaque no painel. Os detalhes persistidos são limitados a 4.096 caracteres para conservar informações de controle, tela e codec. Não são medidas de Bluetooth completo nem de input-to-photon. Evidências e comparação física necessária em [controller-latency-p755-0.31.md](controller-latency-p755-0.31.md).

A validação desta etapa passou em **316 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, além de lint sem problemas e empacotamento release sem assinatura. A captura do relatório com nove cartões foi inspecionada. O ajuste segue na mesma branch publicada; não altera a configuração do ambiente, a branch original ou a assinatura do aplicativo.

## Sétima mudança: testes do celular e negociação experimental na 0.31.1

O arquivo `testes.txt` trouxe três sessões de Fortnite no Samsung SM-A155M, Android 16, toque nativo, Menor atraso e 1080p. O usuário confirmou melhora percebida no atraso do toque. Entretanto, as opções High, HEVC e padrão receberam **H.264 Baseline 42e01f / c2.mtk.avc.decoder em 2080×992**, após respostas SDP Baseline 42e02a. O hardware opcional estava elegível nominalmente; isso não significa que foi usado. Nenhum resultado foi atribuído a HEVC/High, e os testes do celular não demonstram o comportamento do controle/dongle na TV.

A ordenação da preferência anterior mantinha Baseline na mesma oferta e permitia esse resultado. A 0.31.1 passa a tentar somente o formato experimental elegível na primeira sessão, com verificação do SDP realmente gerado pelo SDK. H.264 padrão/Compatibilidade preservam a política anterior. A sessão de retorno continua limitada a uma tentativa H.264 após liberar a anterior; o relatório conserva um resumo sanitizado da oferta, confirmação do POST, resposta e motivo da falha. O codec recebido continua sendo identificado pelas estatísticas RTP, separado do formato solicitado.

HEVC anuncia Main de 8 bits, main tier e o nível decimal correspondente à capacidade Main real de hardware; não depende de parâmetros de nível implícitos. Não há software HEVC, Main10/HDR, nível inventado ou falsificação de perfil. A aceitação do serviço ainda precisa ser confirmada fisicamente.

A tela permaneceu 90 Hz nos testes apesar da sinalização de conteúdo a 60 FPS. O relatório agora distingue conteúdo sinalizado, modo pedido na janela, modo físico atual, taxa informada ao app e modos disponíveis. O histórico do pedido de joystick durante a sessão também é preservado ao voltar ao menu. Os buffers médios de cerca de 3–4 ms nas duas sessões maiores são observações; decode médio de cerca de 25 ms não prova incapacidade de 60 FPS, e a sessão padrão de 14 segundos não permite comparação controlada.

A versão passa a **0.31.1-codec-negotiation / 32**, mantendo pacote e identidade de atualização. A análise completa e o próximo teste estão em [phone-codec-tests-0.31.1.md](phone-codec-tests-0.31.1.md). A validação completa passou em **330 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python de proteção da assinatura**, lint sem problemas e build release sem assinatura. A captura atualizada do seletor foi inspecionada. O envio segue na mesma branch GitHub já autorizada; o APK assinado continua sendo responsabilidade do CI existente.

## Oitava mudança: coleta ICE local vazia no início de jogos

O usuário relatou falha ocasional `ICE_LOCAL: nenhum candidato de rede disponível`. A mensagem era produzida antes de enviar SDP ao Xbox. O código anterior aceitava COMPLETE mesmo com lista vazia e encerrava a tentativa imediatamente, sem aguardar o restante do prazo nem refazer a conexão local. A ocorrência física ainda precisa de confirmação de aparelho/rede; não foi atribuída automaticamente aos codecs ou a falta de Internet.

Na **0.31.2-ice-recovery / 33**, COMPLETE vazio continua esperando candidatos até oito segundos. Se a lista permanecer vazia, o app libera a primeira PeerConnection/canais e tenta uma segunda uma única vez, dentro da mesma sessão Xbox e antes do envio de SDP. O caminho normal com candidatos não ganha espera fixa; host/IPv6 disponíveis são preservados se o STUN continuar pendente. Não há reinício de uma partida já exibida, nova fila solicitada pelo app ou retorno de codec motivado por essa falha local.

Cada coleta fica isolada por PeerConnection/geração; remoções de candidatos e cancelamento são respeitados. O descarte para a segunda tentativa ocorre em Main, como o fechamento final e `onDestroy`. Relatório/erro recebem apenas estados, contagens e códigos numéricos STUN. Detalhes, limites e procedimento físico estão em [local-ice-recovery-0.31.2.md](local-ice-recovery-0.31.2.md).

A validação completa passou em **346 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python de assinatura**, lint sem problemas e build release sem assinatura. Foram conferidos pacote/versão 33/0.31.2. Os testes HTTP mantêm uma única sessão Xbox durante a recuperação local. A confirmação da recuperação no aparelho ainda está pendente; o envio segue somente na mesma branch experimental, com APK assinado pelo workflow existente.

## Nona mudança: botões intermitentes com toque nativo no LG LM-K200

O usuário enviou uma sessão de 26min14s no **LG/LGE LM-K200, Android 10/Qualcomm, Wi-Fi 2,4 GHz, app 0.31.1**, H.264 padrão/720p/Menor atraso e toque nativo. Informou botões sem resposta intermitente e confirmou ocorrência também com um único dedo. O decode médio foi 10,37 ms, buffer 4,62 ms, RTT 45,82 ms e FPS decodificados 57,39, com três segundos de pausas observadas. Os 55.704 ponteiros aceitos incluíam movimentos; o índice 90 e os contadores de espera/gamepad não provavam reconhecimento de cada botão.

A revisão encontrou ausência do pedido de despacho imediato por gesto no modo nativo, embora o HUD virtual de shooters já usasse essa API pública compatível com Android 10. Na **0.31.3-native-touch / 34**, o nativo faz esse pedido no modo Menor atraso, protege a captura do gesto contra interceptação pelo pai e libera contatos pendentes antes de iniciar um gesto novo. Adaptativo/Compatibilidade preservam o agrupamento padrão do Android. A coalescência de movimentos passa a preservar a posição mais recente por ID, mantendo down/up separados e a fila limitada.

O relatório ganha agregados de ações/idade dos eventos Android, contatos gerados, cancelamentos/solturas, inícios fora do vídeo, menu e pedidos de despacho. O buffer distingue recebidos de aceitos pelo canal, inclui pendências/pico, coalescência e ressincronizações; aceitação continua sem significar confirmação do jogo. A evidência da UI sobrevive ao menu e é limpa somente na próxima sessão após congelar a anterior.

A análise e o procedimento de teste estão em [native-touch-lg-k200-0.31.3.md](native-touch-lg-k200-0.31.3.md). A escala do servidor 1920×1080 continua independente da resolução do vídeo; pacote, assinatura, codecs e correção ICE anterior são preservados. A causa física completa do toque intermitente ainda não foi comprovada.

A suíte completa passou em **354 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python de assinatura**. Depois do ajuste final de política de despacho/texto, foram reexecutados **67 testes selecionados** de toque, input, compatibilidade, início de sessão e relatório, todos aprovados. Lint sem problemas e build release sem assinatura passaram nas duas validações; pacote/versão 34/0.31.3 foram conferidos. O envio segue na mesma branch experimental e o workflow existente gera o APK com o certificado fixo, sem alteração da configuração publicada do ambiente.

## Décima mudança: AMD FSR espacial para teste

O usuário pediu a melhor versão possível do AMD FidelityFX Super Resolution para o app. A escolha foi **FSR 1.0.2 completo, EASU + RCAS**, a partir do release oficial AMD `a21ffb8`. Os quadros decodificados não incluem profundidade/vetores/jitter do motor do jogo para uma integração temporal FSR 2/3/4. A adaptação GLES usa precisão highp, RGB pontual e recíprocos exatos; inclui os kernels EASU/RCAS, não apenas o unsharp já existente. Cabeçalhos de referência e licença MIT foram preservados e a licença também está no APK.

Há uma nova escolha explícita em Configurações → AMD FSR, desligada por padrão, e controle RCAS em Nitidez e saturação, com padrão 50%. O preset Compatibilidade desliga o experimento. O filtro atua após decodificar o vídeo, em preparação RGB, EASU e RCAS/saturação, sem fila temporal nem readback Android. A escala da Surface é ajustada para que o filtro trabalhe na resolução de saída. Codecs, rede, toque e perfil de transmissão seguem as escolhas existentes.

Os alvos têm limites de textura/precisão da GPU, até 8,3 MP de saída e 64 MiB estimados. O retorno à escala padrão é identificado no diagnóstico, com recursos liberados e sem repetir indefinidamente uma compilação que falhou. O relatório informa resoluções, RCAS, memória e tempo de submissão CPU/GL, distinto de tempo de GPU/tela. O ganho visual e o custo de desempenho ainda precisam de comparação física, especialmente em 4K/TVs limitadas.

O script de GPU testa os shaders do app contra referências geradas dos cabeçalhos AMD fixados, com rotação, bordas, cores e várias razões de ampliação. A preparação OES/RGB/YUV também mantém highp e seus shaders reais são exportados pelo teste JVM para conferência no Mesa. Fontes, adaptação, limitações e roteiro de teste estão em [fsr1-experimental-0.31.4.md](fsr1-experimental-0.31.4.md). A versão passa a **0.31.4-fsr1 / 35** na mesma branch experimental; pacote, assinatura e configuração publicada do ambiente são preservados.

Passaram **367 testes JVM/Robolectric** na suíte completa, mais **8 testes Python de assinatura**. Após a revisão da cópia highp, passaram **36 testes selecionados**, lint sem problemas e build release sem assinatura. A GPU por software aprovou **42 verificações** de pixels/referência, incluindo cópia YUV, e o filtro padrão manteve seus cinco checks aprovados. As capturas de UI foram inspecionadas, e pacote/versão/assets/licença do APK foram conferidos. A aceitação e o desempenho nas GPUs físicas ainda precisam do teste do usuário; não foram prometidos ganho de FPS nem latência menor.

## Décima primeira mudança: H.264 leve e teste de vibração pelo dongle

O usuário descartou a proposta de User-Agent, confirmou que os codecs experimentais não funcionaram na TCL e no Samsung e pediu uma opção básica para celulares fracos e uma forma de testar vibração no dongle do GameSir T4 Nova Lite. Esta entrega trata desses pedidos; os demais itens da análise de Better xCloud/XStreaming continuam como possibilidades futuras.

Na **0.31.5-light-codec-usb / 36**, H.264 High e HEVC experimentais saem da seleção e suas preferências antigas migram para H.264 padrão. A fábrica/decoders exclusivos e as reaberturas de sessão por falha do experimento foram retirados. O runtime WebRTC M137 foi mantido. H.264 padrão preserva sua negociação de perfis; **H.264 leve — Baseline (720p)** anuncia somente perfis Baseline reais, solicita 720p e orçamento de até 6 Mbps, com apresentação de até 60 FPS. Compatibilidade também usa Baseline e mantém até 30 FPS/FSR desligado. O SDP local/remoto é validado, sem alterar IDs de perfil. O LG já recebia Baseline, portanto o benefício adicional depende dos pedidos de resolução/bitrate e da sessão; não foi prometido um novo codec ou decode menor.

O diagnóstico de vibração ganhou IDs e interfaces USB. Há teste explícito, temporário e opcional para uma interface Xbox 360/XInput reconhecida (ff/5d/01), com autorização Android, pulso de 250 ms e parada. O app tenta `claimInterface(false)` e informa quando o driver impede acesso; não desconecta o driver de entrada para tomar o controle. Outros protocolos não recebem pacotes por tentativa. A via da partida exige associação única ao controle Android; a via Android anterior continua disponível. O executor USB só é criado quando o teste precisa dele, tem esperas limitadas, substitui efeitos antigos e envia parada nas transições de estado. O relatório diferencia encaminhamentos Android/USB de confirmação física.

A identificação Linux do Nova Lite `3537:1040` sugere XInput, mas o receptor/modo/firmware da TV precisa confirmar seus descritores. Não há hardware físico conectado ao ambiente para comprovar rumble ou desempenho. O procedimento, fontes primárias e limitações estão em [light-codec-usb-rumble-0.31.5.md](light-codec-usb-rumble-0.31.5.md). FSR, correções de catálogo, toque e ICE anteriores foram preservados. Não foi acrescentado User-Agent e a configuração publicada do ambiente permanece preservada.

Passaram **365 testes JVM/Robolectric**, sem falhas, erros ou ignorados, mais **8 testes Python de assinatura**. Lint sem problemas e build release sem assinatura passaram; pacote, versionCode 36 e versão 0.31.5 foram conferidos. Os shaders mantiveram **42 checks FSR** e **5 checks do filtro padrão** aprovados no Mesa, e a tela de escolha de codec foi inspecionada. O APK de atualização continua sendo produzido pelo CI com o certificado fixo; a validação física do receptor/TV/celulares permanece pendente.

## Décima segunda mudança: as dez adições da análise de outros clientes

O usuário esclareceu que sua concordância com a análise incluía a lista de dez funcionalidades, além de pedir a retirada dos codecs experimentais e o teste USB. A 0.31.5 havia tratado somente desses últimos pedidos. Na **0.31.6-session-controls-audio / 37**, a lista restante ganha implementações funcionais e opções explícitas na mesma branch. User-Agent continua excluído.

1. **Bitrate máximo por jogo:** Automático ou tetos de 1–50 Mbps, independentes da resolução. Padrão e substituições separados por conta; o valor manual substitui o orçamento automático, inclusive o automático de 6 Mbps de Baseline/Compatibilidade. O SDP preserva perfis reais e limita mínimo/início ao teto. O serviço decide o recebido.
2. **Calibração e remapeamento por controle:** chave vendor/product/descritor, zonas mortas e sensibilidades esquerda/direita, curva, inversão Y, zona morta/troca de gatilhos e destinos de botões. Padrões preservam a resposta anterior. Atua sobre estados físicos completos antes da mistura, sem contaminar HUD/outros jogadores; Menu+View físico permanece reservado.
3. **Região:** login retém as regiões oferecidas à conta. Automático ou seleção do endpoint dessas regiões; região salva ausente volta ao padrão com diagnóstico. Catálogo/histórico mantêm o endpoint padrão. Não há previsão de ping do jogo ou endereço arbitrário digitado.
4. **IPv6 experimental:** adiciona IPv6 primeiro quando as duas famílias existem e concede até 750 ms; recusa/falha libera IPv4 sem espera desnecessária. Mantém as rotas IPv4, prioridades e endereços originais. Automático/família única não recebem espera. Cancelamento não adiciona candidatos a um peer em fechamento.
5. **Mouse/teclado:** emulação de controle com WASD/mira/gatilhos ou relatórios nativos de mouse relativo/VKey. O nativo exige suporte anunciado pelo jogo e input v8 confirmado pelo serviço; falha explícita se a versão não for confirmada. Compartilha sequência/thread do input, reserva o jogador 1 no coop, limita fila e preserva solturas mesmo após saturação. F7 abre o menu, F8 captura/libera; captura relativa usa Android 8+. Mouse emulado volta ao centro sem soltar teclas/gatilhos.
6. **Giroscópio:** sensor Android, sensibilidade e modos LT/LB/sempre, considerando rotação da tela. Não registra listener quando desligado/sem sensor; perda de amostras por 100 ms libera a câmera. Toque nativo mantém o gyro desligado; use controle/HUD. Não é suporte ao sensor interno de um gamepad.
7. **Idioma por jogo:** `settings.locale` separado da interface pt-BR. Inclui oito idiomas; o jogo pode ignorar a solicitação.
8. **Microfone e ganho:** microfone opcional, sempre iniciando mudo; anúncio chatStream, consentimento Android ao ativar e renegociação de áudio na mesma sessão, sem nova fila. Silencia e para captura em segundo plano; falha de chat não encerra a partida. Ganho 0–300%, separado do foco, com subida gradual/redução adaptativa conforme picos PCM. É reativo, sem limiter por amostra/lookahead; transientes podem distorcer. Formato sem medição fica limitado a 100%.
9. **Brilho e contraste:** neutros 0%/100%, aplicação no filtro existente ou passe RCAS final do FSR, sem novo passe/textura/fila temporal. Neutros preservam a saída anterior.
10. **Vibração do celular no toque:** opção separada, desativada, para rumble recebido do jogador zero quando não há controle físico associado e o aparelho é de toque. Não vibra o remoto da TV e não cria feedback que o jogo não envia. Mantém a via física/USB anterior.

As opções de sessão ficam em Configurações → Bitrate, região e idioma por jogo, com padrão e jogo selecionado/ativo. Os perfis, mouse/teclado, sensor e vibração ficam em Controles e toque. Áudio/microfone e ajustes de imagem também podem ser acessados no menu da partida. Liberação de entradas, cancelamento de chat, parada de sensor e mudo são integrados ao ciclo da Activity. O contexto de entrada é congelado antes da limpeza para o relatório.

Formatos e negociação foram conferidos no cliente oficial público, com implementações próprias. Não foram incorporados motores ou código AGPL dos projetos analisados. Detalhes, fontes primárias, limitações e acesso aos dez recursos estão em [session-controls-audio-0.31.6.md](session-controls-audio-0.31.6.md).

A primeira validação completa passou em **384 testes JVM/Robolectric**, mais **8 testes Python de assinatura**, lint sem problemas e build release sem assinatura. Após a revisão final, passaram **70 testes selecionados**, incluindo novos testes de interface, expiração do sensor, espera/recusa IPv6, cancelamento, orçamento, região, chat, entradas e rumble. A GPU por software aprovou **45 verificações FSR** e **9 do filtro padrão**, incluindo brilho/contraste. As telas de opções por jogo e áudio foram inspecionadas. A versão/pacote são conferidos novamente no empacotamento final; o CI executa a suíte completa e assina o APK com o certificado fixo. A aceitação física no Xbox/TV/celulares/periféricos continua pendente; nenhuma melhora de latência, voz ouvida ou rota IPv6 foi garantida.

A revisão da publicação de falhas IPv6 passou em **22 testes selecionados** de rede/interface, com lint e release aprovados. As otimizações de teclado desativado/coop passaram em **12 testes de input/multiplayer**. A pausa explícita das codificações do sender de voz foi conferida contra as APIs da biblioteca fixada; a validação final de áudio/interface/região passou em **14 testes selecionados**, com lint sem problemas e release aprovado. Pacote `com.xcloud.tclnative`, versão **0.31.6-session-controls-audio / 37**, minSdk 24 e target 34 foram conferidos. Captura/voz, roteamento e desempenho físicos continuam sujeitos aos testes no aparelho.


## Décima terceira mudança: categorias, rede automática e giroscópio com toque nativo

O usuário pediu configurações organizadas, uma escolha automática IPv4/IPv6 durante a partida e giroscópio opcional junto ao toque nativo Xbox. A **0.31.7-categories-network-gyro / 38** trata dos três pedidos, na mesma branch experimental.

1. **Categorias:** sete entradas no menu inicial — Vídeo e transmissão; Imagem e tela; Rede e sessão; Controles e toque; Áudio e microfone; Diagnósticos; Biblioteca e conta. Controles separa físico/coop, toque, mouse/teclado, giroscópio e vibração. Salvar/Voltar preserva o destino de navegação, inclusive nos painéis externos de opções por jogo, perfis, vibração, atalhos e relatório. Abrir menus não altera preferências.
2. **Automático IPv4/IPv6:** oferece ambas as famílias disponíveis imediatamente, configura checks nativos de rota ativa/reservas a 1.000 ms e verificações de conectividade fraca a 250 ms, mínimo 200 ms. A escolha segue o controlador ICE M137 e a participação do servidor: conectividade/recebimento, nomeação, custo/prioridades e RTT quando comparáveis. Não há garantia de menor ping/perda em todo instante; o SDK Java não permite substituir o seletor ou impor um par arbitrário. Não se removem rotas saudáveis, criam sessões paralelas ou habilitam candidatos novos sem sinalização. Preferir IPv4 foi acrescentado simetricamente ao IPv6, ambos com até 750 ms iniciais e alternativa preservada. O relatório mostra a família do par realmente selecionado, RTT, mudanças observadas uma vez por segundo e pares alternativos validados, sem endereços/SDP.
3. **Giroscópio nativo experimental:** nova preferência persistida, desligada por padrão, em Controles e toque → Giroscópio → Toque nativo Xbox. Exige sensor Android. Ativar com o modo geral desligado escolhe Sempre; LT/LB no toque nativo dependem de controle físico, pois o app não conhece os botões do HUD remoto. O sensor envia analógico direito pelo serializador existente junto aos contatos nativos, sem simular arrastos ou adivinhar áreas de botões. Ao zerar a mira sem controle físico, envia neutro antes de parar os relatórios auxiliares; contrapressão e atualização concorrente são respeitadas. O slot lógico de toque e os contatos permanecem. Menu/foco/segundo plano/100 ms sem sensor liberam a mira. O jogo pode alternar a interface ou não aceitar a mistura; a opção é explícita e reversível.

O procedimento, os parâmetros, as fontes WebRTC fixadas e os limites estão em [categories-network-native-gyro-0.31.7.md](categories-network-native-gyro-0.31.7.md). A comprovação física de melhor rota, gyro aceito pelo Xbox e desempenho no LG/TCL/Samsung continua pendente; não há conta/aparelhos conectados ao executor.

A execução completa local exerceu **401 testes JVM/Robolectric**; dois testes de navegação foram adaptados às novas categorias/ao despacho assíncrono de Voltar e a validação correspondente passou em **11 testes**, sem falhas. A revisão final da liberação do jogador zero passou em **25 testes de input/toque/gyro**, também sem falhas. Passaram **8 testes Python de assinatura**, lint sem problemas e build release sem assinatura. As telas de categorias/giroscópio foram inspecionadas; pacote `com.xcloud.tclnative`, versão 38/0.31.7, minSdk 24 e target 34 foram conferidos. O workflow existente reexecuta a suíte completa, valida os shaders e gera o APK com o certificado fixo.


## Décima quarta mudança: FSR com saída Automático, 1440p e 4K

O usuário testou Fortnite na TCL/Android 12, Ethernet, 1080p HQ/Menor atraso/FSR selecionado/RCAS 100%. O TXT registrou saída Android 3840×2160@60, mas Surface, janela e vídeo 1920×1080. O filtro indicou `sem ampliação; escala padrão`, portanto EASU/RCAS não estavam processando uma ampliação. O usuário pediu implementar as três escolhas propostas: Automático, 1440p e 4K experimental.

Na **0.31.8-fsr-output / 39**, Configurações → Imagem e tela → Saída do FSR oferece exatamente essas três escolhas, persistidas independentemente da ativação do filtro. Automático acompanha a janela nos celulares e usa até 1440p nas TVs, limitado à saída física disponível. 1440p e 4K pedem até 2560×1440/3840×2160, preservando a proporção, sem ampliar além do espaço físico disponível. Não há benchmark ou seleção dinâmica por FPS nesse Automático. O filtro continua desligado por padrão e inativo em Compatibilidade.

O app mantém decoder, WebRTC/EGL e shaders FSR existentes. Um adaptador de SurfaceHolder preserva o buffer explícito diante dos pedidos de tamanho feitos pelo SurfaceViewRenderer M137 nas mudanças de layout/resolução. A janela dos menus e a escala de coordenadas de toque/metadados Xbox continuam em pixels da interface. A saída local não altera resolução/bitrate recebidos. Na TCL 4K com interface 1080p, pode solicitar buffer 1440p ou 4K para o FSR trabalhar, mantendo a apresentação física Android em 4K.

FSR mantém prioridade sobre a escala nativa da Surface. Recusas de tamanho, falta de confirmação pela Surface e falhas persistentes de GPU/filtro retornam à escala padrão na mesma sessão, restaurando a escolha nativa e sem nova fila/codec. O diagnóstico separa solicitado, Surface real, janela, saída física e execução EASU/RCAS. As texturas do filtro continuam limitadas a 64 MiB estimados e saída de até 8,3 MP; esses limites não incluem todos os buffers/memória do driver. Encerrar a partida libera os recursos da sessão FSR, e mudanças do display atualizam o alvo local.

O teste de GPU por software acrescenta alocações/desenhos de 1080p → 1440p/4K às comparações existentes com o código AMD fixado. Detalhes, acesso, parâmetros, limites e roteiro físico em [fsr-output-0.31.8.md](fsr-output-0.31.8.md). Aceitação e desempenho na TCL ainda precisam ser conferidos no aparelho; decode/ping baixos não medem o custo de GPU do FSR.

Validação local concluída: **410 testes JVM/Robolectric**, sem falhas/erros/ignorados; **8 testes Python de assinatura**; lint sem problemas e release sem assinatura aprovados. A GPU Mesa aprovou **47 verificações FSR** (incluindo os alvos completos 1080p → 1440p/4K) e **9 do filtro padrão**. A tela das três escolhas foi inspecionada. Pacote `com.xcloud.tclnative`, versão **39 / 0.31.8-fsr-output**, minSdk 24 e target 34 conferidos. O workflow existente repete a suíte completa e gera o APK com o certificado fixo. O teste do holder usa callbacks reais do WebRTC e um holder Android simulado; a aceitação física na TCL continua pendente.


## Décima quinta mudança: queda de FPS do FSR confirmada na TCL

O usuário testou a 0.31.8 no menu do Fortnite: FSR 1440p/RCAS 50% ficou em cerca de 13 FPS e 4K/RCAS 50% em seis. O print mostrou o filtro ativo e Surface 4K, com perda zero, RTT perto de 38 ms e decode perto de 9,3 ms. Confirmou que desligar o FSR, mantendo o restante, devolve perto de 60 FPS. A ampliação foi ativada, mas o caminho gráfico atual ficou pesado demais nessa TV. A validação de pixels/recursos anterior não comprovava velocidade física; 1440p também não era adequado como ponto de partida Automático nesse aparelho.

Na **0.31.9-fsr-fluency-guard / 40**, Automático acompanha a janela e fica limitado a 1080p nas TVs; 1440p/4K continuam manuais. O filtro é protegido contra cadência sustentada muito baixa: após dois segundos de aquecimento, quatro segundos consecutivos abaixo de 24 desenhos FSR/s, com amostras válidas de vídeo chegando e perda até 1%, solicitam retorno à escala padrão naquela partida. O buffer normal e a escolha de escala nativa são restaurados na mesma sessão; preferências manuais permanecem salvas. Observações antigas, foco/segundo plano, pausas de rede, perdas relevantes e mudanças de contador/intervalos reiniciam a janela. O contador de desenhos é independente do HUD e não é um timer da GPU nem FPS do jogo/tela.

A proteção é conservadora, não prova automaticamente a causa e não substitui uma otimização comprovada no hardware. Não há promessa de FSR 1440p/4K a 60 FPS nessa TCL. O shader/EASU/RCAS original foi mantido; RCAS 50% não significa metade do custo. A recomendação atual para jogar nesse aparelho é FSR desligado e Upscaling nativo ligado. Evidência, critérios e limites em [fsr-fluency-guard-0.31.9.md](fsr-fluency-guard-0.31.9.md).

Validação local: **416 testes JVM/Robolectric**, sem falhas/erros/ignorados; **8 testes Python de assinatura**; lint sem problemas e release sem assinatura aprovados. Os testes simulam cadências de seis/13 desenhos/s, estabilidade em 30/60, rajadas curtas, perdas/pausas de rede, segundo plano, intervalos inválidos e reset de contadores. A integração simulada da Surface usa o proprietário/guard/retorno reais para restaurar o pedido de 4K a 1080p, preservando proporção, metadados de toque e preferência manual. Pacote `com.xcloud.tclnative`, versão **40 / 0.31.9-fsr-fluency-guard**, minSdk 24 e target 34 conferidos; tela atualizada inspecionada. Os shaders não mudaram. O CI existente reexecuta a suíte e as verificações de pixels, e assina o APK com o certificado fixo. A atuação/recuperação física da nova proteção ainda precisa do teste na TCL.
