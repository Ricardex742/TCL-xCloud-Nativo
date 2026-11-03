# Configurações de reprodução e Game Pass (0.13.0)

O título selecionado usa verde no segundo trecho. O cabeçalho mantém perfil e gamertag, amplia a assinatura e oferece uma engrenagem de Configurações ao lado dela. Rewards e seu atalho foram removidos. Um nível confirmado aparece como GAME PASS ULTIMATE, outro nível reconhecido ou FREE; ausência de informação aparece por extenso como ASSINATURA NÃO INFORMADA. O aplicativo não deduz Ultimate pelo acesso à nuvem nem Free pela ausência de dados.

A aba **GAME PASS** substitui Mais jogados. Ela cruza os IDs de produtos dos títulos disponíveis para streaming com a galeria oficial Game Pass e exclui a galeria free-to-play. As duas consultas precisam ter sucesso. Histórico, favoritos e selos de metadados não preenchem essa aba. Falha de consulta produz uma mensagem de indisponibilidade com indicação para atualizar o catálogo. A pesquisa continua no botão X e filtra a categoria atual.

## Escolhas de reprodução

Start no catálogo ou a engrenagem abre Configurações. O painel reúne vídeo, resolução, upscaling, gerenciamento de assinatura, atualização do catálogo e troca de conta. As três primeiras opções abrem listas com seleção marcada; abrir a lista ou voltar com B não muda nada. Confirmar com A/toque salva a escolha e retorna às configurações. As preferências são locais ao dispositivo e aplicadas na próxima partida. A preferência antiga de vídeo é preservada ao atualizar o app.

| Opção | Escolhas e aplicação |
| --- | --- |
| Vídeo | Menor atraso solicita buffers reduzidos e baixa latência quando suportada; Adaptativo permite mais margem para oscilações. |
| Resolução | 720p (1280×720, perfil Android), 1080p (1920×1080, perfil Windows) e 1080p HQ (1920×1080, perfil Tizen). |
| Upscaling nativo | Ativado preenche a janela pela Surface Android; desativado centraliza o vídeo sem ampliá-lo na janela. Vídeo maior que a janela é reduzido para caber. |

Os seletores de resolução seguem o [contrato usado pelo Better xCloud](https://github.com/redphx/better-xcloud/blob/main/src/utils/network.ts). O pedido usa o perfil no corpo de criação e no cabeçalho de dispositivo, além das dimensões correspondentes. As faixas solicitadas no SDP são 5–12 Mbps em 720p, 10–20 Mbps em 1080p e 20–30 Mbps em HQ. Não obrigam o servidor a entregar esses valores: jogo, serviço e rede determinam o vídeo recebido. 1080p HQ tem a mesma resolução de 1080p, com outro perfil de qualidade. Não há opção de transmissão 4K ou resolução arbitrária sem suporte confirmado.

Desativar upscaling pode deixar barras em volta de um vídeo 720p/1080p numa janela maior e deixa de solicitar a saída 4K60 do app na TV. O tamanho usa os quadros efetivamente recebidos, incluindo mudanças de resolução/rotação e recuperação da Surface. Isso controla a escala na janela Android, sem prometer correspondência física de pixels no painel. AiPQ, MEMC e escala adicional da TV dependem das configurações da TV; a opção não desliga esse processamento proprietário. Ativar mantém o caminho nativo existente sem acrescentar filtro de imagem na GPU.

## Estatísticas

Cada nova partida começa com as estatísticas ocultas, mesmo que estivessem abertas no jogo anterior. **Start + Select**, **INFO** ou **F10** alterna oculto → compacto → completo → oculto. A ativação é manual e não é salva entre partidas. O painel completo informa o perfil solicitado e as medidas reais recebidas; ele não anuncia sempre HQ quando outra opção foi escolhida. A coleta continua disponível para recuperação de vídeo, mas o texto de estatísticas não é formatado enquanto oculto.

## Verificação

Os testes cobrem preferências e migração, seleção/retorno com o controle, persistência das três escolhas, envio dos perfis de resolução em sessões sucessivas, SDP sem duplicação, limites da escala, ativação manual de estatísticas, filtro estrito Game Pass e assinatura sem inferência de valores ausentes. As capturas dos seletores e do catálogo ficam em `app/build/reports/dashboard/` no artefato de relatórios do Actions. Elas usam dados de teste, sem confirmar consultas Microsoft ou desempenho em hardware real.

No aparelho, conferir 720p em um dispositivo fraco, reiniciar uma partida com cada configuração, testar barras com upscaling desligado e ativar estatísticas para comparar resolução/decode/buffer. A compatibilidade do serviço e a diferença de latência precisam dessa medição real.
