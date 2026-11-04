# Toque nativo e análise direta do cliente oficial — 0.23

A versão `0.23.0-native-touch-protocol` corrige divergências verificadas no protocolo do motor Android/WebRTC. **Ainda não demonstra que o toque funciona no Fortnite em uma partida real.** Não houve login no cliente oficial, sessão autenticada de comparação ou celular conectado nesta investigação.

## Acesso e fontes oficiais

Em 4 de novembro de 2026, `https://www.xbox.com/pt-BR/play` respondeu **HTTP 200**, utilizando o proxy configurado e verificação TLS. Tanto a configuração publicada quanto o rascunho já tinham política HTTP `unrestricted`, observada como `enforced` no ambiente. Nenhuma permissão existente foi removida ou ampliada. Não houve necessidade de salvar uma nova lista de domínios. Os controles desta cópia permitem ler e salvar rascunhos, mas não publicar o ambiente.

O executor com isolamento de rede retornou inicialmente `Failed to connect to proxy`. A execução autorizada com acesso ao proxy funcionou; não foi necessário contornar o proxy ou desativar TLS. Esse resultado distingue a falha local de conectividade do antigo 403 relatado na investigação anterior.

O HTML referencia arquivos em **`assets.play.xbox.com`**. Os módulos sob demanda foram identificados no mapa de chunks do próprio cliente. Somente esse host adicional foi necessário para os JavaScripts analisados; os outros scripts de consentimento, navegação e fontes não foram necessários. A política existente já o permite. Em um ambiente com lista restrita, os hosts de leitura desta investigação seriam `www.xbox.com` e `assets.play.xbox.com`; isso não constitui uma lista completa para autenticar ou jogar.

O módulo do SDK declara `webSdkPackageVersion: "10.7.0"`. Foram lidos diretamente:

| Arquivo oficial | Comportamento observado | SHA-256 |
| --- | --- | --- |
| [8128.254147fc.chunk.js](https://assets.play.xbox.com/playxbox/static/js/8128.254147fc.chunk.js) | `StreamSession`: características do cliente, dimensões e ativação de toque; enum dos tipos de input. | `f246f4a06c1e898a63fa008b58475e5b832ba0e6c573c5b2a22df9f8dd833bc5` |
| [web-rtc-stream.d6f6b903.chunk.js](https://assets.play.xbox.com/playxbox/static/js/web-rtc-stream.d6f6b903.chunk.js) | Serializador `generateInputReport`, `generatePointerFrame`, `generateClientMetadataReport`; canal confiável e `ServerMetadata`. | `0e1d97750113681c7c2d1edfc7910c1b2915055450a7cfde8fc7a0a54fef73d2` |
| [game-stream.1f77dc77.chunk.js](https://assets.play.xbox.com/playxbox/static/js/game-stream.1f77dc77.chunk.js) | `shouldEnableTouchInput`, configuração TAK e tradução de `onTouch` para ponteiros virtuais do SDK. | `704a86d5b3e1c41adc9f6339b07c0cd2a049dbd414149bddf8d667d828f2d13e` |
| [stream-page.6da52336.chunk.js](https://assets.play.xbox.com/playxbox/static/js/stream-page.6da52336.chunk.js) | Configuração inicial: `enableTouchInput`, capacidade `navigator.maxTouchPoints` e versão do canal. | `d0f29fc28f70bbf5a3bc5538e54026ebbb6ae1362adb682c56e2ebe66022706e` |

SHA-256 do HTML obtido: `26bf4243347beef64a29763514dbf0cfb330cbbcecc8fa9ac8b2be43ad470efe`. Os nomes dos arquivos são versionados pelo serviço e podem mudar. Não foram incorporados o SDK, TAK, navegador ou código proprietário ao aplicativo.

## Caminho de toque observado

O estado de catálogo embutido no HTML identifica Fortnite como produto `BT5P2X999VH2`, Xbox title ID `267695549`. Suas ofertas `xgpuweb` e `cloudgaming` incluem `nativetouch`, `generictouch`, `mkb` e `nativetouchorcustomtouchoverlay`. Isso confirma a capacidade publicada do título; não confirma qual interface o jogo remoto exibiu em uma sessão.

1. O cliente habilita toque quando o título anuncia `NativeTouch` e o dispositivo oferece toque, ou por uma opção de desenvolvedor. Capacidade de toque e ativação são informações distintas.
2. O canal transmite `ClientMetadata` com o máximo de contatos. A sessão envia `/streaming/characteristics/touchinputenabledchanged` com `touchInputEnabled`. Essa parte já existia no motor Android.
3. A ponte TAK recebe contatos normalizados. Para um contato ativo, `down` passa a ser `pointermove`; para um ID novo, vira `pointerdown`; a soltura vira `pointerup`. Ela chama `onVirtualPointerInput` com posição, diâmetro, pressão, orientação e ID, usando dimensões de cliente unitárias. TAK também desenha controles virtuais em outros títulos; isso é separado de o jogo receber NativeTouch.
4. No canal confiável, `flushData` não envia ponteiros antes de receber `ServerMetadata`. Os metadados contêm altura e largura de input, nessa ordem, depois do tipo de relatório. O serializador converte os valores normalizados para essas dimensões.
5. O bloco de contatos tem um contador de quadros e um contador de contatos por quadro. Cada contato tem 20 bytes: altura uint16, largura uint16, pressão uint8, orientação uint16, ID uint32, X uint32, Y uint32 e evento uint8. Multibytes são little-endian; eventos são down=1, up=2 e move=3. `DataView` trunca as frações ao converter para inteiros.
6. A mensagem `dimensionschanged` descreve a apresentação local. `horizontal`/`vertical` são **milímetros da área de vídeo**; o SDK os estima com um elemento CSS de 1 cm. `preferredWidth`/`preferredHeight` são **pixels do contêiner**, multiplicados por `devicePixelRatio`. A área segura também usa esses pixels. Esse caminho é independente dos metadados de input do servidor.

A leitura mostra o caminho genérico de NativeTouch do cliente oficial. Não foi observado o Fortnite executando esse caminho nem o código interno do jogo. A opção de transporte não confiável, também presente no SDK, não foi habilitada no aplicativo.

## Correções no motor Android

- **Dimensões com unidades corretas.** A 0.22 enviava a resolução de streaming como milímetros e pixels simultaneamente. Ao receber metadados do servidor, sobrescrevia novamente as características da apresentação com a escala de input. Agora `ClientDisplayDimensions` é independente de `TouchInputDimensions`. O renderer informa a área exibida e o tamanho real de seu contêiner, atualizando-os em mudanças de layout, proporção ou vídeo. Valores desconhecidos não produzem uma mensagem inventada.
- **Adaptação Android da medição.** O app usa `xdpi`/`ydpi` informados pelo Android para converter a área exibida para milímetros, com fallback para `densityDpi` quando o valor é inválido. É uma adaptação das unidades observadas, não a reprodução da estimativa CSS do navegador. A precisão depende das métricas anunciadas pelo aparelho. A área segura corresponde ao contêiner do renderer, que já é a área utilizada pelo app.
- **Esperar a escala de input.** O pump recebe as duas dimensões como um par atômico e só retira contatos da fila depois de metadados válidos. Controles físicos continuam funcionando enquanto isso. A fila existente e limitada preserva down/up ocorridos durante a inicialização; o SDK web descarta ponteiros pendentes antes dos metadados. Não se tenta adivinhar a escala pela resolução solicitada.
- **Conversão binária compatível.** Altura, largura, pressão e coordenadas de contatos passam a truncar frações como o serializador oficial. Valores menores que um pixel podem resultar em área zero. A serialização dos botões e eixos Xbox permanece a mesma.

Não foi identificada evidência para alterar por tentativa a identidade da sessão, o endpoint de provisionamento, os tipos de canal ou os códigos dos eventos. HUDs existentes, favoritos, configurações, relatório pós-partida e assinatura fixa foram preservados. Nenhuma chave de atualização nova foi criada.

## Amostra binária de referência

Foi executado localmente somente o serializador isolado do módulo oficial, com dependências mínimas de enums e propriedades. Nenhuma conexão de streaming foi criada. `generateClientMetadataReport(8,10)` utilizou sequência 1; a chamada seguinte a `generateInputReport` utilizou sequência 2. Para comparação determinística, apenas os oito bytes do relógio do cabeçalho foram substituídos por `10.0` ms. A origem do relógio e o número inicial de sequência não foram tratados como exigências do servidor.

Entradas: versão confiável 8, largura do servidor 1280, altura 720, um quadro com estes dois contatos (`clientWidth=clientHeight=1`, orientação 0):

| ID | Evento | X | Y | Largura | Altura | Pressão |
| --- | --- | --- | --- | --- | --- | --- |
| 3 | down | 0.1234 | 0.9876 | 0.002 | 0.003 | 0.5 |
| 9 | up | 1 | 0 | 0.0005 | 0.001 | 0 |

Resultado oficial, 56 bytes:

```text
04000200000000000000000024400102020002007f0000030000009d000000c7020000010000000000000009000000000500000000000002
```

O teste `fractionalContactsMatchTheOfficialReliableV8SerializerFixture` compara o pacote Kotlin inteiro com esse resultado. A versão anterior produz diferenças nos valores fracionários e na área menor que um pixel. Outros testes exercitam espera por metadados, gamepad simultâneo, múltiplos IDs, soltura, unidades, orientação da apresentação e DPI inválido. Essa amostra não é uma captura de uma partida nem prova de aceitação pelo servidor.

## Validação e próximo teste no aparelho

A validação local passou em **226 testes JVM/Robolectric**, sem falhas, erros ou testes ignorados, mais **8 testes Python da proteção de assinatura**. `:app:lintDebug` terminou com `No issues found`. Foi necessário instalar um JDK 21 completo porque o Java inicialmente disponível não incluía `jlink`. A compilação e os testes usaram Gradle 8.7 e Android SDK 34, com TLS e checksums preservados.

Esses testes verificam comportamento local. O APK principal assinado é gerado pelo GitHub Actions da branch, com o segredo já existente e verificação do certificado fixo. Nenhuma chave de assinatura do aplicativo foi provisionada neste ambiente local.

Para validar o objetivo final, é preciso atualizar o celular com o APK desta versão, abrir Fortnite no modo de toque nativo, verificar se os controles do próprio jogo aparecem e testar dois dedos simultâneos, movimento, câmera e soltura ao abrir/fechar o menu. Comparar com o cliente oficial no mesmo celular e guardar o diagnóstico pós-partida. O contador de contatos enviados continua indicando somente pacotes aceitos pelo canal local. Até esse teste, o suporte permanece experimental e a causa completa da falha anterior não está confirmada.
