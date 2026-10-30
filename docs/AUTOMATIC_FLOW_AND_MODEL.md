> Na versão 0.8, a interface nativa e a persistência foram migradas para Compose/MVVM e Room. O catálogo, os contratos e os critérios descritos aqui continuam válidos. Veja [a arquitetura atual](MVVM_ARCHITECTURE.md).

# Fluxo automático e escolha de modelo e raciocínio — 0.7

## Mudança principal

O fluxo normal passou a ser: importar manual → enviar foto → **Identificar e sugerir jogada para a automa** → conferir proposta → executar e confirmar. A importação inicia a interpretação do manual. O pedido de jogada identifica o modelo de estado quando necessário, analisa a foto, preenche o estado aproveitável e usa o mesmo simulador/validador de movimentos já existente.

Deixaram de ser pré-requisitos a aprovação separada do modelo de estado, a importação manual de candidatos e o preenchimento de todos os formulários. Os painéis de regras, variáveis e entidades viraram correções opcionais. O fluxo principal mostra a próxima pergunta relevante quando a decisão está bloqueada; outros esclarecimentos continuam acessíveis numa área opcional.

## Modelo e raciocínio em menus

No controlador, **IA** abre dois menus suspensos: **modelo** e **nível de raciocínio**. A interface web local usa os mesmos dados. Ao escolher outro modelo, o segundo menu mantém o nível se ele for compatível; caso contrário, mostra **Padrão do modelo** antes de salvar. Modelos sem ajuste de raciocínio mostram **Não ajustável neste modelo**. Deixar a chave em branco mantém a atual; apagar a chave é uma ação distinta.

As duas escolhas são salvas juntas nas preferências e usadas em manual/PDF, fotos, cartas privadas e decisões. O valor anterior do modelo é preservado na atualização; instalações sem nível salvo usam `default`. A opção **Padrão do modelo** omite `reasoning` da requisição. **Sem raciocínio adicional** envia explicitamente `reasoning.effort=none`, somente nos perfis que o aceitam. Os demais níveis enviam `reasoning.effort` com o valor escolhido. Não há tradução do nível em texto no prompt nem fallback silencioso.

O catálogo compartilhado está em `app/src/main/assets/ai/models.json`; pode ser ampliado sem alterar as telas. Os níveis foram conferidos na documentação oficial em 28/10/2026. É uma lista local de perfis, não uma consulta aos modelos liberados para a conta. Disponibilidade e compatibilidade efetiva continuam sujeitas à API; HTTP 400/404/422 informa também a possibilidade de nível incompatível. Não é feita chamada paga ao salvar.

Um modelo personalizado já salvo continua como opção do menu. Snapshots datados e IDs de fine tuning com base conhecida usam o perfil correspondente. Um ID desconhecido mantém somente **Padrão do modelo**, pois não há evidência de quais níveis aceita. Não há campo de digitação livre nos menus. Para incluir outras opções, atualize o catálogo. O servidor mantém compatibilidade com o ID salvo anteriormente.

Cada análise captura modelo e raciocínio juntos, sem mudar entre etapas. Alterar apenas o nível também incrementa a revisão da configuração e impede aplicar resultado antigo. O corpo de requisição usado no cache de manuais inclui o nível; respostas de outra configuração não são reutilizadas. As chaves continuam no `ApiKeyStore`, fora do JSON da sala e das configurações web. Clientes LAN podem consultar as escolhas, mas a alteração global continua restrita ao controlador.

Referências oficiais consultadas:

- https://developers.openai.com/api/docs/guides/reasoning
- https://developers.openai.com/api/docs/models/gpt-6.1-sol
- https://developers.openai.com/api/docs/models/gpt-6-astra
- https://developers.openai.com/api/docs/models/gpt-6-sol
- https://developers.openai.com/api/docs/models/gpt-6-luna
- https://developers.openai.com/api/docs/models/gpt-5.5
- https://developers.openai.com/api/docs/models/gpt-5.4
- https://developers.openai.com/api/docs/models/gpt-5.4-mini
- https://developers.openai.com/api/docs/models/gpt-5.2
- https://developers.openai.com/cookbook/examples/gpt-5/gpt-5_new_params_and_tools
- https://developers.openai.com/api/docs/models/gpt-4.1
- https://developers.openai.com/api/docs/models/gpt-4.1-mini

## Inferir sem exigir conferência de cada campo

Definições extraídas do manual podem ser ativadas como `manual_inference`, sem marcar `reviewed=true` ou `validation_complete=true`. A pessoa continua podendo corrigir e revisar, mas o fato de o esquema não ter sido revisado não impede a IA de usá-lo para identificar o estado. Dados necessários de um esquema inferido continuam sendo verificados: remover a aprovação separada não remove as dependências.

O reconhecimento retorna confiança estimada e evidência por entidade, fato e metadado. Dados com evidência e confiança estimada de pelo menos 0,85 podem ser aproveitados; inferências permanecem `inferred`, nunca viram confirmação humana por conveniência. Leituras abaixo desse critério e informações ausentes permanecem desconhecidas. Esse limiar é uma heurística de fluxo: a autoconfiança do modelo não é uma probabilidade calibrada nem comprovação de leitura correta.

A fase e a subetapa são inferidas quando há suporte no contexto/regras/foto. O identificador `automa` é o papel interno do aplicativo, não uma identidade visual inventada. Quando o jogador ativo ainda é desconhecido, clicar explicitamente em sugerir **para a automa** registra essa intenção como informação da interação. Um jogador ativo diferente já conhecido não é sobrescrito dessa forma.

IDs reconhecidos são reconciliados com os existentes por referências, âncoras de lotes/tipo/localização/nível e, para áreas, sobreposição de regiões. Correspondências ambíguas ficam pendentes. Peças indistinguíveis continuam representadas como lotes. Omissão de detecção não esvazia posições nem confirma disponibilidade de um componente visto antes.

As correções humanas são mantidas. Se uma leitura divergir de um dado corrigido e a divergência afetar a jogada, há bloqueio com pergunta. Quando a pessoa resolve uma divergência daquela mesma foto, o sistema registra a resolução e não repete indefinidamente a mesma pergunta; uma foto nova pode exigir nova análise.

Alterações semânticas (modelo, respostas, fase informada) não tornam uma fotografia fisicamente antiga por simples mudança de versão. A versão física muda com execução de movimentos ou correção ampla do tabuleiro. Uma foto anterior a essa mudança exige atualização. Isso não permite provar a data de uma imagem antiga reenviada.

## Aprovação continua necessária

O resultado é simulado e validado contra estado e regras, sequencialmente. Uma intenção genérica não passa a ser jogada. Dados essenciais desconhecidos, estruturas inconsistentes, quantidades indisponíveis e conflitos relevantes continuam bloqueando a proposta.

Reconhecimento automático e regras não verificadas continuam sinalizados. A aprovação da proposta é uma única ação de revisão humana, sem outra caixa de confirmação dos campos. Ela não executa movimentos. Após mover os componentes, a pessoa confirma o prefixo realizado. Essa confirmação não transforma em confirmação humana todos os outros objetos identificados por visão, nem todas as variáveis/fases inferidas.

O fluxo automático pode reunir mais de uma chamada numa solicitação: descoberta do esquema, duas passagens de visão e geração/correções da proposta. Há limite de três tentativas na correção de movimentos e nenhum polling que gera novas decisões por conta própria. A interface mostra as etapas em andamento. Isso não representa redução garantida de custo ou latência.

## Compatibilidade e limites

Foram preservados Android Java, o pipeline Kotlin existente, interface web, LAN, salas, persistência e memória privada. Não há instruções por nome de jogo, Compose, ViewModel, novos testes instrumentados ou UI automatizada.

A automação remove etapas de configuração, não resolve universalmente informação oculta, ambiguidade visual, regras ausentes ou efeitos sem implementação. A qualidade com fotografias, manuais, modelos e contas reais continua dependente da validação manual do usuário.
