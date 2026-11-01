# TCL-xCloud-Nativo
Cliente experimental para Xbox Cloud Gaming em Android 10 ou superior, com vídeo nativo por WebRTC e SurfaceViewRenderer. A disponibilidade do jogo depende da conta, da região e da conectividade; o scaler nativo não comprova ativação de AiPQ 4K.

O workflow `build-apk.yml` pode ser executado manualmente na branch de desenvolvimento. Ele prepara JDK 21, Gradle 8.7 e Android SDK 34, executa os testes JVM, verifica APIs incompatíveis com Android 10, gera o APK e verifica a assinatura. Os artefatos incluem o APK e os resultados de testes/lint. O módulo instalável é `:app`; os comandos do workflow usam esse alvo explicitamente.

No ambiente de desenvolvimento já preparado, use:

```bash
/workspace/.cloud-setup/gradle-android :app:testDebugUnitTest :app:assembleDebug
```

Os testes usam respostas HTTP locais e credenciais fictícias. Exercitam expiração/renovação de tokens, polling de Device Code, cancelamento de sockets/sessões, SDP/ICE, handshakes e o formato dos pacotes de gamepad. Não substituem o teste de login e mídia em um dispositivo real. O lint do workflow seleciona `NewApi` e `InlinedApi`; não representa aprovação de todas as regras de publicação Android TV.

Durante o teste no aparelho, registre a etapa e o código de erro mostrados na tela:

| Etapa | Verificar |
| --- | --- |
| `AUTH_DEVICE_CODE` / `AUTH_TOKEN` | Geração, aprovação e expiração do código. |
| `AUTH_REFRESH` / `AUTH_XBL` / `AUTH_XSTS` / `AUTH_GSSV` | Credencial, código HTTP/OAuth/XErr e acesso da conta à nuvem. |
| `CATALOG` / `PLAY` / `SESSION_STATE` | Catálogo real e criação da sessão do jogo. |
| `SDP` / `ICE_LOCAL` / `ICE_REMOTE` / `TRANSPORT` / `CHANNELS` | Negociação e confirmação dos canais. |
| `VIDEO` | Bytes, quadros recebidos/decodificados e primeiro quadro renderizado. |

O HUD só anuncia vídeo ativo após `onFirstFrameRendered`. Os controles básicos de toque e de gamepad são enviados pelo canal input; vibração e layouts de toque específicos dos jogos ainda não são implementados. Voltar deve encerrar a sessão e permitir abrir outro jogo. Teste também cancelar o QR Code, renovar um login salvo e sair/reabrir o aplicativo. Não compartilhe tokens ou códigos de aprovação nos diagnósticos.
