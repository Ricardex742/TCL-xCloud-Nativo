# Testes no celular e revisão da negociação na 0.31.1

## Evidência recebida

O usuário enviou `testes.txt` com três sessões de Fortnite no Samsung SM-A155M, Android 16/mt6789, app 0.31.0-decoder-display-latency, perfil 1080p/windows, Menor atraso e toque nativo Xbox. Proporção Original, nitidez 0% e saturação 100%. O arquivo foi tratado como evidência; seu conteúdo integral não foi incluído no repositório.

O usuário confirmou que **o atraso do toque melhorou** em relação à versão anterior. É uma percepção relatada, sem medição input-to-photon. Todos os experimentos receberam o mesmo codec, portanto a melhora não pode ser atribuída a HEVC ou High. Esses testes não medem o GameSir/dongle nem a TCL P755.

| Escolha salva | Tempo conectado / amostras | Hardware opcional declarado | Resposta SDP | Codec RTP / decoder realmente recebido |
| --- | --- | --- | --- | --- |
| H.264 High | 2min04s / 124 | c2.mtk.avc.decoder, 1920×1080@60 | Baseline 42e02a | Baseline 42e01f / c2.mtk.avc.decoder |
| HEVC | 1min03s / 63 | c2.mtk.hevc.decoder, 1920×1080@60 | Baseline 42e02a | Baseline 42e01f / c2.mtk.avc.decoder |
| H.264 padrão | 14s / 15 | não solicitado | Baseline 42e02a | Baseline 42e01f / c2.mtk.avc.decoder |

Todos receberam 2080×992. A elegibilidade nominal de HEVC não comprova suporte às dimensões reais; essa guarda só será exercitada se uma sessão negociar HEVC. A diferença de nível entre resposta e estatística RTP não prova corrupção; a assimetria de nível pode ser negociada e o perfil RTP não é uma inspeção independente do SPS.

| Escolha salva (mesmo Baseline recebido) | RTT médio | Decode médio | Buffer observado médio | Alvo WebRTC médio | FPS decodificados médios |
| --- | ---: | ---: | ---: | ---: | ---: |
| H.264 High | 44,41 ms | 25,69 ms | 3,11 ms | 28,35 ms | 50,08 |
| HEVC | 44,94 ms | 25,27 ms | 3,93 ms | 35,78 ms | 50,28 |
| H.264 padrão | 45,02 ms | 28,72 ms | 8,65 ms | 38,13 ms | 30,95 |

As médias incluem períodos sem progresso: quatro segundos nas duas sessões maiores e três na sessão de 14 segundos. A sessão curta não é uma base válida para comparar codecs; as cenas/duração também não são controladas. Os últimos 20 registros de cada sessão maior mostram aproximadamente 58–62 quadros decodificados por segundo, majoritariamente 60. Decode de cerca de 25 ms não prova sozinho incapacidade de sustentar 60 FPS, pois o pipeline pode sobrepor quadros.

Não houve esperas de input reportadas. O agendador de envio teve médias de 2,28/1,50/0,81 ms, respectivamente; isso não mede o caminho completo do toque ou do controle até a tela. `Evento de joystick: n/d` é esperado em testes de toque nativo.

A saída Android permaneceu 1080×2340@90 Hz nas três sessões, embora a Surface sinalizasse conteúdo a 60 FPS. Essa sinalização não confirma uma troca física para 60 Hz. O Android também não declarou suporte a pós-processamento mínimo. Não foi aplicado comando privado de sistema nem forçada uma frequência não anunciada.

## Causa demonstrada e limites

A implementação anterior oferecia o perfil/codec experimental primeiro e também Baseline na mesma oferta. A resposta e o RTP mostram que o serviço usou Baseline em todas as escolhas. O relatório anterior registrava a intenção de preferência, mas não enumerava o SDP local gerado. Não é possível reconstruir desse arquivo se o SDK chegou a preparar e enviar o payload experimental.

A preferência condicional HEVC no cliente público Xbox não demonstra aceitação universal pelo serviço. A ordenação pública H.264 examinada favorece Main (`4d`) em alguns perfis de dispositivo e Baseline (`42`) em outros; Main não é High (`64`). Não foi copiado o hack que reescreve Baseline como Main, nem anunciado um perfil inventado para obter aceitação. High/HEVC continuam experimentais.

## Alterações na 0.31.1

1. Tentativa inicial exclusiva no codec/perfil escolhido, somente quando as capacidades locais já o suportam. H.264 padrão e Compatibilidade conservam a política anterior. RTX somente genérico ou associado aos payloads da oferta.
2. Verificação do SDP efetivamente preparado, antes de aplicar/enviar. Uma oferta experimental que ainda contenha Baseline ou não contenha vídeo válido falha com motivo explícito.
3. Registro separado de oferta preparada, POST confirmado após sucesso HTTP, formatos da resposta e aplicação no WebRTC. Uma resposta incompatível não é aplicada como sucesso do experimento.
4. HEVC informa profile-id=1, tier-flag=0 e level-id mapeado do nível Main de hardware declarado, em vez de depender do nível implícito. Não habilita Main10, HDR ou decoder por software.
5. Retorno automático limitado a uma nova sessão H.264 antes do primeiro quadro. A sessão/PeerConnection anteriores são encerradas primeiro. O relatório final guarda oferta/resposta/motivo sanitizados; a preferência salva permanece a escolhida. Depois do primeiro quadro, a reabertura exige a escolha do jogador já existente no app.
6. Diagnóstico de tela separa conteúdo sinalizado a 60 FPS, modo de janela pedido, modo físico, taxa informada ao app e modos disponíveis. O histórico de pedido do joystick sobrevive à volta ao menu e é limpo na próxima sessão.
7. Identificação distinta: versionCode 32, **0.31.1-codec-negotiation**. Pacote e certificado de atualização preservados.

## Próximo teste físico

Comparar uma sessão estável de alguns minutos em H.264 padrão com tentativas High e HEVC, no mesmo jogo/cena/rede/resolução e com filtros desativados. Copiar o relatório completo de cada sessão. Conferir primeiro oferta, POST, resposta e motivo de eventual retorno; depois conferir a linha **Recebido**, que deve mostrar o codec efetivamente usado. Se houver retorno H.264, não usar os números dessa sessão como benchmark de HEVC/High.

O teste também esclarecerá se o Android anuncia 60/120 Hz no aparelho e se o modo pedido é efetivado. A medição na TCL P755 deve ser feita separadamente com o GameSir/dongle, para não misturar toque do celular com controle físico da TV.

## Validação

Em 6/11/2026, a suíte completa passou em **330 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python de proteção da assinatura**. `:app:lintDebug` terminou com `No issues found` e `:app:assembleRelease` concluiu com sucesso. A captura atualizada do seletor de codecs foi inspecionada.

Os novos casos verificam exclusividade/RTX, SDP misto ainda gerado pelo SDK, resposta Baseline após experimento, vídeo inativo/rejeitado, Main10/high tier, mapeamento de níveis HEVC, confirmação do POST somente após sucesso HTTP, erros de decoder seguidos de timeout, limite de retorno antes do primeiro quadro, ausência de dados sensíveis e histórico de tela/joystick.

O pacote local é release sem assinatura; o workflow da mesma branch gera o APK de atualização com o certificado fixo existente. Não foi criada chave substituta nem publicada uma alteração do ambiente. A aceitação de HEVC/High pelo Xbox e a decodificação experimental real em celular/TV continuam dependentes de teste físico autenticado.
