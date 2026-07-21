# Estudo: princípios comportamentais de leitura visual aplicados ao SAUR

Preparado em 2026-07-10 para orientar a próxima sessão de ajustes de UI.
Não é um plano de implementação fechado — é a base teórica + uma lista de
pontos concretos do sistema atual onde cada princípio se aplica, para
priorizar na próxima conversa.

## Por que isso importa aqui
O SAUR é operado sob pressão (prazos de urgência renal, decisões médicas) por
usuários que repetem a mesma tela dezenas de vezes por dia (operador) ou
raramente (avaliador votando 1x por processo). Os dois perfis têm exigências
opostas: o operador quer varredura rápida e reconhecimento de padrão; o
avaliador esporádico precisa de clareza sem contexto acumulado. Os princípios
abaixo vêm de pesquisa de eye-tracking e psicologia cognitiva aplicada a
interfaces, não de preferência estética.

## Princípios e onde já aparecem (ou faltam) no SAUR

### 1. Padrão de leitura F/Z e "âncora visual" no canto superior esquerdo
Eye-tracking mostra que o olhar entra pelo canto superior esquerdo e decai em
atenção conforme desce/vai à direita (mais forte em telas com texto denso,
como listas e tabelas — padrão F; mais fraco em telas com poucos blocos
grandes, onde o padrão vira Z).
- **Onde já está certo**: a timeline vertical do processo fica na coluna
  esquerda (`detalhe.html`, `col-lg-3`) — primeira coisa vista, o que faz
  sentido porque é o resumo de "onde estou no fluxo".
- **Ponto a investigar**: na lista `/processos` (`lista.html`), confirmar se
  a coluna mais importante para decisão rápida (o que falta / pendência)
  está posicionada onde o olhar naturalmente chega primeiro na leitura em F
  — hoje "O que falta" é a última coluna à direita, fora da zona de maior
  atenção segundo o padrão F.

### 2. Atributos pré-atentivos (cor, forma, posição) para status
O cérebro processa cor, forma e movimento em ~200-250ms, antes de leitura
consciente de texto. Badges de status só cor (sem forma/ícone distinto) são
mais lentos de escanear, e falham para daltônicos (~8% dos homens).
- **Já corrigido nesta sessão**: o `.status-mark` (Pendente/Concluído) usa
  cor de fundo **+ ícone distinto** (X vs check) — não depende só da cor.
  Isso já segue o princípio corretamente; documentar como padrão obrigatório
  para qualquer novo indicador de status que for criado.
- **Ponto a investigar**: os badges de `StatusProcesso` na lista de
  processos (`bootstrapBadge`) — conferir se também têm ícone distinto por
  status, não só cor (Solicitado/Enviado/Deferido/Indeferido/Solicita
  informação/Cancelado são 6 estados, difícil diferenciar só por matiz de
  cor em daltonismo vermelho-verde, que é justamente o par usado aqui).

### 3. Lei de Hick (mais opções = mais tempo de decisão)
Tempo de decisão cresce logaritmicamente com o número de opções visíveis
simultaneamente.
- **Ponto a investigar**: o wizard horizontal tem 5 passos sempre visíveis
  + a timeline vertical tem 6 etapas sempre visíveis — são duas
  representações do mesmo progresso ao mesmo tempo, na mesma tela. Vale
  perguntar ao usuário se as duas são necessárias ou se uma delas poderia
  ficar secundária (ex.: timeline colapsável) para reduzir carga cognitiva
  simultânea, especialmente para o avaliador (perfil que usa o sistema raras
  vezes e não tem o "mapa mental" do fluxo memorizado).

### 4. Lei de Fitts (alvo maior/mais perto = mais rápido de acertar)
Botões de ação crítica devem ser grandes e próximos do fluxo de leitura, não
pequenos ou distantes do conteúdo relacionado.
- **Ponto a investigar**: botões de exclusão/reabertura de anexo (ícones
  pequenos `btn-sm` só com ícone, sem texto) — já têm `title`/`aria-label`
  corrigidos (commit `8f98d60`), mas vale medir se o alvo de clique é grande
  o bastante em mobile/tablet (a Secretaria pode ter usuários em notebooks
  com trackpad, não é só desktop com mouse).

### 5. Princípios de Gestalt — proximidade e região comum
Elementos agrupados visualmente (borda, fundo, espaçamento) são lidos como
"pertencem juntos" antes mesmo de ler o texto.
- **Já está bom**: os cards por etapa (`border-start border-3
  border-primary`) já usam região comum para agrupar sub-passos.
- **Ponto a investigar**: a linha "Copia da solicitação original" que
  ajustamos hoje (`detalhe.html:274-283`) está dentro de uma `list-group`
  separada do card de ação logo abaixo — confirmar visualmente se a relação
  "isso é o que falta / isso é a ação para resolver" fica clara pela
  proximidade, ou se vale unificar num único bloco.

### 6. Efeito Von Restorff (isolamento) para alertas críticos
Um elemento visualmente distinto do padrão ao redor é lembrado/notado muito
mais — mas perde força se usado demais (tudo "gritando" = nada se destaca).
- **Ponto a investigar**: contar quantos `alert-warning`/`alert-danger`
  simultâneos aparecem numa tela de detalhe de processo complexo (conflito
  de equipe + pendência de anexo + aguardando informação, por exemplo) — se
  for mais de 2-3 ao mesmo tempo, o efeito de destaque se perde e o usuário
  pode ignorar todos. Avaliar hierarquia (1 alerta "principal" vs. os demais
  rebaixados a texto simples com ícone).

### 7. Efeito de posição serial (primazia/recência em listas)
Em listas, itens no início e no fim são lembrados/notados mais que os do
meio.
- **Ponto a investigar**: a ordem das etapas na timeline (1 a 6) já segue a
  ordem cronológica real do fluxo — correto por padrão. Mas na lista
  `/processos`, confirmar se a ordenação padrão (mais recentes primeiro?
  mais urgentes primeiro?) está otimizada para o que o operador precisa ver
  primeiro no início do expediente.

## Como usar isto na próxima sessão
Não implementar tudo de uma vez. Sugestão de abertura da próxima conversa:
1. Revisitar este arquivo.
2. Escolher com o usuário 1-2 pontos "a investigar" acima (não os "já está
   certo") para aprofundar com prints reais das telas em uso.
3. Propor mudança pontual, testar em `/processos` e `/processos/{id}` local
   antes de qualquer deploy — mudança de UI comportamental exige validação
   visual, não só teste automatizado.

## Referências conceituais (sem necessidade de link externo)
Padrões de leitura F/Z, atributos pré-atentivos, Lei de Hick, Lei de Fitts,
Gestalt (proximidade/região comum), efeito Von Restorff e efeito de posição
serial são todos princípios consolidados de pesquisa em eye-tracking e
psicologia cognitiva aplicada a UI — não específicos de nenhuma biblioteca
ou framework, então se aplicam igual ao Bootstrap 5 + Thymeleaf usado aqui.
