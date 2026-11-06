# Galerias oficiais Game Pass e jogos comprados

Branch exclusiva destas alterações: `perf/decoder-display-latency-0.31`. Consulta pública feita em **6 de novembro de 2026**, mercado **BR**, idioma **pt-BR**.

## Fontes verificadas e completude

Foram consultadas as páginas oficiais [Game Pass](https://www.xbox.com/pt-BR/play/gallery/game-pass-games) e [Transmita seu próprio jogo](https://www.xbox.com/pt-BR/play/gallery/buy-and-stream). O host `play.xbox.com` retornou 403 ao executor; as páginas oficiais equivalentes em `www.xbox.com/pt-BR/play` responderam 200. A investigação leu os scripts públicos referenciados nessas páginas, sem login ou extração de credenciais.

O cliente oficial associa `AllGamePassGames` ao SIGL `af206485-e87d-4624-9007-cb7f6d0cc42e` e à rota `game-pass-games`; `CloudBYOGAll` ao SIGL `e78d9a61-5ef4-43af-b400-edba1250b18e` e à rota `buy-and-stream`.

As duas listas usam:

```text
GET https://catalog.gamepass.com/sigls/v3
id=<ID da galeria>
market=BR
language=pt-BR
subscriptionContext=none
platformContext=Cloud:XGPUWEB
```

O contexto `none` corresponde à consulta pública, sem uma assinatura personalizada. Não se envia o token Xbox para buscar essas galerias. Usar o SIGL novo na versão v2 sem o contexto cloud poderia incluir produtos de outras plataformas; o app passa a utilizar o contrato v3 observado no cliente oficial.

| Galeria | Produtos únicos na consulta | Metadados encontrados | Oferta XGPUWEB confirmada |
| --- | ---: | ---: | ---: |
| Game Pass | 560 | 560 | 560 |
| Jogos comprados compatíveis | 3.781 | 3.781 | 3.781 |

Os **3.845 produtos distintos** das duas listas foram consultados na API pública de metadados; **496** pertenciam a ambas. As abas não são conjuntos exclusivos: retirar jogos do Game Pass da lista de compra esconderia produtos que a fonte oficial mantém nas duas galerias.

O SIGL antigo do aplicativo (`29a81209-df6f-41fd-a528-2ae6b91f719c`, v2) retornou 559 produtos. A galeria atual inclui também Fortnite (`BT5P2X999VH2`). Por isso, o app não subtrai mais os gratuitos da galeria oficial. Estar nessa categoria não demonstra que o jogo exige pagamento ou uma assinatura específica; essa distinção aparece no texto da interface.

As contagens acima registram a consulta desta data, não constantes de produção. O aplicativo consulta a fonte ao carregar/atualizar o catálogo, sem `top`, corte de 120 itens, lista de nomes fixa ou produtos escolhidos manualmente.

## Metadados e compatibilidade cloud

O site hidrata os produtos por `POST /v3/products` no mesmo domínio, com `market=BR`, `language=pt-BR`, `hydration=RemoteLowJade0` e corpo `{"Products":[...]}`. O serviço exige `MS-CV`, uma correlação de requisição, que não é credencial. O aplicativo gera uma correlação nova por requisição e não registra seu valor.

Os lotes têm até 50 IDs. `ProductTitle`, `Image_Poster`, `Image_Tile` e `XCloudOfferings.XGPUWEB.Programs` são lidos do serviço oficial. A presença de programas nessa oferta demonstra disponibilidade do caminho cloud, **não inclusão no Game Pass** nem propriedade na conta. A classificação Game Pass vem exclusivamente da galeria correspondente.

Produtos sem oferta cloud declarada são removidos da apresentação, como no filtro público examinado do site. Metadados ausentes/inválidos mantêm o produto como não confirmado, com aviso de dados parciais; não são tratados como ausência de jogos ou como compatibilidade comprovada. Nesta auditoria todos os produtos retornaram uma oferta XGPUWEB.

O app reutiliza nomes/capas completos do catálogo nativo e só consulta separadamente o que falta. A hidratação adicional começa após a carga de metadados nativa e respeita a pausa do catálogo durante a partida. Atualizações são agrupadas antes de redesenhar a interface. Não há download antecipado das imagens de todos os jogos: o carregador continua limitado às views anexadas e aos tamanhos exibidos.

## Navegação e início da partida

- **GAME PASS:** mostra os produtos da galeria atual, inclusive edições sem associação local ao catálogo nativo.
- **TRANSMITA SEU JOGO:** aba após Game Pass, com o nome em uma linha e navegação por toque, direcional e LB/RB. Explica que a lista contém jogos compatíveis e não comprova propriedade na conta.
- **TODOS OS JOGOS/pesquisa:** inclui também os produtos das galerias ainda sem associação local; a busca não os perde ao sair de uma categoria.

Uma carta pública tem um `productId`, não um `titleId` inventado. Seu tipo é separado de `GameTitle`. Quando existe uma associação inequívoca ao catálogo autenticado, o caminho de jogo já existente é utilizado. Caso contrário, selecionar consulta `POST /v2/titles` autenticado com `alternateIdType=productId` e um único ID de produto.

Somente uma resposta única e válida pode fornecer o ID de lançamento. Respostas vazias, ambíguas, não filtradas ou falhas não criam uma partida. A associação mantém as capacidades reais de input, incluindo NativeTouch. Uma troca de conta/tela ou o início de outra partida invalida uma resolução atrasada. A presença na galeria e a resolução de um ID ainda não comprovam a licença; o serviço continua autorizando ou negando o provisionamento.

As cartas têm identidade por produto para preservar edições distintas. Favoritos continuam usando IDs reais de lançamento; produtos sem vínculo precisam ser verificados antes de favoritar. Login, favoritos existentes e assinatura do APK não são recriados.

## Evidência e testes

Os testes preservam as respostas completas dos dois SIGLs, em `app/src/test/resources/catalog/*-2026-11-06.json`, e verificam todos os 560/3.781 produtos, sobreposição, gratuitos oficiais, ausência de título fabricado, edição ambígua, contrato HTTP v3, pausa durante a partida, metadados, pesquisa e navegação da nova aba em 1280×720, 2260×1080 e 3840×2160. A captura gerada pelo teste de UI usa um produto de demonstração; não representa a conta do usuário.

SHA-256 dos arquivos oficiais consultados:

| Evidência | SHA-256 |
| --- | --- |
| [client.ccd265d9.js](https://assets.play.xbox.com/playxbox/static/js/client.ccd265d9.js), IDs e rotas | `8b127aca635bd7b14b689feb5bf4b97900f54eb0e5aee9d970bc7a0ca2d12fc0` |
| [2163.c6d2e46b.chunk.js](https://assets.play.xbox.com/playxbox/static/js/2163.c6d2e46b.chunk.js), SIGL v3/plataformas | `f252509a58bdfdf9b6030365f5aeff0e58c1ad4d2df6acf718c6ceedd6af4464` |
| [8925.b031988a.chunk.js](https://assets.play.xbox.com/playxbox/static/js/8925.b031988a.chunk.js), configuração cloud/filtro de ofertas | `3c6562b957509d5df65f5d312772394c124ee59e7edb6c1f5d06324f4900f344` |
| Resposta SIGL v3 Game Pass BR | `34177fe51dacae2fae7e3a96fded0f44f937893de39276e4690786652aeb89cf` |
| Resposta SIGL v3 compra BR | `f677d40e29221e0eed38648fb7c55fc34898921a59b64cd617c6f4c8ff40c3e8` |

Os resultados finais de testes e build desta branch estão no [contexto consolidado](contexto-completo-branch-perf-0.31.md). A investigação pública e os testes com serviços simulados não substituem login, licença e streaming reais no aparelho. O APK de atualização continua sendo assinado pelo CI com o segredo existente; a máquina local não possui essa chave.
