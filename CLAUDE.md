# CLAUDE.md — Guia do projeto SAUR

Sistema de Gestão de Processos de Urgência Renal (SAUR). Substitui a planilha
Excel da equipe de Urgência Renal da Secretaria de Saúde.

## Stack
Java 21 · Spring Boot 3.5.16 (web, data-jpa, thymeleaf, security, validation) ·
PostgreSQL/Neon (prod) e H2 (dev) · Thymeleaf + Bootstrap · OpenPDF · Maven.
Pacote base `br.gov.saude.sgpur` e env vars `SGPUR_*` (mantidos por enquanto,
não renomeados no rebrand SAUR). `artifactId` do Maven é `saur` (gera
`target/saur-0.0.1-SNAPSHOT.jar`).

## Toolchain (Windows desta máquina)
- JDK 21: `C:\Users\rafae\Tools\jdk-21.0.11+10` (NÃO usar o Java 17 do sistema).
- Maven: `C:\Users\rafae\Tools\apache-maven-3.9.6`.

## Como rodar / testar
```powershell
.\start.ps1            # dev (H2) — sandbox de teste
.\start.ps1 prod       # prod (Neon) — usa application-local.yml (gitignored)
```
- App em http://localhost:8080 · login inicial `admin` / `admin123` (criado
  automaticamente por `AdminBootstrap` só quando a tabela `usuario` está
  vazia; em prod exige `SGPUR_ADMIN_PASSWORD` via env var, sem default).
- Testes: `.\test.ps1` (ou `mvn test`) — **113 testes**, sempre com **JDK 21**.
  Build: `mvn -DskipTests package` (gera o JAR).
- **Não há mais empacotamento desktop** (`release.ps1`/`package-desktop.ps1`/
  Inno Setup foram removidos em 2026-07-03). O projeto é só web agora — rode
  via `start.ps1` e acesse pelo navegador.
- **Modo teste de e-mail:** em dev, `app.mail.override-recipient`
  (`application.yml`, default `rafaelioppi@gmail.com`) faz **todo** e-mail
  enviado pelo `EmailSenderService` ser redirecionado para esse endereço,
  independente do destinatário real calculado pelo sistema — nunca manda
  e-mail de teste para avaliadores/solicitantes de verdade. O assunto ganha
  um prefixo `[TESTE - para: ...]` com os destinatários originais. Em prod
  (`application-prod.yml`) fica explicitamente vazio, então o envio real
  funciona normalmente.

## Regras de negócio (não violar)
- Cada processo vai para **exatamente 3 médicos**. Decisão por **maioria
  simples (2 de 3)**: **≥2 favoráveis = Deferido**; **≥2 desfavoráveis =
  Indeferido** (exige **ofício + motivo**). As duas regras são **impostas** no
  serviço e no controller (`decidir` rejeita Deferido sem 2 favoráveis e
  Indeferido sem 2 desfavoráveis).
- **Exceção — coordenador CET-RS defere sozinho:** se o médico marcado como
  `MembroUrgenciaRenal.coordenador` votar **Favorável**, o processo é
  **Deferido com esse único voto**, sem esperar os outros 2 pareceres
  (`ProcessoService.temVotoCoordenadorFavoravel` /
  `favoraveisNecessariosParaDeferir` — usado em `sugerirDecisao` e `decidir`).
  A regra de **Indeferido continua exigindo ≥2 desfavoráveis** sempre (o
  coordenador não tem peso especial para indeferir). O detalhe do processo
  exibe o badge "Deferido pelo Coordenador da CET-RS"
  (`ProcessoService.deferidoPeloCoordenador`). Só 1 membro deve ter
  `coordenador = true` por vez.
- **Toda resposta de médico recebida** (parecer com `resultado` preenchido)
  **precisa ter o anexo comprobatório** (`TipoAnexo.RESPOSTA_AVALIADOR`
  vinculado ao parecer) **antes de Deferir/Indeferir**. Imposto no serviço e no
  controller (`pareceresRecebidosSemAnexo`) e refletido na etapa "Respostas dos
  médicos" do fluxo. Como a decisão exige ≥2 pareceres, isso garante ≥2 anexos.
- **Deferido exige anexar o comprovante de inserção da urgência renal no SNT**
  (`TipoAnexo.COMPROVANTE_SNT`) e enviá-lo junto na resposta ao solicitante; a
  etapa "Comprovante SNT" bloqueia a conclusão até o anexo existir (simétrico
  ao ofício no indeferimento). O comprovante é gerado fora do sistema.
- Status (ciclo expandido, reflete a planilha): `Solicitado` → `Enviado` →
  { `Deferido` / `Indeferido` / `Solicita informação` } (+ `Cancelado`).
  Finais: Deferido/Indeferido/Cancelado. `Em análise` é mantido como sinônimo
  legado de `Enviado` (registros antigos continuam válidos). Ver
  `docs/PLANO-FLUXO.md`.
- **Processo ENCERRADO trava a edição:** quando o status é final
  (Deferido/Indeferido/Cancelado), `ProcessoValidator.edicaoBloqueada` é `true`
  e **toda alteração é rejeitada** — imposto no controller (guarda
  `bloqueadoPorEncerrado` que devolve flash `erro` com
  `ProcessoValidator.MSG_ENCERRADO`) e reforçado no serviço
  (`ProcessoService.atualizarDados`/`decidir` lançam `IllegalStateException`).
  Bloqueia as etapas 1–4 (recebimento, envio/documento clínico/comprovante aos
  avaliadores, pareceres/resposta-avaliador, redecidir), o upload genérico
  `/anexos`, a exclusão de anexos e os lembretes. **Continuam liberadas** as
  etapas 5–6 (ofício, comprovante SNT, comprovante de envio ao solicitante,
  confirmar resposta) e o e-mail de resposta ao solicitante (`email/enviar`) —
  são a papelada pós-decisão. Downloads/relatórios (GET) sempre liberados. Para
  voltar a editar, **só o ADMIN reabre** (`POST /processos/{id}/reabrir`, que
  volta o status para `Enviado`). O detalhe mostra banner "Processo encerrado" e
  esconde os formulários bloqueados.
- **Solicita informação (PAUSA):** quando um avaliador vota
  `ResultadoParecer.SOLICITA_INFORMACAO`, o processo entra em
  `StatusProcesso.SOLICITA_INFORMACAO` (via
  `ProcessoService.atualizarStatusPorPareceres`, chamado em `salvarPareceres`).
  Isso **pausa o fluxo**: a Decisão fica **bloqueada** — `ProcessoService.decidir`
  lança erro ao tentar Deferir/Indeferir, o controller devolve flash de erro e a
  aba **4. Decisão** fica travada (`liberadoDecisao=false`). O checklist
  (`FluxoProcessoService`) insere a etapa **"Informacao complementar"** com o
  aviso *"Aguardando informacao complementar do solicitante"*. O sistema gera o
  e-mail pronto *"Pedido de informacao complementar ao solicitante"*
  (`EmailTemplateService.emailSolicitaInfo`) endereçado à **equipe solicitante**,
  com nº do processo + **nome completo** do paciente (e-mail ao solicitante leva
  o nome completo; só o material dos avaliadores usa iniciais). Na
  aba **3. Respostas** o operador tem dois botões: **registrar o reenvio** ao
  solicitante (`POST /processos/{id}/solicitar-info`, anexa cópia do e-mail em
  `TipoAnexo.INFO_COMPLEMENTAR`, mantém a pausa) e **registrar o recebimento +
  retomar a análise** (`POST /processos/{id}/retomar-analise` →
  `ProcessoService.retomarAposInformacao`): o processo **volta para `Enviado`**,
  os pareceres marcados como *Solicita informação* são **reabertos** (resultado
  limpo) para o voto definitivo, e o fluxo de Respostas/Decisão é liberado.
- **Fluxo em 6 passos** (checklist `FluxoProcessoService` + abas na tela):
  **1 Recebimento · 2 Envio · 3 Respostas · 4 Decisão · 5 Ofício/Comprovante ·
  6 Resposta ao solicitante**.
- **Passo 1 (Recebimento):** exige a **cópia da solicitação original**
  (`SOLICITACAO_RECEBIDA`, manual) **+** a **capa do processo**
  (`CAPA_PROCESSO`, **gerada pelo sistema** com dados do solicitante e os 3
  médicos — reaproveita a capa do Relatório Final via
  `RelatorioService.gerarCapaProcesso`). Endpoint
  `POST /processos/{id}/recebimento`. A etapa bloqueia até os dois existirem.
- **Passo 2 (Envio):** ao registrar o envio o sistema gera a **cópia anonimizada
  para as equipes** (`SOLICITACAO_AVALIADOR`, só iniciais), nome oficial
  `Processo CET-RS NN-AAAA - Paciente X.X.X.pdf`
  (`SolicitacaoAvaliadorService.nomeArquivoOficial`). **Não há mais folha-rosto
  gerada pelo sistema.** Esse anexo é um **PDF único** = os **documentos clínicos
  anonimizados** anexados ao processo (`DOCUMENTO_CLINICO_AVALIADOR`, só os PDF)
  **fundidos** (`SolicitacaoAvaliadorService.consolidar`) e depois **carimbados
  página a página** com um cabeçalho
  (`SolicitacaoAvaliadorService.carimbarCabecalho`, PdfStamper sobre o
  over-content — não altera o conteúdo). Cabeçalho em 2 linhas: "Central de
  Transplantes do Estado do Rio Grande do Sul - URGENCIA RENAL" e "Processo
  CET-RS NN/AAAA - Paciente X.X.X" (número + **iniciais**, nunca o nome
  completo — imparcialidade). O mesmo texto institucional ("Central de
  Transplantes do Estado do Rio Grande do Sul") é usado no Ofício
  (`OficioService`), no Relatório Final (`RelatorioService`) e no Relatório
  Anual (`RelatorioAnualService`) — trocar em um exige trocar nos 4 lugares. **É
  obrigatório ao menos um documento clínico PDF anexado:** `registrarEnvio`
  **bloqueia** (flash `erro`, sem efetivar o envio) se não houver nenhum. A
  **solicitação original** (`SOLICITACAO_RECEBIDA`) **NUNCA** entra nesse PDF
  (contém o nome completo). Documentos clínicos não-PDF são ignorados do merge com
  **aviso não-bloqueante** (flash `aviso`). **O comprovante de envio aos
  avaliadores (`EMAIL_ENVIADO_AVALIADORES`) também é obrigatório** (deixou de
  ser opcional): `registrarEnvio` **bloqueia** (flash `erro`) se não houver
  o PDF/EML/MSG do e-mail enviado aos 3 avaliadores anexado — os 3 sub-passos
  da aba Envio (documentos clínicos, comprovante de envio, registrar envio)
  são todos obrigatórios. O método legado
  `SolicitacaoAvaliadorService.gerar` (folha-rosto) **permanece no código mas não
  é mais chamado** no fluxo de envio. Os documentos clínicos são anexados na
  própria aba Envio (`POST /processos/{id}/documento-clinico`). **Aviso (não
  bloqueia)** se algum médico for da mesma equipe/instituição do solicitante —
  `ConflitoEquipeMatcher.mesmaEquipe(instituicaoMembro, solicitanteEquipe)`
  ignora maiúsculas/acentos e casa sigla × nome por extenso × cidade via mapa
  de apelidos por sigla (`ALIASES`); usa palavra/frase inteira (não substring).
  Instituições novas fora do `ALIASES` caem no match por tokens da própria
  sigla — ao cadastrar uma nova instituição relevante, enriquecer o `ALIASES`.
- Numeração `NN/AAAA`: **manual em 2026**, **automática a partir de 2027**.
- Fluxo por e-mail com anexos por etapa. **Identificação do paciente:** o
  e-mail/material aos **médicos avaliadores oculta o nome** do paciente (só
  iniciais), para preservar a **imparcialidade do julgamento** — os avaliadores
  decidem sem saber quem é o paciente (convenção da equipe de Urgência Renal,
  **não** é LGPD). Já os e-mails/documentos dirigidos à **equipe solicitante**
  (pedido de informação complementar, resposta de Deferido/Indeferido) levam o
  **nome completo** do paciente. Decisão manual com **sugestão automática** por
  maioria simples (2/3 favoráveis → Deferido; 2/3 desfavoráveis → Indeferido).
- "Membros da Urgência Renal" (nunca "Câmara Técnica").

## Portal do Avaliador (/avaliador) — Fase 1 MVP

Modelo **híbrido** de parecer: convive o voto pelo operador (e-mail) e o voto
autenticado do próprio médico no sistema.

### Perfil AVALIADOR
- Novo valor `Perfil.AVALIADOR` em `domain/Perfil.java`.
- `Usuario.membro` (`@ManyToOne`, nullable): vincula o login ao
  `MembroUrgenciaRenal` que ele representa. Obrigatório para AVALIADOR;
  ADMIN/OPERADOR devem ter `membro = null`.
- `UsuarioService` valida e persiste o vínculo (sobrecarga `criar/atualizar` com
  `membroId`). `UsuarioController` passa a lista de membros ao form.
- Seed dev-only: `avaliador1` / `avaliador123`, vinculado ao primeiro membro ativo.

### OrigemParecer (domain/OrigemParecer.java)
- `OPERADOR_EMAIL` — operador lançou o resultado após receber por e-mail; exige
  `TipoAnexo.RESPOSTA_AVALIADOR` como comprovante (comportamento anterior).
- `AVALIADOR_SISTEMA` — médico se autenticou no portal e votou diretamente; o
  registro autenticado (usuario + `dataHoraVoto` + IP no log de auditoria)
  substitui o anexo. `pareceresRecebidosSemAnexo` ignora esses pareceres.
- Origem `null` (legado) equivale a `OPERADOR_EMAIL`.
- Novos campos em `Parecer`: `origem`, `dataHoraVoto`, `votadoPor`.

### Segurança
- `SecurityConfig`: `/avaliador/**` exige `ROLE_AVALIADOR`; OPERADOR/ADMIN ficam
  bloqueados nessa rota. Success handler redireciona AVALIADOR para `/avaliador`,
  demais para `/`.

### AvaliadorController (web/AvaliadorController.java)
- `GET /avaliador` — lista pareceres pendentes do membro logado (status
  ENVIADO/EM_ANALISE, resultado nulo, dataEnvio preenchida). Exibe **somente
  iniciais** do paciente — nunca nome completo, equipe solicitante ou
  co-avaliadores.
- `GET /avaliador/{processoId}` — formulário de voto. 403 se não for avaliador do
  processo, se o parecer já foi emitido, ou se o status não é ENVIADO/EM_ANALISE.
- `POST /avaliador/{processoId}/votar` — grava `resultado`, `dataResposta`,
  `dataHoraVoto`, `votadoPor`, `origem=AVALIADOR_SISTEMA`; chama
  `atualizarStatusPorPareceres`; registra auditoria com IP.

### Auditoria com IP
- `LogAuditoria.ip` (VARCHAR 45, nullable — comporta IPv6).
- `AuditoriaService.registrar(acao, detalhe, ip)` — sobrecarga que grava o IP.
  Método sem IP delega a ela com `null` (sem quebrar chamadas existentes).
- Coluna IP visível em `/auditoria` (ADMIN).

### E-mail
- `EmailTemplateService.emailConviteAvaliador(p, membro)` — gera texto com
  iniciais e link `{app.base-url}/avaliador` para copiar/colar.
- `app.base-url` configurável em `application.yml` (default `http://localhost:8080`,
  variável de ambiente `SGPUR_BASE_URL` em prod).
- Template "convite-portal" incluído em `gerar(p)` quando status ENVIADO/EM_ANALISE.

## Perfis e permissões (SecurityConfig)
- **ADMIN**: acesso total, incluindo `/usuarios/**` (cadastro de LOGINS) e
  `/auditoria/**` — exclusivos dele.
- **OPERADOR**: acesso operacional completo a `/processos/**`,
  `/controle-urgencias/**`, `/membros/**` (criar/editar/inativar médicos
  avaliadores) e `/relatorios/**`. **Não** cria/edita usuários (logins) nem vê
  auditoria. Não acessa `/avaliador/**`.
- **AVALIADOR**: acesso restrito ao portal `/avaliador/**`; não acessa
  `/usuarios/**`, `/auditoria/**` nem as áreas operacionais.
- **Conta própria**: qualquer perfil logado troca a própria senha em
  `/usuarios/minha-senha` (menu dropdown no nome do usuário, navbar) — rota
  liberada com `authenticated()`, ANTES da regra geral `/usuarios/**` (ADMIN).

## Conta de usuário (Usuario)
- **E-mail obrigatório** no cadastro/edição via `/usuarios` (validado no
  `UsuarioController`, criar e atualizar — como a senha). **Não** é
  `@NotBlank` na entidade `Usuario.email`: colocar a anotação lá quebra o
  `AdminBootstrap` (cria o ADMIN inicial sem e-mail) e qualquer seed/usuário
  legado sem e-mail no persist (`ConstraintViolationException` no boot). A
  entidade só valida o **formato** (`@Email`), a obrigatoriedade fica na
  camada web.
- `UsuarioService.atualizar` precisa copiar `form.getEmail()` explicitamente
  (não é campo automático) — já corrigido, mas é fácil esquecer de novo se
  reescrever esse método.

## Indicador: tempo de resposta dos avaliadores
- `TempoRespostaService.calcular()` — média de **dias corridos** entre
  `Parecer.dataEnvio` e `Parecer.dataResposta`, geral e por avaliador, mais a
  contagem "fora do prazo". Prazo-meta configurável em
  `app.avaliador.prazo-dias` (env `SGPUR_PRAZO_AVALIADOR`, default 7).
- Exibido em `/membros` (card da média geral + coluna por avaliador) e no
  Painel (`/`, card "Tempo de resposta"). Formatação pt-BR pronta no service
  (`formatarDias`), nunca calculada na view.
- Pareceres reabertos por "Solicita informação" mantêm `dataEnvio` original
  (só `dataResposta` é limpo) — o 2º voto conta desde o envio original, não
  reseta o relógio.

## Convenções de código
- Entidades JPA em `domain/` com getters/setters simples (sem Lombok).
- Serviços em `service/`, controllers em `web/`, repos em `repository/`.
- Templates Thymeleaf usam os fragments de `templates/layout.html`.
- Não commitar segredos: `application-local.yml`, `deploy/sgpur.env` e `/dist/`
  estão no `.gitignore`.
- **`dashboard.html` (Painel) usa Tailwind PRÉ-COMPILADO estático**
  (`static/css/tailwind-dashboard.css`), não o CDN — só as classes que
  existiam quando o arquivo foi gerado funcionam. Usar uma classe nova (ex.:
  `lg:grid-cols-7`, `border-sky-200`) que não está nesse CSS **não dá erro**,
  simplesmente não aplica estilo nenhum, silenciosamente quebrando o layout
  (já aconteceu: um card novo com `grid-cols-7`/`sky-*` estourou o grid para 3
  colunas e os cards de contador ficaram enormes). Antes de usar uma classe
  Tailwind nova nessa página, `grep` no CSS compilado para confirmar que
  existe; se não existir, reusar uma cor/utilitário já presente (as paletas
  `slate/amber/emerald/rose/indigo` já usadas nos cards existentes estão
  compiladas) em vez de regenerar o CSS. Ver `docs/PLANO-FLUXO.md` para o
  procedimento de regeneração, caso seja mesmo necessário.

## Deploy
Artefatos em `deploy/` (systemd, nginx, env de exemplo, guia). Host alvo:
**Oracle Always Free (São Paulo)** — ver `deploy/README-deploy.md`.
A **Vercel não hospeda o app Java** (só serve de banco).
