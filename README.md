<div align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_logo.png" width="112" alt="Logo xCLOUD" />
  <h1>xCLOUD</h1>
  <p><strong>Seus jogos. Na sua tela.</strong></p>
  <p>Cliente Android independente para Xbox Cloud Gaming, com interface para TV e transmissão nativa.</p>
  <p>
    <a href="https://github.com/Ricardex742/TCL-xCloud-Nativo/actions/workflows/build-apk.yml?query=branch%3Aci%2Fapk-android10-3cc2595"><img src="https://github.com/Ricardex742/TCL-xCloud-Nativo/actions/workflows/build-apk.yml/badge.svg?branch=ci%2Fapk-android10-3cc2595" alt="Compilação Android" /></a>
    <img src="https://img.shields.io/badge/Android-7.0%2B-54de69?logo=android&logoColor=white" alt="Android 7 ou superior" />
    <img src="https://img.shields.io/badge/vers%C3%A3o-0.30.0-173d29" alt="Versão 0.30.0" />
  </p>
  <p><a href="#baixar-e-instalar">Baixar</a> · <a href="#telas-do-aplicativo">Telas</a> · <a href="#controles-de-toque-por-jogo">HUDs de toque</a> · <a href="#funcionalidades">Funcionalidades</a> · <a href="#atalhos-dos-jogos-na-tv">Atalhos na TV</a> · <a href="#compilar">Compilar</a></p>
</div>

![Voltar a jogar no xCLOUD](docs/screenshots/return-to-play.png)

A **lupa no cabeçalho** abre a pesquisa em todos os jogos e solicita o teclado nos celulares. O atalho **X** do controle continua disponível.

O catálogo abre em **Voltar a jogar**, que consulta os últimos jogos da conta no Xbox Cloud Gaming e mantém a ordem retornada pelo serviço. A lista é atualizada ao abrir o catálogo ou voltar da partida. A última consulta fica salva por conta; falhas de atualização são indicadas na tela. Só aparecem jogos que ainda estão disponíveis no catálogo cloud. Veja a [implementação do histórico e as correções de atalhos da versão 0.29](docs/cloud-history-shortcuts-0.29.md).

As [notas técnicas da versão 0.30](docs/resume-catalog-frame-generation-0.30.md) descrevem a abertura do catálogo e a análise de interpolação de quadros e conexão de controles à nuvem.

## Telas do aplicativo

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/transition-startup.png" alt="Abertura com a logo do xCLOUD" /><br /><strong>Abertura</strong> — restauração da sessão com a identidade do aplicativo.</td>
    <td width="50%"><img src="docs/screenshots/transition-shortcut.png" alt="Prévia da entrada por atalho com imagem de demonstração" /><br /><strong>Atalho</strong> — arte do jogo durante a preparação.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/login.png" alt="Tela de entrada do xCLOUD" /><br /><strong>Login</strong> — acesso à conta Microsoft.</td>
    <td width="50%"><img src="docs/screenshots/qr-login.png" alt="Login Microsoft com QR Code de demonstração" /><br /><strong>QR Code</strong> — autorização pelo celular.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/queue.png" alt="Fila com capa, estimativa e tempo aguardado" /><br /><strong>Fila</strong> — estimativa do serviço e etapas da conexão.</td>
    <td><img src="docs/screenshots/settings.png" alt="Configurações de vídeo e controles" /><br /><strong>Configurações</strong> — preferências salvas no aparelho.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/compatibility.png" alt="Modos de vídeo, incluindo compatibilidade para TVs mais fracas" /><br /><strong>Vídeo</strong> — menor atraso, adaptativo ou compatibilidade.</td>
    <td><img src="docs/screenshots/game-menu.png" alt="Menu da partida com diagnóstico e desconexão" /><br /><strong>Partida</strong> — ajustes, estatísticas e desconexão.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/controls.png" alt="Configurações de coop, vibração e toque" /><br /><strong>Controles</strong> — coop local, vibração e toque.</td>
    <td><img src="docs/screenshots/image.png" alt="Ajustes de nitidez e saturação" /><br /><strong>Imagem</strong> — nitidez e saturação durante a partida.</td>
  </tr>
</table>

Ao sair, uma tela com a arte do jogo e **Saindo do jogo…** acompanha o encerramento até o catálogo estar pronto. Ao cancelar a fila, a mensagem é **Voltando ao catálogo…**. Veja as [transições da versão 0.27](docs/session-transitions-0.27.md).

As telas de interface são capturas das views nativas com perfil e dados de demonstração. O QR Code é ilustrativo. Capas e selos exibidos no uso real dependem dos dados oficiais do catálogo.

### Gameplay

![Trecho de gameplay transmitido pelo xCLOUD em uma TV](docs/screenshots/gameplay.jpg)

Trecho de uma gravação de gameplay no aplicativo, com o enquadramento recortado para mostrar somente o jogo. A imagem não representa uma medição de fluidez ou desempenho.

## Controles de toque por jogo

No celular, escolha **Jogar com toque • HUD** ao abrir um jogo. O aplicativo seleciona um layout para Fortnite, Call of Duty, Forza, EA SPORTS FC/FIFA e Hades, além de perfis gerais de tiro, corrida, futebol e ação. Os demais títulos usam o controle Xbox virtual; qualquer jogo permite escolher outro layout manualmente.

Cada família tem um visual próprio: discos táticos e ícones sólidos nos jogos de tiro, painéis azuis no Fortnite, pedais com acabamento metálico nas corridas, botões de futebol com cores por ação e detalhes dourados em Hades. Os códigos dos botões Xbox aparecem no editor; durante a partida, os controles mostram ações do jogo.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/hud-fortnite.png" alt="Prévia do HUD de combate do Fortnite" /><br /><strong>Fortnite</strong> — movimento, câmera por arrasto, tiro e troca de armas.</td>
    <td width="50%"><img src="docs/screenshots/hud-fortnite-build.png" alt="Prévia do HUD de construção do Fortnite" /><br /><strong>Construção</strong> — parede, rampa, piso e teto com comandos Builder Pro.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/hud-call-of-duty.png" alt="Prévia do HUD de tiro com dois botões de disparo" /><br /><strong>Tiro</strong> — mirar, disparar, recarregar, agachar e granadas.</td>
    <td><img src="docs/screenshots/hud-forza.png" alt="Prévia do HUD de corrida com setas e pedais" /><br /><strong>Corrida</strong> — direção, acelerador, freio e marchas.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/hud-ea-fc.png" alt="Prévia do HUD de futebol em ataque com gestos no chute" /><br /><strong>Ataque</strong> — passe, chute, enfiada, corrida e gestos para finalização.</td>
    <td><img src="docs/screenshots/hud-ea-fc-defense.png" alt="Prévia do HUD de futebol em defesa" /><br /><strong>Defesa</strong> — marcar, bote, carrinho, troca de jogador e contenção.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/hud-hades.png" alt="Prévia do HUD de Hades com ações de combate" /><br /><strong>Hades</strong> — ataque, especial, feitiço e esquiva.</td>
    <td><img src="docs/screenshots/hud-editor.png" alt="Editor de posição, tamanho, combos e gestos de cada botão" /><br /><strong>Editor</strong> — posição, tamanho, comandos combinados e gestos.</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/hud-settings.png" alt="Configurações do HUD por jogo" /><br /><strong>Ajustes</strong> — layout, tamanho, opacidade e sensibilidade.</td>
    <td><strong>Gestos no futebol</strong><br />No chute: ↓ colocado, ↑ cavadinha, ← / → superchute.<br />No passe: ↑ tabela, ↓ passe forte.<br />Na enfiada: ↑ lançamento.<br />Na defesa, Marcar combina RT + A.</td>
  </tr>
</table>

São prévias das views do aplicativo sobre um fundo neutro. Os HUDs enviam comandos de controle Xbox: não dependem do toque nativo do jogo e não recriam indicadores de vida, munição ou inventário. Os nomes das ações seguem os comandos padrão; ajustes feitos dentro do jogo podem exigir remapeamento. O toque nativo Xbox permanece uma opção experimental separada.

Durante a partida, abra **⋯ → HUD e controles deste jogo** para escolher o perfil ou editar. No editor, selecione um controle, arraste para mover, use **− / +** para redimensionar, **Ação** para combinar botões Xbox e **Gestos** para configurar cada direção de arrasto. **Salvar** mantém os ajustes por jogo no aparelho, inclusive depois de fechar o app; **Cancelar** descarta as alterações.

### Toque direto no motor nativo

A seleção de 720p, 1080p ou 1080p HQ também se aplica ao toque nativo. O perfil solicitado e a resolução recebida aparecem separadamente no diagnóstico: a saída final continua dependendo do serviço, do jogo e da proporção negociada. Veja os [ajustes de qualidade e áudio da versão 0.28](docs/stream-quality-audio-0.28.md).

O modo **Toque nativo Xbox — experimental** envia contatos da tela pelo canal de input do WebRTC, sem navegador embutido. Ele é oferecido para jogos que informam suporte a `NativeTouch`. O HUD virtual continua disponível como outra forma de jogar com toque.

Em **Automático — detectar dispositivo e jogo**, TVs usam controle físico. Celulares com tela de toque iniciam com toque nativo quando o catálogo autenticado anuncia suporte; nos demais jogos, usam o HUD virtual. **Exclusivo — controle virtual** mantém o HUD no celular mesmo em jogos com toque nativo, e **Desativado — controle físico** preserva a escolha de jogar sem toque. Catálogo e atalhos usam a mesma regra.

O toque direto no Fortnite foi validado em uma sessão real em Android 10 na versão 0.24. A compatibilidade com outros dispositivos e títulos precisa ser verificada. Contar eventos enviados, por si só, confirma apenas o envio local. Consulte a [validação no aparelho e análise do diagnóstico](docs/catalog-performance-0.25.md).

No futebol, toque no botão **ATAQUE ⇄ DEFESA** para trocar as ações sem interromper o movimento. Essa troca é manual: a sessão cloud não informa a posse de bola ao aplicativo. Escolha o esquema **Clássico — chute B** ou **Alternativo — chute X** nos ajustes do HUD, conforme a configuração do seu jogo. Os gestos de chute combinam RB, LB ou LB + RB com o botão de finalização; não é necessário pressionar esses botões separadamente.

Nos HUDs de tiro, arraste na área livre à direita ou sobre o botão de disparo para mover a câmera. A sensibilidade vai até **1200%** e há compensação ajustável da zona morta. A câmera usa envio antecipado e solicita retorno ao repouso após 32 ms sem novo movimento. A velocidade máxima de giro continua limitada pela sensibilidade de controle configurada dentro do jogo, e a latência total também depende da transmissão.

## Diagnóstico pós-partida

![Relatório pós-partida com dados de demonstração](docs/screenshots/session-report.png)

Ao desconectar uma partida com vídeo, o aplicativo mostra um resumo da transmissão: tempo conectado, resolução e codec recebidos, médias e picos de RTT, perda, jitter, FPS decodificados, bitrate e tempo de decodificação. O relatório registra também pausas observadas pelo monitor de vídeo e tentativas de recuperação, para ajudar a comparar configurações e conexões.

Em **Configurações → Diagnóstico pós-partida**, escolha se ele abre ao sair do jogo. **Último diagnóstico** permite consultá-lo depois, inclusive após reiniciar o app. **Copiar** coloca o resumo na área de transferência para compartilhar manualmente. A coleta mantém agregados de tamanho fixo e guarda apenas o último relatório local; as estatísticas durante a partida continuam ocultas por padrão.

Campos ausentes aparecem como **n/d**, e o índice de qualidade só é calculado com amostras suficientes. RTT não é a latência completa até a imagem; FPS decodificados não são FPS do jogo ou da tela. O relatório não mede velocidade da internet e as pausas são observações do monitor, não uma medição quadro a quadro. A captura acima usa dados de demonstração.

## Funcionalidades

| Área | Recursos |
| --- | --- |
| Conta | Login Microsoft por QR Code, renovação da sessão salva, perfil, avatar e identificação da assinatura quando informada pelos serviços Xbox. |
| Biblioteca | Voltar a jogar com histórico da conta no xCloud, favoritos por conta, Game Pass, adições recentes e todos os jogos. Pesquisa por nome e navegação pelo controle. |
| Catálogo | Nomes e capas originais, cache de imagens e selos Game Pass e X\|S conforme os dados disponíveis. A inclusão no Game Pass usa a galeria oficial, separada do acesso a jogos comprados. |
| Fila | Fundo do jogo, estimativa de espera, tempo aguardado, etapas de conexão e cancelamento. |
| Transmissão | Vídeo nativo por WebRTC/H.264, com preferência por decodificação de hardware. Perfis solicitados de 720p, 1080p e 1080p HQ, preservados também no toque nativo. |
| Áudio | Saída configurada como mídia/jogo, estéreo solicitado, teclas de volume no canal de mídia e foco de áudio com liberação ao encerrar. Volume e foco disponíveis no diagnóstico. |
| Vídeo | Seletores de menor atraso, adaptativo e compatibilidade. Escala nativa, proporção original, 16:9, 18:9, 21:9, 16:10, 4:3, esticar e zoom. |
| Imagem | Nitidez e saturação opcionais, com aplicação durante a partida. |
| HDR10 | Diagnóstico do suporte anunciado pela tela, separado do formato do streaming. O motor nativo atual usa H.264 SDR de 8 bits e não ativa HDR10 real. |
| Controle físico | USB, Bluetooth e dongles reconhecidos pelo Android. Navegação por direcional, botões e analógicos. |
| Coop local | Até quatro jogadores com estados independentes, quando aceitos pelo jogo e pela sessão cloud. |
| Vibração | Recepção de comandos do jogo e teste de suporte. O controle e o driver Android precisam expor a função de vibração. |
| Toque | HUDs por jogo, múltiplos dedos, câmera por arrasto, pedais, ataque/defesa no futebol, comandos combinados e gestos editáveis. Posição, tamanho, opacidade e ações salvas por título. Toque nativo experimental no mesmo motor WebRTC. |
| Menu da partida | Atalho pelo controle ou pelo botão de toque, ajustes de HUD e imagem, estatísticas, diagnóstico e desconexão da sessão. |
| Estatísticas | FPS, resolução recebida, bitrate, RTT, jitter, buffer, perdas, descarte e tempo de decodificação. Começam ocultas e são ativadas manualmente. |
| Pós-partida | Relatório local com médias e picos de rede/vídeo, tráfego informado pelo WebRTC, pausas observadas, recuperações e resumo de input. Último relatório salvo, cópia manual e abertura automática opcional. |
| Recuperação | Solicitação de quadro-chave, recuperação do decoder e da superfície de vídeo, preservando a sessão enquanto possível. |
| Tela inicial da TV | Atalhos instaláveis por jogo, pedidos de atalhos ao launcher e canal de favoritos nos sistemas compatíveis. |
| Atualizações | Certificado fixo para atualizar o aplicativo sem trocar sua identidade de instalação. |

A disponibilidade dos jogos depende da conta, da região e do serviço Xbox Cloud Gaming. Recursos de coop e toque variam por título. A escala nativa usa a Surface Android; o aplicativo não configura o processamento proprietário de imagem de cada TV.

## Baixar e instalar

1. Abra [GitHub Actions nesta branch](https://github.com/Ricardex742/TCL-xCloud-Nativo/actions/workflows/build-apk.yml?query=branch%3Aci%2Fapk-android10-3cc2595) e escolha a execução bem-sucedida mais recente.
2. Em **Artifacts**, baixe **TCL-xCloud-App-Debug-Android10** e extraia o arquivo ZIP.
3. Instale **app-debug.apk** no Android TV, Google TV ou celular. O pacote também inclui checksum e metadados públicos de versão e assinatura.
4. Abra **xCLOUD**, selecione **Entrar com QR Code** e conclua a autorização Microsoft.
5. Escolha um jogo e aguarde a fila e a conexão.

O aplicativo declara Android 7.0/API 24 como mínimo. O aparelho precisa oferecer os recursos de vídeo utilizados pelo WebRTC. A partir da versão 0.16, os APKs desta branch usam a mesma assinatura fixa: instale a atualização sobre a versão anterior para manter os dados locais. Desinstalar ou limpar os dados remove login e favoritos.

## Controles e navegação

| Ação | Comando |
| --- | --- |
| Navegar | Direcional ou analógico esquerdo |
| Selecionar / jogar | A |
| Voltar | B |
| Alternar categorias | LB / RB |
| Adicionar ou remover favorito | Y |
| Pesquisar jogos | X |
| Abrir configurações no catálogo | Menu / Start ou engrenagem |
| Abrir o menu da partida | **Menu/Start + View/Select juntos**, no controle do jogador 1 |
| Alternar estatísticas pelo teclado/TV | INFO / F10 |

Durante a partida, Menu e View pressionados separadamente continuam sendo enviados ao jogo. O botão Voltar do Android abre o menu da partida; use **Desconectar sessão e voltar ao catálogo** para encerrar a conexão.

## Compatibilidade com TVs mais limitadas

Em **Configurações → Vídeo**, selecione **Compatibilidade — TVs mais fracas**. O preset escolhe 720p, ativa a escala nativa e restaura nitidez/saturação para os valores originais. Também prioriza H.264 Baseline, solicita até 6 Mbps, limita a apresentação a 30 quadros/s e reduz a frequência dos relatórios de controle, preservando os eventos de botões.

A resolução e a taxa realmente recebidas dependem da negociação com o serviço. O limite de apresentação reduz o trabalho de renderização; ele não garante que o servidor envie ou que o decoder processe somente 30 quadros/s. Consultas e imagens do catálogo são pausadas durante a partida. O menu **Diagnóstico da partida** reúne amostras locais para distinguir falta de dados de vídeo e ausência de progresso da decodificação.

### HDR10 da tela e do streaming

Em **Configurações → HDR10: verificar tela e transmissão**, o app consulta o suporte HDR10 anunciado pelo Android. Uma tela compatível não altera o formato do vídeo recebido: o motor nativo negocia H.264 de 8 bits e usa uma superfície RGB de 8 bits. HDR10 real exige um fluxo de 10 bits, BT.2020/PQ e reprodução compatível em toda a cadeia. Forçar o modo da TV ou aumentar a saturação não acrescenta essa informação ao vídeo SDR. O diagnóstico não modifica o modo de cor da janela.

## Atalhos dos jogos na TV

Em **Configurações → Atalhos dos favoritos**, há três caminhos:

- **Instalar atalho na TV: nome do jogo** — cria um pequeno aplicativo local com ícone e banner do jogo. Confirme a instalação no instalador do Android e, se solicitado, permita que o xCLOUD instale esse atalho. Depois, procure o jogo em **Aplicativos**. No Google TV, adicione essa entrada à fileira de apps favoritos usando a própria tela inicial.
- **Solicitar canal de favoritos na tela inicial** — publica o canal **Favoritos xCLOUD** e solicita autorização do sistema. Sua exibição depende do launcher. O app verifica a autorização e informa quando o canal não ficou visível.
- **Pedir atalho ao launcher** — disponível quando a tela inicial oferece a API de atalhos fixados. O pedido ainda precisa ser aceito pelo launcher.

Ao abrir um atalho, o xCLOUD verifica o jogo no catálogo da conta e segue diretamente à fila, concluindo o login antes se necessário. Os atalhos não contêm credenciais. Os atalhos instaláveis podem ser removidos como qualquer aplicativo da TV.

A versão 0.29 corrige o campo de alinhamento dos APKs gerados e verifica a assinatura e a leitura do pacote pelo Android antes de abrir o instalador. Se uma tentativa anterior apresentou erro de análise, atualize o xCLOUD e crie novamente o atalho em **Configurações → Atalhos dos favoritos**. As identidades dos atalhos existentes são preservadas.

Com sessão salva, o app mostra a preparação do jogo enquanto restaura a conexão, sem exibir a tela de login nem montar a grade inteira. A fila usa a capa em cache ou consulta somente a arte do jogo escolhido quando ela falta. Uma sessão que exige renovação continua solicitando login pelo QR Code. Veja os [ajustes de menu, atalhos e entrada automática da versão 0.26](docs/device-aware-startup-0.26.md).

## Compilar

O projeto usa **JDK 21 completo, Gradle 8.7 e Android SDK 34**. Prepare a [assinatura persistente](docs/stable-signing-0.16.md) antes de gerar o APK principal.

```bash
gradle --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

O módulo `shortcut` gera o modelo sem assinatura usado nos atalhos locais; ele é incorporado automaticamente ao aplicativo. Cada aparelho assina seus atalhos com uma chave própria do Android Keystore, separada da chave de atualização do xCLOUD.

O pipeline valida testes, compatibilidade de APIs Android, assinatura do APK, pacotes de controle, fluxo de autenticação/sessão, geração dos atalhos e shaders de imagem. As capturas da interface podem ser reproduzidas pelos testes de UI. Consulte a [validação do toque no celular e otimização do catálogo na versão 0.25](docs/catalog-performance-0.25.md), o [retorno do teste no celular e ajustes da versão 0.24](docs/touch-source-and-scaling-0.24.md), a [comparação direta com o SDK oficial na versão 0.23](docs/native-touch-official-sdk.md), o [diagnóstico nativo da versão 0.22](docs/native-diagnostics-0.22.md), a [análise de HDR e toque da versão 0.21](docs/hdr-native-touch-0.21.md), os [gestos e câmera da versão 0.20](docs/mobile-huds-0.20.md) e o [modo de compatibilidade da versão 0.18](docs/tv-compatibility-0.18.md) para a implementação e os limites de validação.

---

Cliente independente, sem afiliação com Microsoft ou Xbox. Nomes, marcas e artes dos jogos pertencem aos respectivos titulares.
