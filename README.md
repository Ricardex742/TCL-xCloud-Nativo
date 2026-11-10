<div align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_logo.png" width="112" alt="Logo xCLOUD" />
  <h1>xCLOUD</h1>
  <p><strong>Seus jogos. Na sua tela.</strong></p>
  <p>Cliente Android independente para Xbox Cloud Gaming, com transmissão nativa e interface para Android TV, Google TV e celulares.</p>
  <p><a href="#conta-e-biblioteca">Biblioteca</a> · <a href="#transmissão-e-fila">Transmissão</a> · <a href="#imagem-e-tela">Imagem</a> · <a href="#controles-e-periféricos">Controles</a> · <a href="#controles-de-toque">Toque</a><br /><a href="#áudio-e-microfone">Áudio</a> · <a href="#rede-e-preferências">Rede</a> · <a href="#estatísticas-e-diagnósticos">Diagnósticos</a> · <a href="#tela-inicial-da-tv">Atalhos na TV</a> · <a href="#compatibilidade">Compatibilidade</a></p>
</div>

![Voltar a jogar no xCLOUD](docs/screenshots/return-to-play.png)

## Telas do aplicativo

As imagens abaixo mostram a interface do app com perfil e dados de demonstração. O QR Code é ilustrativo; as prévias dos HUDs usam fundo neutro.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/transition-startup.png" alt="Abertura — Restauração da sessão com a identidade do aplicativo." /><br /><strong>Abertura</strong> — Restauração da sessão com a identidade do aplicativo.</td>
    <td width="50%"><img src="docs/screenshots/settings.png" alt="Configurações por categoria — Vídeo, imagem, rede, controles, áudio, diagnósticos e conta." /><br /><strong>Configurações por categoria</strong> — Vídeo, imagem, rede, controles, áudio, diagnósticos e conta.</td>
  </tr>
</table>

## Conta e biblioteca

- Login Microsoft por QR Code e restauração da sessão salva.
- Perfil Xbox com avatar e identificação da assinatura, quando informados pelo serviço.
- **Voltar a jogar** com o histórico da conta no Xbox Cloud Gaming.
- Favoritos separados por conta e salvos no aparelho.
- **Game Pass**, **Transmita seu jogo**, **Adições recentes** e **Todos os jogos**.
- Catálogos oficiais com nomes, capas e identificação das edições.
- Selos de Game Pass e Xbox Series X|S conforme a inclusão e otimização informadas pelo catálogo.
- Pesquisa por nome, tolerando diferenças de acentos, pontuação e marcas.
- Navegação por controle ou toque e cache de imagens do catálogo.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/login.png" alt="Conta Microsoft — Tela de entrada do aplicativo." /><br /><strong>Conta Microsoft</strong> — Tela de entrada do aplicativo.</td>
    <td width="50%"><img src="docs/screenshots/qr-login.png" alt="Login por QR Code — Autorização pelo celular." /><br /><strong>Login por QR Code</strong> — Autorização pelo celular.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/catalog.png" alt="Biblioteca — Capas, favoritos e categorias de jogos." /><br /><strong>Biblioteca</strong> — Capas, favoritos e categorias de jogos.</td>
    <td width="50%"><img src="docs/screenshots/search.png" alt="Pesquisa — Busca por nome usando o teclado." /><br /><strong>Pesquisa</strong> — Busca por nome usando o teclado.</td>
  </tr>
</table>

## Transmissão e fila

- Reprodução nativa por WebRTC/H.264, com preferência por decodificação de hardware.
- Perfis solicitados de **720p, 1080p e 1080p HQ**.
- Modos **Menor atraso**, **Adaptativo** e **Compatibilidade — TVs mais fracas**.
- Opção **H.264 leve — Baseline (720p)**.
- Fila com arte do jogo, estimativa do serviço, tempo aguardado e cancelamento.
- Recuperação de vídeo por solicitação de quadro-chave, decoder e superfície de exibição.
- Menu da partida com ajustes e desconexão para retornar ao catálogo.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/queue.png" alt="Fila — Arte do jogo, estimativa e etapas da conexão." /><br /><strong>Fila</strong> — Arte do jogo, estimativa e etapas da conexão.</td>
    <td width="50%"><img src="docs/screenshots/video-settings.png" alt="Vídeo e transmissão — Modo de vídeo, codec e resolução." /><br /><strong>Vídeo e transmissão</strong> — Modo de vídeo, codec e resolução.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/stream-resolution.png" alt="Resolução — Perfis 720p, 1080p e 1080p HQ." /><br /><strong>Resolução</strong> — Perfis 720p, 1080p e 1080p HQ.</td>
    <td width="50%"><img src="docs/screenshots/video-modes.png" alt="Modo de vídeo — Menor atraso, adaptativo e compatibilidade." /><br /><strong>Modo de vídeo</strong> — Menor atraso, adaptativo e compatibilidade.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/game-menu.png" alt="Menu da partida — Ajustes, estatísticas e desconexão." /><br /><strong>Menu da partida</strong> — Ajustes, estatísticas e desconexão.</td>
    <td width="50%"><img src="docs/screenshots/transition-exit.png" alt="Saída da partida — Retorno ao catálogo com a arte do jogo." /><br /><strong>Saída da partida</strong> — Retorno ao catálogo com a arte do jogo.</td>
  </tr>
</table>

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

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/image-settings.png" alt="Imagem e tela — Opções de proporção, escala e FSR." /><br /><strong>Imagem e tela</strong> — Opções de proporção, escala e FSR.</td>
    <td width="50%"><img src="docs/screenshots/image.png" alt="Ajustes básicos — Nitidez e saturação do vídeo." /><br /><strong>Ajustes básicos</strong> — Nitidez e saturação do vídeo.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/fsr-settings.png" alt="AMD FSR — Explicação e ativação do filtro experimental." /><br /><strong>AMD FSR</strong> — Explicação e ativação do filtro experimental.</td>
    <td width="50%"><img src="docs/screenshots/fsr-output.png" alt="Saída do FSR — Automático, 1440p e 4K experimental." /><br /><strong>Saída do FSR</strong> — Automático, 1440p e 4K experimental.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/image-adjustments.png" alt="Ajustes da imagem — RCAS, nitidez, brilho, contraste e saturação." /><br /><strong>Ajustes da imagem</strong> — RCAS, nitidez, brilho, contraste e saturação.</td>
    <td width="50%"><img src="docs/screenshots/aspect-ratio.png" alt="Proporção — Formato original, proporções fixas, esticar e zoom." /><br /><strong>Proporção</strong> — Formato original, proporções fixas, esticar e zoom.</td>
  </tr>
</table>

## Controles e periféricos

- Controles USB, Bluetooth e dongles reconhecidos pelo Android.
- Perfis por controle com zonas mortas, sensibilidade, curvas dos analógicos, inversão, gatilhos e remapeamento.
- Coop local com até quatro controles, quando aceito pelo jogo e pela sessão.
- Mouse e teclado nativos nos jogos compatíveis ou emulação de controle.
- Giroscópio opcional para mira, com sensibilidade e ativação por LT, LB ou contínua.
- Opção experimental de giroscópio junto ao toque nativo Xbox.
- Vibração nos dispositivos compatíveis, teste USB/XInput opcional e vibração do celular no toque.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/controls.png" alt="Controles — Preferências de coop local, vibração e toque." /><br /><strong>Controles</strong> — Preferências de coop local, vibração e toque.</td>
    <td width="50%"><img src="docs/screenshots/physical-controls.png" alt="Controle físico — Acesso à calibração e ao remapeamento." /><br /><strong>Controle físico</strong> — Acesso à calibração e ao remapeamento.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/mouse-keyboard.png" alt="Mouse e teclado — Entrada nativa ou emulação de controle." /><br /><strong>Mouse e teclado</strong> — Entrada nativa ou emulação de controle.</td>
    <td width="50%"><img src="docs/screenshots/gyro-settings.png" alt="Giroscópio — Modo de mira e sensibilidade do movimento." /><br /><strong>Giroscópio</strong> — Modo de mira e sensibilidade do movimento.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/rumble-settings.png" alt="Vibração — Opções para o controle e para o celular." /><br /><strong>Vibração</strong> — Opções para o controle e para o celular.</td>
    <td width="50%"><img src="docs/screenshots/native-touch-gyro.png" alt="Giroscópio com toque nativo — Ativação experimental quando há sensor." /><br /><strong>Giroscópio com toque nativo</strong> — Ativação experimental quando há sensor.</td>
  </tr>
</table>

## Controles de toque

- Controle Xbox virtual e layouts para tiro, corrida, futebol e ação.
- HUDs para Call of Duty, Forza, EA SPORTS FC/FIFA e Hades.
- Movimento com múltiplos dedos, câmera por arrasto e pedais nos jogos de corrida.
- Alternância manual entre ataque e defesa no futebol.
- Editor de posição, tamanho, opacidade, comandos combinados e gestos por jogo.
- Toque nativo Xbox experimental nos títulos compatíveis, incluindo Fortnite.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/hud-call-of-duty.png" alt="Tiro — Mirar, disparar, recarregar e usar granadas." /><br /><strong>Tiro</strong> — Mirar, disparar, recarregar e usar granadas.</td>
    <td width="50%"><img src="docs/screenshots/hud-forza.png" alt="Corrida — Direção, acelerador, freio e marchas." /><br /><strong>Corrida</strong> — Direção, acelerador, freio e marchas.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/hud-ea-fc.png" alt="Futebol em ataque — Passes, chute, enfiada e corrida." /><br /><strong>Futebol em ataque</strong> — Passes, chute, enfiada e corrida.</td>
    <td width="50%"><img src="docs/screenshots/hud-ea-fc-defense.png" alt="Futebol em defesa — Marcação, bote, carrinho e troca de jogador." /><br /><strong>Futebol em defesa</strong> — Marcação, bote, carrinho e troca de jogador.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/hud-hades.png" alt="Hades — Ataque, especial, feitiço e esquiva." /><br /><strong>Hades</strong> — Ataque, especial, feitiço e esquiva.</td>
    <td width="50%"><img src="docs/screenshots/hud-editor.png" alt="Editor — Posição, tamanho, comandos combinados e gestos." /><br /><strong>Editor</strong> — Posição, tamanho, comandos combinados e gestos.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/hud-settings.png" alt="Ajustes do HUD — Layout, opacidade e sensibilidade por jogo." /><br /><strong>Ajustes do HUD</strong> — Layout, opacidade e sensibilidade por jogo.</td>
    <td width="50%"><img src="docs/screenshots/touch-modes.png" alt="Modo de toque — Detecção automática, HUD virtual ou toque nativo." /><br /><strong>Modo de toque</strong> — Detecção automática, HUD virtual ou toque nativo.</td>
  </tr>
</table>

As posições, tamanhos, ações e gestos podem ser ajustados por jogo. No futebol, a alternância entre ataque e defesa é manual.

## Áudio e microfone

- Reprodução estéreo com gerenciamento de foco de áudio do Android.
- Ganho ajustável até 300%, com redução adaptativa de picos.
- Microfone opcional com permissão ao ativar e início em mudo.
- Controle de mudo durante a sessão.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/audio-settings.png" alt="Áudio e microfone — Ganho local e disponibilidade do microfone." /><br /><strong>Áudio e microfone</strong> — Ganho local e disponibilidade do microfone.</td>
    <td width="50%"><img src="docs/screenshots/microphone-settings.png" alt="Microfone opcional — Habilitação para as próximas partidas, iniciando em mudo." /><br /><strong>Microfone opcional</strong> — Habilitação para as próximas partidas, iniciando em mudo.</td>
  </tr>
  <tr>
    <td colspan="2" align="center"><img src="docs/screenshots/audio-gain.png" width="70%" alt="Ganho de áudio — Seleção do volume local até 300%." /><br /><strong>Ganho de áudio</strong> — Seleção do volume local até 300%.</td>
  </tr>
</table>

## Rede e preferências

- Configurações organizadas por categoria.
- Teto de bitrate solicitado em Automático ou entre 1 e 50 Mbps, salvo por jogo.
- Escolha entre as regiões oferecidas à conta.
- Idioma solicitado à sessão configurável por jogo.
- Modo automático IPv4/IPv6 e opções de preferência por uma família de rede.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/network-settings.png" alt="Rede e sessão — Família de rede e opções por jogo." /><br /><strong>Rede e sessão</strong> — Família de rede e opções por jogo.</td>
    <td width="50%"><img src="docs/screenshots/ip-preference.png" alt="IPv4 e IPv6 — Automático e preferências por família de rede." /><br /><strong>IPv4 e IPv6</strong> — Automático e preferências por família de rede.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/session-options.png" alt="Opções por jogo — Bitrate, região e idioma da sessão." /><br /><strong>Opções por jogo</strong> — Bitrate, região e idioma da sessão.</td>
    <td width="50%"><img src="docs/screenshots/bitrate-options.png" alt="Teto de bitrate — Automático ou limite manual solicitado." /><br /><strong>Teto de bitrate</strong> — Automático ou limite manual solicitado.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/server-region.png" alt="Região do servidor — Exemplo com uma região oferecida à conta de demonstração." /><br /><strong>Região do servidor</strong> — Exemplo com uma região oferecida à conta de demonstração.</td>
    <td width="50%"><img src="docs/screenshots/session-language.png" alt="Idioma da sessão — Idioma solicitado ao jogo, separado da interface." /><br /><strong>Idioma da sessão</strong> — Idioma solicitado ao jogo, separado da interface.</td>
  </tr>
</table>

## Estatísticas e diagnósticos

- Resolução e codec recebidos, FPS decodificados e bitrate.
- RTT, jitter, perda de pacotes, buffer, descartes e tempo de decodificação.
- Estatísticas durante a partida com ativação manual.
- Resumo local pós-partida com médias, picos, pausas observadas e recuperações.
- Motivo do encerramento em destaque, quando identificado.
- Último diagnóstico salvo no aparelho e exibição automática opcional.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/session-report.png" alt="Resumo pós-partida — Métricas de rede e vídeo com dados de demonstração." /><br /><strong>Resumo pós-partida</strong> — Métricas de rede e vídeo com dados de demonstração.</td>
    <td width="50%"><img src="docs/screenshots/diagnostic-settings.png" alt="Preferências do diagnóstico — Abertura automática e consulta ao último resumo." /><br /><strong>Preferências do diagnóstico</strong> — Abertura automática e consulta ao último resumo.</td>
  </tr>
</table>

## Tela inicial da TV

- Atalhos instaláveis para abrir jogos diretamente no xCLOUD.
- Canal de favoritos nos launchers compatíveis.
- Solicitação de atalhos fixados quando oferecida pelo sistema.
- Restauração da sessão e entrada na fila ao abrir um atalho.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/library-settings.png" alt="Biblioteca e conta — Acesso ao menu Atalhos dos favoritos." /><br /><strong>Biblioteca e conta</strong> — Acesso ao menu Atalhos dos favoritos.</td>
    <td width="50%"><img src="docs/screenshots/transition-shortcut.png" alt="Entrada por atalho — Arte do jogo durante a preparação da sessão." /><br /><strong>Entrada por atalho</strong> — Arte do jogo durante a preparação da sessão.</td>
  </tr>
</table>

## Compatibilidade

- Android 7.0 ou superior, incluindo Android TV, Google TV e celulares compatíveis.
- Interface adaptada para navegação pelo controle e por toque.
- Recursos experimentais e suporte a periféricos dependem do aparelho, do driver, do jogo e do serviço.
- A disponibilidade dos jogos depende da conta, região, assinatura ou licença exigida pelo Xbox Cloud Gaming.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/compatibility.png" alt="Modo de compatibilidade — Opção de vídeo para dispositivos mais modestos." /><br /><strong>Modo de compatibilidade</strong> — Opção de vídeo para dispositivos mais modestos.</td>
    <td width="50%"><img src="docs/screenshots/light-codec.png" alt="H.264 leve — Perfil Baseline com solicitação de 720p." /><br /><strong>H.264 leve</strong> — Perfil Baseline com solicitação de 720p.</td>
  </tr>
</table>

## Licença

O código original do projeto está sob a [licença MIT](LICENSE). Bibliotecas, fontes e componentes de terceiros mantêm suas próprias licenças; consulte os [avisos de terceiros](THIRD_PARTY_NOTICES.md). A licença do código não concede direitos sobre nomes, marcas ou artes de jogos de terceiros.

---

Cliente independente, sem afiliação com Microsoft ou Xbox. Nomes, marcas e artes dos jogos pertencem aos respectivos titulares.
