# xCLOUD
Cliente experimental para Xbox Cloud Gaming em Android 10 ou superior, com vídeo nativo por WebRTC e SurfaceViewRenderer. A disponibilidade do jogo depende da conta, da região e da conectividade; o scaler nativo não comprova ativação de AiPQ 4K.

A [versão 0.16.0](docs/stable-signing-0.16.md) fixa o certificado de assinatura dos APKs para permitir atualizações sobre a instalação existente. O Actions precisa do segredo privado `XCLOUD_ANDROID_KEYSTORE_BASE64`; sem ele, ou com outro certificado, o build bloqueia a publicação em vez de gerar uma identidade nova. O APK inclui metadados públicos para conferir assinatura, pacote e versão.

A [versão 0.15.0](docs/input-image-0.15.md) restringe a aba e os selos Game Pass à galeria oficial, resolvendo edições pelo catálogo autenticado. Acrescenta coop local com até quatro jogadores, recepção de vibração do jogo com teste do suporte Android, controle virtual multitoque e toque nativo nos jogos que o anunciam. Nitidez e saturação são opcionais; começam em 0% e 100%, respectivamente, e podem ser ajustadas durante a partida pelo menu de toque.

A [versão 0.14.0](docs/xcloud-0.14.md) reconhece os códigos autenticados XGPULTIMATE/GPULTIMATE e os programas da conta, corrige jogos Game Pass ausentes por divergência entre IDs de edição e acrescenta oito modos de proporção. O aviso verde de sessão fica oculto durante reprodução normal. O aplicativo passa a se chamar **xCLOUD**, com ícone adaptativo, banner para TV e [duas propostas visuais](docs/branding/README.md).

A [versão 0.13.0](docs/playback-settings-0.13.md) troca Mais jogados por uma aba exclusiva do Game Pass, remove Rewards e coloca Configurações ao lado da assinatura ampliada. Vídeo, resolução (720p, 1080p ou 1080p HQ) e upscaling nativo têm seletores explícitos, com preferências salvas para a próxima partida. As estatísticas começam ocultas em cada jogo: Start + Select ou INFO/F10 ativa e alterna os painéis.

A [versão 0.12.0](docs/green-console-0.12.md) unifica o aplicativo no tema verde: busca, conta, login, QR Code, catálogo e fila usam os mesmos painéis e controles. O destaque de foco padrão do Android é removido das capas para evitar o segundo retângulo sobre o contorno de seleção do aplicativo.

A [versão 0.11.0](docs/art-and-queue-0.11.md) amplia o cabeçalho/perfil, usa o Microsoft Store v7 com fallback e cache para carregar nomes e capas originais, e acrescenta selos Game Pass e X|S conforme os dados oficiais. A fila usa o fundo do jogo e destaca estimativa, tempo aguardado e etapas até chegar o vídeo. A grade mantém seis colunas com rolagem abaixo do cabeçalho maior.

O [catálogo original 0.10.0](docs/dashboard-0.10.md) introduziu o template fornecido, a tipografia condensada e seis colunas de capas. Atualmente usa o tema verde: LB/RB alterna as cinco categorias, Y favorita, X pesquisa e Start abre Configurações. Favoritos/histórico são locais e separados por conta. Perfil e capas são obtidos dos serviços Microsoft; quando a conta não informa o nível, o cabeçalho mostra `ASSINATURA NÃO INFORMADA`.

O workflow `build-apk.yml` roda nos pushes da branch `ci/apk-android10-3cc2595` e pode ser executado manualmente nela. Ele prepara JDK 21, Gradle 8.7 e Android SDK 34, executa os testes JVM, verifica APIs incompatíveis com o Android mínimo declarado (API 24, incluindo Android 10), gera o APK debug e verifica sua assinatura. Baixe `TCL-xCloud-App-Debug-Android10` na seção **Artifacts** da execução: ele contém o APK e seu checksum SHA-256. O relatório de lint é salvo separadamente. O módulo instalável é `:app`; os comandos usam esse alvo explicitamente.

Com JDK 21, Gradle 8.7 e Android SDK 34 configurados, use na raiz do repositório:

Para montar o APK, prepare antes a [chave privada de assinatura](docs/stable-signing-0.16.md). Testes JVM/lint podem ser executados separadamente sem a chave. O arquivo privado e a exportação base64 não fazem parte do repositório.

```bash
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Os testes JVM usam HTTP local e credenciais fictícias: verificam renovação de login, credenciais de transferência, fila, autenticação em `/connect`, ordem SDP/ICE, cancelamento, handshakes e pacotes de gamepad. O workflow falha se não houver testes executados ou se algum falhar/for ignorado. A geração do APK e os testes não comprovam login ou mídia em um dispositivo real. O lint seleciona `NewApi` e `InlinedApi`; não representa aprovação de todas as regras de publicação Android TV.

Durante o teste no aparelho, registre a etapa e o código de erro mostrados na tela:

| Etapa | Verificar |
| --- | --- |
| `AUTH_DEVICE_CODE` / `AUTH_TOKEN` | Geração, aprovação e expiração do código. |
| `AUTH_REFRESH` / `AUTH_XBL` / `AUTH_XSTS` / `AUTH_GSSV` | Credencial, código HTTP/OAuth/XErr e acesso da conta à nuvem. |
| `CATALOG` / `PLAY` / `SESSION_STATE` | Catálogo real e criação da sessão do jogo. |
| `SDP` / `ICE_LOCAL` / `ICE_REMOTE` / `TRANSPORT` / `CHANNELS` | Negociação e confirmação dos canais. |
| `VIDEO` | Bytes, quadros recebidos/decodificados e primeiro quadro renderizado. |

O HUD só anuncia vídeo ativo após `onFirstFrameRendered`. Os controles básicos de toque e de gamepad são enviados pelo canal input; vibração e layouts de toque específicos dos jogos ainda não são implementados. Voltar deve encerrar a sessão e permitir abrir outro jogo. Teste também cancelar o QR Code, renovar um login salvo e sair/reabrir o aplicativo. Não compartilhe tokens ou códigos de aprovação nos diagnósticos.

## Inicialização da sessão (0.5.0)

`ReadyToConnect` exige autenticação do console: obtenha a credencial OAuth com o escopo `PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN`, envie-a em `/connect` e aguarde `Provisioned`. O token GSSV autentica a API de streaming e não substitui essa credencial. O cliente solicita a transferência apenas nessa etapa, preservando o refresh token e seu client ID. Se o login antigo de navegador não tiver refresh token, saia e entre pelo QR Code; não cole tokens nos diagnósticos.

`WaitingForResources` é a fila do serviço. O aplicativo aguarda até 60 minutos, mostra o tempo decorrido e permite cancelar com Voltar. Ele não pode garantir capacidade nem uma posição na fila. Erros e cancelamentos encerram a sessão alocada; durante a reprodução, `/keepalive` é enviado a cada 30 segundos.

O WebRTC configura H.264 antes de gerar a oferta; o SDP enviado é o mesmo instalado localmente, sem falsificar o perfil do decoder. Candidatos IPv4/IPv6 são preservados, incluindo rotas IPv4 extraídas de Teredo. Um STUN público auxilia a travessia de NAT; redes que bloqueiam UDP ou exigem relay ainda precisam de diagnóstico no aparelho. O cliente não inventa credenciais TURN.

O renderizador é inicializado a cada partida e recebe a trilha nativa. A tela só informa vídeo ativo no evento `onFirstFrameRendered`; transporte sem quadro por 20 segundos gera diagnóstico `VIDEO`. Isso não comprova ativação do AiPQ. Controles físicos enviam estados pelo canal input; controles de toque e vibração ainda não estão implementados.

A ordem de provisionamento foi conferida no [Greenlight](https://github.com/unknownskl/greenlight/blob/main/packages/desktop/main/helpers/streammanager.ts) e no [xbox-xcloud-player](https://github.com/unknownskl/xbox-xcloud-player/blob/main/src/example/stream.ts). Os testes locais simulam os estados e o protocolo; Fortnite/Forza e a disponibilidade da conta/região devem ser verificados no dispositivo com o novo APK.

## Login e previsão de fila (0.6.0)

O login QR agora usa o cliente xCloud `1f907974-e22b-4810-a9de-d9647380c97e` e o Device Code Microsoft v2 (`login.microsoftonline.com/consumers/oauth2/v2.0`), com `xboxlive.signin openid profile offline_access`. A renovação normal usa esse mesmo fluxo v2; a troca pelo console transfer token usa `login.live.com/oauth20_token.srf`, o mesmo client ID e o escopo de transferência. O login antigo não pode ser reutilizado para esse escopo: a tela pede um novo QR Code, sem trocar arbitrariamente o client ID de um refresh token existente. A autorização do console é verificada antes de mostrar o catálogo para detectar falhas antes da fila.

Em `WaitingForResources`, a tela central exibe a estimativa retornada por `/v1/waittime/{titleId}`, tempo já aguardado, idade da última confirmação da fila e botão Cancelar. A consulta da previsão tem timeout de 3 segundos e intervalo de 30 segundos. Uma previsão ausente/falha não interrompe a fila e nunca vira uma espera inventada de zero minutos. A última previsão válida é marcada como desatualizada se a atualização falhar. O relógio da interface continua atualizando a cada segundo mesmo enquanto uma chamada HTTP aguarda resposta.

A estimativa é do serviço para aquele jogo, não uma posição individual nem contagem regressiva garantida. A espera máxima desta tentativa é exibida na tela (60 minutos); Voltar ou Cancelar libera a sessão. Sem confirmação de `Provisioned`, o aplicativo não inicia a troca de SDP.

## Perfil HQ, estatísticas e controle (0.7.0)

O padrão agora solicita 1920×1080 HQ pelo seletor de perfil TV (`osName=tizen`, conferido no Better xCloud). A oferta SDP pede mínimo/início de 20.000 kbps e máximo de 30.000 kbps por parâmetros `x-google-*-bitrate`, mais limites de banda `b=AS`/`b=TIAS`. Esses parâmetros são solicitações: o servidor pode ignorá-los e usar VBR ou reduzir a taxa pela rede. O aplicativo não pode garantir um piso real de 20 Mbps. O SDP configurado localmente e o enviado ao Xbox são o mesmo; perfil H.264, ICE, fingerprints, áudio e RTX são preservados. A ordem de codecs favorece perfis H.264 de maior qualidade somente quando o decoder realmente os anuncia.

As estatísticas começam ocultas em cada partida e podem ser ativadas com Start + Select, INFO ou F10. A coleta ocorre a cada segundo, fora do envio de controle. Resolução/FPS e decoder vêm de `inbound-rtp`; bitrate usa delta de bytes e o intervalo real entre amostras. O RTT vem do par ICE selecionado e é reavaliado após troca de rota — não mede a latência completa do botão até a imagem. Jitter RTP e tempo de buffer são medidas diferentes e aparecem separadamente. Perda de pacotes e quadros descartados incluem valores por intervalo e acumulados. O tempo de decode usa o delta de `totalDecodeTime` dividido pelos quadros decodificados. Campos não disponíveis ficam como `n/d`; não são convertidos em zeros nem em confirmação de hardware. O nome do decoder e `powerEfficientDecoder`, quando presentes, são exibidos sem inferir AiPQ.

O envio do controle usa uma thread própria com prioridade de exibição e relatórios periódicos de 60 Hz, com despertar antecipado para botões. Movimentos analógicos são coalescidos; toques curtos preservam a sequência pressionar/soltar. Quando o canal acumula dois relatórios, novos estados antigos deixam de ser enfileirados e o envio retoma com o estado atual. Isso limita backlog local e evita bursts de ticks atrasados, preservando o canal ordenado do protocolo. Atraso máximo de agendamento local, bytes pendentes e supressões por congestionamento aparecem no painel. Essas medidas não substituem um teste de latência real nem eliminam congestionamento/retransmissão na rede.

O `SurfaceViewRenderer` já usava `setEnableHardwareScaler(true)`. Em Android TV, o aplicativo agora solicita um modo 3840×2160 próximo de 60 Hz somente se ele estiver na lista de modos suportados; mantém uma saída 4K já ativa a 60 Hz ou mais e não troca por 4K24/30. O painel informa os tamanhos da Surface, janela e saída reportada pelo Android. Isso confirma geometria/configuração de Surface, não processamento proprietário AiPQ: não há API pública usada pelo aplicativo para verificar ou ativar o AiPQ da TCL P755. A preferência de modo de exibição também pode ser recusada pelo sistema. Para investigar atraso na TV, compare os dados com o Modo Jogo e com interpolação de movimento/MEMC desativada nas configurações da própria TV; processamento de imagem extra pode acrescentar latência.

## Câmera/gatilhos sem zona morta e menor atraso (0.8.0)

A [análise da gravação fornecida](docs/video-review-0.8.md) identifica vídeo 1080p60 chegando à saída 4K60, rede sem perda nas amostras, cerca de 25–27 ms de decode e 50–60 ms de buffer. Não equivale a medir input lag completo.

A zona morta adicional do app é 0% no analógico direito e nos gatilhos, mesmo se o driver informar `flat` elevado. Faixas de eixos assinadas/não assinadas são normalizadas; valores pequenos continuam sendo enviados, respeitando a quantização do protocolo. O analógico esquerdo mantém o `flat` do driver. Drift ou zona morta aplicados pelo controle ou pelo jogo não podem ser eliminados por essa opção do app.

O envio passa a até 120 relatórios/s e não reutiliza valores antigos da câmera/gatilhos ao enviar uma transição de botão. O seletor **Vídeo** em Configurações permite escolher Menor atraso ou Adaptativo para a próxima partida. Menor atraso solicita playout de 0–40 ms, pós-processamento mínimo no Android 11+ e o modo oficial de baixa latência do decoder apenas quando declarado como suportado. O decoder anterior é fallback. Nenhuma promessa de tempos menores é feita sem medir no aparelho; buffers menores podem exigir voltar ao modo adaptativo em uma rede instável.

INFO/F10 agora alterna estatísticas compactas, completas e ocultas. O modo completo mostra os valores normalizados enviados pela câmera/gatilhos e o estado do pedido de baixa latência do codec. O upscale continua pelo caminho nativo da Surface para a saída da TV; AiPQ proprietário não é confirmado ou forçado, e não há novo filtro pesado de imagem.

A ponte `org.webrtc.LowLatencyDecoderFactory` usa classes de pacote da dependência 1.1.1. Ao atualizar essa dependência, revalidar construtores/interfaces do wrapper, fallback e reprodução em hardware. Compilar com JDK completo (incluindo `jlink`), não somente JRE.

## GameSir T4 Nova Lite, atalho e recuperação de vídeo (0.9.0)

O envio periódico passa a 250 relatórios/s (intervalo de 4 ms), com a mesma proteção contra congestionamento do canal e sem repetir ticks atrasados. Isso acompanha a capacidade informada do GameSir T4 Nova Lite, mas não configura o firmware do controle nem mede/altera a frequência física entregue pelo receptor USB ou Bluetooth ao Android. Os gatilhos e a câmera continuam sem zona morta adicional no app.

Durante a partida, **Start + Select juntos** alterna compacto → completo → oculto → compacto. A combinação aceita as duas ordens, precisa ocorrer dentro de 150 ms e só dispara outra vez depois de soltar ambos. Start/Select são reservados durante essa janela: um toque isolado preserva pressionar/soltar; segurar um deles o envia ao jogo após a janela. Os outros botões, gatilhos e analógicos não passam por essa espera. INFO/F10 continua disponível. Perder o foco, cancelar ou trocar de sessão libera os comandos pendentes.

O cliente agora acompanha a apresentação de novos quadros durante toda a partida. A verificação usa um callback de desenho por segundo, sem copiar pixels da GPU; uma tela de carregamento estática que continua recebendo quadros não dispara o diagnóstico. Aproximadamente 3,5 segundos sem progresso iniciam pedidos limitados de quadro-chave pelo canal de controle. Após 8 segundos e novamente após 16, o app reinicializa o decoder de hardware se os quadros decodificados pararam, ou recria a Surface se os quadros chegam sem ser apresentados. O reset do decoder ocorre na thread de decodificação, ao receber um quadro-chave, preservando o callback e adotando suas dimensões; a configuração padrão permanece como fallback se o modo de baixa latência for rejeitado.

Essas tentativas preservam áudio, PeerConnection e sessão Xbox, incluindo keepalive. Se a imagem não voltar em cerca de 30 segundos, um diálogo operável pelo controle oferece **Recuperar vídeo** ou **Voltar ao catálogo**. A primeira opção tenta recuperar a imagem na mesma sessão; a segunda encerra a sessão. O retorno de quadros fecha o diálogo automaticamente. Uma trilha de vídeo substituída também é reconectada ao renderizador. A mensagem de quadro-chave segue o [canal de controle do Greenlight](https://github.com/unknownskl/greenlight/blob/main/packages/player/src/client/lib/channel/control.ts).

Os testes JVM cobrem a combinação em ambas as ordens, repetição de tecla, toque isolado, foco, ausência de quadros, recuperação e reset no quadro-chave, incluindo mudança de resolução e fallback. Não reproduzem o hardware da TCL nem o reinício real do Forza Horizon 5. No aparelho, conferir a troca qualidade → desempenho com reinício, confirmar retorno da imagem e testar Start/Select separadamente e juntos.
