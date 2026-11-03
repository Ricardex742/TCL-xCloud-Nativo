# HUDs de toque — versão 0.19

Os controles virtuais são desenhados por uma view Android sobre a transmissão. Cada contato alimenta o mesmo estado e pacote de controle Xbox utilizados pelo controle físico. O funcionamento não depende dos layouts oficiais de toque do serviço Xbox.

## Perfis

| Perfil | Comandos iniciais |
| --- | --- |
| Fortnite — combate | Analógico de movimento, câmera por arrasto, disparo RT, mira LT, pulo A, agachar R3, recarregar/interagir X, picareta Y, armas LB/RB, mochila ↑ e corrida L3. |
| Fortnite — construção | Parede RT, rampa LT, piso RB, teto LB e armadilha X, seguindo Builder Pro. B alterna combate/construção. |
| Call of Duty / tiro | Movimento, câmera por arrasto, dois botões de disparo RT, mira LT, pulo A, agachar B, recarregar/interagir X, arma Y, corrida L3, corpo a corpo R3 e granadas LB/RB. |
| Forza / corrida | Setas para o eixo horizontal do analógico esquerdo, acelerador RT, freio LT, freio de mão A, rebobinar Y, marchas B/X, câmera RB e embreagem LB. Direcional separado para navegar nos menus. |
| EA SPORTS FC / FIFA / futebol | Movimento, analógico direito para fintas, passe A, chute B, enfiada Y, cruzamento X, corrida RT, troca de jogador LB e proteção LT. |
| Hades / Hades II | Movimento, esquiva A, ataque X, especial Y, feitiço B, interação RB e comandos auxiliares. |
| Ação | Botões de combate e dois analógicos. |
| Controle Xbox | ABXY, gatilhos, ombros, dois analógicos, direcional, L3/R3, Menu e View. Usado quando não há um perfil reconhecido. |

A escolha automática usa o nome e o identificador do título. Famílias reconhecidas usam seu perfil; jogos desconhecidos ficam no controle Xbox. Não há mapeamento individual verificado para todo o catálogo. É possível selecionar qualquer perfil manualmente e remapear os botões conforme os comandos configurados no jogo.

Os ícones são vetores próprios. Indicadores de vida, munição, mapa e inventário continuam sendo desenhados pelo jogo remoto; o app não recebe esses dados. Nos perfis de tiro, o arrasto em espaço livre à direita e sobre o disparo emula o analógico direito. Isso segue a sensibilidade e a aceleração de controle do jogo, sem equivaler ao toque nativo 1:1.

## Uso e edição

1. Em um celular com a preferência automática, selecione **Jogar com toque • HUD** na abertura do jogo. Também é possível definir **Configurações → Controles e toque → Jogar com toque → Controle virtual — HUD por jogo**.
2. Na partida, abra **⋯ → HUD e controles deste jogo**. No controle físico, abra o mesmo menu com **Menu/Start + View/Select**.
3. Escolha o layout, tamanho geral, opacidade e, nos perfis de tiro, a sensibilidade do arrasto.
4. Em **Editar posição, tamanho e ações**, selecione e arraste um controle. Os botões **− / +** mudam seu tamanho; **Ação** escolhe o comando Xbox de um botão. Analógicos e direcional podem ser movidos e redimensionados.
5. **Salvar** confirma os ajustes no armazenamento local. **Cancelar**, Voltar do Android ou B do controle descartam a edição. **Restaurar** no editor prepara os valores iniciais; só **Salvar** os confirma.

Cada título tem suas próprias preferências no aparelho. Elas sobrevivem ao fechamento e à atualização do app, desde que seus dados locais sejam mantidos. Uma falha na gravação mantém o editor aberto e mostra o erro. Durante a edição, os contatos e o controle físico não enviam ações de gameplay. Ocultar o HUD solta os comandos ativos e mantém o botão de menu disponível em celulares.

No Fortnite, B é enviado ao jogo e muda a página local do HUD ao ser solto. O app não observa se o jogo aceitou a troca. Se o HUD sair de sincronia com o jogo, use **Mostrar HUD de combate** ou **Mostrar HUD de construção** no menu; essas duas opções só alteram a página visual. O perfil de construção pressupõe Builder Pro. Outros esquemas exigem remapeamento.

## Entrada e persistência

- Os dedos são acompanhados por seus IDs Android. Soltar um botão não solta os demais; dois botões de disparo podem manter o mesmo gatilho ativo.
- O movimento da câmera usa velocidade de arrasto e sensibilidade configurável. Após 80 ms sem movimento, um relatório neutro encerra a rotação. Levantar o dedo, cancelar um gesto, abrir um menu ou perder a janela também libera os comandos.
- A mudança entre combate e construção preserva contatos em controles compartilhados, como movimento e câmera. Contatos em ações removidas são liberados.
- Posição e tamanho usam coordenadas proporcionais à tela. Os controles são limitados à área da view após redimensionamento.
- Os ajustes são armazenados por identificador de título normalizado, separados das credenciais e da renovação do login. Leituras inválidas usam valores iniciais; gravações confirmadas usam `SharedPreferences.commit()`.
- Na primeira abertura após esta atualização, uma preferência antiga de toque nativo é migrada para o HUD virtual. Uma nova escolha explícita de **Toque nativo Xbox — experimental** é preservada.

## Validação

A versão inclui testes de seleção de perfil, limites de layout, combinações de múltiplos dedos, gatilhos e direção independentes, câmera e retorno ao repouso, troca de página do Fortnite, remapeamento até o pacote binário de controle, persistência por título e interação com as views Android. As prévias são capturas dessas views, incluindo formatos 1280×720 e redimensionamento para 2400×1080.

Os testes locais e de CI verificam o comportamento do aplicativo e o formato dos comandos. A resposta de cada jogo e a ergonomia em aparelhos físicos precisam de validação durante uma sessão cloud; não foi realizada uma sessão em celular neste ambiente.
