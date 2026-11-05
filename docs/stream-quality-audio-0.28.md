# Perfil de vídeo e reprodução de áudio — 0.28

## Resolução com toque nativo

A seleção de qualidade usa os mesmos perfis de protocolo já disponíveis no aplicativo: `android` para 720p, `windows` para 1080p e `tizen` para 1080p HQ. Essa associação também está presente em [Better xCloud, `getOsNameFromResolution` e `handlePlay`](https://github.com/redphx/better-xcloud/tree/f8397043f6d2148d2345d508902a38c69cf1ee20/src/utils).

Até a 0.27, uma sessão de toque nativo substituía o seletor por `android`, independentemente da resolução escolhida. O SDP e as dimensões anunciadas ainda podiam indicar 1080p, mas o provisionamento enviava o perfil de outra qualidade. Agora `settings.osName` e o campo correspondente de `X-MS-Device-Info` seguem a resolução selecionada em todos os modos de input. A descrição de hardware usada para o toque, o registro do slot virtual 0, os contatos e seus metadados são preservados.

O diagnóstico registra o perfil e as dimensões solicitadas, além dos quadros efetivamente recebidos. Não se altera a estatística de resolução para apresentar um valor artificial. Essa correção garante a coerência da solicitação local, não uma resolução fixa do servidor: jogo, proporção, disponibilidade do serviço e adaptação da transmissão ainda influenciam a saída.

Uma imagem com cerca de 1384×664 tem aproximadamente o número de pixels de 1280×720, distribuídos em uma proporção mais larga. O tamanho da Surface mostra a ampliação local e não muda a resolução do vídeo recebido. Os testes de provisionamento verificam os três perfis em sessões de toque nativo e a troca posterior para controle.

## Áudio de jogo

Foi inspecionada a dependência usada no aplicativo, `io.getstream:stream-webrtc-android:1.1.1`. Sem um módulo de áudio explícito, `PeerConnectionFactory.Builder` cria um `JavaAudioDeviceModule` padrão. O adaptador de `WebRtcAudioTrack` utiliza `USAGE_VOICE_COMMUNICATION` e `CONTENT_TYPE_SPEECH` quando não recebe atributos. Esse caminho corresponde ao volume de chamada, não ao volume de mídia utilizado por jogos.

O aplicativo passa a fornecer um módulo com `USAGE_GAME`, `CONTENT_TYPE_MOVIE` e saída estéreo solicitada. Os controles de volume da Activity apontam para `STREAM_MUSIC`. Não se fixa taxa de amostragem diferente da escolhida para o aparelho. O módulo tem sua referência local liberada após a fábrica adquirir a referência nativa.

`GameAudioSession` solicita foco de mídia, usa ganho local unitário após concessão, reduz/interrompe o áudio quando o sistema solicita e restaura o ganho ao recuperar foco. Se o foco for atrasado ou negado, o áudio não é liberado como se a concessão tivesse ocorrido. Ao encerrar, o foco é abandonado; callbacks tardios não reativam faixas já descartadas. Android 7 usa a API legada de foco com o canal de mídia. Não se muda o modo global de chamada, não se força alto-falante ou Bluetooth e não se altera o nível de volume escolhido no sistema.

O diagnóstico passa a mostrar o nível/mudo de mídia, estado do foco, ganho local e início/parada/erro de reprodução informado pelo módulo WebRTC. Esses estados não medem o volume acústico nem comprovam que o som remoto contém sinal audível. O resumo local mantém seu limite de armazenamento, com espaço de até 1024 caracteres para os detalhes de input, perfil e áudio.

## Validação

Os testes de áudio exercitam o adaptador real de atributos do WebRTC, a associação ao canal de mídia, concessão/perda/recuperação de foco, foco negado/atrasado, callbacks após encerramento e a API de Android 7. Também verificam que níveis globais de volume e modo de chamada não são alterados. O teste da Activity confirma o destino das teclas de volume. Não se cria um peer JNI nesses testes.

A melhora de resolução, preservação do toque e audibilidade precisam ser comparadas em uma nova partida no aparelho. Para uma comparação de desempenho, nitidez 0% e saturação 100% ajudam a separar o custo dos filtros da mudança de perfil. Decodificação acima do orçamento de 16,7 ms por quadro pode limitar uma apresentação de 60 FPS mesmo sem perdas de rede. Não há garantia de 1080p a 60 FPS em todo aparelho ou sessão.
