> As definições e a simulação abaixo continuam válidas, mas a 0.5 acrescentou modelo por jogo, seletores semânticos, formulários e esclarecimentos. Veja [as mudanças](GAME_KNOWLEDGE_AND_PDF.md).

# Estado e decisões concretas — 0.4

## Causas verificadas no código 0.3

1. `board.json` admitia somente frases; `GameStore` mantinha notas, variáveis e um resultado visual isolado, sem representação de oferta, casas, lotes ou pilhas.
2. A foto chegava à análise (`input_image`, detalhe alto), mas o contexto enviado continha somente turno, fase e variáveis. O resultado era salvo sem reconciliação estruturada e não era enviado a `propose`.
3. A ação era normalizada para nome/descrição/condição, descartando qualquer extensão. A extração e o editor tinham a mesma limitação.
4. O prompt de decisão exigia apenas o nome de uma ação, brevidade e frases para o jogador; a validação aceitava o nome cadastrado e não verificava peças, posições ou passos.
5. Quando havia cartas privadas, o servidor substituía os passos pela descrição da ação. A proteção de privacidade eliminava a jogada concreta.
6. A interface já exibia uma lista de passos: não era apenas um resumo visual. O problema principal estava antes da renderização.
7. Confirmar aplicava variáveis livres sugeridas e avançava automaticamente o turno, confundindo aprovação e execução. A versão da foto também não era conferida após a análise assíncrona.

## Fluxo implementado

Regras revisadas → foto pública/entrada manual → observação candidata → revisão humana do estado → contrato de decisão → validação e simulação sequencial → proposta numerada → aprovação → confirmação física total/parcial → nova versão do estado.

Nenhuma condição depende do nome do jogo. Harmonies aparece somente no roteiro manual. A arquitetura de Activities Java, interface web e servidor LAN foi preservada; o pipeline Kotlin de PDFs que já existia também foi mantido, sem Compose ou ViewModel.

## Estado e reconciliação

`game_state` possui identidade própria, versão, jogador ativo, fase, turno, subetapa, situação dos metadados, entidades e pendências. Cada entidade tem ID lógico estável, tipo, dono/área, localização, grupo, quantidade, nível e situação (`observed`, `confirmed`, `inferred`, `unknown`). Fonte, versão da imagem e região normalizada ficam junto da entidade. `attributes` comporta cartas públicas, objetivos, recursos e outros detalhes extensíveis; não existe interpretação automática universal desses campos.

Entidades: `board`, `region`, `position`, `group`, `component`, `resource`, `card`, `objective`. Somente dados públicos entram neste estado. Cartas privadas continuam em memória exclusiva do servidor, sem persistência nem envio a clientes da sala.

Lotes `identity=pooled` representam tipo/quantidade/localização/nível, sem afirmar identidade visual individual. Os IDs gerados durante divisão de lotes são identificadores contábeis da simulação. Não representam marcas visuais de peças. Ao empilhar, descreva cada nível conhecido como um lote lógico; uma base oculta deve permanecer desconhecida. A posição vazia exige `occupancy_known=true`, além da ausência explícita de ocupantes: a ausência de detecção não a torna vazia.

Nova análise gera candidatos, não altera o estado. IDs existentes e correções humanas são preservados. A importação para o editor deixa conflitos para comparação manual, recusa análise desatualizada e não adiciona lotes com a mesma âncora. A gravação humana rejeita IDs e lotes indistinguíveis duplicados e referências inexistentes. Isso privilegia revisão explícita em vez de prometer rastreamento visual automático. Exclusão, deslocamento e alteração de entidade existente são feitos no editor, após comparar a observação.

A foto fica congelada ao iniciar análise. Novos uploads são ignorados enquanto ela estiver congelada; **Liberar captura de nova foto** retoma o recebimento. Uma foto vinculada a uma versão anterior à alteração do estado exige nova captura. O aplicativo registra recepção e versão; não consegue provar a data física de uma imagem antiga enviada pelo usuário.

A calibração por toque grava referências x/y normalizadas na foto. Não estima perspectiva nem detecta automaticamente toda a malha. Marcadores somente são sobrepostos à versão de imagem a que pertencem. Casas/grupos também podem ser inspecionados na representação textual. Para hexágonos, o tabuleiro usa `topology=hex_axial` e casas q/r axiais; para outros tabuleiros, use `neighbors` como grafo explícito. Não se presume grade quadrada.

## Definições de ações

O editor aceita um array JSON e preserva o formato legado `nome | descrição | condição`. Ações antigas continuam carregando, mas sua cobertura é não verificável até a configuração humana. Campos novos:

- `parameters`: name, type (`string`, `integer`, `boolean`), required e values (escolhas públicas permitidas; vazio permite valor livre quando não há contexto privado).
- `sources`, `destinations`: IDs permitidos do estado, sem curingas implícitos.
- `operations`: `select`, `transfer`, `choose`.
- `min_moves`, `max_moves`, `phase`, `player`, `required_state`: dados e limites necessários.
- `constraints`: pares kind/value; `effects`: field/value; `rule_refs`: referências ao manual.
- `reviewed`: revisão humana; `validation_complete`: declaração humana de que toda regra relevante foi representada por validadores suportados. Não deve ser marcada para contornar uma lacuna.

A extração propõe definições com ambos os indicadores falsos, e o servidor força essa condição nos rascunhos recebidos. IDs dependentes da montagem precisam ser associados às entidades reais depois de configurar a partida. O cadastro não é um compilador de regras em linguagem natural. Pré-condições em prosa não são executadas silenciosamente.

Validadores implementados: existência e disponibilidade, localização e quantidade, parâmetros/tipos/escolhas, fase/jogador/subetapa, origens/destinos permitidos, ordem das pilhas e nível seguinte, somente topo para retirada, `max_height`, `empty_destination`, `adjacent`, `selected_only`, `same_group`, `min_quantity`, `max_quantity`. O total de quantidade conta transferências quando a ação permite transferir; em ações só de seleção, conta seleções. Efeitos suportados são atribuições literais de `active_player`, `phase`, `substep` e `turn`. Outros efeitos/restrições são preservados e sinalizados como não verificáveis.

Transferências são unitárias, para tornar a ordem de pilha inequívoca. `select` marca a quantidade selecionada no lote; `choose` exige o nome de um parâmetro preenchido. Não há uma semântica universal para efeitos de cartas, pontuação, padrões geométricos ou recursos dinâmicos.

## Contrato e execução

A API Responses existente continua usando JSON Schema estrito. Há validação local de estrutura, tipos e enumerações. `parameters` vem da API como lista nome/valor e é convertido a objeto internamente, rejeitando nomes duplicados. A validação de legalidade é separada da validação do formato.

Resultados: `proposed`, `needs_data`, alegação `no_legal_move` e `analysis_error`. Sem busca exaustiva implementada, `no_legal_move` é exibido como alegação não comprovada, nunca como certeza. Dados essenciais desconhecidos impedem a apresentação de proposta executável. Ausência de cobertura de regras produz `unverifiable`, com aviso e revisão humana explícita; não aparece como `valid`.

Cada proposta identifica estado, versão, revisão das regras, jogador/fase/subetapa e ação. Movimentos carregam operation, component, source, destination, quantity, level e choice_parameter. A simulação opera numa cópia sequencial do estado. Erros locais são enviados à IA em até três tentativas totais; esgotamento exibe o motivo, sem fallback genérico. Contextos grandes são recusados, em vez de truncar o estado. Escolhas que dependem de revelação futura exigem uma nova etapa após atualizar o estado.

Passos públicos e fala são produzidos no servidor a partir dos movimentos validados, não de prosa livre. Em presença de cartas privadas, a justificativa é neutra e parâmetros textuais precisam corresponder a escolhas públicas cadastradas ou IDs públicos. Campos extras da resposta não são publicados. Essa proteção mantém as referências dos movimentos concretos, em vez de apagá-los.

Aprovação não altera o tabuleiro nem avança turno. A confirmação física aplica somente o prefixo informado. Execução parcial registra histórico, incrementa a versão e exige novo cálculo; efeitos finais da ação somente são aplicados na execução total. Se a pessoa executou algo diferente, deve corrigir o estado manualmente. Propostas antigas são bloqueadas na aprovação/execução.

## Compatibilidade e limites

Salas antigas são migradas de maneira aditiva, preservando regras, notas, variáveis e histórico. Notas antigas não se tornam peças ou ocupação inventada. Propostas antigas sem vínculo ao estado ficam arquivadas como `legacy_proposal` e não podem ser confirmadas como jogadas novas. Cartas privadas continuam efêmeras. Identificador de aplicativo, servidor, código de sala e armazenamento interno foram preservados.

A UI de correção é um editor JSON assistido por inclusão de entidades e calibração por toque. Não é um editor gráfico completo de pilhas. Há uma imagem pública congelada por sala; múltiplas áreas podem estar na mesma imagem ou ser registradas manualmente no estado. Não foi implementada fusão automática de várias câmeras, reconhecimento universal, estimativa de perspectiva ou validação universal de regras. A validação funcional com fotografias reais e API real cabe ao usuário conforme o roteiro.
