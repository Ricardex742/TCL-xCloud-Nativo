# Responsividade do catálogo na 0.31

## Relato e diagnóstico

O usuário relatou travamentos ao alternar para Game Pass e Transmita seu jogo na versão 0.31.0, em um LG K10, incluindo o diálogo Android de aplicativo sem resposta (ANR).

A expansão das galerias introduziu trabalho proporcional ao catálogo na thread principal: normalização/classificação dos títulos, agrupamento por produto, filtragem, ordenação e `DiffUtil.calculateDiff` com detecção de movimentos. A ordenação mudava conforme chegavam nomes em lotes; listas de milhares de produtos podiam gerar comparações e notificações de movimentação muito caras. Os getters de chave e a ordenação também criavam strings repetidamente durante as comparações.

Esses caminhos foram confirmados no código. Não foi recebido um trace de ANR do LG K10; portanto, o diagnóstico identifica um gargalo concreto compatível com o relato, sem atribuir todos os possíveis ANRs ao mesmo caminho.

## Correção

- Captura dos dados para uma apresentação imutável; classificação, indexação, busca, ordenação e comparação executadas em `Dispatchers.Default`.
- Um único processamento por view e apenas uma solicitação pendente, substituída pela mais recente. Trocar rapidamente de aba não cria uma fila de tarefas de ordenação concorrentes.
- Revisão de solicitação para impedir que resultados antigos substituam a aba ou a busca atual. Os cartões da categoria anterior são retirados ao iniciar outra categoria/busca.
- Cliques e foco consultam a posição atual do holder. Cartões antigos que ainda aguardam o próximo layout não iniciam o jogo da categoria anterior.
- Substituição única da lista com IDs estáveis quando a ordem muda, sem calcular ou despachar milhares de movimentos. Atualizações na mesma ordem usam intervalos de metadados, limitados a 32 notificações por apresentação.
- Chave estável e nome para ordenação calculados uma vez por cartão; índice nativo compartilhado pelas duas galerias durante a pesquisa global.
- Agrupamento de atualizações de metadados em intervalos de 400 ms. Entrada de navegação não aguarda esse intervalo.
- Seleção/foco preservados pela chave do produto após reordenações; posição de rolagem preservada quando não há foco no grid.
- Pausa durante partidas e cancelamento ao fechar impedem a aplicação tardia de resultados.

As fontes oficiais, a lista completa, as edições distintas, os favoritos e a verificação autenticada para iniciar jogos continuam no fluxo. Não há corte para os primeiros 120 produtos. Decodificação de vídeo não participa desta correção.

## Validação

Os testes usam as fixtures completas de 560 produtos Game Pass e 3.781 produtos da galeria de compra. Os totais pertencem às respostas coletadas e não são constantes em produção.

Além dos testes existentes de seleção, busca, imagens, favoritos e navegação, foram adicionados cenários que seguram deliberadamente o processamento do catálogo fora de Main e verificam que a interface atende outro evento, que trocas rápidas só exibem a última busca, que a hidratação de milhares de nomes mantém o produto focado e que pausar/fechar não aplica resultados atrasados.

A suíte completa passou em **289 testes JVM/Robolectric**, sem falhas ou testes ignorados. Os **8 testes Python de assinatura**, `:app:lintDebug` e `:app:assembleRelease` também passaram. O empacotamento é release sem assinatura; a chave fixa de atualização permanece exigida para o APK debug distribuível.

Após a proteção final contra cliques em holders antigos, foram reexecutados com sucesso **23 testes** de galerias, dashboard, imagens, agrupamento de metadados, capturas e atalhos de inicialização, além de lint e empacotamento release.

Os testes locais não medem FPS ou tempo de resposta no LG K10. A confirmação no aparelho deve repetir a alternância das duas abas durante o carregamento das capas, a busca, a navegação e o retorno de uma partida, usando um APK assinado pelo certificado fixo do projeto.
