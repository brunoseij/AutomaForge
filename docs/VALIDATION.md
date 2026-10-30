# Verificações — 0.8.0-alpha

## Resultado local

- Build limpo com JDK 17, Gradle 8.7 e SDK Android 35: `:app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon` concluiu com **BUILD SUCCESSFUL**.
- Após a revisão final de nulabilidade, os mesmos testes/lint/APK passaram novamente com `--offline --no-daemon`, sem novo `clean`.
- **121 testes JVM em 15 suítes**, zero falhas, erros ou ignorados. São 104 regressões anteriores e 17 novos casos relacionados à arquitetura e ao ciclo de vida.
- **8 contratos estáticos** passaram, além da inspeção estática de HTML, selects e esquema Room. Não houve execução automatizada da interface.
- **Lint: zero erros e 8 avisos** no relatório atualizado. Permanecem avisos sobre commit de preferências, orientação, dependência Bouncy Castle, backup, kapt/KSP e ícone. Isso não é uma auditoria de segurança ou declaração de que todos os avisos foram corrigidos. Há também avisos do compilador sobre conversões redundantes e APIs mantidas por compatibilidade.
- `git diff --check`: sem erros.
- Código de produção: **43 arquivos Kotlin**, sem arquivos Java em `app/src/main`. Java continua apenas nas regressões JVM existentes; Dagger/Room geram suas implementações durante o build.
- APK `com.automaforge.android.debug`, **0.8.0-alpha / versionCode 8**, minSdk 26, targetSdk 35. Assinatura v2 verificada por `apksigner verify`.
- Esquema Room versão 1 exportado em `app/schemas/com.automaforge.android.data.local.AutomaDatabase/1.json`.

## Cobertura nova

- Room real com SQLite no host JVM: importação de JSON legado, campos variáveis preservados, arquivos/fotos mantidos e inclusão idempotente sem sobrescrever dados mais novos.
- Arquivo legado inválido isolado e mantido para recuperação; demais salas continuam disponíveis.
- Proposta genérica legada continua sem possibilidade de aprovação como movimento concreto após importar.
- Memória privada excluída do documento persistido e perdida ao recriar o repositório; encerramento do servidor limpa essa memória e recusa novos comandos.
- Fluxo Room emitindo alterações já persistidas e componente Dagger fornecendo as instâncias singleton esperadas.
- ViewModel recuperando rota pública via SavedStateHandle, observando salas e recusando destino externo; nenhuma Activity, imagem ou chave é incluída no estado salvo.
- Uma captura em envio impede que imagens adicionais formem uma fila privada. Destinos de câmera inválidos são recusados antes do envio.
- Configuração IA com evento de sucesso sem replay: reabrir o diálogo não repete o fechamento do salvamento anterior. Salvar e apagar chave não disputam entre si; erro mantém a seleção; cancelamento não vira erro de negócio ou sucesso. A seleção de modelo aceita somente os níveis correspondentes.

As regressões anteriores continuam cobrindo regras/ações parametrizadas, estado e versões, dados ausentes, reconciliação de IDs/lotes, correções humanas, esclarecimentos, respostas genéricas rejeitadas, destinos e componentes inválidos, simulação sequencial, aprovação versus execução parcial, PDF, transporte multimodal e seleção de modelo/raciocínio.

As chamadas de IA nos testes usam **MockWebServer e respostas preparadas**. As imagens usadas no encadeamento são sintéticas; os PDFs de teste são fixtures do projeto. Esses testes provam contratos e integração local, não a qualidade real de reconhecimento ou estratégia.

## Limites e validação do usuário

Não houve aparelho, emulador, Android instrumentation, UI automatizada ou API real. Permanecem para validação manual: interação Compose, dropdowns, câmera física, recriação real de Activity, comportamento LAN em aparelhos diferentes, acesso aos modelos da conta, imagens/manuais reais e decisões em partidas reais, incluindo Harmonies.

A interface completa da partida continua web, hospedada na apresentação Compose por WebView e servida aos clientes LAN. A migração não afirma que o HTML foi reescrito como componentes Compose. O novo banco preserva documentos variáveis por jogo, sem colunas/regras por nome de jogo. Regras/cobertura geradas pela IA e o limiar de confiança 0,85 continuam sem comprovação universal ou calibração empírica.

Os JSON legados são preservados como backups anteriores à migração; o progresso posterior é persistido no Room. Um rollback para um APK antigo não recebe automaticamente essas novas alterações. Não há fallback destrutivo no banco.

Consulte [a arquitetura](MVVM_ARCHITECTURE.md) e [o roteiro manual curto](MANUAL_VALIDATION.md). O ZIP inclui relatórios XML/HTML de testes, lint e logs do build limpo e da verificação final.

## Assinatura e instalação

A chave debug da entrega 0.7 não estava disponível no ambiente retomado. O APK anexado usa outra assinatura e **não atualiza diretamente o APK 0.7 entregue anteriormente**. Não desinstale uma instalação com partidas para testar este arquivo. Use uma instalação de teste separada, ou compile o patch/fontes com a chave da instalação atual para permitir atualização e importação dos dados existentes.

Certificados SHA-256 públicos verificados:

- 0.7: `127f66a405c430175c6fad8415c02a88a76111fc6e882346c8086c9163865c32`
- 0.8: `15284e92af2e61f5c2fe3cce973eacfc4c0795e2d4d8055cce3cd7b374017a44`

## Aplicação do patch

Nenhuma alteração foi publicada no remoto. O patch principal é incremental sobre os fontes da entrega **0.7**. O ZIP inclui também um patch cumulativo sobre `12272c1` (base 0.3); escolha apenas o patch correspondente à sua base.

```bash
# Em código que já contém a entrega 0.7:
git apply --check /caminho/AutomaForge-Android-v0.8.patch
git apply /caminho/AutomaForge-Android-v0.8.patch
```
