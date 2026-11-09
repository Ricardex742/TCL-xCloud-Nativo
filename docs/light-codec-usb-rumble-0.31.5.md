# H.264 leve e teste de vibração USB — 0.31.5

## Solicitação e escopo

O usuário descartou a funcionalidade de User-Agent discutida na análise de Better xCloud/XStreaming. Também informou que H.264 High e HEVC não funcionaram nos testes na TCL e no Samsung, pediu a retirada dos experimentos e uma opção mais simples para celulares fracos, além de experimentar vibração pelo dongle USB do GameSir T4 Nova Lite.

As mudanças ficam na branch `perf/decoder-display-latency-0.31`. A versão é `0.31.5-light-codec-usb`, versionCode **36**. Os demais itens da análise continuam como possibilidades futuras; esta entrega trata de codec e vibração. Não foi acrescentada uma opção de User-Agent. Os seletores de perfil de vídeo já existentes no protocolo Xbox continuam necessários à negociação de resolução.

## Codec

- As escolhas H.264 High experimental e H.265/HEVC experimental foram retiradas. Preferências antigas com esses nomes migram para H.264 padrão.
- H.264 padrão preserva os perfis H.264 reais anunciados pelo decoder e o caminho nativo de recuperação.
- **H.264 leve — Baseline (720p)** oferece somente perfis Baseline/constrained Baseline com `profile-level-id` real começando por `42`. Não renomeia Main/High nem inventa capacidades do aparelho.
- O modo leve solicita o perfil Android/720p e um orçamento de 1–6 Mbps, com início em 2,5 Mbps. A apresentação continua solicitada em até 60 FPS. Esses pedidos não garantem bitrate, resolução ou FPS recebidos.
- O preset Compatibilidade também restringe a negociação a Baseline e mantém sua apresentação de até 30 FPS e FSR desligado.
- Oferta local e resposta remota são verificadas e registradas. Um fluxo incompatível gera um erro explícito, sem reabertura automática em outro codec ou nova fila escondida. A recuperação ICE anterior continua independente.
- A fábrica experimental e seus decoders exclusivos foram removidos. A versão WebRTC M137 continua fixada para preservar o pipeline que já estava em uso.

Baseline é um perfil do H.264, não um novo codec. Os diagnósticos do LG LM-K200 já mostravam Baseline. Nesse caso, o teste compara principalmente os pedidos de resolução/bitrate e a restrição de perfil; não há promessa de redução adicional do tempo de decodificação. FSR continua uma escolha separada com custo de GPU.

## Vibração pelo dongle

O caminho Android de vibração do controle continua disponível. Foi acrescentado um teste USB opcional em **Configurações → Controles e toque → Testar vibração / verificar suporte**.

O diagnóstico mostra os IDs vendor/product do controle, se o Android expõe vibrador, os dispositivos USB e os descritores de suas interfaces. Um botão de teste USB aparece somente para um receptor com uma única interface Xbox 360/XInput reconhecida: classe `ff`, subclasse `5d`, protocolo `01`, alternate setting zero, entrada interrupt e uma saída interrupt com pelo menos oito bytes.

Ao escolher um receptor, o app solicita a autorização USB normal do Android. Tenta obter a interface com `claimInterface(..., false)`. Se o driver já a possui, o teste informa a recusa e fecha a conexão. Não força a remoção do driver que lê os botões, não altera firmware/configuração USB, não lê os eventos USB e não solicita root.

Se a interface puder ser aberta, envia um pulso de 250 ms a 40% seguido de parada. O pacote de saída Xbox 360 usa dois motores independentes. A implementação aceita somente esse protocolo; interfaces HID genéricas, Xbox One/GIP e Xbox 360 wireless usam outros formatos e não recebem comandos por tentativa.

A via USB fica habilitada para a partida somente quando há associação única por vendor/product ao controle Android conectado. A escolha é temporária, não é persistida nem ativada automaticamente ao iniciar o app. Uma transferência aceita significa que o pacote foi enviado; o usuário ainda precisa confirmar que houve vibração física. Os motores de gatilho não são emulados nesse protocolo.

Os comandos da partida usam um executor próprio, com espera USB limitada e substituição de efeitos pendentes pelo comando mais recente. O app envia parada ao cancelar o efeito, trocar de estado/foco, ir para segundo plano, desativar o teste ou fechar a conexão. Duração e repetições são limitadas. O relatório da partida inclui encaminhamentos por Android/USB e o estado do teste; não apresenta esses contadores como confirmação física.

O driver Linux atual identifica **GameSir Nova Lite `3537:1040`** como Xbox 360. O receptor do usuário ainda precisa confirmar seus próprios descritores no Android: modelos, modos e firmware podem anunciar outra identidade. Em muitas TVs o driver detém a interface e o Android não expõe force feedback; nesses casos este teste pode registrar a limitação sem conseguir ativar os motores.

## Fontes consultadas

- [Android USB Host](https://developer.android.com/develop/connectivity/usb/host): enumeração, autorização e interfaces.
- [Android UsbDeviceConnection.claimInterface](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#claimInterface(android.hardware.usb.UsbInterface,%20boolean)): `force=true` desconecta o driver do kernel; o app usa `false`.
- [Android UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest): transferências interrupt assíncronas; espera limitada disponível a partir da API 26.
- [Linux xpad](https://github.com/torvalds/linux/blob/master/drivers/input/joystick/xpad.c): identificação Nova Lite/XInput e formato do comando dos motores. Consultado para interoperabilidade; não foi importado código desse driver para o app.

## Como testar

1. No celular fraco, selecione H.264 leve e Menor atraso. Compare o mesmo jogo/local de rede com H.264 padrão em 720p. Confira codec recebido, decode, perdas, pausas e buffer no relatório.
2. Na TCL P755, conecte o GameSir pelo dongle e pressione um botão para o Android identificá-lo. Abra o diagnóstico de vibração e copie os IDs/interfaces se o botão USB não aparecer.
3. Se o teste USB aparecer, selecione o receptor e autorize no Android. Observe a mensagem final e confirme o pulso físico. Uma mensagem de interface ocupada significa que o driver impediu o acesso independente.
4. Se houver pulso e associação ao controle, entre em um jogo com rumble. Verifique também se menus, segundo plano e desconexão param os motores e se os botões continuam funcionando.
5. Para encerrar, use Desativar teste USB ou feche o app. Reabra para confirmar que o teste não foi ativado automaticamente.

## Validação

A validação local cobre migração de preferências, UI de seleção, perfil real oferecido/respondido, filtros da fábrica de codecs, orçamento SDP, protocolo USB, recusa de interface ocupada, parada e liberação de recursos. O resultado final dos testes/build fica registrado no contexto consolidado. A vibração física e o desempenho em aparelhos reais dependem do teste do usuário; o ambiente não possui a TV, o receptor ou os celulares conectados.

Passaram **365 testes JVM/Robolectric**, sem falhas, erros ou ignorados, mais **8 testes Python de assinatura**. Lint sem problemas e build release sem assinatura passaram; pacote, versionCode 36 e versão 0.31.5 foram conferidos. Os shaders mantiveram **42 checks FSR** e **5 checks do filtro padrão** aprovados no Mesa, e a tela de escolha de codec foi inspecionada. O APK de atualização continua sendo produzido pelo CI com o certificado fixo; a validação física do receptor/TV/celulares permanece pendente.
