# Validação — pipeline PDF

## Verificações executadas

- Build Android debug e APK dos testes instrumentados compilados com JDK 17, Gradle 8.7 e Android SDK 35. A execução final de testes JVM, lint e compilação terminou com `BUILD SUCCESSFUL`.
- Assinatura do APK verificada por `apksigner verify`: válida (v2). Metadados verificados: `com.automaforge.android.debug`, versão 0.3.0-alpha, minSdk 26 e targetSdk 35.
- `:app:testDebugUnitTest`: 26 testes passaram, incluindo recuperação de backup atômico e proteção contra repetição após dano no cache remoto.
- Sete contratos estáticos passaram, incluindo sintaxe JS, IDs da interface, rotas, esquemas existentes e preservação das cartas privadas.
- `:app:lintDebug`: zero erros e 13 avisos (principalmente internacionalização e APIs legadas existentes).
- `git diff --check`: sem erros.

## Testes em Android

Há oito testes instrumentados: PDF nativo/cache, OCR digitalizado, documento híbrido, página vazia, recursos visuais, interrupção/retomada, servidor LAN durante processamento e migração/criptografia da chave. Eles exercitam PdfRenderer e ML Kit reais, sem chamadas à OpenAI.

A primeira execução local em emulador sem KVM falhou antes de instalar o APK: `cmd: Can't find service: package`. Nenhum teste instrumentado chegou a executar nessa tentativa. A segunda tentativa inicializou o gerenciador de pacotes, mas o Android reiniciou durante a instalação: `Failure calling service package: Broken pipe (32)` / `DeadSystemException`. Novamente, nenhum teste instrumentado chegou a executar. O ambiente não possui `/dev/kvm`; o emulador estava em execução por software. O workflow incluído configura execuções aceleradas nas APIs 26 e 30, mas elas **não foram disparadas**, pois a integração do GitHub recusou escrita com HTTP 403. OCR real, Keystore real e concorrência LAN em Android permanecem pendentes de validação no aparelho/CI.

## Transporte e integridade

Os testes JVM usam PDFBox-Android real com Robolectric para texto nativo, digitalizado, híbrido, colunas, tabelas, diagramas, símbolos, página vazia, manual de 120 páginas e PDF corrompido. As verificações de integridade conferem condições, exceções, números, referências, ordem das colunas e relações das células das tabelas.

MockWebServer valida requisições textuais sem anexar o PDF, recuperação de imagem por ID e página, cache sem novas chamadas, cancelamento de rede, recusa de JSON inválido e confirmação de repetição após resultado desconhecido. A simulação não comprova a qualidade semântica de uma interpretação real da OpenAI.

## Medições locais com fixtures sintéticas

| Fixture | Páginas | PDF original (bytes) | Texto nativo literal (bytes) |
|---|---:|---:|---:|
| Texto nativo | 2 | 2298 | 352 |
| Duas colunas | 1 | 1486 | 149 |
| Tabela | 1 | 1461 | 37 |
| Diagramas | 1 | 1762 | 214 |
| Símbolos | 1 | 1805 | 212 |
| Extenso | 120 | 77432 | 21492 |

Esses números medem **somente extração textual**. Imagens, metadados, JSON das requisições e consolidação não estão incluídos. Não representam economia total de bytes, tokens ou dinheiro. Nas fixtures de diagramas/símbolos, a parte visual continua recuperável e não pode ser substituída apenas pelo texto listado.

Não houve chamada faturada à OpenAI, ensaio em aparelho físico ou medição financeira. Não há porcentagem universal de economia atribuída ao pipeline.

## Publicação e aplicação das alterações

O repositório foi lido a partir de `4e2a240302e5751c3af61624d999fbfcc9e997af`. A tentativa de criar o primeiro blob pelo GitHub retornou **403: Resource not accessible by integration**. Nenhuma alteração, branch ou PR foi publicado no remoto. O pacote de fontes e o patch contêm todas as alterações.

Para aplicar o patch no checkout dessa base, com árvore de trabalho limpa:

```bash
git switch -c feat/local-rulebook-pipeline
git apply --check /caminho/AutomaForge-Android-v0.3.patch
git apply --index /caminho/AutomaForge-Android-v0.3.patch
```

Os relatórios brutos JVM/lint e a medição local estão na pasta `validation/` do ZIP de entrega. O APK é de debug, versão `0.3.0-alpha` (`versionCode=3`), para teste. A geração do APK não representa validação completa em aparelho.
