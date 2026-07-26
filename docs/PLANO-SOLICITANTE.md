# PLANO — Módulo experimental "Solicitação Online" (novo perfil SOLICITANTE)

Status: **proposta de arquitetura, ainda não implementada.**
Objetivo: permitir que a equipe solicitante abra o pedido de urgência renal
*direto no sistema*, como alternativa ao fluxo atual (solicitação chega por
e-mail e o operador cadastra manualmente). **Os dois caminhos convivem** — o
processo pode nascer por e-mail (como hoje) ou pelo portal (novo). Módulo
**opcional/experimental**, deve poder ser desligado sem afetar o fluxo atual.

## O que o solicitante NÃO faz (confirmado pelo usuário)

- Não envia o processo para avaliação (não aciona o Passo 2 — Envio).
- Não escolhe os 3 médicos avaliadores.
- Não gera o PDF anonimizado para as equipes (Passo 2) — isso continua
  exclusivamente com o OPERADOR.

Ou seja: o solicitante só **alimenta dados de entrada**. Tudo que hoje é
"Passo 1 — Recebimento" em diante continua manual, do operador, sem mudança
nenhuma nas regras de negócio já vigentes (maioria simples, coordenador,
anexo obrigatório em parecer, comprovante SNT, ofício, processo encerrado
trava edição, pausa "solicita informação", imparcialidade por iniciais etc.
— nenhuma dessas regras é tocada por este módulo).

## Decisão de arquitetura: NÃO criar o `Processo` direto

`ProcessoService.cadastrar()` hoje é atômico e tem invariantes fortes:

- `numero` é `NOT NULL UNIQUE` no banco, numeração manual em 2026 (o
  operador digita) — não é algo que um solicitante externo deveria poder
  reivindicar ou disputar.
- Todo `Processo` nasce **com os 3 `Parecer` já criados** junto (mesma
  transação), atrelados aos 3 `MembroUrgenciaRenal` escolhidos — não existe
  hoje conceito de "processo sem avaliador ainda".
- `FluxoProcessoService`, e-mails, decisão, etc. todos assumem esse
  invariante.

Fazer o solicitante criar um `Processo` "incompleto" exigiria afrouxar essas
garantias em vários lugares documentados no `CLAUDE.md` como regra de
negócio dura. **Risco desnecessário** para um módulo que o próprio pedido já
descreve como experimental/opcional.

**Decisão:** criar uma entidade de **staging** separada,
`SolicitacaoOnline`, desacoplada do `Processo`. O operador faz a **triagem**
e, ao aceitar, "converte" a solicitação num `Processo` de verdade passando
pelo formulário/fluxo `ProcessoService.cadastrar()` **já existente e
inalterado** — só pré-preenchido com os dados que o solicitante mandou. Bug
ou abuso no módulo novo, na pior hipótese, produz um registro de staging
órfão — nunca corrompe um `Processo` real.

## Novo modelo de dados

### `Perfil.SOLICITANTE` (`domain/Perfil.java`)
Novo valor de enum, mesmo padrão de `AVALIADOR`. `ROLE_SOLICITANTE`.

### `Usuario` (`domain/Usuario.java`)
- Novo campo `equipeSolicitante` (String, nullable na entidade — mesmo
  padrão condicional de `Usuario.membro`/`email`: obrigatório só quando
  `perfil == SOLICITANTE`, validado no `UsuarioController`/`UsuarioService`,
  não `@NotBlank` na entidade).
- Cadastro de login SOLICITANTE continua exclusivo de ADMIN/OPERADOR via
  `/usuarios` (sem autocadastro — mesma decisão já tomada para AVALIADOR).
  Isso garante que "equipe solicitante" é confiável na origem: quem cria o
  login já vincula o usuário à equipe certa, o próprio solicitante não pode
  se declarar outra equipe depois.

### `SolicitacaoOnline` (nova entidade, `domain/SolicitacaoOnline.java`)
Espelha só o subconjunto de campos do `Processo` que o solicitante pode
preencher:

```
id
usuarioSolicitante   -> ManyToOne Usuario (quem enviou)
pacienteNome, pacienteRgct
solicitanteEquipe, solicitanteEmail   (pré-preenchidos e travados a partir
                                        do Usuario logado — o form não deixa
                                        editar, evita spoofing de equipe)
dataSituacaoEspecial
justificativaClinica   (TEXT — novo: hoje essa informação vem implícita no
                         e-mail/anexo; aqui precisa de um campo próprio)
status   -> StatusSolicitacaoOnline
dataEnvio
processoGerado        -> ManyToOne Processo, nullable (preenchido só ao
                          converter — link de rastreabilidade/auditoria)
observacoesTriagem     (texto livre do operador, ex. motivo de devolução)
versao   -> @Version (tabela nova, sem risco do pitfall de backfill já
             documentado em CLAUDE.md, mas mantém o padrão do projeto)
```

### `StatusSolicitacaoOnline` (novo enum)
`ENVIADA -> { CONVERTIDA, DEVOLVIDA, CANCELADA }`. Sem estado "em triagem"
separado no MVP (a fila do operador já é implicitamente "as ENVIADA").
- `CONVERTIDA`: virou `Processo` (`processoGerado` preenchido). Estado final.
- `DEVOLVIDA`: operador recusou/pediu correção, com `observacoesTriagem`.
  Solicitante pode reenviar (nova `SolicitacaoOnline`, não edita a antiga —
  mantém histórico).
- `CANCELADA`: o próprio solicitante cancelou antes da triagem.

### `AnexoSolicitacaoOnline` (nova entidade)
Documentos clínicos anexados antes de existir um `Processo` (não dá pra usar
`Anexo` direto: `Anexo.processo` é `@ManyToOne(optional = false)`). Campos
espelham `Anexo` (nome, contentType, tamanho, caminho armazenado, data) sem
o campo `tipo`/`parecer` — aqui só existe uma categoria (documento clínico
do pedido). Armazenamento em disco separado, ex.
`data/anexos/solicitacoes-online/{id}/`, via um `AnexoSolicitacaoOnlineStorage`
que segue o mesmo padrão de `AnexoStorageService` já existente.

## Segurança (`SecurityConfig`)

- `/solicitante/**` exige `ROLE_SOLICITANTE`; ADMIN/OPERADOR/AVALIADOR
  bloqueados nessa rota (mesmo padrão de `/avaliador/**`).
- SOLICITANTE bloqueado de tudo mais: `/processos/**`, `/membros/**`,
  `/avaliador/**`, `/usuarios/**`, `/auditoria/**`.
- Success handler do login roteia SOLICITANTE para `/solicitante` (mesmo
  padrão do handler que já roteia AVALIADOR para `/avaliador`).
- Seed dev-only: `solicitante1` / `solicitante123` (mesmo padrão de
  `avaliador1`), com `equipeSolicitante` de exemplo.

## Portal do Solicitante (`web/SolicitanteController.java`) — espelha `AvaliadorController`

- `GET /solicitante` — lista as **próprias** solicitações do usuário logado
  (nunca de outra equipe), com status e link "Nova solicitação".
- `GET /solicitante/nova` — formulário: paciente (nome + RGCT), data da
  situação especial, justificativa clínica, upload de documentos clínicos
  (múltiplos arquivos). Equipe/e-mail exibidos como somente-leitura
  (vêm do `Usuario` logado).
- `POST /solicitante/nova` — cria `SolicitacaoOnline` (status `ENVIADA`),
  salva os anexos, dispara e-mail de notificação para o(s) operador(es)
  ("Nova solicitação recebida pelo portal — aguardando triagem"), reusando
  `EmailSenderService`/`EmailTemplateService` e respeitando
  `app.mail.override-recipient` em dev (nunca manda para operador de
  verdade em ambiente de teste).
- `GET /solicitante/{id}` — detalhe/status só do próprio pedido; se
  `CONVERTIDA`, mostra o número do processo gerado (sem link para
  `/processos/**`, que é área restrita a ADMIN/OPERADOR).
- `POST /solicitante/{id}/cancelar` — só enquanto `ENVIADA` (antes da
  triagem).

## Fila de triagem do operador

- `GET /processos/solicitacoes-online` (ADMIN/OPERADOR) — lista as
  `ENVIADA`, como uma fila de entrada separada da lista de `Processo`.
- `GET /processos/solicitacoes-online/{id}` — revisão dos dados + documentos
  anexados pelo solicitante.
- `POST /processos/solicitacoes-online/{id}/converter` — **ponto de
  integração central**: redireciona para `GET /processos/novo` com os
  campos pré-preenchidos (paciente, RGCT, equipe, e-mail, data da situação
  especial, justificativa → campo `observacoes`) via parâmetro
  `origemSolicitacaoOnlineId`. O operador passa pelo **mesmo formulário e
  pelo mesmo `ProcessoService.cadastrar()` de sempre** — escolhe os 3
  médicos manualmente (regra de negócio intacta: "não escolhe os membros" é
  função do solicitante, continua sendo função do operador), confere/corrige
  os dados, digita o número (2026 ainda é manual). Ao salvar com sucesso:
  - copia cada `AnexoSolicitacaoOnline` para `Anexo` real no novo `Processo`
    como `TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR` (candidatos — o operador
    ainda revisa/anonimiza normalmente no Passo 2, igual hoje);
  - marca `SolicitacaoOnline.status = CONVERTIDA` e `processoGerado = <novo>`;
  - grava auditoria ligando os dois IDs (`SOLICITACAO_ONLINE_CONVERTIDA`).
- `POST /processos/solicitacoes-online/{id}/devolver` — marca `DEVOLVIDA`
  com `observacoesTriagem`; visível pro solicitante em `/solicitante`.

Passar pelo formulário normal (em vez de criar o `Processo` automaticamente
no clique de "converter") é proposital: preserva a triagem humana — conferir
dados, escolher os 3 avaliadores deliberadamente — e não duplica nenhuma
validação já existente em `ProcessoDetalheController`/`ProcessoService`.

## Feature flag

`app.solicitante.habilitado` (env `SGPUR_SOLICITANTE_HABILITADO`, default
`false` em prod inicialmente) — gate nas rotas `/solicitante/**`, no link de
navegação e na fila de triagem. Permite subir o módulo "apagado" e ligar
quando o time decidir usar, sem exigir outro deploy — alinhado com "pode ou
não ser utilizado" do pedido original.

## Não escopo deste módulo (v1)

- Sem autocadastro de SOLICITANTE (login sempre criado por ADMIN/OPERADOR).
- Sem edição de `SolicitacaoOnline` após envio (corrigir = reenviar nova,
  igual ao padrão de "Solicita informação" que sempre gera novo registro
  em vez de editar o antigo).
- Sem numeração/atribuição de avaliadores pelo solicitante (por definição
  do pedido).
- Sem exposição de `pacienteNome` completo a ninguém além de
  ADMIN/OPERADOR/o próprio solicitante — mesma convenção de imparcialidade
  já vigente para avaliadores (não se aplica aqui porque avaliador nunca vê
  `SolicitacaoOnline`, só o `Processo` já anonimizado no Passo 2).

## Entrega incremental sugerida

1. Entidades + enum novo (`Perfil.SOLICITANTE`, `Usuario.equipeSolicitante`,
   `SolicitacaoOnline`, `StatusSolicitacaoOnline`, `AnexoSolicitacaoOnline`)
   + repositórios.
2. `SecurityConfig` + seed dev + feature flag desligada por padrão.
3. `SolicitanteController` + templates (`solicitante/lista.html`,
   `solicitante/form.html`, `solicitante/detalhe.html` — reaproveitando os
   fragments de `layout.html` e o visual de `avaliador/*`).
4. Fila de triagem do operador + `converter`/`devolver` + hook de
   pré-preenchimento em `ProcessoDetalheController.novo`.
5. E-mail de notificação ao operador na nova solicitação.
6. Testes: unit da conversão (staging → `Processo`), `@WebMvcTest` do
   `SolicitanteController` (`@MockitoBean`, padrão do repo), 1 cenário
   Playwright de ponta a ponta (solicitante envia → operador converte →
   fluxo normal continua) anexado à suíte `.\e2e.ps1` existente.

## Riscos / pontos de atenção

- **Duplicidade de pedido**: nada impede o mesmo paciente ser solicitado
  duas vezes (pelo portal e por e-mail, ou duas vezes pelo portal). Fora de
  escopo do MVP — fica como checagem manual do operador na triagem (mesma
  situação de hoje, sem deduplicação automática).
- **Cópia de dados no `converter`**: se o operador editar os dados no
  formulário de `/processos/novo` antes de salvar, o `Processo` final pode
  divergir do que o solicitante mandou — é o comportamento esperado (o
  operador tem a palavra final), mas vale deixar visível na tela de triagem
  os dados originais lado a lado para conferência.
- Ao adicionar `@Version`/colunas obrigatórias em tabelas já populadas,
  lembrar do pitfall documentado em CLAUDE.md ("ddl-auto: update não faz
  backfill") — não se aplica aqui porque as tabelas são novas, mas vale
  registrar caso o campo `versao` seja adicionado depois de já ter dados.
