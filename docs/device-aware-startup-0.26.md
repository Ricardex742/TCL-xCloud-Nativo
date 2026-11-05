# Menu, atalhos e entrada por dispositivo — 0.26

O teste da 0.25 no celular continuou apresentando travamento no menu. Abrir o atalho do Fortnite mostrava a tela de login brevemente, seguia sozinho para uma fila sem capa e iniciava o HUD virtual. O usuário confirmou que não precisou escanear outro QR Code. A restauração automática da conta estava acontecendo; a tela apresentada durante essa etapa era inadequada.

## Trabalho de interface no menu

A 0.25 já reduzia downloads e decodificação, mas a chegada de uma imagem ainda podia solicitar outra medição da grade. Os binds de metadados também reatribuíam geometria de capas e emblemas, e cada redesenho do fundo construía gradientes e um Path novos.

A 0.26 mantém as miniaturas e caches anteriores e altera esses caminhos:

- ImageViews com dimensões fixadas pelo layout atualizam os pixels sem solicitar nova medição por mudança nas dimensões intrínsecas da imagem. Mudanças reais de tamanho continuam solicitando layout.
- A grade aplica no máximo duas imagens por quadro, inclusive acertos do cache em memória. Entregas canceladas ou substituídas não alteram uma célula reciclada.
- Atualizações de metadados recebidas em sequência são agrupadas por 180 ms. A seleção e o conteúdo mais recente são preservados, e sair para o jogo cancela a atualização pendente.
- Capas e emblemas só reatribuem geometria quando ela muda. O fundo reutiliza gradientes e desenho enquanto suas dimensões permanecem iguais.
- A criação do contexto EGL é adiada para o início da transmissão; abrir somente o menu não inicializa esse recurso de reprodução.

Essas mudanças reduzem trabalho identificado no código e exercitado pelos testes. Ainda não há uma medição de fluidez, tempo de abertura ou consumo de memória da 0.26 no LM-K200. O retorno anterior impede tratar a melhoria como confirmada no aparelho.

## Atalho e arte da fila

Com credenciais salvas compatíveis, a inicialização mostra preparação e restaura a sessão. Autenticação Xbox, concessão para jogos e validação do ID no catálogo autenticado continuam obrigatórias. Sem credenciais ou quando o serviço exige renovação, o login continua disponível; uma falha transitória preserva o login salvo e oferece nova tentativa.

Depois dessa validação, um atalho abre o jogo antes da construção da grade. A hidratação de todo o catálogo, galerias e perfil ficam para quando o usuário voltar ao menu. Isso elimina trabalho desnecessário no caminho direto para a fila.

A capa pública em cache é restaurada sobre os jogos do catálogo autenticado. Esse cache não concede acesso a jogos nem restaura capacidades de entrada antigas. A fila passa a receber metadados da lista atual, sem depender de uma dashboard oculta ou ainda inexistente. Quando falta arte, consulta apenas o título escolhido durante o carregamento; isso não retoma as consultas do catálogo inteiro. Os atalhos também salvam a arte disponível antes de sua criação. Falta de arte no serviço ou falha no download conserva o nome do jogo como fallback.

Os atalhos já instalados continuam transportando o mesmo ID, sem credenciais. Cancelar a fila não consome o atalho outra vez. Voltar ao catálogo após uma abertura direta constrói a dashboard nesse momento.

## Entrada escolhida antes da sessão

Catálogo e atalhos usam uma única política:

| Aparelho e preferência | Entrada inicial |
| --- | --- |
| TV ou aparelho sem touchscreen, qualquer preferência | Controle físico, sem HUD e sem toque nativo |
| Celular, Automático ou Toque nativo, título anuncia `NativeTouch` | Toque nativo Xbox |
| Celular, Automático ou Toque nativo, título sem suporte anunciado | HUD virtual |
| Celular, Exclusivo — controle virtual | HUD virtual, inclusive no Fortnite |
| Celular, Desativado — controle físico | Controle físico |

A detecção considera o modo Android TV e as capacidades TV/Leanback antes de considerar a presença de touchscreen. A capacidade de toque vem do catálogo autenticado, sem inferir suporte pelo nome Fortnite. As chaves das preferências permanecem iguais; a escolha explícita de controle físico também é preservada.

O protocolo nativo e o registro do slot lógico usados na partida bem-sucedida da 0.24 não foram reescritos. Favoritos, ajustes de HUD, diagnóstico e certificado fixo de atualização são preservados.

## Verificação e limites

Os testes de política cobrem telefone, TV que anuncia touchscreen, jogo com e sem suporte nativo e preferências explícitas. Os testes de UI verificam que mudar uma capa não solicita medição do pai, que seus pixels continuam aparecendo, que um redimensionamento real funciona, o orçamento de entregas por quadro e a atualização mais recente de metadados.

A integração de inicialização usa autenticação e catálogo simulados por HTTP local e a Activity real: credenciais salvas levam à fila sem criar tela de login ou grade, somente o produto escolhido recebe consulta de metadados e a arte chega à fila. A imagem HTTPS desse cenário está previamente no cache de memória; download, decode e exibição das miniaturas são verificados separadamente com PNGs reais. Sem credenciais, o teste exige a tela de login e verifica que nenhuma consulta foi iniciada. A integração interrompe o job antes do transporte JNI/WebRTC; não simula uma partida real.

A validação local passou em **242 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, além do lint Android e dos **8 testes Python de proteção da assinatura**. O APK principal continua sendo assinado no CI com o segredo existente. A fluidez do menu, a abertura pelo launcher instalado e a entrada escolhida dentro de uma nova partida no celular ainda precisam de validação no aparelho.
