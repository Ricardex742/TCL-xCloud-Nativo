# Contexto completo das modificações desta branch

## Estado e objetivo

O trabalho está exclusivamente na branch **`perf/decoder-display-latency-0.31`**, criada a partir de `ci/apk-android10-3cc2595`, commit **`777a7e3487c230fc515a8b33665a08815bd1eeb0`**. A branch original foi preservada. Após o usuário perguntar pela branch, ela chegou a ser enviada ao GitHub e foi removida de lá a pedido dele. A cópia local foi mantida. Na solicitação mais recente, o usuário autorizou novamente a publicação desta branch no GitHub, incluindo a correção de responsividade e os codecs experimentais. A configuração publicada do ambiente não é alterada.

Estado atual do código: **0.31.2-ice-recovery, versionCode 33**. As seções abaixo registram as entregas em ordem; a sétima substitui a política inicial de oferecer H.264 junto com os experimentos, e a oitava trata a coleta ICE vazia no início da conexão.

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
