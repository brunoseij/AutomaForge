# Verificações — 0.4.0-alpha

## Resultado local desta entrega

- JDK 17, Gradle 8.7 e SDK Android 35: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon` terminaram com **BUILD SUCCESSFUL**.
- **52 testes JVM**, zero falhas/erros/ignorados: 20 testes de estado/validação/simulação, 6 testes do ciclo de proposta no dispatcher LAN, 26 regressões do pipeline de manuais.
- **7 contratos estáticos** passaram: sintaxe JavaScript, IDs da interface, esquemas, rotas, PDF, isolamento das cartas privadas e câmera/build existentes.
- Lint: **zero erros, 13 avisos**. Os avisos restantes são de internacionalização, APIs antigas e configurações existentes; não foram apresentados como erros funcionais resolvidos.
- `git diff --check`: sem erros. Inspeção confirmou ausência de testes instrumentados, dependências exclusivas e job de emulador. As variantes instrumentadas estão desabilitadas no Gradle.
- APK: `com.automaforge.android.debug`, versionCode 4, versionName 0.4.0-alpha, minSdk 26, targetSdk 35. Assinatura v2 verificada com `apksigner verify`.
- Nenhuma instrução ou condição específica de Harmonies existe em `app/src/main`.

## O que os dados simulados cobrem

Estado completo com lotes, grupos, casas e níveis; oferta desconhecida; ocupação não confirmada; componente inferido; correção humana preservada após análise candidata; duplicação de lotes; correção concorrente; formato/enumeração inválidos; intenção genérica; escolha textual vaga; parâmetros obrigatórios; peça/destino inexistentes; jogador/fase incorretos; versão antiga do estado/regras; seleção prévia; vizinhança hexagonal axial; simulação sequencial de níveis e mudança de localização; ausência de validador; migração e persistência de sala antiga; rejeição de informação privada no estado público.

Os testes do dispatcher usam Robolectric na JVM, sem abrir navegador, interface Android ou dispositivo. Conferem que aprovar não executa, execução exige aprovação, prefixo parcial altera somente o realizado, execução total usa a simulação, correção torna a proposta antiga inaprovável e a rota manual recusa ação sem movimentos. Não são testes instrumentados nem testes automatizados de interface.

As regressões de PDF usam documentos sintéticos e MockWebServer. Respostas, chamadas e tokens simulados não demonstram qualidade ou custo da API real. Testar esquemas e lógica de observações não demonstra capacidade visual do modelo.

## Não executado

**Nenhum aparelho físico, emulador, Android instrumentation, automação de interface, fotografia real de partida ou chamada real à OpenAI foi usado nesta entrega.** Não há evidência de reconhecimento confiável de Harmonies ou de comportamento estratégico real. O usuário fará essa validação com o [roteiro manual](MANUAL_VALIDATION.md).

Não há validação universal de regras. Restrições/efeitos sem implementação ficam explicitamente não verificáveis. Não há busca exaustiva que prove ausência de jogada legal. A correção e calibração são assistidas por JSON/toque, sem fusão automática multivisão, rastreamento visual universal ou estimativa de perspectiva. Uma imagem antiga reenviada pode não ser detectável como antiga; versões e hora de recepção apenas ajudam a identificar desatualização conhecida.

## Código entregue

Trabalho realizado sobre a versão local 0.3, commit `12272c1`, preservando o pipeline de manuais anterior. A entrega inclui código completo e patch incremental sobre essa base. Nenhuma alteração foi publicada no repositório remoto nesta solicitação. Os relatórios brutos JVM, lint e build estão em `validation/` do pacote de fontes.
