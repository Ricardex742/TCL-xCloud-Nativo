# Catálogo inspirado no template fornecido

A tela após o login foi substituída por uma interface Android nativa, com fundo carvão e roxo, faixas diagonais, fonte Barlow Condensed, cabeçalho do jogo em foco, perfil à direita, abas largas e capas verticais em seis colunas. O título usa branco e amarelo; seleção de jogo e categoria usam contorno violeta. A fonte é distribuída com sua licença OFL em `app/src/main/assets/fonts/`.

A referência tem proporção 1280×948. Em 16:9, o cabeçalho é compactado para manter duas fileiras completas acima do rodapé, preservando a proporção das capas. A grade continua rolando para os demais jogos. O catálogo não é limitado aos primeiros 120 resultados. As capas são carregadas em segundo plano, com até quatro requisições simultâneas, cache de imagens de 16 MiB, cache HTTP de 64 MiB e cancelamento das requisições de células recicladas. Falha de imagem mantém o nome legível e o jogo selecionável.

## Controle

| Comando | Catálogo |
| --- | --- |
| Direcional | Navegar pelas capas e abas; o cabeçalho acompanha o foco. |
| LB / RB | Categoria anterior / próxima, com retorno às extremidades. |
| A / OK | Selecionar ou iniciar o jogo em foco. |
| B / Voltar | Limpar busca, voltar às abas ou abrir a opção de sair. |
| Y | Adicionar/remover o jogo selecionado dos favoritos. |
| X | Pesquisar em todo o catálogo. |
| Start / perfil | Abrir pesquisa, preferência de vídeo, páginas da conta, atualização do catálogo e troca de conta. |

Durante uma partida, os comandos continuam enviados ao Xbox, incluindo Start/Select separados e a combinação Start+Select para as estatísticas. O catálogo só recebe esses atalhos enquanto está visível. Cancelar ou terminar uma sessão restaura o foco no catálogo.

## Origem dos dados

- **Jogos jogáveis:** continuam vindo de `/v2/titles` autenticado. Metadados públicos nunca tornam um título ausente desse catálogo jogável. O ID original de streaming é preservado.
- **Nomes, capas e ícones:** `catalog.gamepass.com/v3/products`, em lotes de até 50 produtos, mercado BR e idioma pt-BR. A forma `Image_Poster.URL` / `Image_Tile.URL` foi conferida no [Greenlight](https://github.com/unknownskl/greenlight/blob/main/packages/desktop/main/helpers/titlemanager.ts). Esse serviço público não recebe tokens GSSV/XSTS.
- **Mais jogados / adições recentes:** galerias públicas SIGL de popularidade e adições, cruzadas com os jogos realmente disponíveis. IDs conferidos no [Better xCloud](https://github.com/redphx/better-xcloud/blob/main/src/enums/game-pass-gallery.ts). Se a popularidade estiver indisponível, o rodapé identifica o histórico local como “Mais jogados neste aparelho”. Falha em adições recentes gera uma mensagem; não inventa datas ou uma ordenação alfabética como se fosse recente.
- **Favoritos / jogados recentemente:** salvos neste aparelho por conta, com chave derivada do hash do usuário. Entrar com outra conta não mistura esses dados. Uma sessão só entra no histórico quando o primeiro quadro é renderizado; cancelar uma fila não conta como jogo iniciado.
- **Nome, gamertag e avatar:** grant XSTS opcional para `http://xboxlive.com`, seguido do perfil Xbox. Esse grant não substitui o GSSV nem o console transfer token. Falha na consulta não impede abrir jogos.
- **Assinatura:** mostra um nível apenas quando `userSubscriptions` do catálogo o informa de forma reconhecida. O offering `xgpuweb`, uma credencial válida ou acesso ao Fortnite não comprovam Ultimate nem Free. Sem confirmação, o selo mostra `ASSINATURA —`.
- **Microsoft Rewards:** o login atual não fornece um saldo Rewards confirmado. O cabeçalho mostra `— pts`; o menu abre a página oficial para consulta. Gamerscore não é Rewards. Nenhum saldo ilustrativo é enviado à tela de produção.

## Validação

Os testes de interface usam Robolectric com renderização nativa de Canvas, sem adicionar uma Activity de demonstração ao APK. Exercitam foco direcional, início pelo botão A, categorias, busca além do 120º jogo e persistência isolada por conta. Exportam PNGs em `app/build/reports/dashboard/` nos tamanhos 1280×948, 1920×1080 e 3840×2160; verificam seis capas uniformes e duas fileiras completas. As capturas usam dados fictícios do template e placeholders, sem acessar uma conta nem baixar capas durante os testes. Não representam login/perfil/capas validados contra a conta real do usuário.

O Actions salva essas capturas junto aos relatórios de testes/lint, além do artefato do APK. No aparelho, conferir a obtenção de capas/perfil, todas as abas com LB/RB, busca, favoritos após fechar/reabrir, troca de conta e retorno ao catálogo após uma partida. A aparência depende do catálogo e dos dados reais da conta; os jogos, foto e valores ilustrativos da imagem não foram fixados no aplicativo.
