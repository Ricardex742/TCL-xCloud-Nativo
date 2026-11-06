# H.264 High e HEVC experimentais na 0.31

## Escolha no aplicativo

Em **Configurações → Codec**, estão disponíveis:

- **H.264 — padrão:** preserva a política anterior de perfis H.264, preferindo Baseline no perfil leve e High quando anunciado no perfil de qualidade.
- **H.264 High — experimental:** prioriza High também em 720p, se houver hardware elegível. O anúncio é baseado no perfil High real do MediaCodec, incluindo decoders `c2.*` que não constam da lista antiga de fabricantes do helper WebRTC.
- **H.265/HEVC — experimental:** oferece HEVC Main de 8 bits antes de H.264, se houver hardware elegível e capacidade RTP correspondente no WebRTC.

Abrir a tela não altera a preferência. A seleção é persistida e vale para a próxima partida. Preferência ausente ou desconhecida usa H.264. Selecionar o preset Compatibilidade restaura H.264 e 720p; durante esse preset, uma preferência experimental salva não substitui a política leve.

H.264 continua disponível na oferta de um experimento. O servidor pode escolher H.264 mesmo quando o aparelho oferece HEVC ou High. Não há promessa de que o codec solicitado será o recebido ou de ganho de qualidade/desempenho em toda TV.

## Dependência nativa e transporte

A dependência foi alterada de `io.getstream:stream-webrtc-android:1.1.1` para **`io.github.webrtc-sdk:android:137.7151.05`**. A enumeração Java dos builds antigos examinados incluía H265, mas isso não estabelecia um caminho completo de recepção. O build M137 escolhido contém o parser de bitstream e o despacotizador RTP H.265 nas quatro ABIs: armeabi-v7a, arm64-v8a, x86 e x86_64.

O AAR foi conferido contra o checksum publicado no Maven Central e novamente contra o arquivo resolvido pelo Gradle:

```text
2343a6462b66130e0ac4ed62cbcf5592aac7d5cf8d55c13e3db18a118023d57b
```

Foram verificados `video_rtp_depacketizer_h265.cc`, `h265_sps_parser.cc` e a presença do field trial `WebRTC-ForcePlayoutDelay` nos binários de cada ABI. A inspeção estática demonstra a inclusão desses componentes, não uma sessão Xbox física bem-sucedida.

A migração adapta o construtor de `VideoCodecInfo` e verifica o resultado de `setCodecPreferences`. A inicialização da fábrica nativa e a descoberta de capacidades passam a acontecer fora da thread da interface. A versão Android mínima permanece API 24, e as proteções de assinatura são preservadas. Como a biblioteca nativa também muda para H.264, esse caminho precisa ser retestado fisicamente junto com os experimentos.

## Elegibilidade e decoder

Os codecs opcionais exigem decoder de hardware, perfil de 8 bits e suporte declarado ao tamanho/taxa solicitados. Em Android 10+, usam-se os indicadores de hardware/software do MediaCodec. Em Android 7–9, o nome de implementação é uma heurística conservadora; decoders de software conhecidos são excluídos. Uma capacidade ausente ou inválida não habilita o experimento.

O teste é feito antes da oferta e novamente para as dimensões efetivamente recebidas, inclusive em mudanças durante o fluxo. Um aparelho que anuncia 1920×1080 não é automaticamente aprovado para 2080×992. O suporte declarado a tamanho/taxa não é uma medição de latência ou FPS sustentados.

O decoder opcional usa MediaCodec com SurfaceTexture, o renderer e a recuperação existentes. A prioridade de tempo real é solicitada no modo Menor atraso; a opção de baixa latência continua dependente de Android 11+ e suporte declarado. Se o driver rejeitar esses ajustes, a recuperação tenta o mesmo decoder de hardware sem os ajustes. Não há fallback de HEVC por software nem entrega de pacotes HEVC a um decoder H.264.

High usa o descritor padrão do SDK após verificar as capacidades reais. A preferência usa apenas capacidades RTP anunciadas; não reescreve `profile-level-id` ou níveis para fingir suporte. RTX é mantido apenas quando genérico ou associado aos codecs oferecidos. O orçamento de banda da seção de vídeo e os pedidos de bitrate também contemplam HEVC, preservando perfis, áudio, ICE e feedback.

HEVC Main10, HDR10, VP9 e AV1 não são habilitados nesta entrega. O caminho continua SDR de 8 bits. A tela de HDR foi atualizada para refletir H.264 ou HEVC Main experimental sem prometer HDR.

## Retorno ao H.264

1. **Sem hardware/capacidade RTP opcional:** a oferta usa H.264; não é necessária outra sessão.
2. **Servidor escolhe H.264:** a resposta é aplicada normalmente e o diagnóstico registra o resultado.
3. **Falha do experimento antes do primeiro quadro:** rejeição de negociação/decoder ou falta de primeiro quadro após uma resposta experimental permite uma única reabertura de sessão em H.264. A conexão e a sessão Xbox anteriores são encerradas antes de criar as novas; pode haver nova fila. A preferência salva do usuário não é alterada.
4. **Falha do decoder depois do primeiro quadro:** o jogador escolhe entre reabrir em H.264 e voltar ao catálogo. A mensagem informa que a sessão será encerrada e que progresso ainda não salvo pode ser perdido. Não há reabertura automática de uma partida já exibida.

A tentativa em H.264 não habilita outra tentativa automática. Falhas de login, provisionamento ou ICE não são classificadas como falhas de codec para disparar esse retorno. Cancelamento, troca de geração e saída da Activity impedem o relançamento automático.

## Diagnóstico e validação física

O relatório distingue preferência solicitada, hardware declarado, formatos oferecidos e preferência da resposta SDP. O codec recebido é obtido do registro `codecId` das estatísticas RTP de entrada. Quando informado pelo WebRTC, o perfil RTP aparece como High/Baseline/Main ou HEVC Main. Isso não é uma inspeção independente de cada SPS do bitstream.

Para comparar, manter jogo, cena, resolução, rede, proporção e filtros iguais. Conferir codec e resolução recebidos, bitrate, decode, buffer, quadros descartados, pausas, aquecimento e estabilidade durante uma partida longa. A confirmação do serviço e do desempenho em aparelhos reais continua pendente; não há TV/celular conectado nem sessão autenticada de gameplay neste ambiente.

Referências: [artefato Maven](https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/137.7151.05/), [projeto WebRTC SDK](https://github.com/webrtc-sdk/android), [análise anterior](codec-and-project-review-0.31.md).

## Validação desta entrega

Em 6/11/2026, a suíte completa passou em **308 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, além dos **8 testes Python de proteção da assinatura**. `:app:lintDebug` terminou com `No issues found` e `:app:assembleRelease` concluiu com sucesso. A captura do seletor de codecs foi inspecionada.

Os testes adicionais cobrem preferência persistida, opt-in, precedência do modo Compatibilidade, ausência de codec opcional, filtros de hardware/perfil/tamanho/taxa, rejeição de software/Main10, associação de RTX, preservação de SDP, dimensões reais do decoder, retorno limitado ao H.264, escolha explícita após o primeiro quadro e identificação do codec/perfil nas estatísticas.

O pacote local é release sem assinatura. O APK de atualização depende do workflow existente e do segredo de assinatura fixa no GitHub; não foi criada uma chave substituta nem feita publicação do ambiente.
