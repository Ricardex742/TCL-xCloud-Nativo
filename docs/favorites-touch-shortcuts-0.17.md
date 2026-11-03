# xCLOUD 0.17.0

Durante a partida, **Menu/Start + View/Select** no controle do jogador 1 abre o painel Partida. Aperte os dois juntos; separados, continuam funcionando no jogo. A janela da combinação é de 150 ms e os botões consumidos não são encaminhados ao Xbox. B/Voltar fecha o painel. O botão Voltar do Android durante a partida também abre o painel, permitindo sair sem encerrar por acidente.

O painel reúne nitidez/saturação, ativação manual das estatísticas e **Desconectar sessão e voltar ao catálogo**. A desconexão cancela a fila/keep-alive, envia DELETE para a sessão Xbox e libera WebRTC/decoder; iniciar outro jogo aguarda essa limpeza. INFO/F10 continua alternando as estatísticas. Start + Select passa a abrir o menu, substituindo o antigo atalho direto de estatísticas.

## Favoritos

A biblioteca usa o XUID verificado na autenticação Xbox; o hash de uma concessão não é sua identidade permanente. Se a concessão não trouxer XUID, a consulta autenticada de perfil pode associar a biblioteca ao XUID. A biblioteca antiga do hash atual é migrada uma única vez, com um vínculo persistido; favoritos removidos depois não reaparecem na migração seguinte. Contas diferentes mantêm bibliotecas separadas. IDs do catálogo são comparados sem diferença de maiúsculas/minúsculas.

A estrela só é confirmada depois de gravar os favoritos no armazenamento. Falha de gravação informa um erro. Fechar/reabrir o aplicativo e atualizar com o mesmo certificado preservam os dados. Desinstalar o app ou limpar seus dados apaga favoritos e login locais. A migração não mistura arquivos de outras contas para tentar recuperar listas já perdidas.

## Toque nativo

O handshake envia o relatório binário de capacidade de toque antes da mensagem de ativação. O primeiro pacote de contato usa sequência 1, depois da sequência 0 dos metadados. Contatos que chegam durante a abertura dos canais ficam no buffer, preservando DOWN/UP; perder o foco também libera os contatos pendentes.

A sessão reafirma a ativação depois da negociação. Em telefones sem sensor de pressão, um dedo pressionado usa pressão ativa em vez de zero, e a área de contato é enviada. A transformação de coordenadas continua respeitando proporção, zoom e barras. No menu da sessão nativa há **Usar controle virtual nesta partida**, sem sair da fila ou desconectar o jogo.

Somente jogos que anunciam NativeTouch recebem a opção nativa. O controle virtual do app continua disponível. Testes locais cobrem o contrato e o fluxo de contatos; validar os controles exibidos pelo Fortnite exige uma sessão real no telefone, não disponível no ambiente de compilação.

## Tela inicial

Em **Configurações → Atalhos dos favoritos**, escolha **Mostrar favoritos na tela inicial da TV**. Em Android TV 8 ou superior, o app publica o canal **Favoritos xCLOUD**, com capas, nomes e intents de abertura direta. O launcher pode pedir para habilitar o canal. Novos favoritos, capas atualizadas e remoções sincronizam enquanto o app está aberto. A opção de remover o canal também fica nesse painel; trocar de conta remove os jogos da conta anterior da lista publicada.

Em launchers que oferecem `requestPinShortcut`, o mesmo painel disponibiliza **Criar atalho: nome do jogo** para cada favorito, usando sua capa como ícone quando disponível. A disposição e a exibição na tela inicial dependem do launcher: alguns modelos Google TV não exibem canais de terceiros nem aceitam atalhos fixados. O app não promete adicionar um jogo à fileira de aplicativos onde o sistema não oferece essa API.

O atalho transporta apenas um ID do catálogo, sem tokens ou URLs de sessão. Se necessário, o app conclui o login, verifica o ID no catálogo autenticado e inicia diretamente a fila. O pedido fica salvo durante a recriação da tela de login e é consumido uma vez; cancelar não reinicia o jogo. Um jogo indisponível para a conta exibe uma mensagem. A preferência de entrada é respeitada; no celular, Automático usa controle virtual ao abrir um atalho.

## Verificação

Testes de regressão verificam gravação real do XML dos favoritos antes de fechar, renovação da concessão Xbox, migração sem ressurreição de itens, isolamento entre contas, consumo único do deep link, rejeição de links inválidos, sincronização com um provedor TV simulado, ordem do handshake, sequência do primeiro contato e telefone sem sensor de pressão. O pipeline também verifica APIs Android, assinatura fixa e os testes de imagem existentes.
