# Configurações, rede automática e giroscópio no toque nativo — 0.31.7

Versão `0.31.7-categories-network-gyro`, versionCode **38**, exclusivamente em `perf/decoder-display-latency-0.31`.

## Configurações por categoria

O menu inicial passa a apresentar sete categorias. Abrir uma categoria não altera preferências; selecionar uma opção salva e volta à sua categoria. Voltar/B retorna ao nível anterior, inclusive a partir de perfis de controle, opções por jogo, teste de vibração, atalhos e último relatório.

| Categoria | Conteúdo |
| --- | --- |
| Vídeo e transmissão | Modo de vídeo, codec e resolução |
| Imagem e tela | Proporção, upscaling, FSR, nitidez, saturação, brilho, contraste e consulta HDR |
| Rede e sessão | Automático/IPv4/IPv6 e opções globais ou por jogo de bitrate, região e idioma |
| Controles e toque | Controle físico/coop/calibração; modo de toque; mouse/teclado; giroscópio; vibração |
| Áudio e microfone | Ganho e disponibilidade do microfone, que continua iniciando mudo |
| Diagnósticos | Exibição automática pós-partida e último relatório |
| Biblioteca e conta | Atalhos, assinatura, atualizar catálogo e trocar conta |

As escolhas existentes são preservadas. Áudio e ajustes de imagem continuam disponíveis durante a partida, com retorno ao menu da partida.

## Automático IPv4/IPv6 durante a partida

Em **Rede e sessão → IPv4 / IPv6**, há três escolhas:

- **Automático — IPv4/IPv6 durante a partida**: padrão; oferece os candidatos disponíveis sem espera por família. Configura verificações contínuas de conectividade/RTT do WebRTC para o par ativo e pares de reserva existentes.
- **Preferir IPv4**: até 750 ms iniciais para IPv4 quando o servidor oferece ambas as famílias; depois adiciona também IPv6.
- **Preferir IPv6 — experimental**: o mesmo procedimento, começando por IPv6. Recusa/falha inicial libera a alternativa imediatamente.

A preferência vale na próxima partida. Nenhuma dessas escolhas exclui permanentemente a outra família, altera o SDP dos candidatos, solicita outra sessão Xbox ou inicia nova fila.

### O que foi implementado e o que o transporte permite

O app usa o controlador ICE nativo do **WebRTC M137, branch-heads/7151**, que considera escrita/recebimento/conectividade, nomeação pelo par remoto, custo de rede, prioridades e RTT. Quando os demais critérios empatam, seu algoritmo exige uma melhora de RTT de pelo menos 10 ms para trocar. O SDK Java usado pelo app não oferece uma chamada para selecionar arbitrariamente um par ICE ou substituir esse algoritmo.

No Automático, `RTCConfiguration` usa todas as interfaces permitidas, mantém IPv6 no Wi-Fi e até cinco redes IPv6, com:

| Parâmetro | Valor |
| --- | ---: |
| `iceBackupCandidatePairPingInterval` | 1.000 ms |
| `stableWritableConnectionPingIntervalMs` | 1.000 ms |
| `iceCheckIntervalStrongConnectivityMs` | 1.000 ms |
| `iceCheckIntervalWeakConnectivityMs` | 250 ms |
| `iceCheckMinInterval` | 200 ms |

Os checks são STUN pequenos, sem um segundo fluxo de vídeo ou sondagem de banda. Temporizadores nativos de recebimento/inatividade não são reduzidos. A coleta continua no modo existente `GATHER_ONCE`: o protocolo atual envia os candidatos iniciais ao Xbox, portanto não se habilita coleta contínua de candidatos novos sem sinalizá-los ao serviço.

**Isso melhora o acompanhamento das rotas e permite a seleção/falha automática suportada pelo transporte; não é um comparador de duas partidas simultâneas nem uma garantia de sempre escolher o menor ping ou a menor perda de vídeo.** O servidor também participa da seleção. Rotas podem ser podadas pelo ICE; a disponibilidade de uma alternativa depende do provedor, da rede e dos candidatos do Xbox. Se o serviço só oferecer IPv4, o app não cria uma rota IPv6. Não são removidos candidatos saudáveis para forçar uma troca que interromperia a partida.

O diagnóstico pós-partida registra a família local/remota **do par efetivamente selecionado**, RTT, mudanças observadas e quantidade de pares alternativos validados. Usa `transport.selectedCandidatePairId` dos stats existentes uma vez por segundo; um backup nomeado ou de RTT menor não é apresentado como rota em uso. Endereços IP e SDP não entram no texto. Trocas mais rápidas que a amostragem podem não ser contadas. Não há estimativa inventada de perdas RTP para cada família.

Fontes primárias consultadas, na versão correspondente à biblioteca:

- [BasicIceController](https://webrtc.googlesource.com/src/+/refs/branch-heads/7151/p2p/base/basic_ice_controller.cc)
- [IceConfig](https://webrtc.googlesource.com/src/+/refs/branch-heads/7151/p2p/base/ice_transport_internal.h)
- [RTCConfiguration Java](https://webrtc.googlesource.com/src/+/refs/branch-heads/7151/sdk/android/api/org/webrtc/PeerConnection.java)

## Giroscópio com toque nativo Xbox

Em **Controles e toque → Giroscópio → Toque nativo Xbox**, ative **Ativado — experimental**. Requer sensor `TYPE_GYROSCOPE` informado pelo Android e fica desligado por padrão, inclusive em instalações atualizadas. Não pede uma nova permissão Android.

Se o modo geral estiver Desativado, ativar o teste escolhe **Sempre durante a partida**. Modos LT/LB e sensibilidade continuam configuráveis. **No toque nativo, LT/LB dependem de um controle físico**: os botões desenhados pelo próprio Xbox não são expostos ao app como estado de gamepad, portanto não se deduzem pressionamentos pela posição dos dedos. Para testar usando só o celular, use Sempre.

O sensor emula o analógico direito do jogador zero, considerando a rotação da tela. Os contatos continuam no protocolo nativo e no espaço de coordenadas informado pelo servidor. Gamepad e contatos compartilham o serializador/thread/sequência existentes. Não são injetados arrastos em uma região presumida da câmera, que poderia coincidir com um botão.

Quando não há controle físico associado, o envio auxiliar de gamepad para ao retornar a zero, **depois** de um pacote neutro ser aceito pelo canal. Esse caminho preserva o registro lógico do jogador de toque e os contatos pendentes. Contrapressão mantém a soltura pendente; uma nova amostra concorrente não é apagada pela aposentadoria do estado anterior. Sem o experimento, o toque puro mantém seu caminho anterior, sem relatórios de gamepad ociosos.

Menu, perda de foco/segundo plano e ausência de amostras por 100 ms liberam a mira. O sensor não permanece registrado quando desligado/sem sensor. O jogo/serviço pode não aceitar a mistura ou alternar sua interface ao receber o analógico: a compatibilidade real precisa ser testada e a opção pode ser desligada sem afetar o toque nativo.

## Validação e teste no aparelho

A validação automatizada cobre navegação e persistência sem alterar preferências ao abrir menus, opções do SDK, preferência IPv4/IPv6 e retorno limitado, rota realmente selecionada sem endereços, opt-in/sensor/expiração, serialização conjunta de contatos e mira, envio neutro sob contrapressão e atualização concorrente. O CI existente verifica também a assinatura fixa e os shaders.

Roteiro físico:

1. Abra as categorias, altere uma opção e confira Voltar/B e a preferência salva.
2. Use Automático em uma partida e copie o diagnóstico ao sair. Confirme a família em uso e se há pares alternativos validados. Compare com Preferir IPv4/IPv6 na mesma rede; ausência de mudança não demonstra defeito quando só há uma rota viável.
3. Num celular com sensor, ative o teste nativo + Sempre e inicie um jogo compatível com toque nativo. Teste mira por movimento com um e vários dedos, botões, menu, imobilidade e segundo plano. Compare com a opção desligada.

Sem TV, celular com sensor e conta Xbox conectados neste executor, não foi comprovada migração física entre famílias, aceitação do gyro no jogo ou melhora de desempenho/latência.

Validação local: 401 casos exercitados na execução completa, com dois testes de navegação atualizados e 11 testes de interface/relatório aprovados na repetição; 25 testes de input/toque/gyro aprovados após a revisão final; oito testes Python de assinatura, lint sem problemas e release sem assinatura aprovados. Pacote/versão 38/0.31.7/minSdk 24/target 34 conferidos e capturas de categorias/giroscópio inspecionadas. O APK instalável é o artefato assinado produzido pelo CI da mesma branch.
