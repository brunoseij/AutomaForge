# AutomaForge Android — 0.4.0-alpha

Aplicativo Android que hospeda interface web e servidor LAN para partidas com uma automa. A melhoria de estado e jogadas é geral: nenhuma instrução ou regra é escolhida pelo nome do jogo. Mantém Activities Java, câmera nativa, salas, persistência e o pipeline Kotlin de manuais já existente; sem migração para Compose ou ViewModel.

## Usar

1. Instale o APK debug no controlador e, se desejar, nos aparelhos de câmera/leitura privada. Configure chave OpenAI e modelo no controlador. Abra a interface local e crie/abra uma sala; conecte os outros aparelhos pelo IP/código da sala.
2. Importe o manual e revise regras e definições de ações. As ações usam JSON parametrizado; o formato antigo de três campos continua aceito, com cobertura não verificada.
3. Fotografe a área pública e analise. A foto fica congelada para revisão. Compare entidades, grupos, casas e pilhas, corrija o estado e confirme. Ou preencha o mesmo editor manualmente. Informe jogador, fase e subetapa. Para atualizar a foto, toque em **Liberar captura de nova foto**.
4. Associe os IDs reais às origens, destinos e dados necessários das ações. Calibre referências por toque; use coordenadas axiais ou vizinhos explícitos para hexágonos. Não marque cobertura completa se houver regras sem implementação.
5. Solicite uma decisão. Confira movimentos numerados, referências e situação da validação. Aprovar não altera o estado. Depois de realizar fisicamente os movimentos, informe quantos movimentos iniciais foram executados e confirme a execução.
6. Após execução parcial, informação revelada ou correção, solicite nova decisão. Não reaproveite uma proposta de versão anterior.

Cartas privadas permanecem em memória do servidor e desaparecem ao reiniciar. Somente informações públicas devem ser colocadas no estado compartilhado. A rede LAN mantém o modelo anterior de HTTP e código de sala.

## Documentação

- [Causas encontradas, implementação e contrato](docs/STATE_AND_MOVES.md)
- [Roteiro de validação manual, incluindo Harmonies](docs/MANUAL_VALIDATION.md)
- [Verificações locais e limitações](docs/VALIDATION.md)
- [Pipeline local de manuais PDF, OCR e referências](docs/RULEBOOK_PIPELINE.md)

## Compilar e verificar localmente

JDK 17, Gradle 8.7, SDK Android 35 e build-tools 35.0.0; Android mínimo 26. Dependências são resolvidas de Google/Maven Central.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
python - <<'PY'
import runpy
for name, fn in runpy.run_path('tests/test_static_contract.py').items():
    if name.startswith('test_'):
        fn()
PY
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Identificador debug: `com.automaforge.android.debug`; versão 4 / 0.4.0-alpha. Atualização sem desinstalação exige assinatura compatível; não desinstale uma versão com partidas que deseje preservar.

Testes instrumentados, dependências exclusivas e job de emulador foram removidos; as variantes de teste instrumentado estão desabilitadas. O pipeline executa apenas testes JVM, contratos estáticos, lint e APK. Não há testes de UI automatizados. A validação funcional em partidas reais será feita pelo usuário. Reconhecimento de imagem, qualidade estratégica e legalidade completa não são garantidos pela compilação ou pelos dados sintéticos.
