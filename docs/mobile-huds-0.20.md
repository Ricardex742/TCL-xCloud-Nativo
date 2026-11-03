# HUDs mobile — versão 0.20

Os HUDs têm estilos vetoriais próprios por família, gestos direcionais, combinações simultâneas e modos de ataque/defesa no futebol. O aplicativo continua enviando comandos de controle Xbox pelo canal de entrada da sessão. Não há leitura de posse de bola, inventário, veículo ou outros estados internos dos jogos.

## Visual e áreas de toque

- Call of Duty e tiro: discos escuros, anéis segmentados, ícones sólidos e destaque âmbar ao pressionar.
- Fortnite: placas azuis e peças de construção com cantos cortados.
- EA SPORTS FC e futebol: botões com cores por ação, rótulos internos e marcas direcionais nos botões com gestos.
- Forza e corrida: pedais e direção com acabamento metálico.
- Hades e ação: tons de obsidiana, anéis dourados e detalhes geométricos.

São elementos originais desenhados em Canvas, sem texturas ou marcas extraídas dos jogos. Os códigos Xbox ficam visíveis no editor; a partida mostra nomes e símbolos de ações. As áreas de toque acompanham os discos, pedais e botões retangulares. Tamanho, posição e opacidade continuam configuráveis por título.

## Futebol: modos e gestos

O botão **ATAQUE ⇄ DEFESA** muda a página local do HUD sem enviar um comando de troca ao jogo. O contato de movimento é preservado; comandos que deixam de existir na nova página são liberados. A posse de bola não é enviada pelo serviço Xbox, portanto a troca é manual. Não há inferência de posse pelo vídeo.

| Situação | Ação mobile | Comando Xbox no esquema clássico |
| --- | --- | --- |
| Ataque | Manter / soltar Chute | B |
| Ataque | Arrastar Chute para baixo | RB + B — colocado |
| Ataque | Arrastar Chute para cima | LB + B — cavadinha |
| Ataque | Arrastar Chute para os lados | LB + RB + B — superchute |
| Ataque | Passe | A |
| Ataque | Arrastar Passe para cima | LB + A — tabela |
| Ataque | Arrastar Passe para baixo | RB + A — passe forte |
| Ataque | Enfiada / arrastar para cima | Y / LB + Y |
| Defesa | Manter Marcar | RT + A |
| Defesa | Bote / Carrinho | B / X |
| Defesa | Trocar jogador | LB |
| Defesa | Manter Conter | LT + RT |
| Defesa | Pressão / Goleiro | RB / Y |

O esquema alternativo troca B e X nas ações de finalização, cruzamento, bote e carrinho, incluindo os gestos de chute. Ele pode ser selecionado em **HUD e controles deste jogo → Esquema de futebol**. A configuração deve corresponder ao esquema escolhido dentro do jogo. Versões e outros títulos de futebol podem atribuir ações diferentes; use o editor para adequá-las.

O chute começa a carregar quando o dedo pressiona o botão. O arrasto acrescenta os modificadores à mesma pressão; a soltura encerra o chute. Uma combinação recém-selecionada permanece por um mínimo de 64 ms desde a seleção para ser recebida antes da soltura, sem repetir o comando. Abrir um menu, cancelar um gesto, perder a janela ou trocar de página elimina contatos e liberações pendentes.

## Editor de combos e gestos

Em **⋯ → HUD e controles deste jogo → Editar posição, tamanho e ações**, selecione um botão:

- **Ação** permite selecionar vários botões e gatilhos que serão mantidos juntos. **Aplicar** confirma a combinação no rascunho do editor.
- **Gestos** configura as quatro direções de arrasto. Cada direção pode conter uma combinação diferente ou ficar sem gesto.
- **Salvar** grava o rascunho por título no aparelho. **Cancelar** restaura o estado anterior. Abrir o seletor de comandos e marcar opções não inicia ações no jogo.

As combinações são simultâneas, sem sequências automáticas nem repetição. Nos jogos que executam uma ação ao soltar um botão, prefira gestos que mantenham o botão principal e acrescentem modificadores, como os presets de chute. Trocar o botão principal por outro pode liberar a ação anterior. Os rótulos do jogo não são deduzidos de remapeamentos personalizados.

## Resposta da câmera

O ganho inicial do arrasto foi triplicado. O seletor aceita 40–1200%, mantendo percentuais anteriormente salvos, e permite compensar a zona morta em 0–35%. A curva de resposta favorece pequenos movimentos; o vetor continua limitado ao alcance de um analógico Xbox. Ajustar a sensibilidade de controle dentro do jogo é necessário quando seu limite de giro é baixo.

A view solicita entrega sem agrupamento no início de gestos de tiro. O estado é publicado no evento de toque, antes de desenhar o próximo frame do HUD. Mudanças da câmera acordam o envio de entrada, incluindo o retorno ao neutro; não precisam aguardar o próximo relatório periódico. O prazo para neutralizar uma câmera sem novos movimentos caiu de 80 para 32 ms.

O buffer mantém somente a posição mais recente dos analógicos. Pressões e solturas dos botões e gatilhos são preservadas em uma fila limitada; mudanças de intensidade de um gatilho mantido são consolidadas no valor mais recente dessa pressão. O limite de dados enfileirados e a política de descarte em congestionamento permanecem ativos. Essas mudanças reduzem esperas locais; não medem nem eliminam a latência do servidor, da rede ou do vídeo.

## Persistência e verificação

O formato local de HUD passa para a versão 2, com combinações, gestos, esquema de futebol e compensação de zona morta. Layouts da versão 1 continuam sendo lidos. Coordenadas, controles e opções válidas são preservados, e gravações confirmadas continuam usando armazenamento síncrono por título.

Os testes verificam os modificadores reais dos quatro gestos em ambos os esquemas, contenção com RT + A, troca de página sem comando remoto, manutenção do movimento, liberação dos combos, cancelamento, persistência, comandos de múltiplos dedos, câmera, buffer de entrada, áreas de toque e interação dos seletores Android. As prévias do README vêm das views de produção em testes com renderização nativa. A validação em uma partida cloud e em aparelhos físicos permanece necessária para avaliar a sensação de controle.
