# Tema verde e painéis nativos (0.12.0)

A cor de destaque do aplicativo passa de violeta para verde `#54DE69`. Catálogo, abas, assinatura, indicadores, fila, botões, campos de texto e fundos usam uma paleta carvão/verde. A tipografia Barlow Condensed e a geometria do catálogo são compartilhadas com os novos painéis.

As caixas padrão de busca e conta foram substituídas por painéis próprios. A busca usa campo escuro, foco verde e ações Limpar busca, Voltar e Buscar; a ação de pesquisa do teclado também envia a consulta. O menu de conta mantém todas as opções anteriores, incluindo modo de vídeo, Rewards, assinatura, atualização de catálogo e troca de conta, com rolagem em telas baixas. A seleciona o controle em foco; B/Voltar fecha o painel. Mensagens, confirmação de saída e recuperação de vídeo usam os mesmos componentes.

As telas de entrada e QR Code têm fundo, painel, textos e botões no novo tema. O QR mantém módulos pretos sobre branco e área de respiro para leitura. A tela mostra o código, endereço oficial, validade e estado de aprovação. Durante a conexão, o botão não inicia outro login. Voltar cancela o trabalho de login pendente; as consultas de autenticação e os escopos Microsoft existentes permanecem em `XalAuthenticator`.

## Contorno de foco duplicado

As capas desenhavam o contorno do aplicativo enquanto o Android ainda podia desenhar seu destaque padrão de foco. Esse destaque é desativado com `defaultFocusHighlightEnabled=false` nas capas, grade, abas e demais controles com foco próprio, em API 26 ou superior. O guard mantém compatibilidade com o Android mínimo declarado. O foco continua funcional pelo controle e acessível: apenas a decoração padrão é removida. A capa passa a ter somente o contorno verde arredondado do aplicativo com brilho suave.

## Verificação

Além dos testes existentes, os testes nativos exercitam envio/limpeza/cancelamento da busca, seleção pelo botão A no menu, callback de cancelamento da recuperação de vídeo e bloqueio de login duplicado durante a conexão. A regressão das capas verifica que o destaque padrão do Android fica desativado, inclusive após mudanças de foco. Um teste lê o QR Code a partir da imagem completa renderizada, usando dados fictícios.

As capturas de busca, opções, entrada e QR Code ficam em `app/build/reports/dashboard/`, junto às capturas do catálogo e da fila. Os testes exportam telefone, 1080p e 4K; os PNGs e relatórios são salvos no artefato de relatórios do Actions. Essas verificações não substituem a conferência do foco e da leitura do QR na TV/celular reais.
