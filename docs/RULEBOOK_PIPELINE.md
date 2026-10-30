> A partir da 0.5, o modo padrão é PDF completo com otimização conservadora. Este documento descreve o pipeline anterior de OCR seletivo, agora opcional. Veja [o fluxo atual](GAME_KNOWLEDGE_AND_PDF.md).

# Processamento local de manuais

O controlador Android continua sendo o servidor HTTP/WebSocket da partida. A interface continua em HTML/JS dentro da WebView. O projeto original usa Java, `ServerSocket`, Camera2 e arquivos JSON privados, sem Compose, ViewModel, Room ou contêiner de injeção. Os novos componentes são Kotlin e usam Coroutines/StateFlow; não foi necessário substituir a arquitetura existente.

## Fluxo implementado

1. O upload existente preserva o PDF em armazenamento privado. O pipeline calcula SHA-256 por streaming e mantém uma cópia original endereçada pelo hash.
2. PdfBox-Android extrai texto e geometria página a página. A memória auxiliar do PDF usa arquivos temporários. Títulos são identificados pela fonte; ordenação separa colunas e mantém células alinhadas de tabelas. Não há resumo local nem remoção de condições, números ou exceções.
3. ML Kit com modelo latino **incorporado ao APK** faz OCR de páginas sem texto suficiente e de regiões de imagens em páginas híbridas. Apenas uma página/região é processada por vez. Blocos mantêm coordenadas, origem e confiança disponível. Duplicações são removidas somente quando texto normalizado e posição coincidem.
4. O catálogo conserva imagens incorporadas, grupos de traços vetoriais e uma referência à página completa. A página original cobre conteúdo não detectado, símbolos, gráficos complexos e páginas rotacionadas. Nenhum candidato é eliminado por parecer decorativo. Catálogos vetoriais excessivamente complexos são agrupados na referência da página, sem perder o original.
5. Recortes são renderizados **sob demanda**, diretamente em bitmaps limitados, sem rasterizar todas as páginas como padrão. A cópia mestre é PNG sem perdas; a versão transmitida usa WebP sem perdas no Android 30+ e PNG no Android 26–29. Conteúdo desconhecido pode conter texto pequeno: por isso não recebe JPEG com perdas automaticamente. O PDF original mantém a resolução de origem. Os recortes têm lados máximos de 3200/2400 pixels (mestre/transmissão); OCR usa até 2200 pixels. Uma leitura visual insuficiente interrompe a conclusão e permite fallback.
6. A representação intermediária JSON contém documento, páginas, blocos, seções, referências cruzadas, recursos visuais e incertezas. Repetições exatas nas margens de pelo menos três páginas e 80% do documento são marcadas. Na transmissão, seu texto completo aparece em um dicionário por pacote, com referências em cada seção afetada; o conteúdo literal continua integralmente no cache.
7. A interpretação textual agrupa trechos em pacotes, preserva referências de origem e solicita fatos, lacunas, IDs visuais e páginas complementares em JSON Schema. O aparelho recupera e envia apenas os recursos solicitados. Páginas com OCR ou layout incerto são enviadas para verificação visual. Há até três rodadas de recuperação; solicitações ainda pendentes interrompem a análise, sem declarar completude.
8. A consolidação usa as evidências de todos os pacotes, mantém fontes e gera os campos usados pela automa. Referências desconhecidas, respostas incompletas/recusadas e JSON inválido são rejeitados. Fontes não cobertas e ambiguidades continuam explícitas. O rascunho exige confirmação humana antes de substituir as regras salvas.

O fluxo é genérico: não há registro prévio de jogos, componentes ou baralhos.

## API e contratos

O pipeline usa `POST https://api.openai.com/v1/responses`, `input_text`, `input_image`, `text.format` com `json_schema` estrito e `store:false`. O fallback explícito usa `input_file` para o PDF original. O suporte foi conferido na documentação oficial para **gpt-4.1-mini** e seu snapshot `gpt-4.1-mini-2025-04-14`; outros modelos são rejeitados neste pipeline até serem verificados. As demais operações da aplicação mantêm o cliente anterior.

- [Modelo e capacidades](https://developers.openai.com/api/docs/models/gpt-4.1-mini)
- [Saídas estruturadas](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Entradas visuais](https://developers.openai.com/api/docs/guides/images-vision)
- [ML Kit incorporado](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)

O esquema é validado também no aparelho. Isso verifica estrutura e referências, **não prova a correção semântica da interpretação**. O usuário deve comparar o rascunho com o manual, especialmente OCR, exceções e símbolos. As evidências interpretadas ficam junto das regras editadas na sala.

## Execução, cancelamento e cache

`RulebookJobs` usa `SupervisorJob`, `Dispatchers.IO`, `StateFlow` e semáforo de uma análise pesada por vez. Nenhum bloqueio de `GameStore` fica mantido durante OCR, extração ou chamadas de interpretação. A interface consulta o progresso sem aguardar uma requisição HTTP longa. O servidor continua atendendo a partida.

Persistência por `AtomicFile`, seguindo a decisão original de arquivos JSON:

- `files/rulebooks/<sha256>/original`: fonte preservada;
- `pipeline-1-schema-1/page-N.json`: checkpoint de cada página;
- `document.json`: representação intermediária completa;
- `visuals/`: mestres, versões de transmissão e metadados dos recortes;
- `human-validated.json`: correções confirmadas, fora do diretório versionado;
- `responses/`: respostas remotas e marcadores de chamadas em andamento;
- `files/rulebook-jobs/<sala>.json`: progresso persistido.

A chave de cache remoto considera modelo, esquema, instruções, versões e conteúdo efetivamente enviado. Respostas válidas são reutilizadas. Uma interrupção depois do POST pode ter resultado e cobrança desconhecidos: nesse caso a aplicação exige a opção explícita de repetir a chamada. OkHttp está configurado sem repetição automática de conexões.

Alterações incompatíveis no pipeline/esquema usam outro diretório de cache. As correções humanas não são invalidadas automaticamente. O resultado de uma tarefa não substitui um manual ou regras que mudaram enquanto ela estava em execução. Apagar manuais elimina suas fontes/cache; as regras já salvas na sala permanecem. Como respostas podem combinar vários documentos, essa operação também invalida o cache remoto compartilhado, preservando correções dos outros manuais.

O servidor mantém o ciclo de vida original: o controlador deve permanecer aberto. Não há serviço remoto de processamento nem serviço Android permanente em segundo plano. Se o processo for encerrado, o usuário retoma pelos checkpoints.

## Interface e endpoints

- `POST /api/rooms/{sala}/rules/interpret`: inicia/retoma e responde imediatamente; aceita `retry_uncertain` e `original_fallback`.
- `GET /api/rooms/{sala}/rules/processing`: etapa, páginas, OCR, visual, erros, métricas e rascunho.
- `POST /api/rooms/{sala}/rules/cancel`: solicita cancelamento e desconecta chamadas pendentes.
- `DELETE /api/rooms/{sala}/rules/manuals`: apaga arquivos importados/cache; exige que a tarefa já tenha terminado.
- `PUT /api/rooms/{sala}/rules`: salva correções com `human_validated: true`.

O seletor nativo agora aceita PDF e imagens. A interface mostra progresso, solicitações de confirmação e resumo das métricas. Os campos e ações não são mais cortados silenciosamente ao salvar. O limite de corpo JSON continua em 1 MiB, e o fluxo de upload conserva os limites existentes (10 MiB por arquivo, 30 MiB por requisição, 30 arquivos por sala). O fallback original é limitado a 20 MiB por pacote.

## Credenciais e diagnóstico

A chave fornecida pelo usuário é criptografada com AES-GCM e Android Keystore. Preferências antigas são migradas, removendo a chave em texto simples somente após persistência bem-sucedida. A chave não é incluída no APK, no JSON da interface ou nos logs. Os logs novos registram apenas estado, contagens de páginas/chamadas e bytes; não incluem texto de manuais nem corpos de resposta.

A comunicação LAN mantém as limitações anteriores de HTTP e código de sala. A mudança não adiciona autenticação ou TLS à rede local.

## Métricas e limites das medições

São registrados páginas nativas/OCR, candidatos visuais, imagens confirmadas e tentadas, bytes originais, bytes do corpo JSON escritos na conexão, bytes preparados para tentativa, tempos local/remoto, chamadas, uso de tokens retornado pela API e cache hit/miss. O contador de bytes acompanha escritas bem-sucedidas no corpo da conexão (sem cabeçalhos/TLS); a última escrita de uma conexão interrompida pode ter resultado desconhecido. Imagens confirmadas exigem resposta válida, enquanto tentativas são contadas separadamente. Uma diferença negativa de bytes é possível, especialmente em PDFs pequenos. Essa diferença compara o arquivo original com as requisições JSON; não equivale à comparação de dois fluxos completos da API.

Não há estimativa apresentada como token faturado. Sem execução de referência e preços suficientes, economia de tokens e custo monetário permanecem indisponíveis. Tokens fornecidos pelo servidor simulado nos testes não são medições da OpenAI.

O teste `measureSyntheticNativeFixtures` grava `app/build/reports/rulebook/local-extraction.json`. Ele compara o PDF sintético com seu texto nativo literal. **Não inclui imagens, JSON das requisições ou custo de API**, portanto não demonstra economia total do fluxo. Os resultados de validação desta entrega ficam em `VALIDATION.md`.

## Compilar e verificar

Requisitos: JDK 17 completo, Android SDK 35, Gradle 8.7 e acesso aos repositórios Google/Maven Central. Compatibilidade Android preservada: API 26+.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
# Contratos estáticos, sem dependência de pytest:
python - <<'PY'
import runpy
for name, fn in runpy.run_path('tests/test_static_contract.py').items():
    if name.startswith('test_'):
        fn()
PY
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Instale no controlador e nos satélites. Importe um manual, toque em **Interpretar com IA**, acompanhe o progresso, revise as fontes e confirme as regras. O fallback só é usado quando sua opção é marcada.

Fixtures sintéticas incluem texto nativo, digitalização, híbrido, duas colunas, tabela, diagramas vetoriais, símbolos, página vazia, manual de 120 páginas e PDF corrompido. `tests/generate_pdf_fixtures.py` permite recriá-las com ReportLab/Pillow; os PDFs já fazem parte do repositório.

## Dependências adicionadas

| Dependência | Versão | Uso |
|---|---|---|
| Kotlin Android | 1.9.25 | Componentes novos, integração com Java |
| Coroutines Android | 1.8.1 | Tarefas, cancelamento, StateFlow |
| Gson | 2.11.0 | Modelos e cache JSON |
| PdfBox-Android | 2.0.27.0 | Texto/geometria/catálogo local |
| ML Kit Text Recognition | 16.0.1 | OCR latino incorporado |
| OkHttp | 4.12.0 | Chamadas canceláveis |
| JUnit / Robolectric | 4.13.2 / 4.14.1 | Testes JVM |
| Coroutines Test / MockWebServer | 1.8.1 / 4.12.0 | Testes de concorrência/transporte |

OCR de outros sistemas de escrita não foi incorporado. Manuscritos, diagramas ilegíveis, PDFs protegidos e layouts incomuns podem exigir conferência ou fallback. Pacotes/evidências que excedem os limites seguros interrompem a análise em vez de truncar regras. A qualidade estratégica da automa continua dependendo da interpretação e da validação humana.

## Arquivos criados e alterados

- `.gitattributes`
- `app/src/main/assets/schemas/rulebook_analysis.json`
- `app/src/main/assets/schemas/rulebook_consolidation.json`
- `app/src/main/java/com/automaforge/android/rulebook/ApiKeyStore.kt`
- `app/src/main/java/com/automaforge/android/rulebook/OpenAiRulebookGateway.kt`
- `app/src/main/java/com/automaforge/android/rulebook/PdfImageOptimizer.kt`
- `app/src/main/java/com/automaforge/android/rulebook/PdfOcrProcessor.kt`
- `app/src/main/java/com/automaforge/android/rulebook/PdfTextExtractor.kt`
- `app/src/main/java/com/automaforge/android/rulebook/PdfVisualAnalyzer.kt`
- `app/src/main/java/com/automaforge/android/rulebook/RulebookCacheRepository.kt`
- `app/src/main/java/com/automaforge/android/rulebook/RulebookInterpreter.kt`
- `app/src/main/java/com/automaforge/android/rulebook/RulebookJobs.kt`
- `app/src/main/java/com/automaforge/android/rulebook/RulebookModels.kt`
- `app/src/main/java/com/automaforge/android/rulebook/RulebookPipeline.kt`
- `app/src/main/java/com/automaforge/android/rulebook/RulebookPreprocessor.kt`
- `app/src/test/java/com/automaforge/android/rulebook/PdfExtractionTest.kt`
- `app/src/test/java/com/automaforge/android/rulebook/RulebookRemoteTest.kt`
- `app/src/test/java/com/automaforge/android/rulebook/RulebookTextTest.kt`
- `app/src/test/resources/manuals/blank.pdf`
- `app/src/test/resources/manuals/columns.pdf`
- `app/src/test/resources/manuals/corrupt.pdf`
- `app/src/test/resources/manuals/diagrams.pdf`
- `app/src/test/resources/manuals/hybrid.pdf`
- `app/src/test/resources/manuals/large.pdf`
- `app/src/test/resources/manuals/native.pdf`
- `app/src/test/resources/manuals/scanned.pdf`
- `app/src/test/resources/manuals/symbols.pdf`
- `app/src/test/resources/manuals/tables.pdf`
- `docs/RULEBOOK_PIPELINE.md`
- `docs/VALIDATION.md`
- `tests/generate_pdf_fixtures.py`
- `.github/workflows/android-apk.yml`
- `README.md`
- `app/build.gradle`
- `app/src/main/assets/web/app.js`
- `app/src/main/assets/web/index.html`
- `app/src/main/java/com/automaforge/android/AiClient.java`
- `app/src/main/java/com/automaforge/android/LanServer.java`
- `app/src/main/java/com/automaforge/android/MainActivity.java`
- `build.gradle`
