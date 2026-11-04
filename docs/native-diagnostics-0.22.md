# Motor nativo e diagnóstico pós-partida — 0.22

> Registro da investigação na 0.22. A análise direta posterior do cliente oficial e os ajustes da 0.23 estão em [Toque nativo e SDK oficial](native-touch-official-sdk.md). A validação em uma partida no celular continua pendente.

## Toque e execução

O streaming usa exclusivamente o motor Android/WebRTC/H.264. A Activity do cliente WebView, seu processo separado e a opção de abertura foram removidos. Instalações que haviam selecionado `WEB_NATIVE` migram para `NATIVE`, preservando as demais preferências e a assinatura fixa do aplicativo.

`NativeTouch` é diferente de um HUD que converte toques em botões Xbox. No modo direto, o motor anuncia dez contatos no metadata do canal `input/1.0`, informa `touchInputEnabled` no canal `message` e transmite down/move/up com ID, posição, área de contato e pressão. As coordenadas da tela são convertidas para a área exibida do vídeo e depois para as dimensões de input informadas pelo servidor. Não há um navegador nessa sequência.

### Evidência e limites da investigação

Foram comparadas referências públicas, sem incorporar suas interfaces ou runtime:

- [Better xCloud, commit f8397043](https://github.com/redphx/better-xcloud/tree/f8397043f6d2148d2345d508902a38c69cf1ee20): identificação de `NativeTouch`, habilitação de input, aviso de toque ao jogo e atualização da área de apresentação. O patch que força a interface de console no Fortnite desativa `touchInputEnabled`; isso ajuda a distinguir a seleção da interface de toque do desenho de um HUD virtual.
- [XStreaming, commit 06ef1c7e](https://github.com/Geocld/XStreaming/tree/06ef1c7ee01d09cf9bb2a97fc4895da85e8488a8): canais, metadata, formato dos contatos e estrutura do diagnóstico de sessão.
- [`xbox-xcloud-player` 0.2.11](https://www.npmjs.com/package/xbox-xcloud-player/v/0.2.11): outra implementação pública do canal. Sua implementação antiga fixa dimensões e ID de contato; não foi tratada como modelo de multitouch atual.

Essas referências são clientes e extensões independentes, não uma captura do SDK oficial executando Fortnite. A leitura dos arquivos de `www.xbox.com` não estava disponível na rede de desenvolvimento durante esta investigação. Não houve uma sessão autenticada de comparação, inspeção do código do jogo ou validação em um celular físico nesta versão. Portanto, **o toque direto permanece experimental: a correção do toque no Fortnite ainda não foi demonstrada**. Um contador de contatos enviados mede a aceitação pelo canal local, não a aceitação pelo jogo remoto.

O relatório passa a guardar esse contador e os bytes de input efetivamente aceitos pelo canal, facilitando separar ausência de eventos locais de problemas posteriores. Não foram alterados campos de protocolo por tentativa sem evidência do comportamento oficial.

## Relatório pós-partida

O relatório começa no primeiro quadro apresentado, excluindo a fila. É finalizado ao desconectar, ocorrer uma falha de transmissão, trocar de partida ou fechar normalmente a Activity. Cancelar a fila antes de receber vídeo não cria um relatório vazio. Uma partida encerrada rapidamente pode ser consultada nas configurações; a abertura automática ocorre a partir de cinco segundos de vídeo ou em uma falha após o primeiro quadro.

**Configurações → Diagnóstico pós-partida** controla apenas a abertura automática. **Último diagnóstico** consulta o último resumo salvo. **Copiar** exporta o texto por ação explícita. As estatísticas na tela continuam ocultas no início de cada jogo.

| Indicador | Origem e agregação |
| --- | --- |
| Duração | Relógio monotônico entre primeiro quadro e encerramento. |
| RTT | Par ICE selecionado, média ponderada pelo intervalo observado, mínimo e pico. Não é input-to-photon. |
| Jitter, FPS, bitrate e buffer | Estatísticas RTP disponíveis, ponderadas pelo tempo. FPS é do decoder. |
| Perda | Pacotes perdidos / (recebidos + perdidos) nos intervalos medidos; porcentagens observadas quando os contadores não estão disponíveis. |
| Decodificação | Média ponderada pelos quadros decodificados quando disponíveis. |
| Tráfego | Contadores cumulativos reais de vídeo RTP e transporte WebRTC; deltas com tratamento de reset e mudança de origem. Sem estimativa artificial de upload. |
| Input | Bytes e contatos de pacotes aceitos pelo canal; esperas por congestionamento e transições consolidadas. Atraso local usa os máximos observados do agendador, não o RTT do comando. |
| Pausas de imagem | Probes do watchdog sem progresso de apresentação, separados conforme havia ou não recebimento recente de vídeo. |
| Recuperação | Quantidade de solicitações de quadro-chave e tentativas de recuperar decoder/superfície. |

O monitor consulta o vídeo aproximadamente a cada segundo; seus episódios não medem cada congelamento curto nem identificam sua causa. A rede descreve o transporte e, quando expostos pelo Android, a banda e o enlace Wi-Fi. Enlace Wi-Fi não equivale à velocidade da internet. Não se consulta SSID, BSSID ou localização.

### Índice de qualidade

É uma estimativa local de 0 a 100, calculada somente com pelo menos três amostras de RTT, perda, jitter, FPS e decodificação e cinco segundos observados. Parte de 100 e aplica penalidades limitadas por RTT acima de 30 ms, perda, jitter acima de 2 ms, FPS abaixo da apresentação solicitada e decode acima de 12 ms/quadro. Pausas de apresentação observadas também reduzem o índice, até 40 pontos conforme sua proporção do tempo medido, mesmo quando o decoder ainda informa FPS altos. A apresentação solicitada é de 30 FPS em compatibilidade e 60 nos outros modos.

Não é uma nota oficial do Xbox ou XStreaming, teste de velocidade ou garantia de fluidez. Dados ausentes permanecem `n/d`; não são substituídos por números de uma conexão saudável. O relatório apresenta observações e sugestões de comparação, sem atribuir automaticamente a causa à rede ou ao aparelho.

### Custo e armazenamento

Agregados têm tamanho constante por sessão: somas ponderadas, mínimos, máximos e contadores. Não se mantém uma lista por segundo durante uma partida longa. O histórico curto usado pela recuperação de vídeo é independente. A tela do diagnóstico só é criada depois de encerrar a reprodução. Não há consultas de rede adicionais para gerar o relatório.

O aplicativo salva apenas o último relatório no armazenamento privado local, sem tokens, SDP, endereços IP, identificadores da conta ou histórico ilimitado. Amostras em segundo plano e o primeiro intervalo após voltar são excluídos dos indicadores de qualidade; contadores de tráfego continuam sendo acumulados. Encerramento forçado do processo pode impedir a finalização, como em outros dados ainda não salvos.

## Verificação

Testes exercitam sessões com dez mil amostras, médias ponderadas, contadores reiniciados, dados desconhecidos, pausas, exclusão de fila/segundo plano, finalização única, persistência, migração da preferência antiga, cópia e foco por controle. A captura `docs/screenshots/session-report.png` usa dados de demonstração e não representa uma sessão medida. Não substitui teste de streaming em um dispositivo físico.
