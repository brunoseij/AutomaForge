# AutomaForge Android — 0.8.0-alpha

Aplicativo para partidas com uma automa, com interface nativa **Kotlin + Compose**, **MVVM**, persistência **Room** e injeção **Dagger 2**. O servidor HTTP/WebSocket e a interface web da partida continuam disponíveis para os clientes LAN. Os componentes Android tradicionais usados na integração com câmera e WebView são adaptadores de plataforma dentro da apresentação Compose.

O código de produção foi migrado para Kotlin e organizado em `presentation`, `domain`, `data`, `core` e `di`. As Activities são pontos de entrada; ViewModels expõem StateFlow e delegam aos casos de uso/repositórios. O motor genérico de estado, reconhecimento e movimentos mantém seus contratos. Não há regras escolhidas pelo nome do jogo.

## Usar

1. Para preservar dados existentes, gere o APK com a assinatura da instalação atual; o APK de teste anexado tem assinatura diferente da entrega 0.7. Não desinstale uma instalação com partidas. Na tela inicial, inicie o controlador ou retome uma partida salva. Outros aparelhos podem conectar pelo IP local.
2. Em **IA**, escolha modelo e nível de raciocínio nos menus. Deixe a chave vazia para manter a atual.
3. Importe o manual e envie uma foto na interface da partida. Use **Identificar e sugerir jogada para a automa**. Esquema e dados aproveitáveis continuam inferidos automaticamente, com dúvidas respondíveis quando bloqueiam a decisão.
4. Confira/aprove a proposta. Execute os movimentos físicos e confirme o prefixo realizado. Aprovar não executa nem altera o tabuleiro físico.

As salas antigas são importadas dos JSON para Room sem apagar os originais, fotos ou manuais. A importação é idempotente e nunca substitui uma sala já presente no banco por um backup antigo. Arquivos inválidos são isolados e sinalizados. Cartas privadas continuam apenas em memória, fora de Room e dos clientes públicos; a chave permanece no armazenamento cifrado existente.

## Compilar e verificar

JDK 17, Gradle 8.7, SDK Android 35. O projeto mantém Kotlin 1.9.25 com Compose Compiler 1.5.15, Activity Compose 1.9.3, Compose UI 1.7.8/Material 3 1.3.2, Lifecycle 2.8.7, Room 2.6.1 e Dagger 2.51.1 via kapt. Versões fixadas para compatibilidade; não é uma afirmação de que sejam as mais recentes.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
python - <<'CHECKS'
import runpy
for name, fn in runpy.run_path('tests/test_static_contract.py').items():
    if name.startswith('test_'): fn()
CHECKS
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. ID debug: `com.automaforge.android.debug`; versão 8 / 0.8.0-alpha; minSdk 26 e targetSdk 35. Testes instrumentados continuam removidos/desabilitados, sem tarefas de emulador ou UI automatizada. A validação funcional com aparelho, imagens e IA reais é do usuário.

## Documentação

- [Arquitetura MVVM e migração Room](docs/MVVM_ARCHITECTURE.md)
- [Roteiro manual](docs/MANUAL_VALIDATION.md)
- [Verificações e limitações](docs/VALIDATION.md)
- [Inferência, modelo e nível de raciocínio](docs/AUTOMATIC_FLOW_AND_MODEL.md)
- [Contratos do estado e movimentos](docs/STATE_AND_MOVES.md)
- [Pipeline PDF e referências visuais](docs/RULEBOOK_PIPELINE.md)
