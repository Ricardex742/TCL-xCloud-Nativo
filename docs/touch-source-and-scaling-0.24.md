# Retorno do teste no celular: fonte de toque e escala — 0.24

A versão `0.24.0-touch-source-and-scaling` corrige o limite local que deixava a imagem pequena e passa a registrar a fonte virtual usada pelo toque nativo. **Atualização posterior: o usuário confirmou o funcionamento do toque nativo no Fortnite, no LGE LM-K200 com Android 10, em uma sessão de 5min46s.** Veja a [análise desse relatório e a versão 0.25](catalog-performance-0.25.md). Essa validação no aparelho não estabelece suporte universal a outros dispositivos ou títulos.

## Evidência recebida

Após a entrega da 0.23, o usuário relatou que o toque continuou sem funcionar no Fortnite, em um LGE LM-K200 com Android 10. O relatório copiado não continha a versão do aplicativo. Foram registrados 27 contatos enviados, 587,6 KB de input, cerca de 112 segundos conectado e vídeo H.264 de 1392×656. O usuário confirmou que ativou o HUD virtual depois da falha. Por isso, o tráfego de input da sessão não pode ser interpretado como uso de controle físico durante o teste de toque nativo.

O diagnóstico também informou:

```text
Surface 1166x656 → janela 1452x689
Escala da Surface: desativada, sem ampliação na janela
Proporção: 16:9 — padrão
```

A captura mostra o jogo reduzido e centralizado. O relatório registra pausas de imagem, buffer médio de 66,79 ms, decode médio de 11,17 ms e FPS médio de 47,25. Essas medidas não demonstram a causa do problema de toque. Esta alteração não declara que resolveu as pausas ou o desempenho do decoder.

## Tamanho da imagem

`VideoScalingPolicy` vinculava o tamanho do layout à opção de escala da Surface. Com a opção desativada, a geometria era limitada pelas dimensões do vídeo recebido, mesmo que a janela comportasse uma imagem maior. Aplicar essa regra aos números do relatório reproduz exatamente os 1166×656.

O layout agora usa o maior tamanho que cabe na janela, respeitando a proporção escolhida, independentemente da opção de hardware. Essa opção continua salva e continua controlando `setEnableHardwareScaler`; o renderer WebRTC já desenha no tamanho do layout quando ela está desativada. Não foi adicionado um passo de readback, cópia de quadros na CPU ou navegador.

Para a janela de 1452×689 e o vídeo de 1392×656:

| Proporção | Tamanho do layout na 0.24 | Comportamento |
| --- | --- | --- |
| 16:9 — padrão | 1225×689 | Usa toda a altura; mantém barras laterais próprias de 16:9 numa janela mais larga. |
| Original | 1452×684 | Preserva a proporção larga recebida e ocupa quase toda a janela. |
| Esticar | 1452×689 | Preenche a janela, podendo deformar. |

As coordenadas de toque e as dimensões de apresentação usam a mesma geometria do renderer. Não houve migração ou alteração automática da preferência de proporção. Os textos do seletor e do diagnóstico foram atualizados para explicar o novo comportamento.

## Registro da fonte de toque

A análise anterior havia tratado a ausência de um controle físico como motivo para omitir `gamepadChanged` no modo nativo. A inspeção adicional do código oficial mostrou que esse raciocínio confundia uma conexão lógica de input com relatórios de controle físico.

Nos mesmos arquivos oficiais e hashes documentados na [análise da 0.23](native-touch-official-sdk.md):

- Em `game-stream.1f77dc77.chunk.js`, a classe da ponte TAK registra a fonte `tak` no índice 0 por `onVirtualGamepadChanged`, nas mudanças de estado/configuração. O registro não depende de haver um controle físico.
- Em `8128.254147fc.chunk.js`, o gerenciador de fontes registra o índice lógico 0 no primeiro `onGamepadChanged` e mantém esse índice enquanto houver fontes associadas.
- O mapeamento inicial da fonte tem `PhysicalPhysicality=0` e `VirtualPhysicality=0`. Registrar a fonte não exige fabricar botões pressionados ou transmitir continuamente um controle físico neutro.

Foram executadas isoladamente as classes oficiais da ponte e do gerenciador, com dependências mínimas simuladas. Disparar mudanças de estado e configuração produziu uma única conexão lógica:

```json
[{"index":0,"added":true}]
```

Isso demonstra uma divergência de inicialização da implementação anterior. Não é uma captura autenticada do Fortnite nem prova de que o registro seja a causa completa da falha.

Na 0.24, o handshake registra o índice lógico 0 também em sessões NativeTouch. `GamepadConnections` evita registros duplicados ao ativar o HUD ou usar um controle nesse índice e preserva a fonte lógica quando um controle físico é removido. Outros índices continuam podendo conectar e desconectar para coop local.

O pump de uma sessão nativa continua começando sem relatórios de gamepad físico; ele só transmite contatos após metadados válidos do servidor, conforme a 0.23. Os formatos dos contatos, botões e eixos não foram alterados nesta versão. Não foi incorporado código do SDK oficial ao runtime do aplicativo.

## Diagnóstico para o próximo teste

O relatório persistido e a tela pós-partida agora incluem versão do aplicativo, modo inicial de entrada, estado final do toque nativo, dimensões de input recebidas e estado do registro do slot virtual. A informação é limitada a 512 caracteres e não inclui tokens, SDP, IP ou IDs da conta.

Assim, uma sessão iniciada em toque nativo e depois alternada para o HUD fica identificável na cópia do relatório. Relatórios antigos continuam sendo lidos e mantêm os campos já salvos. O contador de contatos enviados continua medindo somente aceitação pelo canal local.

## Verificação e limite

Os testes reproduzem a geometria do LGE, mapeiam bordas/centro/barras da imagem ampliada, exercitam registro único e permanência do slot lógico e verificam persistência/cópia do estado de entrada após fallback. A suíte e o lint são executados antes do envio à branch; o GitHub Actions gera o APK com o segredo de assinatura já existente e verifica o certificado fixo.

Validação local: **229 testes JVM/Robolectric**, zero falhas, erros ou testes ignorados; `:app:lintDebug` sem problemas; **8 testes Python da proteção de assinatura** aprovados. Não houve celular conectado ao ambiente nem uma partida de validação da 0.24.

No próximo teste físico, atualizar o APK, escolher explicitamente toque nativo e tentar os comandos do jogo antes de ativar o HUD. Para comparar o preenchimento sem deformação, selecionar **Proporção → Original**; em 16:9, barras laterais numa tela mais larga são esperadas. Guardar o novo relatório se o toque continuar sem responder. A correção de escala está reproduzida por testes locais; o resultado do toque no jogo remoto permanece experimental.
