# xCLOUD: assinatura, catálogo, imagem e identidade (0.14.0)

## Assinatura automática e Game Pass

O catálogo autenticado declara os programas disponíveis para cada jogo (`details.programs`) e os programas/assinaturas efetivos do usuário (`details.userPrograms`, `details.userSubscriptions`). A versão anterior ignorava `userPrograms` e não reconhecia as formas `XGPULTIMATE` e `GPULTIMATE`. Agora essas formas e as equivalentes Premium, Standard, Essential e Core são reconhecidas. Só os campos do usuário identificam a assinatura; a presença de um jogo no catálogo Ultimate não identifica a conta como Ultimate. A atualização posterior de avatar/nome e metadados preserva o nível autenticado da conta.

O formato desses campos pode ser conferido no [exemplo público de resposta do serviço](https://github.com/Geocld/XStreaming/blob/main/docs/2.Web.md#24-get-all-xgpu-cloud-games). O ID da oferta `xgpuweb`, permissões genéricas de lançamento e a informação manual do usuário continuam sem substituir uma declaração de assinatura do serviço. Se não houver declaração reconhecida, a interface mantém o estado não informado.

A aba Game Pass e os selos usam o programa Game Pass declarado para o título, além da galeria oficial. Assim, um título de nuvem pode ser incluído mesmo quando a galeria referencia outra edição/pacote do mesmo jogo. Os IDs de produto são comparados sem diferenciar maiúsculas/minúsculas. Jogos gratuitos marcados como tais são excluídos; propriedade/BYOG e assinatura global do usuário não tornam um jogo Game Pass. Nenhum título é incluído pelo nome Forza ou por uma lista fixa. A aba fica em ordem alfabética e mantém a pesquisa por X. Sem programa confirmado e sem correspondência na galeria, o título permanece fora da aba.

## Proporção e reprodução

Configurações → Proporção oferece **Original**, **16:9** (padrão), **18:9**, **21:9**, **16:10**, **4:3**, **Esticar** e **Zoom**. A escolha é salva para a próxima partida.

- Original mantém a proporção recebida e usa barras quando necessário.
- Proporções fixas ajustam o vídeo à razão escolhida, com possíveis deformações.
- Esticar usa toda a janela, com possível deformação.
- Zoom preenche a janela preservando a proporção, cortando as bordas.

Upscaling desligado continua limitando as dimensões ao tamanho do vídeo na janela. Por isso, ele pode deixar barras também em Esticar/Zoom. Esse controle não altera o processamento proprietário da TV. Os modos de proporção não alteram a resolução pedida ao Xbox.

O renderizador mantém o drawer padrão do WebRTC. Para proporções fixas/Esticar, uma correção da matriz de textura desfaz somente o corte central automático do EglRenderer, preservando rotação, espelhamento e transformação do decoder. Original/Zoom mantêm o caminho normal. Não há leitura de pixels da GPU, filtro pesado ou uma passagem adicional de renderização. Os testes de matriz e geometria não substituem a reprodução no aparelho.

O HUD verde “Vídeo nativo ativo” fica oculto quando chegam os quadros e após uma recuperação. Avisos de recuperação/erro continuam disponíveis. As estatísticas seguem ocultas por padrão e são ativadas manualmente com Start + Select ou INFO/F10.

## Nome e logo

O nome de instalação e a marca nos painéis passam a **xCLOUD**. O pacote Android permanece o mesmo para preservar login e preferências ao atualizar uma instalação compatível. A proposta B, escolhida pelo usuário após a comparação, é o ícone: nuvem simples com caminhos cruzados e símbolo de transmissão. Há variante adaptativa Android 8+, fallback Android 7 e banner para Android TV. O login também mostra o símbolo. As [duas propostas](branding/README.md) permanecem disponíveis para comparação.

## Verificação

Os testes cobrem respostas autenticadas com XGPULTIMATE e GPULTIMATE, atualização do perfil, Game Pass com IDs de edição divergentes, comparação sem diferença de caixa, exclusão de BYOG/F2P, proporções em telas largas, limites com upscaling desligado, correção da matriz sob rotação/espelhamento e seleção/persistência com o controle. O fluxo de QR, controles, fila, recuperação, estatísticas, lint e APK continuam nos checks completos. Capturas dos painéis e login ficam no artefato de relatórios do Actions.

Consultas Microsoft ao vivo e a conta real do usuário não estão disponíveis neste ambiente. No aparelho, conferir a identificação Ultimate, pesquisar Forza Horizon 5/6 na aba Game Pass e comparar Original, Esticar e Zoom numa partida real.
