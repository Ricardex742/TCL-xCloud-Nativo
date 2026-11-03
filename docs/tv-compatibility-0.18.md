# xCLOUD 0.18 — compatibilidade e tela inicial

## Vídeo

O modo Compatibilidade é um preset explícito. Selecioná-lo salva 720p, escala nativa ativada, nitidez 0% e saturação 100%. Os parâmetros de recepção pedem 1 Mbps mínimo, 2,5 Mbps inicial e 6 Mbps máximo. O serviço pode negociar outros valores; as estatísticas apresentam os valores recebidos separadamente do pedido.

O codec H.264 Baseline/constrained baseline anunciado pelo decoder passa à frente de Main/High em 720p e no modo Compatibilidade. Outros perfis mantêm a preferência de qualidade. A seleção ordena capacidades existentes antes da oferta, sem fabricar ou substituir `profile-level-id`.

Nesse modo, a Surface apresenta no máximo 30 quadros/s e os relatórios periódicos de controle usam 60 Hz; transições de botão continuam despertando a thread imediatamente. Quadros codificados de referência não são descartados pelo aplicativo. O limite de apresentação não limita a decodificação: o servidor ainda pode entregar 60 quadros/s. O modo não comprova nem promete eliminar congelamentos causados pelo driver, pela rede ou pelo serviço.

O catálogo deixa de iniciar consultas de metadados, galerias e resolução de edições durante a fila e a reprodução. Pedidos já iniciados podem terminar. A entrada da partida cancela downloads de capas da biblioteca e libera o cache compartilhado; retornar ao catálogo retoma o trabalho. O orçamento do cache também acompanha a classe de memória Android, entre 4 e 20 MiB.

A recuperação no modo Compatibilidade começa após aproximadamente 1,2 segundo sem progresso medido; um decoder sem novos quadros pode ser reiniciado após quatro segundos, no próximo quadro-chave. Se as amostras recentes indicam ausência de bytes de vídeo, a recuperação solicita atualização e evita reiniciar o decoder para tratar uma falta de dados da rede. Quadros decodificados sem apresentação continuam direcionando a recuperação à Surface. As amostras não identificam sozinhas uma causa definitiva.

O diagnóstico da partida conserva somente as últimas 20 amostras em memória. Inclui bytes de vídeo, quadros decodificados, buffer, perdas e nome do decoder, além dos dados técnicos do aparelho. Não registra identificadores da conta, endereços de rede, credenciais ou SDP, e não envia esses dados a um serviço externo. A cópia é uma ação manual no menu.

## Atalhos

A publicação automática de capas e a solicitação manual de visibilidade agora têm jobs diferentes, serializados por mutex. Um lote de metadados não cancela o pedido iniciado pelo usuário. A autorização do canal é solicitada por `ACTION_REQUEST_CHANNEL_BROWSABLE` quando disponível, com retorno à atividade. O app consulta a coluna `browsable` e informa quando o sistema não autorizou o canal. Um canal autorizado ainda depende da apresentação do launcher.

A opção Instalar atalho na TV funciona sem a API de canais. O módulo `shortcut` produz um APK mínimo, sem permissões e com uma atividade de launcher Android/Leanback. O app incorpora esse modelo sem assinatura como asset. Ao escolher um favorito, ele personaliza pacote, nome, ID do jogo, ícone e banner e assina o resultado com uma chave RSA do Android Keystore do próprio aparelho. O certificado privado usado para atualizar o xCLOUD não é incluído nem utilizado nesse processo.

O editor de manifesto recompõe o string pool binário preservando índices e tipos de atributos, incluindo comprimentos UTF-8/UTF-16. A tabela de recursos recebe o nome de pacote correspondente. O ZIP mantém `resources.arsc` sem compressão e com alinhamento de quatro bytes, inclusive quando o aapt2 encurta os nomes dos arquivos. A biblioteca oficial `apksig` cria a assinatura APK v2, compatível com o mínimo API 24.

O instalador do sistema mantém o controle sobre a instalação e a permissão de fontes. O arquivo é compartilhado por um FileProvider restrito à pasta de atalhos, com permissão temporária de leitura. Os atalhos aparecem como aplicativos independentes e podem ser adicionados à fileira de favoritos da TV pelo launcher. Remover um jogo da biblioteca não desinstala esse aplicativo auxiliar.

Cada atalho contém apenas o ID público do jogo. Sua atividade abre `xcloud://play/<id>` no pacote principal e encerra. A atividade principal continua validando o jogo no catálogo autenticado antes de criar a sessão. Um identificador novo por abertura diferencia um toque novo no atalho de uma recriação da atividade. O nome do pacote por jogo e a chave local permanecem estáveis enquanto a instalação principal e seus dados forem mantidos.

## Verificação

Os testes incluem ordenação dos perfis H.264, SDP com teto menor sem alterações de áudio/transporte, recuperação sem reset em falta de dados, suspensão/cancelamento de trabalho do catálogo e a corrida entre atualização automática e autorização do canal.

Os APKs auxiliares gerados são verificados por `ApkVerifier`, `aapt dump badging` e `zipalign`. Os testes conferem assinatura v2, ausência de permissões, entrada Leanback, personalização de ícone, labels Unicode e atualização preservando pacote/certificado. Os testes de UI também reproduzem o preset e geram capturas das views nativas. O pipeline mantém a verificação da assinatura fixa do APK principal e dos shaders existentes.

Essas verificações não executam a instalação em uma TV física nem medem fluidez no hardware. A validação do aparelho deve observar as estatísticas recebidas e o comportamento da tela inicial após a instalação do atalho.
