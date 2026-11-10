<div align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_logo.png" width="112" alt="Logo xCLOUD" />
  <h1>xCLOUD</h1>
  <p><strong>Seus jogos. Na sua tela.</strong></p>
  <p>Cliente Android independente para Xbox Cloud Gaming, com transmissão nativa e interface para Android TV, Google TV e celulares.</p>
</div>

![Voltar a jogar no xCLOUD](docs/screenshots/return-to-play.png)

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

Capturas da interface com perfil e dados de demonstração. O QR Code é ilustrativo.

## Conta e biblioteca

- Login Microsoft por QR Code e restauração da sessão salva.
- Perfil Xbox com avatar e identificação da assinatura, quando informados pelo serviço.
- **Voltar a jogar** com o histórico da conta no Xbox Cloud Gaming.
- Favoritos separados por conta e salvos no aparelho.
- **Game Pass**, **Transmita seu jogo**, **Adições recentes** e **Todos os jogos**.
- Catálogos oficiais com nomes, capas e identificação das edições.
- Pesquisa por nome, tolerando diferenças de acentos, pontuação e marcas.
- Navegação por controle ou toque e cache de imagens do catálogo.

## Transmissão e fila

- Reprodução nativa por WebRTC/H.264, com preferência por decodificação de hardware.
- Perfis solicitados de **720p, 1080p e 1080p HQ**.
- Modos **Menor atraso**, **Adaptativo** e **Compatibilidade — TVs mais fracas**.
- Opção **H.264 leve — Baseline (720p)**.
- Fila com arte do jogo, estimativa do serviço, tempo aguardado e cancelamento.
- Recuperação de vídeo por solicitação de quadro-chave, decoder e superfície de exibição.
- Menu da partida com ajustes e desconexão para retornar ao catálogo.

### Gameplay

![Gameplay transmitido pelo xCLOUD em uma TV](docs/screenshots/gameplay.jpg)

## Imagem e tela

- Upscaling nativo do Android.
- Proporção original, 16:9, 18:9, 21:9, 16:10, 4:3, esticar e zoom.
- Ajustes de nitidez, saturação, brilho e contraste durante a partida.
- **AMD FSR 1.0.2 experimental**, com ampliação EASU e nitidez RCAS; em resolução nativa, aplica somente RCAS.
- Saída do FSR em **Automático, 1440p ou 4K experimental**, com proteção para retorno à escala padrão.
- Preferência por uma taxa de atualização compatível com a apresentação do vídeo.
- Verificação do suporte HDR anunciado pela tela; a transmissão atual utiliza SDR.

## Controles e periféricos

- Controles USB, Bluetooth e dongles reconhecidos pelo Android.
- Perfis por controle com zonas mortas, sensibilidade, curvas dos analógicos, inversão, gatilhos e remapeamento.
- Coop local com até quatro controles, quando aceito pelo jogo e pela sessão.
- Mouse e teclado nativos nos jogos compatíveis ou emulação de controle.
- Giroscópio opcional para mira, com sensibilidade e ativação por LT, LB ou contínua.
- Opção experimental de giroscópio junto ao toque nativo Xbox.
- Vibração nos dispositivos compatíveis, teste USB/XInput opcional e vibração do celular no toque.

## Controles de toque

- Controle Xbox virtual e layouts para tiro, corrida, futebol e ação.
- HUDs para Fortnite, Call of Duty, Forza, EA SPORTS FC/FIFA e Hades.
- Movimento com múltiplos dedos, câmera por arrasto e pedais nos jogos de corrida.
- Alternância manual entre ataque e defesa no futebol.
- Editor de posição, tamanho, opacidade, comandos combinados e gestos por jogo.
- Toque nativo Xbox experimental nos títulos compatíveis.

### HUDs e editor

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

Prévias dos controles sobre fundo neutro. Os layouts e comandos podem ser ajustados por jogo.

## Áudio e microfone

- Reprodução estéreo com gerenciamento de foco de áudio do Android.
- Ganho ajustável até 300%, com redução adaptativa de picos.
- Microfone opcional com permissão ao ativar e início em mudo.
- Controle de mudo durante a sessão.

## Rede e preferências

- Configurações organizadas por categoria.
- Teto de bitrate solicitado em Automático ou entre 1 e 50 Mbps, salvo por jogo.
- Escolha entre as regiões oferecidas à conta.
- Idioma solicitado à sessão configurável por jogo.
- Modo automático IPv4/IPv6 e opções de preferência por uma família de rede.

## Estatísticas e diagnósticos

![Tela de diagnóstico pós-partida com dados de demonstração](docs/screenshots/session-report.png)

- Resolução e codec recebidos, FPS decodificados e bitrate.
- RTT, jitter, perda de pacotes, buffer, descartes e tempo de decodificação.
- Estatísticas durante a partida com ativação manual.
- Resumo local pós-partida com médias, picos, pausas observadas e recuperações.
- Motivo do encerramento em destaque, quando identificado.
- Último diagnóstico salvo no aparelho e exibição automática opcional.

## Tela inicial da TV

- Atalhos instaláveis para abrir jogos diretamente no xCLOUD.
- Canal de favoritos nos launchers compatíveis.
- Solicitação de atalhos fixados quando oferecida pelo sistema.
- Restauração da sessão e entrada na fila ao abrir um atalho.

## Compatibilidade

- Android 7.0 ou superior, incluindo Android TV, Google TV e celulares compatíveis.
- Interface adaptada para navegação pelo controle e por toque.
- Recursos experimentais e suporte a periféricos dependem do aparelho, do driver, do jogo e do serviço.
- A disponibilidade dos jogos depende da conta, região, assinatura ou licença exigida pelo Xbox Cloud Gaming.

## Licença

O código original do projeto está sob a [licença MIT](LICENSE). Bibliotecas, fontes e componentes de terceiros mantêm suas próprias licenças; consulte os [avisos de terceiros](THIRD_PARTY_NOTICES.md). A licença do código não concede direitos sobre nomes, marcas ou artes de jogos de terceiros.

---

Cliente independente, sem afiliação com Microsoft ou Xbox. Nomes, marcas e artes dos jogos pertencem aos respectivos titulares.
