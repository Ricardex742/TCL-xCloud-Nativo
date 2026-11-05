# Toque confirmado no aparelho e catálogo mais leve — 0.25

## Resultado real da 0.24

O usuário confirmou que conseguiu jogar Fortnite com toque nativo na `0.24.0-touch-source-and-scaling`, em um LGE LM-K200 com Android 10. O relatório dessa partida registra 5min46s conectado, 345 amostras, toque nativo sinalizado ativo, registro do slot virtual 0 enviado e 9.203 eventos aceitos pelo canal, sem esperas de input. O relato do funcionamento no jogo é a evidência de sucesso no aparelho; o contador isolado continuaria sendo apenas uma medida do envio local.

Isso valida o resultado da 0.24 nesse aparelho e nessa sessão. Não estabelece suporte universal a outros aparelhos/títulos nem prova que o registro do slot 0 foi a única causa da diferença em relação à versão anterior. O fluxo de toque validado não foi alterado na 0.25.

## Leitura do diagnóstico

| Observação | Interpretação e limite |
| --- | --- |
| RTT médio 44,71 ms; jitter 2,22 ms; perda RTP reportada 0% | Indicadores razoáveis nos intervalos medidos. Enlace Wi-Fi de 72 Mbps não é velocidade de internet. Perda reportada zero não exclui interrupções de envio, retransmissões ou variação do Wi-Fi. |
| Buffer médio 48,73 ms | Menor que os 66,79 ms do relato anterior. Os cenários e a duração das partidas diferem; não é uma comparação controlada atribuível somente à atualização. |
| Decode médio 10,50 ms; pico 37,25 ms | A média está abaixo do orçamento de 16,7 ms por quadro a 60 FPS, mas há picos. Isso não identifica sozinho a causa das pausas. |
| FPS do decoder médio 52,34; pico 77 | Não são FPS apresentados pela tela nem garantia de 60 FPS do jogo. Picos em um intervalo podem incluir processamento de quadros acumulados. |
| Atraso local de input médio 2,82 ms, sem espera no canal | É o atraso do agendador local observado, não o tempo total do dedo até a imagem. Não há evidência para reescrever o input agora que o usuário confirmou seu funcionamento. |
| 17 s sem progresso em 14 episódios: 12 s sem dados e 5 s com dados | Aproximadamente 4,9% dos 345 s observados. São classificações do watchdog de cerca de um segundo, não uma captura de cada congelamento. O principal alvo de comparação são as interrupções de vídeo. |
| Nenhuma recuperação automática | A política normal aguarda pelo menos 3,5 s contínuos sem progresso. Vários episódios curtos podem somar 17 s sem atingir esse limiar. Isso não demonstra falha da recuperação. |

O vídeo 1392×656 contém 913.152 pixels, aproximadamente a mesma carga geométrica de 1280×720 (921.600). O campo `espaço de input: 1920×1080` informa coordenadas virtuais do servidor e não uma transmissão em 1080p. Não se deve reduzir essa escala de input para tentar economizar decode ou banda.

Para uma comparação no aparelho, manter resolução, proporção, filtros, local do Wi-Fi e cenário semelhantes e comparar **Adaptativo** com **Menor atraso**, que já existe no app. O modo de compatibilidade pode ser comparado se a prioridade for apresentação a 30 FPS. Nenhuma dessas preferências foi alterada automaticamente, nem foram forçados resets ou quadros-chave mais frequentes com base apenas nesse relatório.

## Surface, layout e janela

O relatório apresenta `Surface 1452x717 → janela 1452x689`. O campo Surface vinha de `SurfaceHolder.surfaceFrame`, que descreve o buffer da Surface; ele não informava o tamanho medido da View nem a geometria calculada pelo app. Sem essas duas medidas, não se pode concluir somente pelo texto que houve corte ou distorção.

O diagnóstico de vídeo da 0.25 acrescenta **Layout do vídeo**, **Área calculada** e **Quadro do renderer**. Isso permite comparar buffer, View, janela e resolução conhecida pelo renderer em uma próxima sessão. Não foi aplicada outra mudança de geometria ou de coordenadas ao toque com base nessa divergência.

## Custo encontrado no menu

As capas da grade eram solicitadas em até 720×1080 mesmo quando apareciam com cerca de 170×260 pixels. Atualizações do catálogo e dos favoritos podiam limpar a ImageView e reiniciar a mesma carga, e até quatro downloads/decodificações disputavam recursos. Navegar rapidamente também solicitava ícones de cabeçalho de 512×512 para seleções passageiras.

A 0.25 modifica esse caminho nativo:

- **Miniaturas pelo tamanho exibido.** Células e ícones usam dimensões em pixels agrupadas em faixas de 64. Por exemplo, uma capa de 191×290 usa limite 192×320. As URLs dos hosts de imagens Microsoft recebem esses limites e qualidade 80 para miniaturas; imagens grandes conservam qualidade 90. Outros hosts conservam a URL original.
- **Decode limitado também quando o servidor ignora o tamanho pedido.** Primeiro são lidas as dimensões; a decodificação usa amostragem e a eventual redução final acontece fora da UI. Um poster 720×1080 RGB565 ocupa cerca de 1,48 MiB; o limite 192×320 corresponde a até 120 KiB em RGB565. A quantidade real depende de proporção, formato e transparência, e o cache contabiliza os bytes efetivos.
- **Uma carga por imagem/tamanho.** Repetir o bind da mesma capa não limpa a imagem nem reinicia o download. Views que pedem a mesma imagem compartilham o trabalho em andamento. Cancelar uma View não cancela outra consumidora; o último cancelamento interrompe a carga.
- **Orçamento de segundo plano.** Download e decode usam dois trabalhos simultâneos em aparelhos com pouca memória ou até quatro processadores, e até três nos demais. Threads de imagens têm prioridade Android de segundo plano; tamanho do cache é limitado conforme a memória disponível.
- **Trabalho visível.** Capas de Views recicladas/desanexadas são canceladas. Views apenas preparadas pelo RecyclerView não iniciam downloads antes de se anexarem. O cancelamento e a liberação do cache ao iniciar uma partida foram mantidos.
- **Menos trabalho na UI.** Atualizações de metadados que preservam a ordem usam comparação linear dos itens. Favoritar atualiza o indicador sem recarregar a capa. O cabeçalho aguarda 120 ms de foco estável para buscar seu ícone, enquanto o texto e a seleção mudam imediatamente. O painel da conta só é refeito quando seus dados ou a geometria mudam.

Os caches HTTP e de memória continuam compartilhados com a tela de carregamento, e a chave de memória inclui o tamanho pedido. Não houve alteração de favoritos, login, HUDs, assinatura fixa, filtros de vídeo ou protocolo de toque. Nenhuma dependência de navegador foi adicionada.

## Verificação

Os testes usam PNGs reais e servidor HTTP local controlado. Exercitam download compartilhado, cancelamento de uma consumidora, limite de duas requisições enquanto a UI executa tarefas, dimensões e memória da miniatura resultante, cache, reciclagem e proteção contra imagem antiga. A integração do catálogo em paisagem verifica carregamento das capas anexadas, estabilidade das imagens durante hidratação/favoritos e abertura do jogo correto. A suíte completa e o lint são executados antes da publicação na branch; o CI também verifica o APK e a assinatura fixa existente.

A validação local passou em **235 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados; `:app:lintDebug` terminou sem problemas. Os **8 testes Python da proteção de assinatura** também passaram. Um caso adicional verifica que cancelar a última consumidora interrompe um corpo HTTP lento e libera o trabalho seguinte.

Essas verificações demonstram redução do trabalho solicitado e manutenção das funções. Ainda não foi medido tempo de abertura, uso de memória ou perda de quadros do menu em um LM-K200 com a 0.25. A comparação no aparelho deve incluir a primeira entrada com cache frio e uma segunda entrada com cache disponível.
