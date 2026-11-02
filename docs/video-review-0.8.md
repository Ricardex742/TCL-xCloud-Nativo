# Análise da gravação e ajustes 0.8.0

A gravação enviada tem cerca de 29 segundos, câmera/arquivo com aproximadamente 25 FPS e resolução armazenada 720×1280 com orientação de exibição em paisagem. Ela mostra a TV, sem o instante de acionamento dos botões. Por isso, não permite medir botão-até-fóton, a cadência real de 60 Hz pelo movimento gravado, nem avaliar nitidez/cor do sinal original sem interferência da câmera e da compressão do arquivo.

O painel do aplicativo, visível nas amostras do vídeo, fornece evidência melhor para o caminho de streaming:

| Medida visível | Valores aproximados observados | Interpretação |
| --- | --- | --- |
| Vídeo recebido | 1920×1080, cerca de 60 FPS, H.264 | A resolução e a cadência solicitadas estão chegando. |
| Bitrate recebido | aproximadamente 8–17 Mbps nas amostras | O serviço usa VBR; o pedido de 20–30 Mbps não é um piso garantido. |
| RTT ICE | 38–45 ms | Não é latência total do comando até a imagem. |
| Jitter RTP / perda | 1–3 ms / 0% nas amostras legíveis | Não há evidência de perda ou jitter elevado nesse trecho. |
| Buffer de vídeo | aproximadamente 50–60 ms | Há margem para experimentar uma política de playout menor. |
| Decode | aproximadamente 25–27 ms/quadro | O nome é `OMX.amlogic.avc.decoder.awesome2`; o WebRTC o informa como eficiente. Pipeline de hardware pode sobrepor quadros: 26 ms não prova incapacidade de manter 60 FPS. |
| Controle | fila 0 B; atraso local geralmente poucos ms, chegando perto de 9 ms em uma amostra | A fila do app não explica sozinha o atraso percebido. |
| Geometria | Surface/janela 1920×1080, saída Android 3840×2160@60 Hz | O caminho de upscale para a saída 4K já está presente. AiPQ proprietário não é confirmado por esse dado. |

## Alterações implementadas

- Câmera e gatilhos passam a ignorar o `flat` do driver: zona morta adicional do aplicativo em 0%, sem suavização temporal ou curva extra. A normalização da faixa física e os limites do protocolo de 16 bits continuam necessários. O analógico esquerdo mantém a zona morta informada pelo driver. A zona morta do firmware/controle e do Fortnite é independente.
- Relatórios periódicos do controle sobem para até 120 Hz, com estados recentes de câmera/gatilhos mesmo quando há transições de botões na fila. Isso reduz a espera de agendamento local; não aumenta a frequência física do Bluetooth/USB nem remove o RTT.
- O perfil padrão de menor atraso solicita `WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:40/`, opção presente no binário e cujo parser foi conferido no receptor RTP do WebRTC. A janela não é uma promessa de latência total. O modo adaptativo pode ser restaurado no catálogo para a próxima partida.
- Android 11+ usa `MediaFormat.KEY_LOW_LATENCY` apenas se o decoder selecionado declarar `FEATURE_LowLatency`. A integração é específica da dependência WebRTC 1.1.1 fixada no projeto, sem reflexão ou parâmetros privados de fabricante. O decoder padrão é preservado como fallback. O painel completo indica suporte/solicitação; uma redução efetiva só pode ser confirmada medindo novamente no aparelho.
- Em Android 11+, é solicitado pós-processamento mínimo da janela e cadência de Surface de 60 Hz. São solicitações ao Android/TV: o sistema pode ignorá-las. O caminho nativo de escalonamento é mantido; nenhum filtro de CPU, sharpening pesado ou suposto AiPQ é adicionado.
- As estatísticas começam compactas para obstruir menos a imagem. INFO/F10 alterna compacto/completo/oculto. O modo completo mostra os comandos enviados, zona morta do app e status das opções de baixa latência.

## Validação prática que permanece necessária

Comparar os modos menor atraso/adaptativo no mesmo jogo, rede e controle. Registrar buffer, decode, RTT, jitter e descartes antes/depois; observar também se a redução de buffer aumenta descartes. Para medir input lag real, é necessário filmar o acionamento e a tela juntos, idealmente com câmera de alta velocidade. Recursos e menus de imagem/MEMC/AiPQ da TCL não são controlados por uma API pública deste aplicativo.

Referência do parser de playout: https://github.com/webrtc-sdk/webrtc/blob/m125_release/video/rtp_video_stream_receiver2.cc
