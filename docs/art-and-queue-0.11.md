# Capas originais, cabeçalho ampliado e carregamento (0.11.0)

O cabeçalho reserva novamente a altura completa para ícone do jogo, nome, avatar, gamertag e dados da conta. O ícone passa de 64 para 116 unidades do layout de referência, sem a compressão anterior para TV. As abas ficam abaixo desse espaço e têm altura maior. As capas mantêm proporções e seis colunas; a grade rola em telas onde o cabeçalho maior impede duas fileiras completas. A interface solicita modo imersivo para retirar a faixa de navegação do Android, que continua acessível por gesto.

## Capas e selos

O print recebido mostra os identificadores de streaming e placeholders, sem nomes/capas. O carregamento anterior aceitava apenas o mapa `Products` do Game Pass v3 e ignorava as falhas dessa consulta. Agora:

- Microsoft Store v7 é a primeira fonte: `GET /v7.0/products`, `bigIds`, `fieldsTemplate=details`, mercado BR e idiomas pt-br/en-us. O parser aceita `Products` em lista e em mapa, lê os nomes localizados e as imagens `LocalizedProperties.Images`.
- As capas usam `Poster`/`BoxArt`, os ícones priorizam `SquareTile` e o fundo de carregamento prioriza `SuperHeroArt`/`TitledHeroArt`/`Screenshot`. São imagens oficiais recebidas dos serviços; não são substituídas por artes geradas.
- Produtos incompletos tentam também o endpoint Game Pass v3. Títulos sem Store ID podem ser consultados pelo Xbox Title ID. O Store ID do catálogo pode estar em `details.productId` ou no nível principal. O ID de streaming nunca é inventado ou alterado.
- As consultas ocorrem em lotes de até 50 produtos, com quatro requisições simultâneas. Os metadados públicos são salvos no cache e reaplicados somente aos jogos do catálogo autenticado atual. O catálogo informa carregamento/indisponibilidade em vez de esconder completamente a falha.
- Catálogo e fila compartilham o cache de imagens da aplicação: memória de 20 MiB, HTTP de 64 MiB e até quatro downloads simultâneos. Fechar uma tela não fecha o cache da outra. A CDN Store recebe os tamanhos pedidos para evitar baixar arquivos originais desnecessariamente grandes. O cabeçalho pede ícones quadrados e o fundo pede imagem horizontal.
- **Game Pass** é marcado por pertencimento à galeria oficial de jogos cloud do Game Pass, excluindo a galeria de jogos gratuitos quando disponível. O selo não depende de a conta ser Ultimate. Falha na consulta deixa o selo desconhecido, sem marcá-lo em todos os jogos.
- **X|S** exige `Properties.XboxConsoleGenOptimized` indicando ConsoleGen9/Series. `XboxConsoleGenCompatible` isolado não basta: retrocompatibilidade não comprova uma edição Series.

O contrato Store foi conferido no [xbox-webapi-python](https://github.com/OpenXbox/xbox-webapi-python/blob/master/xbox/webapi/api/provider/catalog/__init__.py) e seus [modelos](https://github.com/OpenXbox/xbox-webapi-python/blob/master/xbox/webapi/api/provider/catalog/models.py). Os IDs SIGL vêm do [Better xCloud](https://github.com/redphx/better-xcloud/blob/main/src/enums/game-pass-gallery.ts). Tokens Xbox/GSSV não são enviados às consultas públicas de metadados ou imagens.

## Fila e preparação

A tela cobre o vídeo ainda vazio com o fundo oficial do jogo, escurecimento para legibilidade, capa e selos, nome, previsão do Xbox em destaque e tempo aguardado separado. Mostra a idade da última confirmação da fila e da previsão. Uma previsão antiga ou falha de atualização recebe aviso; uma previsão ausente não vira zero. O valor não é transformado numa contagem regressiva ou porcentagem fictícia.

Os indicadores acompanham os estados de fila, preparação, conexão e recebimento de vídeo. A tela continua visível ao sair de `WaitingForResources`, durante SDP/ICE e até o primeiro quadro, evitando a transição de volta para uma tela preta. A informação de capa/fundo pode chegar após iniciar a espera e é aplicada ao mesmo jogo sem reiniciar a sessão ou seu relógio. O limite de espera permanece 60 minutos. A/OK no botão, B e Voltar cancelam e retornam ao catálogo. O envio de comandos de jogo só retoma depois que a tela de carregamento desaparece.

## Validação e limites

Os testes cobrem a estrutura JSON do contrato Store, nomes localizados, URLs oficiais, fallback de lote, lookup por Xbox Title ID, recuperação de cache, preservação dos IDs, selos condicionais e fila sem contagem regressiva falsa. Um teste com servidor HTTP local fornece PNGs reais e verifica download, decode, apresentação, troca de capa com requisição anterior ainda em andamento e reutilização do cache por outra tela.

Robolectric exporta capturas de catálogo e fila em telefone, 1080p e 4K para o artefato de relatórios do Actions. As capturas usam dados de teste e placeholders; não comprovam acesso à conta nem download dos servidores Microsoft no aparelho. A política de rede deste ambiente não permite consultas diretas aos domínios Microsoft de catálogo/CDN; os testes de protocolo usam servidores locais e os contratos públicos. Na TV, conferir as capas carregadas, a arte do jogo durante uma fila e os selos nas edições correspondentes.
