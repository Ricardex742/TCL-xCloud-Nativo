# Motivo de encerramento fotografável na TV — 0.31.11

Versão **0.31.11-failure-reason / 42**, exclusivamente na branch `perf/decoder-display-latency-0.31`.

## Ocorrência que motivou a revisão

O usuário relatou que Forza Horizon 6 saiu da partida sozinho e enviou uma foto do diagnóstico. O app exibia o catálogo e o relatório com **“Falha na transmissão”**, cerca de sete minutos conectado, 423 amostras e índice 95/100. Os números visíveis eram aproximadamente:

| Indicador | Média | Pico |
|---|---:|---:|
| RTT | 46,6 ms | 52 ms |
| Perda | 0,02% | 1,72% |
| FPS decodificados | 58,8 | 61 |
| Bitrate de vídeo | 10,5 Mbps | 21,5 Mbps |
| Jitter | 2,2 ms | 6 ms |
| Decode | 8,3 ms/quadro | 13,3 ms/quadro |
| Buffer observado | 3,0 ms | 34 ms |

Esses agregados não mostram o evento terminal. Não comprovam uma queda de internet nem permitem atribuir a saída ao filtro, decoder ou serviço. O índice descreve as amostras observadas; uma desconexão no fim não é excluída por médias boas. O retorno ao catálogo com relatório não comprova encerramento do processo Android.

A foto não permite confirmar a versão, as opções de filtro ou o erro terminal. Os protótipos de qualidade discutidos após a 0.31.10 não foram integrados ao aplicativo.

## Lacuna encontrada no código

Após o primeiro quadro, com o relatório automático habilitado, o `catch` da partida podia omitir o diálogo da exceção original. O relatório guardava somente `SessionEnd.FAILURE`, sem o motivo. A exceção era perdida ao concluir a sessão.

O laço de reprodução também encerra a sessão se uma requisição periódica de manutenção (`keepalive`) falhar. Isso é um caminho possível de encerramento com vídeo anteriormente saudável, não uma comprovação de que ocorreu neste teste. Falhas ICE/canais, decoder e apresentação local também podem chegar ao mesmo rótulo genérico.

O usuário esclareceu que copiar na TV não permite enviar o texto convenientemente pelo controle. Portanto, a causa precisa aparecer em destaque para leitura/foto, sem depender da transferência da área de transferência.

## Alteração implementada

- O primeiro erro terminal é normalizado e congelado junto ao relatório, antes da liberação dos recursos. Eventos posteriores de fechamento não substituem esse motivo.
- **MOTIVO DO ENCERRAMENTO** aparece acima das métricas. Mostra descrição em português e código técnico, por exemplo `KEEPALIVE/HTTP_503`, `KEEPALIVE/TIMEOUT`, `TRANSPORT/ICE_FAILED` ou `CHANNELS/CHANNEL_CLOSED`. São exemplos de classificação, não o resultado da partida relatada.
- Diferencia HTTP, timeout, DNS, TLS, conexão/socket, ICE, controles, decoder, ausência de vídeo e apresentação local. Erros desconhecidos recebem classificação genérica e tipo, sem inventar uma causa.
- Requisições que falham no transporte preservam a etapa em uma exceção tipada. Respostas HTTP mantêm status/código já validados. Cancelamento continua sendo propagado como cancelamento.
- O motivo persiste no último relatório e no texto copiado. O formato antigo continua legível; motivos ausentes aparecem como **“Não registrado nesta versão do relatório”**.
- O relatório esclarece que o índice calculado não exclui uma interrupção terminal, sem alterar artificialmente a pontuação das amostras anteriores.

A informação nova usa enums, status validado, código de serviço limitado e tipo de exceção validado. Não inclui mensagens arbitrárias, URL da sessão, corpo HTTP, SDP, credenciais ou identificador de requisição.

## Alcance e limites

Esta versão corrige a perda da informação necessária ao diagnóstico. **Não recupera a exceção da partida anterior e não comprova corrigir a saída do Forza.** Não acrescenta tentativas de reconexão ou supõe que a rede falhou. As regras de encerramento, keepalive, filtros e transporte foram preservadas.

Em uma nova ocorrência na versão 0.31.11 ou posterior, basta fotografar o bloco **MOTIVO DO ENCERRAMENTO**. Um código de transporte fechado identifica o evento recebido pelo app, mas sozinho pode não distinguir uma falha de rede de um encerramento remoto; essa limitação permanece explícita.

## Validação

Os testes cobrem classificação e persistência, precedência do primeiro erro, congelamento do relatório, cancelamento normal, compatibilidade com registros antigos, limites de campos e ausência de URLs/mensagens privadas. A tela de falha é renderizada em landscape para conferir o motivo e o código antes das métricas. Dois testes exercem uma sessão simulada real até o keepalive, injetando timeout de socket e HTTP 503 para verificar a etapa e o motivo preservados.

O contexto consolidado registra os resultados locais. O workflow existente repete a suíte completa, lint, verificação da assinatura fixa e testes dos shaders; o APK instalável é o artefato assinado desse CI.

Validação local concluída: **433 testes JVM/Robolectric**, sem falhas/erros/ignorados; **oito testes Python de assinatura**; lint sem problemas e release sem assinatura aprovados. A tela de demonstração de falha foi inspecionada. Pacote `com.xcloud.tclnative`, versão 42/0.31.11-failure-reason, minSdk 24 e target 34 conferidos. Os shaders não mudaram nesta entrega. O exemplo HTTP 503 usado nos testes não identifica a causa da partida relatada.
