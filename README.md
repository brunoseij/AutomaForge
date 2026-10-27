# AutomaForge Android — v0.2.0-alpha

**O celular controlador é o próprio servidor da partida.** Não é necessário computador, Python, Termux, Docker ou serviço em nuvem para sincronizar o jogo. A implementação é **Java para Android (API 26+)** e contém um servidor HTTP/WebSocket embutido, interface web hospedada no próprio aparelho e captura Camera2 nativa no celular satélite.

> Estado da entrega: **código-fonte implementado, sintaxe JS e contratos estáticos checados; compilação real Android e testes em aparelhos NÃO executados neste ambiente** (Android SDK/Gradle não estão disponíveis). Não há APK verificado no pacote. Siga o procedimento de build abaixo para produzir o APK de teste.

## Arquitetura

```
Controlador Android (instale o APK)
  ├─ interface WebView em http://127.0.0.1:8765
  ├─ servidor ServerSocket 0.0.0.0:8765 (HTTP + WebSocket)
  ├─ salas, regras, fotos de tabuleiro e histórico em armazenamento interno
  ├─ memória privada das cartas apenas em RAM (não persiste)
  ├─ TextToSpeech pt-BR nativo (opcional)
  └─ OpenAI Responses API (opcional; não é offline)
           │ Wi-Fi ou hotspot do controlador
           ├─ Satélite Android com mesmo APK: Camera2, fotos automáticas a cada 5 s
           ├─ Satélite Android com mesmo APK: leitor privado com captura manual
           └─ Navegadores HTTP: interface + captura manual por arquivo
```

## Recursos incluídos

- O **controlador Android hospeda a sala** e publica seu IPv4 e porta `8765` na barra superior.
- Os satélites se conectam digitando o endereço IPv4 do controlador no primeiro painel do APK.
- Uma única interface web disponibiliza controlador, câmera do tabuleiro e leitor privado.
- Salas de oito caracteres e atualização de estado quase imediata por WebSocket.
- Upload de até 30 arquivos do manual (PDF ou fotografias), interpretação em lotes de até 12 arquivos por IA (opcional), edição e confirmação humana.
- Regras, ações, fase, turno, variáveis, notas e histórico salvos em JSON no armazenamento privado Android.
- **Câmera nativa secundária** com prévia e captura do tabuleiro a cada 5 segundos, sem mover o tripé.
- **Leitor privado nativo** com botão de captura individual: imagem e interpretação permanecem fora do estado público. As descrições das cartas residem somente na RAM da instância do servidor e são perdidas quando o processo morre.
- Análise conservadora opcional da foto do tabuleiro; não atualiza automaticamente o estado das peças.
- Proposta de jogada pela IA (quando configurada) ou entrada manual. O controlador sempre pode **aprovar ou rejeitar** antes de movimentar componentes físicos.
- Voz nativa em pt-BR no controlador, podendo ser ativada e desativada sem interromper a partida; texto continua exibido.
- **Nenhum cadastro prévio de baralhos**: o leitor privado lê cartas individualmente com IA, quando disponível.

## Como obter o APK

### Opção A: Android Studio

1. Instale uma versão recente do [Android Studio](https://developer.android.com/studio) com Android SDK Platform 35, Build Tools 35.0.0, JDK 17 e Gradle 8.7.
2. Abra a pasta raiz `AutomaForge-Android` como um projeto Gradle no Android Studio.
3. Se solicitado, selecione/configure uma distribuição **Gradle 8.7**. O pacote não inclui um `gradle-wrapper.jar`, pois essa ferramenta não estava disponível no ambiente de geração.
4. Aguarde a sincronização e selecione **Build → Build Bundle(s) / APK(s) → Build APK(s)**, variante `debug`.
5. APK esperado: `app/build/outputs/apk/debug/app-debug.apk`. Instale no controlador e no celular da câmera.

### Opção B: Linux com SDK e Gradle já instalados

```bash
cd AutomaForge-Android
export ANDROID_HOME="$HOME/Android/Sdk"  # ajuste conforme a instalação
# requer Gradle 8.7 (binário `gradle`) e JDK 17
gradle :app:assembleDebug --no-daemon
# arquivo gerado:
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

### Opção C: GitHub Actions (gera APK automaticamente, sem Android Studio)

1. Crie um repositório GitHub e envie **o conteúdo da pasta** `AutomaForge-Android` para a raiz da branch `main`.
2. Vá a **Actions → Build Android APK → Run workflow**.
3. Após o job passar, baixe o artefato **AutomaForge-Android-debug** (contém `app-debug.apk`).

O workflow está em `.github/workflows/android-apk.yml`. O APK de debug usa assinatura de desenvolvimento do executor e destina-se somente a testes.

## Teste na rede local (2 celulares)

1. No celular controlador, abra AutomaForge e toque em **Iniciar controlador + servidor**. Mantenha a tela ligada e o aplicativo aberto. Na barra superior aparece algo como `LAN: 192.168.1.50:8765`.
2. Crie a sala e compartilhe seu código com oito caracteres.
3. No segundo celular, instale o **mesmo APK**, abra **Conectar a servidor existente** e digite apenas `192.168.1.50` (sem `http://` ou porta).
4. Entre com o código da sala, escolha **Câmera do tabuleiro** e toque em **Abrir câmera nativa automática**. Permita acesso à câmera e posicione o celular no tripé. Foto é enviada aproximadamente a cada cinco segundos enquanto a tela de captura está aberta.
5. No controlador, abra o painel de regras e selecione o PDF do manual ou fotos de suas páginas; se tiver IA configurada, solicite a interpretação. Revise antes de salvar. Sem IA, digite as regras e pelo menos uma ação.
6. Atualize fase, turno e condições do tabuleiro manualmente; solicite proposta via IA ou escolha uma ação manual. Confirme, então execute o movimento físico.
7. Ative **Falar as ações em voz alta** no controlador para ouvir a jogada aprovada em português.
8. Para ler cartas privadas, use o segundo ou terceiro celular, selecione **Leitor privado** e toque em **Abrir leitor privado nativo**, capturando cada carta dentro da caixa de leitura. Esse recurso requer chave de IA.

O mesmo procedimento funciona usando o **hotspot do controlador**, desde que os clientes tenham conectividade IP com o aparelho servidor. Alguns roteadores e hotspots usam isolamento de clientes e podem impedir a conexão.

## IA (opcional)

Com o servidor iniciado no controlador, toque em **IA** na barra superior. Digite a chave e salve. A chave fica em `SharedPreferences` privadas do aplicativo (não é exportada para a interface web); ao habilitar IA, a API recebe regras, fotos, cartas privadas e contexto de partida, de acordo com a operação solicitada. Esse recurso requer internet, gera custos de API e **não é IA offline**. Não compartilhe o controlador com pessoas que não devam usar essa chave.

Sem IA: manual, regras editáveis, câmera, salas, histórico, sincronização, jogadas manuais e voz continuam utilizáveis. Leitura/entendimento de cartas ocultas e sugestões inteligentes ficam indisponíveis.

## Segurança e limitações

- **A porta 8765 usa HTTP sem TLS nesta versão.** Isso evita configurar certificados para o protótipo, mas permite observação ou alteração de tráfego por terceiros com acesso à rede. Use apenas em rede de confiança. Não exponha a porta ao roteador/Internet. O código de sala **não é autenticação real**.
- Fotos e estado público são acessíveis a quem conhece a sala na rede. Cartas privadas não são retornadas ao cliente, mas uploads usam HTTP local sem criptografia; a privacidade depende também da segurança da rede.
- O processo servidor vive enquanto o app controlador estiver ativo; **não há serviço foreground permanente**. Android pode suspender/encerrar o processo quando o aplicativo sair do primeiro plano ou sofrer restrições de bateria. Mantenha controlador e câmera com telas ativas.
- Capturas nativas de câmera têm prévia somente no aparelho satélite. Em navegador, `getUserMedia()` exige HTTPS em endereço LAN; use o APK para automação ou o upload manual por arquivo.
- O reconhecimento visual ainda é assistido. A IA não garante movimento legal, não possui engine universal de regras e pode cometer erros.
- Chave OpenAI salva nas preferências privadas **sem criptografia adicional via Android Keystore** neste MVP. O aplicativo desabilita backup, mas não afirma segurança de um cofre criptográfico.
- Leitura de cartas em RAM é apagada quando o servidor é encerrado; estado público/regras/histórico persistem em arquivos privados do app.
- A leitura de manuais com IA em lotes pode perder referências cruzadas. Revise tudo antes de jogar.

## Estrutura

```
app/src/main/java/com/automaforge/android/
  MainActivity.java       Início, interface WebView, configuração IA, voz e conexão
  LanServer.java         HTTP/WebSocket, endpoints e publicação de estados
  GameStore.java         Estado/persistência e memória privada
  AiClient.java          Chamadas opcionais à OpenAI Responses API
  CameraActivity.java    Captura Camera2 e upload nativo de fotos
app/src/main/assets/
  web/                   Interface responsiva HTML, CSS, JS
  schemas/               Esquemas de resposta estruturada de IA
app/src/main/AndroidManifest.xml
.github/workflows/android-apk.yml
```

## Verificações neste ambiente

- `node --check app/src/main/assets/web/app.js` — sintaxe JS validada.
- Arquivos JSON Schema analisados por testes estáticos.
- `javac` analisou a gramática dos arquivos Java sem apontar erro de sintaxe, **mas não pode compilar** sem `android.jar` e classes do SDK Android.
- **Não houve compilação APK, instalação ou teste em celulares.** O build em Android Studio/CI poderá identificar problemas de integração adicionais.
