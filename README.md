# TCL-xCloud-Nativo
Cliente experimental para Xbox Cloud Gaming em Android 10 ou superior, com vídeo nativo por WebRTC e SurfaceViewRenderer. A disponibilidade do jogo depende da conta, da região e da conectividade; o scaler nativo não comprova ativação de AiPQ 4K.

O workflow `build-apk.yml` roda nos pushes da branch `ci/apk-android10-3cc2595` e pode ser executado manualmente nela. Ele prepara JDK 21, Gradle 8.7 e Android SDK 34, verifica APIs incompatíveis com o Android mínimo declarado (API 24, incluindo Android 10), gera o APK debug e verifica sua assinatura. Baixe `TCL-xCloud-App-Debug-Android10` na seção **Artifacts** da execução: ele contém o APK e seu checksum SHA-256. O relatório de lint é salvo separadamente. O módulo instalável é `:app`; os comandos usam esse alvo explicitamente.

Com JDK 21, Gradle 8.7 e Android SDK 34 configurados, use na raiz do repositório:

```bash
gradle --no-daemon --max-workers=2 :app:assembleDebug :app:lintDebug
```

Os testes JVM legados estão desativados no módulo desta branch e não são executados pelo workflow de APK. A geração do APK e o lint não comprovam login ou mídia em um dispositivo real. O lint seleciona `NewApi` e `InlinedApi`; não representa aprovação de todas as regras de publicação Android TV.

Durante o teste no aparelho, registre a etapa e o código de erro mostrados na tela:

| Etapa | Verificar |
| --- | --- |
| `AUTH_DEVICE_CODE` / `AUTH_TOKEN` | Geração, aprovação e expiração do código. |
| `AUTH_REFRESH` / `AUTH_XBL` / `AUTH_XSTS` / `AUTH_GSSV` | Credencial, código HTTP/OAuth/XErr e acesso da conta à nuvem. |
| `CATALOG` / `PLAY` / `SESSION_STATE` | Catálogo real e criação da sessão do jogo. |
| `SDP` / `ICE_LOCAL` / `ICE_REMOTE` / `TRANSPORT` / `CHANNELS` | Negociação e confirmação dos canais. |
| `VIDEO` | Bytes, quadros recebidos/decodificados e primeiro quadro renderizado. |

O HUD só anuncia vídeo ativo após `onFirstFrameRendered`. Os controles básicos de toque e de gamepad são enviados pelo canal input; vibração e layouts de toque específicos dos jogos ainda não são implementados. Voltar deve encerrar a sessão e permitir abrir outro jogo. Teste também cancelar o QR Code, renovar um login salvo e sair/reabrir o aplicativo. Não compartilhe tokens ou códigos de aprovação nos diagnósticos.
