# TCL-xCloud-Nativo
Cliente experimental para Xbox Cloud Gaming em Android 10 ou superior, com vídeo nativo por WebRTC e SurfaceViewRenderer. A disponibilidade do jogo depende da conta, da região e da conectividade; o scaler nativo não comprova ativação de AiPQ 4K.

O workflow `build-apk.yml` roda nos pushes da branch `ci/apk-android10-3cc2595` e pode ser executado manualmente nela. Ele prepara JDK 21, Gradle 8.7 e Android SDK 34, executa os testes JVM, verifica APIs incompatíveis com o Android mínimo declarado (API 24, incluindo Android 10), gera o APK debug e verifica sua assinatura. Baixe `TCL-xCloud-App-Debug-Android10` na seção **Artifacts** da execução: ele contém o APK e seu checksum SHA-256. O relatório de lint é salvo separadamente. O módulo instalável é `:app`; os comandos usam esse alvo explicitamente.

Com JDK 21, Gradle 8.7 e Android SDK 34 configurados, use na raiz do repositório:

```bash
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Os testes JVM usam HTTP local e credenciais fictícias: verificam renovação de login, credenciais de transferência, fila, autenticação em `/connect`, ordem SDP/ICE, cancelamento, handshakes e pacotes de gamepad. O workflow falha se não houver testes executados ou se algum falhar/for ignorado. A geração do APK e os testes não comprovam login ou mídia em um dispositivo real. O lint seleciona `NewApi` e `InlinedApi`; não representa aprovação de todas as regras de publicação Android TV.

Durante o teste no aparelho, registre a etapa e o código de erro mostrados na tela:

| Etapa | Verificar |
| --- | --- |
| `AUTH_DEVICE_CODE` / `AUTH_TOKEN` | Geração, aprovação e expiração do código. |
| `AUTH_REFRESH` / `AUTH_XBL` / `AUTH_XSTS` / `AUTH_GSSV` | Credencial, código HTTP/OAuth/XErr e acesso da conta à nuvem. |
| `CATALOG` / `PLAY` / `SESSION_STATE` | Catálogo real e criação da sessão do jogo. |
| `SDP` / `ICE_LOCAL` / `ICE_REMOTE` / `TRANSPORT` / `CHANNELS` | Negociação e confirmação dos canais. |
| `VIDEO` | Bytes, quadros recebidos/decodificados e primeiro quadro renderizado. |

O HUD só anuncia vídeo ativo após `onFirstFrameRendered`. Os controles básicos de toque e de gamepad são enviados pelo canal input; vibração e layouts de toque específicos dos jogos ainda não são implementados. Voltar deve encerrar a sessão e permitir abrir outro jogo. Teste também cancelar o QR Code, renovar um login salvo e sair/reabrir o aplicativo. Não compartilhe tokens ou códigos de aprovação nos diagnósticos.

## Inicialização da sessão (0.5.0)

`ReadyToConnect` exige autenticação do console: obtenha a credencial OAuth com o escopo `PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN`, envie-a em `/connect` e aguarde `Provisioned`. O token GSSV autentica a API de streaming e não substitui essa credencial. O cliente solicita a transferência apenas nessa etapa, preservando o refresh token e seu client ID. Se o login antigo de navegador não tiver refresh token, saia e entre pelo QR Code; não cole tokens nos diagnósticos.

`WaitingForResources` é a fila do serviço. O aplicativo aguarda até 60 minutos, mostra o tempo decorrido e permite cancelar com Voltar. Ele não pode garantir capacidade nem uma posição na fila. Erros e cancelamentos encerram a sessão alocada; durante a reprodução, `/keepalive` é enviado a cada 30 segundos.

O WebRTC configura H.264 antes de gerar a oferta; o SDP enviado é o mesmo instalado localmente, sem falsificar o perfil do decoder. Candidatos IPv4/IPv6 são preservados, incluindo rotas IPv4 extraídas de Teredo. Um STUN público auxilia a travessia de NAT; redes que bloqueiam UDP ou exigem relay ainda precisam de diagnóstico no aparelho. O cliente não inventa credenciais TURN.

O renderizador é inicializado a cada partida e recebe a trilha nativa. A tela só informa vídeo ativo no evento `onFirstFrameRendered`; transporte sem quadro por 20 segundos gera diagnóstico `VIDEO`. Isso não comprova ativação do AiPQ. Controles físicos enviam estados pelo canal input; controles de toque e vibração ainda não estão implementados.

A ordem de provisionamento foi conferida no [Greenlight](https://github.com/unknownskl/greenlight/blob/main/packages/desktop/main/helpers/streammanager.ts) e no [xbox-xcloud-player](https://github.com/unknownskl/xbox-xcloud-player/blob/main/src/example/stream.ts). Os testes locais simulam os estados e o protocolo; Fortnite/Forza e a disponibilidade da conta/região devem ser verificados no dispositivo com o novo APK.

## Login e previsão de fila (0.6.0)

O login QR agora usa o cliente xCloud `1f907974-e22b-4810-a9de-d9647380c97e` e o Device Code Microsoft v2 (`login.microsoftonline.com/consumers/oauth2/v2.0`), com `xboxlive.signin openid profile offline_access`. A renovação normal usa esse mesmo fluxo v2; a troca pelo console transfer token usa `login.live.com/oauth20_token.srf`, o mesmo client ID e o escopo de transferência. O login antigo não pode ser reutilizado para esse escopo: a tela pede um novo QR Code, sem trocar arbitrariamente o client ID de um refresh token existente. A autorização do console é verificada antes de mostrar o catálogo para detectar falhas antes da fila.

Em `WaitingForResources`, a tela central exibe a estimativa retornada por `/v1/waittime/{titleId}`, tempo já aguardado, idade da última confirmação da fila e botão Cancelar. A consulta da previsão tem timeout de 3 segundos e intervalo de 30 segundos. Uma previsão ausente/falha não interrompe a fila e nunca vira uma espera inventada de zero minutos. A última previsão válida é marcada como desatualizada se a atualização falhar. O relógio da interface continua atualizando a cada segundo mesmo enquanto uma chamada HTTP aguarda resposta.

A estimativa é do serviço para aquele jogo, não uma posição individual nem contagem regressiva garantida. A espera máxima desta tentativa é exibida na tela (60 minutos); Voltar ou Cancelar libera a sessão. Sem confirmação de `Provisioned`, o aplicativo não inicia a troca de SDP.
