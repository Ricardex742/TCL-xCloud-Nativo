# Game Pass, coop, toque, vibração e imagem (0.15.0)

## Game Pass

A versão 0.14 aceitou `programs`/`userPrograms` como inclusão na assinatura. O teste real mostrou que GPULTIMATE também aparece em títulos BYOG; essa inferência foi removida. Agora **nem o programa GPULTIMATE nem a assinatura Ultimate da conta tornam um jogo Game Pass**.

A única origem de inclusão é a galeria oficial Game Pass, subtraída da galeria free-to-play. IDs de produto são normalizados sem diferença de caixa. Para produtos oficiais sem correspondência direta no catálogo da conta, o app consulta `POST /v2/titles` com `alternateIds: [productId]` e `alternateIdType: productId`, seguindo o [lookup do cliente público](https://github.com/redphx/better-xcloud/blob/main/src/utils/xcloud-api.ts). Isso permite reconhecer uma edição que retorna outro ID de produto, preservando seu verdadeiro `titleId` de lançamento.

As consultas usam no máximo três solicitações simultâneas, limite de seis segundos por chamada e apenas produtos ausentes da correspondência direta. Uma resposta vazia, com erro, não filtrada ou ambígua não acrescenta títulos. O resultado continua limitado aos jogos do catálogo autenticado; nenhum jogo é criado pelo nome. A mesma regra alimenta a aba e os selos das capas. Sem galeria válida, a aba permanece sem jogos e informa a falha. A assinatura do perfil mantém a leitura automática dos campos do usuário, separada da inclusão dos jogos.

## Coop local e controles

Em **Configurações → Controles e toque → Coop local**, ative até quatro jogadores para a próxima partida. Cada dispositivo Android ocupa um índice 0–3 do protocolo, com seu próprio estado e anúncio `gamepadChanged`. O controle que navegou no catálogo tem prioridade na atribuição inicial. Os índices dos demais controles permanecem estáveis; desconectar um não muda o jogador dos outros. Perder o foco libera botões, eixos, gatilhos e contatos pendentes.

Com toque ativo e coop ligado, a tela ocupa o jogador 1; até três controles físicos podem ocupar os demais jogadores. Sem toque, há até quatro controles físicos. O modo fica desligado por padrão. O jogo e a sessão Xbox precisam aceitar coop/multiplayer local; a opção não transforma um jogo de um jogador em coop nem disponibiliza um recurso bloqueado pelo serviço. A combinação Start + Select continua reservada ao controle do jogador 1 para estatísticas; Start/Select dos outros jogadores são encaminhados ao jogo.

Os relatórios dos jogadores compartilham o mesmo contador de sequência e o mesmo envio nativo de até 250 Hz. Transições rápidas de botões são preservadas por jogador. O limite de backlog considera o tamanho real do pacote, incluindo contatos; congestionamento não gera uma repetição ilimitada de movimentos antigos. As zonas mortas anteriores são preservadas: câmera e gatilhos continuam sem zona morta adicional do app.

## Vibração por controle/dongle

A vibração recebida no canal input é decodificada e encaminhada ao `InputDevice` do jogador correspondente. O app usa o vibrador do controle, sem recorrer ao vibrador do celular ou do controle remoto da TV. Nos modelos com um atuador lógico, os motores são combinados na intensidade máxima informada; o Android/driver faz a reprodução física. A opção vem ativada, respeita duração/atraso/repetição com limites e é interrompida ao perder foco, desconectar ou encerrar a sessão.

**Controles e toque → Testar vibração / verificar suporte** mostra os controles detectados e se o Android expõe vibração. O teste manual dura 250 ms. Um dongle pode enviar botões normalmente e não expor force feedback ao Android. Nesse caso, o app não consegue forçar vibração por essa API; o modo XInput e o firmware/driver precisam ser compatíveis. Nenhum driver USB que desanexa o controle do Android é instalado, e nenhuma frequência física de polling é alterada.

## Jogar com toque

No celular, o modo Automático oferece **Jogar com controle** ou **Jogar com toque — controle virtual** ao iniciar. Quando o catálogo do jogo informa `NativeTouch`, há também **Jogar com toque nativo do jogo**. O modo padrão na TV continua com controle físico. Em Configurações, é possível fixar Controle virtual, Toque nativo ou Desativado; uma escolha de nativo num jogo sem suporte informado usa controle virtual e informa o fallback.

O controle virtual é um layout próprio do app: dois analógicos, ABXY, direcional, LB/RB, LT/RT, L3/R3, Start e Select. Aceita vários dedos, conserva entradas independentes e solta tudo ao cancelar/perder o foco. Não baixa nem reproduz os layouts personalizados oficiais do Xbox. Por emular um controle, pode ser usado em jogos de gamepad mesmo sem layout oficial de toque.

O modo nativo negocia toque e até dez contatos. Envia IDs, posição, pressão e transições de cada dedo no canal input, usando as dimensões informadas pelo servidor. As coordenadas respeitam barras e o corte do Zoom. Sair da área de vídeo e cancelar uma sequência também libera os contatos. Um controle físico pode ser usado depois; um controle inativo não é anunciado automaticamente no início do modo nativo.

O botão **⋯** do overlay abre estatísticas, ajustes de imagem e retorno ao catálogo. O texto verde de sessão continua oculto durante reprodução normal. As estatísticas continuam desligadas por padrão.

## Nitidez e saturação

**Configurações → Nitidez e saturação** salva nitidez de 0–100% e saturação de 0–200%. Valores iniciais: **nitidez 0% e saturação 100%**. Durante a partida com toque, **⋯ → Nitidez e saturação** aplica as mudanças imediatamente. Os demais ajustes continuam disponíveis antes de iniciar o jogo.

O caminho padrão mantém o drawer original. Ao alterar os valores, um fragment shader aplica uma máscara de nitidez com quatro vizinhos e ajuste de saturação na mesma passagem do vídeo, com adaptação OES/RGB/YUV do WebRTC. Não há leitura de pixels no app ou etapa extra de renderização. A máscara pode realçar ruído e consumir GPU; começar em 25% ajuda a comparar com a imagem original. O resultado e seu custo real precisam ser avaliados na TV/celular.

## Validação

Os testes verificam exclusão de jogos comprados mesmo com GPULTIMATE, lookup de edições oficiais, respostas não filtradas, quatro índices de controle, desconexão, toque + jogador físico, pacote multitoque, liberação após cancelamento/congestionamento, rumble combinado com metadados, mapeamento de barras/Zoom e persistência dos menus. Robolectric exporta os novos painéis e o controle virtual.

`python3 scripts/verify_image_filter.py` cria um contexto GLES de software Mesa, compila e liga o shader real do app nas três variantes de textura e verifica cinco saídas de pixels RGB: imagem original, cor constante com nitidez, cinza, saturação aumentada e borda com nitidez. O readback é exclusivo desse teste. O workflow instala Mesa e executa essa verificação antes de disponibilizar o APK; seu relatório integra os artefatos.

O wrapper de shader usa uma classe de pacote da dependência WebRTC 1.1.1. Ao atualizar essa dependência, revalidar construtores, callback, GLSL, matrizes e os caminhos OES/RGB/YUV, junto dos wrappers de decoder existentes.

Os testes não reproduzem a conta Microsoft real, a restrição de coop de cada título nem o force feedback do GameSir/dongle na TV. No aparelho: conferir a aba Game Pass; conectar dois controles e entrar num jogo com coop local; rodar o teste de vibração; escolher toque num jogo; comparar nitidez/saturação observando renderização e fluidez.
