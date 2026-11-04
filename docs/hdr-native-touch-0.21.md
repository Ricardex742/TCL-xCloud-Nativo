> Documento histórico da versão 0.21. O cliente WebView foi removido na 0.22; a transmissão permanece no motor nativo. Veja [diagnóstico nativo 0.22](native-diagnostics-0.22.md).

# HDR10, toque oficial e pesquisa — versão 0.21

## Resultado da análise HDR10

O suporte anunciado pela tela é consultado por `Display.getHdrCapabilities()`, disponível desde a API 24. Esse dado descreve o display, e não confirma que a transmissão ou a janela estejam em HDR.

O motor nativo atual seleciona H.264 nas capacidades do decoder WebRTC, com perfis de 8 bits, e inicializa o renderer com `EglBase.CONFIG_PLAIN`. Os shaders e superfícies utilizam o caminho RGB de 8 bits. Não há negociação de um fluxo HDR10 nem uma cadeia de apresentação BT.2020/PQ de 10 bits.

Por isso, HDR10 real não foi ativado. Solicitar uma janela HDR sem uma fonte e uma reprodução compatíveis poderia apenas mudar a interpretação das cores do SDR. Saturação, nitidez e processamento da TV não recuperam os dados de luminância e cor de um fluxo HDR.

Foi adicionado **Configurações → HDR10: verificar tela e transmissão**. O relatório mostra se HDR10 foi anunciado pelo Android e explica o limite do motor. A leitura não altera o modo de cor da janela. Isso não é uma conclusão sobre todos os clientes, codecs ou capacidades presentes ou futuras do serviço Xbox; aplica-se ao caminho nativo implementado neste projeto.

## Referências de toque e limites da investigação

Foram comparadas implementações públicas de interoperabilidade:

- [Better xCloud — configuração de entrada](https://github.com/redphx/better-xcloud/blob/f8397043f6d2148d2345d508902a38c69cf1ee20/src/utils/xcloud-interceptor.ts#L217): exposição de `enableTouchInput` e `maxTouchPoints` na configuração utilizada pelo SDK web.
- [Better xCloud — identificação dos títulos com NativeTouch](https://github.com/redphx/better-xcloud/blob/f8397043f6d2148d2345d508902a38c69cf1ee20/src/utils/bx-exposed.ts#L151).
- [Better xCloud — dimensões do player nativo de toque](https://github.com/redphx/better-xcloud/blob/f8397043f6d2148d2345d508902a38c69cf1ee20/src/modules/stream-player-manager.ts#L114).
- [XStreaming — serialização dos contatos](https://github.com/Geocld/XStreaming/blob/06ef1c7ee01d09cf9bb2a97fc4895da85e8488a8/src/webrtc/Packet/index.ts#L303).
- [XStreaming — metadados e características do cliente](https://github.com/Geocld/XStreaming/blob/06ef1c7ee01d09cf9bb2a97fc4895da85e8488a8/src/webrtc/Channel/Message.ts#L215).

O bloco de ponteiro comparado usa 20 bytes por contato: altura e largura de 16 bits, pressão de 8 bits, twist de 16 bits, ID de 32 bits, X/Y de 32 bits e tipo de evento de 8 bits. Os campos multibyte desse bloco são little-endian. Essa estrutura já era compatível com a referência; não foi identificada uma divergência demonstrada que justificasse reescrevê-la.

O cliente oficial possui o SDK, o tratamento de PointerEvents do navegador, a identificação de dispositivo e a lógica de ativação por título. Os projetos públicos acima ajudam a analisar o protocolo, mas não substituem a observação de uma sessão oficial. O ambiente de desenvolvimento não tinha acesso de rede ao cliente em xbox.com e não havia um celular conectado. Não foi feita uma captura ao vivo do SDK nem demonstrada a causa única da falha do Fortnite.

## Ajustes do motor nativo experimental

- Sessões escolhidas como toque nativo usam identificação Android em vez dos seletores desktop/TV. A resolução e o bitrate continuam sendo solicitados, mas o perfil do serviço pode entregar outra qualidade.
- Os IDs Android 0–31 são adaptados a IDs estáveis positivos 1–32. A associação permanece até a soltura/cancelamento do contato. Isso aproxima a representação dos PointerEvents de navegador; não prova que o servidor exigia IDs positivos.
- Quando o canal de entrada informa suas dimensões, os valores são utilizados para os contatos e reapresentados nas características do cliente. Há sincronização também no início do envio.
- O diagnóstico informa dimensões e quantidade de eventos aceitos pelo envio local. O contador não confirma que o jogo remoto os processou.

Permanecem a pressão substituta para aparelhos sem sensor, múltiplos dedos, cancelamento e soltura dos contatos, metadados de capacidade e a ativação de toque do handshake. O modo continua **experimental** até validação em uma sessão real.

## Opção do cliente oficial em WebView

Foi incluída uma alternativa explícita para celulares: **Toque oficial Xbox • WebView**. Ela carrega o cliente hospedado pela Microsoft diretamente no jogo e deixa o SDK oficial receber os eventos DOM produzidos pelo Chromium. Não há um overlay de tela inteira traduzindo esses eventos para nosso protocolo, cópia do SDK proprietário ou injeção de tokens/cookies da sessão nativa.

O identificador de produto do catálogo é validado antes de montar a URL HTTPS de lançamento. Quando ausente, a alternativa é a busca oficial. O login da página é independente do login nativo. O fluxo aceita a navegação principal nos hosts do Xbox e de autenticação Microsoft previstos; links HTTPS externos iniciados pelo usuário vão para o navegador. O modo não cria uma ponte JavaScript para o Android, não permite acesso a arquivos/conteúdo local, não aceita conteúdo misto e não concede microfone ou outras permissões da página.

O user agent conserva a versão real do Chromium instalado e a identidade Android/mobile, retirando os marcadores específicos de WebView. A compatibilidade ainda depende da versão instalada e dos requisitos do site. Existe opção para abrir no navegador quando o cliente embutido não funcionar.

### Desempenho e ciclo de vida

- Não é o modo padrão e não é oferecido como substituto do motor de TV. O Chromium só é inicializado ao abrir essa opção.
- Usa o WebView do sistema, sem incorporar outro Chromium ao APK.
- A Activity não é exportada e roda no processo `:official_web`, separado do motor nativo.
- Consultas do catálogo são pausadas, solicitações de capas são canceladas e seu cache é liberado antes da abertura.
- Não há filtro de imagem, HUD de gameplay ou renderização adicional do nosso motor sobre o cliente oficial.
- Aceleração de hardware permanece habilitada; o decoder efetivamente escolhido é responsabilidade do Chromium. Pré-renderização fora da tela fica desativada.
- Views e janelas de autenticação são destruídas no encerramento. Os cookies do login web são persistidos; o Android gerencia os processos em cache.

Essas medidas reduzem o custo da integração, mas não tornam o cliente oficial tão leve quanto o motor nativo. Não há benchmark em uma TV ou celular fraco. Para aparelhos limitados, a recomendação continua sendo o motor nativo com seu perfil de compatibilidade.

O menu **⋯** oferece retorno, histórico da página, recarga, navegador externo e saída para o catálogo. Para encerrar a alocação cloud, use **Sair do jogo** no menu do próprio site Xbox. Fechar a Activity interrompe o cliente local e não é apresentado como confirmação de encerramento da sessão no servidor.

## Lupa e verificação

A lupa vetorial fica no cabeçalho do catálogo, com área de toque maior que a placa visível, foco próprio e navegação pelo controle. Ela abre a pesquisa existente e solicita o teclado em aparelhos com touchscreen. O botão X mantém o mesmo caminho.

A validação local passou em **206 testes**, mais lint de APIs Android e verificação de assinatura. Os casos novos verificam pesquisa pelo teclado, múltiplos contatos, identificação da sessão, política de URLs, configurações e isolamento da WebView e ausência de ativação HDR para o fluxo SDR. Os testes da WebView usam o ambiente simulado do Robolectric; não executam a página Xbox nem demonstram gameplay ou desempenho real.
