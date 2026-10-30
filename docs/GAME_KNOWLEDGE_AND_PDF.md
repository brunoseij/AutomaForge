> Documento da 0.5. Na 0.6, a aprovação separada do esquema e a importação manual de candidatos deixaram de ser pré-requisitos. Veja [o fluxo automático atual](AUTOMATIC_FLOW_AND_MODEL.md).

# Modelo de estado por jogo, esclarecimentos e PDF multimodal — 0.5

## O que faltava na 0.4

O estado genérico representava entidades, mas não descrevia **quais variáveis aquele jogo exige**. Não havia uma especificação de áreas/campos derivada do manual, nem associação entre variáveis e ações. A correção dependia excessivamente de JSON. As dúvidas eram exibidas em texto, sem resposta vinculada à informação correspondente. O reconhecimento analisava uma única imagem reduzida a 1280 pixels, sem comparar ilustrações do manual. As ações antigas também precisavam ser parametrizadas novamente.

As capturas fornecidas mostram o fluxo antigo de observações em frases e o botão “Confirmar e anunciar”. Isso não permite concluir qual APK estava instalado. Foi acrescentado um indicador com a versão real do servidor e a versão da interface, além do contrato informado por `/api/health`, para detectar controlador/cliente de versões diferentes.

## Modelo específico do manual, sem catálogo de jogos

A extração multimodal propõe `state_model` contendo:

- `areas`: função, nome legível, escopo por jogador/compartilhado, topologia, pistas visuais, ações dependentes e fontes das ilustrações.
- `fields`: chave, nome, descrição, tipo, escopo de partida/jogador/área, visibilidade, opções, observabilidade, ações/fases que necessitam do dado e ações que podem alterá-lo (`changes_with_actions`).
- `visual_sources`: referências `document_id:pN` para páginas com exemplos visuais úteis.

Campos e áreas precisam de revisão humana. Não há constantes por nome de jogo. Salas antigas podem usar **Identificar dados deste jogo com IA**, derivando o modelo das regras já salvas e propondo a parametrização das mesmas ações. A aprovação verifica que nomes de ações cadastradas foram preservados. Essa operação não declara cobertura completa das regras: ações propostas ficam com `validation_complete=false`.

As variáveis passam a ter fatos tipados em `game_state.facts`, com sujeito/escopo, origem e situação. O núcleo verifica tipo e dependências; não presume que recursos, objetivos ou trilhas de jogos diferentes sejam equivalentes. Valores privados não são aceitos no estado compartilhado. Ações podem usar `field:chave` e `area:chave`; origens/destinos `area:chave` resolvem os locais reais e seus descendentes, respeitando o dono ativo, sem exigir IDs inventados durante a leitura do manual.

Valores necessários desconhecidos geram bloqueios explícitos. Variáveis afetadas por uma execução ficam desconhecidas quando o aplicativo não calcula seu novo valor; o valor anterior pode permanecer como referência visual, com situação desconhecida. Alterar o modelo exige reconfirmação dos fatos antigos.

## Dúvidas respondíveis

Perguntas carregam ID, escopo, entidade/campo de destino, tipo de entrada, alternativas, relevância e versões de estado/regras. A interface permite responder ou marcar **Não sei**. Perguntas antigas permanecem identificáveis como desatualizadas; alterações de estado/regras são verificadas ao responder.

Uma resposta tipada pode identificar o dono de um tabuleiro, corrigir quantidade/nível/localização ou preencher uma variável do jogo. Respostas sem campo estruturado ficam registradas como esclarecimentos humanos e são enviadas na próxima análise; não são convertidas automaticamente em regras oficiais ou certezas visuais. Responder não dispara outra chamada paga: os botões de reanálise/reinterpretação são explícitos.

A gravação de respostas pelo servidor usa uma cópia: uma falha de validação não pode deixar metade da resposta aplicada. Esclarecimentos humanos de uma mesma foto podem reusar a foto congelada; uma execução física ou uma correção ampla do estado exige nova atualização. Correções humanas continuam protegidas de substituição automática por visão.

Este canal é público para a sala. Perguntas de variáveis declaradas privadas não são publicadas, e a API recusa preenchê-las pelo canal público. A memória privada anterior e a proteção dos movimentos/falas continuam separadas. A aplicação não consegue impedir alguém de digitar voluntariamente um segredo num campo de texto público.

## Reconhecimento em duas etapas

1. A primeira chamada localiza áreas na foto atual, usando regras, modelo, respostas humanas e até três referências de páginas do manual preservado. Reconhecer um tabuleiro não implica saber a quem ele pertence.
2. A segunda recebe a foto, as mesmas referências e até cinco recortes das áreas detectadas, com IDs e coordenadas normalizadas. Ela propõe casas, grupos, componentes, níveis e fatos do modelo.

Regiões impossíveis são recusadas. Referências do manual são rotuladas como exemplos, não como ocupação da partida. A propriedade do tabuleiro da automa precisa de confirmação humana; o sistema não presume que o primeiro tabuleiro visível seja o seu. O usuário compara regiões sobrepostas e corrige a leitura em formulários de entidades e variáveis antes de confirmar. JSON continua disponível para ajustes avançados.

O servidor preserva fotos públicas com até 2560 pixels no lado maior; o navegador também usa esse limite, e a captura nativa escolhe uma resolução dentro de um orçamento maior. Não há super-resolução: ampliar um recorte não recupera detalhe ausente da fotografia original. Há limites de anexos e memória, e algumas áreas podem ficar sem recorte dedicado. Isso não comprova precisão visual; detalhes ilegíveis continuam exigindo foto melhor ou informação humana.

## PDF completo com otimização

O padrão deixou de ser OCR seletivo. `pdf_lossless` envia páginas completas de PDF como `input_file`, com detalhe visual alto, preservando texto nativo, imagens, vetores e relações entre texto e ilustração. O método anterior continua disponível como opção explícita, para compatibilidade.

A preparação usa PDFBox já existente, mantém o original e não transforma todas as páginas em fotografias. A otimização conservadora tenta recompressão sem perda em imagens elegíveis, preservando casos especiais (máscaras, espaços de cor especiais, imagens já comprimidas e outros casos que não são seguros para essa rotina). A cópia só é escolhida se for menor e passar pela comparação de quantidade de páginas e texto nativo extraído.

`pdf_balanced` permite reduzir imagens grandes e recomprimir JPEG com qualidade 90; é uma opção com perda e pode prejudicar letras/ícones pequenos. A opção de original permanece disponível. O teste de texto/páginas não comprova igualdade visual; a revisão do usuário continua necessária.

Pacotes têm no máximo 12 páginas e 12 MiB. Se um pacote exceder o tamanho, ele é dividido mantendo o mapeamento das páginas originais; uma página que ainda exceda o orçamento causa bloqueio explícito, sem descarte. Todas as páginas são enviadas. As respostas por pacote são consolidadas, com fontes e dúvidas preservadas; isso não garante que o modelo tenha compreendido cada detalhe.

A interface distingue bytes originais, PDF escolhido, soma dos pacotes e bytes JSON realmente escritos na conexão. Dividir um PDF pode aumentar o tamanho, e Base64 acrescenta overhead. Reduzir bytes do arquivo não demonstra redução de tokens/custo: a API interpreta também as imagens das páginas.

Referência oficial consultada: https://developers.openai.com/api/docs/guides/file-inputs — PDFs em modelos com visão incluem texto e imagens de páginas; o detalhe pode ser configurado em Responses. O aplicativo preserva `store=false` e o modelo já configurado, sem migrar automaticamente de modelo.

## Escopo e limites

Activities Java, servidor LAN, interface web, salas, persistência e aprovação humana foram mantidos. Os novos serviços de estado/visão são Java; a preparação PDF permanece junto do pipeline Kotlin já existente. Não foram introduzidos Compose, ViewModel, testes instrumentados, testes automatizados de UI ou emulador.

Não há reconhecimento universal de tabuleiros, recuperação de informação oculta, rastreamento visual garantido ou validador universal. Efeitos e regras não implementados permanecem não verificáveis. As imagens enviadas pelo usuário foram inspecionadas para diagnóstico; não houve execução do aplicativo ou chamada real de visão sobre elas nesta entrega.
