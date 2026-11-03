# Atualização sem trocar a assinatura (0.16.0)

## Causa do erro

O workflow anterior usava a chave de debug criada automaticamente pelo Android Gradle Plugin. Cada execução do GitHub Actions começa em uma máquina limpa e criava uma chave diferente. O `applicationId` e os números de versão eram mantidos, mas o certificado mudava. O Android bloqueia substituir um aplicativo por outro com o mesmo pacote e outra assinatura, o que explica a instalação funcionar depois de desinstalar.

## Identidade persistente

O pacote de atualização é `com.xcloud.tclnative`. A versão inicial da correção tem `versionCode=16`; novas versões precisam manter esse identificador e aumentar o código de versão. O nome exibido permanece xCLOUD.

A chave privada é armazenada no segredo **XCLOUD_ANDROID_KEYSTORE_BASE64** do GitHub. O alias e a senha padrão de debug são `androiddebugkey` / `android`; o arquivo de chave é que precisa ficar privado. Não é uma chave pública de exemplo nem uma chave compartilhada do AOSP. Somente o fingerprint do certificado público está versionado em [signing/certificate-sha256.txt](../signing/certificate-sha256.txt).

1. Abra [Settings → Secrets and variables → Actions](https://github.com/Ricardex742/TCL-xCloud-Nativo/settings/secrets/actions).
2. Escolha **New repository secret**.
3. Nome: `XCLOUD_ANDROID_KEYSTORE_BASE64`.
4. No campo Secret, cole o conteúdo completo do arquivo privado `github-secret.txt` preparado para este projeto. Salve o segredo.
5. Execute **Compilar APK Android 10 e TV** na branch `ci/apk-android10-3cc2595` ou envie um commit para ela.

Guarde o backup privado do keystore e sua exportação fora do repositório. A API do GitHub não devolve valores de segredos cadastrados. Criar uma chave nova no lugar da original volta a impedir atualizações; o workflow rejeita esse caso pelo fingerprint fixo. A chave não aparece nos artefatos públicos, cache do Actions ou logs.

## Verificações do build

`scripts/prepare_signing.py` restaura o segredo em uma pasta temporária privada, confere o certificado com `keytool` e disponibiliza apenas o caminho/tipo do keystore ao Gradle. Ausência do segredo, base64 inválido, chave ilegível ou certificado diferente fazem o build falhar. Não existe fallback para uma chave temporária.

O Gradle também exige o certificado fixo antes de assinar. `scripts/verify_apk_signing.py` verifica a assinatura criptográfica do APK pronto, exige o certificado fixo e confirma o pacote e a versão. O artefato contém:

- `app-debug.apk`;
- `app-debug.apk.sha256`;
- `app-debug.apk.metadata.json`, com pacote, versão, SHA-256 do APK e fingerprint público de assinatura.

Os testes Python criam chaves efêmeras exclusivamente para teste e verificam restauração em duas pastas limpas com a mesma identidade, permissões privadas, rejeição de outra chave, ausência de segredo, proteção contra publicação acidental de keystores e identidade do APK.

## Instalações antigas

As chaves aleatórias dos runners anteriores não foram guardadas. O APK novo não pode atualizar uma instalação assinada por uma dessas chaves sem possuir sua chave privada antiga. Pode ser necessária uma última desinstalação para adotar a identidade fixa; isso apaga os dados locais daquela instalação. As versões assinadas com a chave persistente podem atualizar essa nova instalação preservando login, preferências e favoritos.

O print do instalador apresenta uma mensagem genérica. A troca de chave foi confirmada no processo de build; uma validação da atualização no aparelho ainda deve ser feita instalando duas versões assinadas pela mesma chave.

## Builds locais

O responsável pela chave pode preparar um build com:

```bash
python3 scripts/prepare_signing.py --secret-file /caminho/privado/github-secret.txt
```

Configure `XCLOUD_KEYSTORE_PATH` e `XCLOUD_KEYSTORE_TYPE` com os valores de caminho/tipo exibidos pelo script, e execute os comandos Gradle do README. Nenhum material privado deve ser adicionado ao Git. O diretório `.private-signing/` é ignorado para quem configurar manualmente um keystore local de debug compatível.
