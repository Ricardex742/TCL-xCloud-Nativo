# Coleta ICE local intermitente e recuperação na 0.31.2

## Relato e diagnóstico

O usuário relatou que, às vezes, o início de um jogo falha com `ICE_LOCAL: nenhum candidato de rede disponível`. Aparelho, versão instalada e tipo de conexão não foram confirmados no relato inicial. Não foi recebida uma captura nativa da ocorrência; a causa física permanece por confirmar.

Essa mensagem é gerada pelo próprio app em `XCloudWebRtcClient.createOfferSdp`, antes de enviar a oferta SDP ao Xbox. Significa que a lista de endereços/candidatos reais produzidos pelo WebRTC ficou vazia; não demonstra, por si só, falta de Internet, problema do jogo, recusa de HEVC ou bloqueio do STUN.

A implementação anterior esperava o evento `IceGatheringState.COMPLETE` por até oito segundos e então consultava a lista. Se COMPLETE chegasse sem candidatos, encerrava imediatamente, mesmo antes do restante desse prazo. Não havia nova tentativa local. Isso deixava o fluxo vulnerável a uma descoberta tardia das interfaces/candidatos. A revisão trata esse cenário; sem trace do aparelho, ele não é apresentado como causa comprovada de todas as ocorrências.

## Mudanças

1. Cada PeerConnection possui uma coleta própria. COMPLETE só encerra a espera com sucesso quando há pelo menos um candidato. COMPLETE vazio continua aguardando até o limite de oito segundos. Eventos tardios da tentativa descartada não alimentam a seguinte.
2. Ao terminar o prazo, candidatos reais disponíveis continuam sendo usados, inclusive host/IPv6, mesmo se o STUN não tiver concluído. Não são inventados endereços para preencher uma coleta vazia. A remoção de candidatos por mudança de interface durante a coleta passa a ser considerada.
3. Somente uma coleta local vazia permite uma segunda PeerConnection. Os canais e a conexão antigos são liberados antes da segunda tentativa. A fábrica nativa é reutilizada; o monitor de rede já tem mais tempo para atualizar seu inventário.
4. A nova tentativa acontece **antes do POST de SDP**, dentro da sessão Xbox já provisionada. Ela não solicita outro jogo nem outra fila. Somente a oferta e os candidatos da PeerConnection final são enviados. Preferência de codec, perfil de vídeo, áudio e input são preservados.
5. Há no máximo duas coletas, cada uma com prazo de oito segundos; a preparação local completa tem limite de 30 segundos incluindo criação de PeerConnection/canais/SDP. O início normal não ganha um atraso fixo: COMPLETE com candidatos libera o fluxo imediatamente. A interface continua livre enquanto a coroutine aguarda.
6. Uma geração identifica a PeerConnection atual. Callbacks de ICE, trilhas e canais antigos são ignorados depois da invalidação; os observers de canais são removidos antes de fechar o transporte. O descarte para a nova tentativa executa em Main, como o fechamento final e `onDestroy`, para não fechar o mesmo transporte simultaneamente em threads diferentes. A liberação da fábrica/áudio/renderer ao fim da sessão conserva o caminho anterior.
7. Cancelar/voltar não dispara a nova tentativa. Erros HTTP no envio ICE, falhas de codec/SDP, ICE remoto e transporte não são tratados como coleta local vazia. Essa política não reinicia partidas já exibidas e não altera o retorno experimental para H.264.
8. O relatório e o erro final incluem tentativa, estado da coleta, quantidade/tipo de candidatos e códigos numéricos STUN quando fornecidos pelo SDK. Não registram SDP, IPs, portas, URLs STUN nem texto bruto dos erros nativos. Sem candidato nas duas tentativas, o app conserva a falha e orienta verificar a conexão/tentar novamente.
9. Versão **0.31.2-ice-recovery**, versionCode **33**. Pacote e certificado de atualização permanecem os mesmos.

## Testes e limites

Os testes de tempo usam o relógio virtual de `kotlinx-coroutines-test:1.7.3`, alinhado à versão de coroutines já usada em produção. Cobrem COMPLETE vazio seguido de candidato tardio, prazo fixo com lista vazia, host/IPv6 com STUN pendente, caminho normal imediato, remoção de interface, duplicatas, callbacks tardios após fechar, cancelamento, diagnósticos sem endereços, descarte antes da segunda tentativa e limite de duas coletas.

Os testes HTTP usam o cliente de sinalização real contra MockWebServer e a política de recuperação real com um peer simulado. Conferem que a recuperação faz um único PLAY, um único CONNECT e um único POST de SDP/ICE, sem DELETE entre as tentativas. Duas coletas vazias encerram a sessão Xbox sem enviar SDP ou candidatos falsos. Os testes não exercitam o JNI de rede Android nem comprovam conectividade física.

A validação final passou em **346 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, além dos **8 testes Python de proteção da assinatura**. Foram incluídos 14 testes de coleta/recuperação com relógio virtual e dois novos casos HTTP na suíte de sinalização. `:app:lintDebug` concluiu com `No issues found` e `:app:assembleRelease` gerou o pacote sem assinatura. O pacote foi conferido como `com.xcloud.tclnative`, versionCode 33 e versionName `0.31.2-ice-recovery`.

O APK de atualização continua sendo gerado pelo workflow existente na mesma branch experimental, com o certificado fixo. Não foi criada uma chave substituta nem alterada a configuração publicada do ambiente. A correção foi validada por testes locais; a confirmação da falha intermitente e da recuperação no aparelho continua pendente.

## Conferência no aparelho

Instalar a 0.31.2 e repetir o início de jogos algumas vezes, incluindo retorno ao catálogo e novo início. Se houver recuperação, a tela informa a segunda tentativa e o relatório identifica `ICE local 1/2` e `2/2`. Se persistir a falha, copiar a mensagem completa com os estados/códigos e informar aparelho, versão e tipo de conexão. Isso permitirá distinguir coleta vazia persistente de uma falha posterior de alcance do Xbox.
