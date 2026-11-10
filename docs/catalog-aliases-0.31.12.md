# Edições vinculadas ao título de nuvem — 0.31.12

Versão **0.31.12-catalog-aliases / 43**, exclusivamente na branch `perf/decoder-display-latency-0.31`.

## Problema e confirmação oficial

O usuário relatou que o card Battlefield 6 encontrado na pesquisa não permitia favoritar nem abrir, enquanto um card chamado Battlefield funcionava. A mensagem dizia: “O produto está na galeria oficial, mas o serviço não retornou um título de nuvem disponível para esta conta/região. Nenhuma partida foi iniciada.”

O aplicativo só associava um produto público ao catálogo autenticado pelo StoreId ou por uma consulta autenticada de produto que retornasse exatamente um título. O modelo de produto descartava a relação explícita `XCloudTitleId`. Sem vínculo, o botão de favorito era bloqueado e selecionar tentava aquela consulta, cuja ausência/ambiguidade virava uma mensagem de indisponibilidade da conta/região.

A consulta pública oficial em **10 de novembro de 2026** confirmou:

| Campo | Valor |
|---|---|
| StoreId | `9P2FF14JZLL3` |
| Nome | Battlefield™ 6 |
| XCloudTitleId | `BATTLEFIELD` |
| Oferta web | `XCloudOfferings.XGPUWEB`, com programas informados |
| IsAdditionalProduct | `true` na oferta web |

Fonte: `POST https://catalog.gamepass.com/v3/products?market=BR&language=pt-BR&hydration=RemoteLowJade0`, corpo `{"Products":["9P2FF14JZLL3"]}`. A consulta pública não utiliza token Xbox. A resposta JSON formatada está em `app/src/test/resources/catalog/battlefield6-br-2026-11-10.json`, SHA-256 **e849470ee99afa76018b2b87c5a2f24f3511318ccf69fa5abc34d30f566f1073**.

A relação oficial explica que o nome comercial Battlefield™ 6 pode usar o título de nuvem `BATTLEFIELD`. Ela não comprova a licença da edição na conta, nem substitui a autorização do provisionamento Xbox. Não houve uma sessão autenticada real do usuário no executor para observar a resposta específica da consulta de produto.

## Correção

O produto conserva `cloudTitleId` como metadado separado de `GameTitle`. Uma oferta cloud confirmada pode ser associada quando o identificador declarado existe **uma única vez no catálogo autenticado atual**. Uma associação direta por produto continua prioritária; se ela for ambígua, permanece pendente da consulta autenticada. Um vínculo previamente confirmado por essa consulta também continua válido no contexto da mesma tela/conta.

Não se associa pelo nome parecido, não se constrói um título jogável apenas com dados públicos e não se troca Battlefield 6 por outro Battlefield. Identidade, produto de lançamento e capacidades de input vêm do título autenticado. Nome e capas oficiais podem ser usados na apresentação e na seleção do jogo.

A lógica é genérica: a correção não introduz StoreId ou nome de Battlefield fixado em produção. Os índices são construídos por atualização no worker do catálogo. O novo vínculo não acrescenta uma requisição por card.

## Pesquisa, favoritos e estabilidade

- A pesquisa considera o nome apresentado após a associação. Marcas, pontuação e acentos não impedem buscas equivalentes; `Battlefield 6`, `battlefield6` e `Battlefield™ 6` encontram a mesma edição.
- Uma apresentação oficial inequívoca pode nomear o card nativo e o favorito. Isso evita mostrar apenas o nome genérico do identificador e mantém os favoritos existentes.
- Edições diferentes continuam com identidades de produto próprias nas galerias/pesquisa. Compartilhar um título de nuvem não faz outra edição desaparecer da busca.
- A linha nativa conserva sua chave estável quando recebe nome/capa oficiais, preservando o foco durante a hidratação.
- Favoritos continuam gravados por ID de lançamento, com a confirmação em disco antes da estrela. Não há migração ou recriação da biblioteca. Edições que usam o mesmo ID continuam compartilhando a identidade de favorito do jogo, como anteriormente.
- Metadados públicos sem título correspondente na conta não passam a ser jogáveis/favoritáveis por suposição.

Se o vínculo não puder ser confirmado, o diálogo passa a dizer **“Vínculo do jogo não confirmado”** e identifica o produto. Uma resposta ausente/ambígua não é apresentada como prova de restrição regional ou falta de acesso na conta. Falhas HTTP continuam no tratamento de verificação indisponível, e o serviço continua autorizando ou recusando o início real da partida.

## Validação e limites

Passaram **442 testes JVM/Robolectric**, sem falhas/erros/ignorados, **oito testes Python de assinatura**, lint sem problemas e build release local sem assinatura. O pacote foi conferido como `com.xcloud.tclnative`, versão 43/0.31.12-catalog-aliases, minSdk 24 e target 34.

Os testes usam a resposta oficial preservada para conferir o vínculo Battlefield 6 → BATTLEFIELD. Exercitam ausência do título na conta, títulos com nomes parecidos, metadados inválidos, oferta não confirmada, associações ambíguas, capacidades de input preservadas, pesquisa por edição, favorito existente, múltiplas edições e identidade estável. A integração de UI pesquisa, seleciona, aciona Jogar sem consulta adicional de produto, favorita, verifica a gravação, abre Favoritos e remove a estrela.

A suíte existente continua exercendo catálogos grandes fora da thread principal, navegação rápida, troca de conta e persistência. Login, provisionamento/licença e streaming físicos na conta/TV do usuário ainda precisam de confirmação. Nenhum filtro, codec ou política de conexão foi modificado nesta entrega. O workflow existente repete a suíte e as verificações de assinatura e shaders; o APK instalável é o debug assinado desse CI.
