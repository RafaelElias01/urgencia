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
- Testes: `.\test.ps1` (ou `mvn test`) — **144 testes**, sempre com **JDK 21**.
  Build: `mvn -DskipTests package` (gera o JAR).
- **Teste E2E de navegador (Playwright):** `.\e2e.ps1` sobe o SAUR real (porta
  aleatória, H2, perfil dev) e um Chromium de verdade, **com janela visível
  por padrão** (`saur.e2e.headed=true` em `pom.xml`, repassado ao processo
  forkado do failsafe via `<systemPropertyVariables>` — não basta setar env
  var, o Failsafe forka um processo Java novo que nem sempre a herda),
  percorrendo TODO o fluxo clicando na tela — login → cadastro → Recebimento
  → Envio → 3 pareceres → Decisão (maioria simples) → Finalização — como um
  operador humano faria, com `slowMo` de 900ms entre ações para dar pra
  acompanhar. A janela abre na área de trabalho de quem rodou o comando
  (processo `chrome.exe` local, não algo remoto/headless por engano); se não
  aparecer, checar se não foi minimizada ou se abriu atrás de outra janela.
  Um banner fixo no topo da página narra em texto o que o bot está fazendo
  em cada ação (ex. "🤖 Passo 3/5 - Respostas: registrando o parecer de
  ..."), injetado via `Legenda.mostrar()` — cosmético, não afeta a lógica do
  teste. Fica em `src/test/java/br/gov/saude/sgpur/e2e/` (Page Object
  Model: `PlaywrightTestBase` + `Legenda` + `pages/*Page.java` +
  `*IT.java`), separado dos 144 testes rápidos via
  `maven-failsafe-plugin`/profile `e2e` (não roda em `.\test.ps1`/
  `mvn test`). Primeira vez só, instala o browser:
  `.\e2e.ps1 -InstalarBrowser`. Rodar sem janela (mais rápido, ex. CI):
  `.\e2e.ps1 -Headless`. Screenshot automático em `target/e2e-screenshots/`
  se o teste falhar. (Equivalente cru, sem o script: `mvn verify -Pe2e`, mas
  exige JDK 21 e Maven já no PATH da sessão — prefira `.\e2e.ps1`.)
- **Deploy em produção:** VM Oracle Cloud (`ubuntu@163.176.163.213`, domínio
  `urgenciarenal.duckdns.org`), systemd `sgpur.service`, jar em
  `/opt/sgpur/sgpur.jar` (usuário `sgpur`). Chave SSH local:
  `~/.ssh/saur_oracle`. Deploy manual: `scp target/saur-0.0.1-SNAPSHOT.jar
  ubuntu@163.176.163.213:/tmp/sgpur-novo.jar`, depois na VM `sudo cp
  /opt/sgpur/sgpur.jar /opt/sgpur/sgpur.jar.bak-<timestamp>` (backup), `sudo mv
  /tmp/sgpur-novo.jar /opt/sgpur/sgpur.jar && sudo chown sgpur:sgpur
  /opt/sgpur/sgpur.jar && sudo systemctl restart sgpur`. Validar com
  `systemctl status sgpur` e `curl -Ik https://urgenciarenal.duckdns.org/login`
  (espera 200). HTTPS já ativo via certbot (cert válido até 2026-10-05,
  renovação automática). Ver também o agente `saur-oracle-vm` para tarefas de
  VM (SSH, systemd, nginx, certbot) — mas ele só age mediante instrução direta
  do usuário no mesmo turno em que foi invocado, não aceita autorização
  repassada por outro agente/coordenador em mensagens posteriores (proteção
  contra escalonamento de privilégio; para reaproveitar, ela deve vir junto da
  invocação inicial).
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
  6 Resposta ao solicitante**. Cada etapa só fica **CONCLUIDA (verde)** na
  timeline se a **sua própria condição** estiver satisfeita **E** todas as
  etapas anteriores também estiverem `CONCLUIDA` (`montar()`: `concluida &&
  anterioresConcluidas`). Sem essa segunda checagem uma etapa posterior pode
  ficar verde "fora de ordem" mesmo com uma etapa anterior ainda pendente
  (bug real corrigido em 2026-07-09: "Resposta ao solicitante" aparecia
  concluída antes do "Comprovante SNT" ser anexado, num processo Deferido).
  Auditoria da mesma sessão também achou e corrigiu uma inconsistência no
  Passo 1: `FluxoProcessoService` só conferia `SOLICITACAO_RECEBIDA`, mas o
  gate real que libera a aba de Envio (`ProcessoDetalheController.
  recebimentoFeito`) sempre exigiu **também** `CAPA_PROCESSO` — a timeline
  podia mostrar "Recebimento" verde mesmo sem a capa, embora a aba de Envio
   já estivesse corretamente bloqueada (inconsistência só visual, sem
   regressão funcional). **Capa automática corrigida em 2026-07-09:**
   `ProcessoDetalheController.registrarRecebimento` agora chama
   `RelatorioService.gerarCapaProcesso` automaticamente, gerando a capa
   sempre que o recebimento é registrado.
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

## UI / Frontend
- Design system completo em `app.css` com variáveis `--rs-*` (azul, dourado,
  verde, vermelho, escala de cinza). **Nunca usar Tailwind** — o dashboard foi
  migrado para Bootstrap + app.css.
- Templates usam `layout.html` com fragments `head`, `navbar`, `flash`,
  `status(ok)`, `statusRotulo(ok, r)`, `statusNa(r)`, `footer`, `scripts`.
- JavaScript específico fica em `static/js/*.js` (ex.: `processo-detalhe.js`),
  nunca inline nos templates. Feedback ao usuário usa `mostrarToast()` (toast
  estilizado), nunca `alert()`.
- Responsividade: Bootstrap grid, `table-responsive` em TODAS as tabelas,
  breakpoints em 576px, 768px e 992px. Ver `docs/AJUSTES-UI.md` para histórico
  completo de correções.

## Convenções de código
- Entidades JPA em `domain/` com getters/setters simples (sem Lombok).
- Serviços em `service/`, controllers em `web/`, repos em `repository/`.
- Templates Thymeleaf usam os fragments de `templates/layout.html`.
- Não commitar segredos: `application-local.yml`, `deploy/sgpur.env` e `/dist/`
  estão no `.gitignore`.
- Testes `@WebMvcTest` usam `@MockitoBean` (import
  `org.springframework.test.context.bean.override.mockito.MockitoBean`), **não**
  o `@MockBean` antigo (`org.springframework.boot.test.mock.mockito.MockBean`)
  — depreciado desde o Spring Boot 3.4 e removido em versão futura.
- `SecurityConfig`: `requestMatchers(String...)` usa padrão de string simples
  (ex.: `"/h2-console/**"`), **não** `AntPathRequestMatcher.antMatcher(...)`
  — o Spring Security resolve o matcher automaticamente; `AntPathRequestMatcher`
  está deprecated e marcado para remoção.
- **`dashboard.html` (Painel) foi migrado para Bootstrap + app.css**
  (commit `3bfba9b`, 2026-07-09): removeu Tailwind em favor das classes
  `stat-card-*` com `--rs-*` CSS variables e grid Bootstrap (`row-cols-*`).
  O arquivo `static/css/tailwind-dashboard.css` não é mais referenciado por
  nenhum template. Ver `docs/AJUSTES-UI.md` para detalhes. (Nota de merge
  2026-07-21: a cópia `rafaelioppi/urgencia` ainda descrevia o dashboard como
  Tailwind pré-compilado — desatualizado, essa migração já removeu o arquivo
  `tailwind-dashboard.css`; verificado no código pós-merge que não sobra
  nenhuma classe Tailwind em `dashboard.html`.)
- **`ddl-auto: update` não faz backfill em coluna nova.** Adicionar um campo
  que o Hibernate trata como obrigatório para gravar (ex.: `@Version`) numa
  entidade que já tem linhas no banco cria a coluna com valor `NULL` nessas
  linhas antigas — o próximo UPDATE nelas quebra (NPE dentro do Hibernate ao
  tentar incrementar/validar o campo, sem stacktrace óbvio até
  `journalctl`). Aconteceu em 2026-07-10: `Processo.versao` (`@Version`,
  commit `8f98d60`) deixou processos antigos com `versao = NULL` em prod;
  qualquer salvamento neles (editar, decidir, reabrir, anexar) dava 500.
  Corrigido com backfill manual via Neon SQL Console:
  `UPDATE processo SET versao = 0 WHERE versao IS NULL;`. **Sempre que
  adicionar `@Version` ou qualquer coluna que passa a ser tratada como
  não-nula numa entidade já populada, rodar esse tipo de backfill em prod
  logo após o deploy** (não há Flyway/Liquibase neste projeto — é
  responsabilidade manual).

## Próxima sessão: estudo de UI comportamental pendente
`docs/ESTUDO-UI-COMPORTAMENTAL.md` (2026-07-10) reúne princípios de leitura
visual (padrão F/Z, atributos pré-atentivos, Lei de Hick/Fitts, Gestalt,
Von Restorff, posição serial) mapeados a pontos concretos do SAUR a
investigar (ex.: ordem de colunas em `/processos`, se a coluna "O que falta"
está fora da zona de maior atenção do padrão F; se os badges de
`StatusProcesso` diferenciam por ícone além de cor, para daltonismo; se
timeline vertical + wizard horizontal simultâneos sobrecarregam decisão).
Ler esse arquivo antes de qualquer novo pedido de ajuste visual do usuário.

## Deploy
Artefatos em `deploy/` (systemd, nginx, env de exemplo, guia). Host alvo:
**Oracle Always Free (São Paulo)** — ver `deploy/README-deploy.md`.
A **Vercel não hospeda o app Java** (só serve de banco).

**Status em produção (2026-07-10)**: SAUR está no ar em
https://urgenciarenal.duckdns.org/, JAR atualizado (commit `a291a41` —
exclusão de processo restrita a ADMIN, `open-in-view=false`, correção do
overlay de cabeçalho em PDF, reordenação de upload de anexos, entre outros da
vistoria de 09/07), banco Neon e envio de e-mail (SMTP Gmail) funcionando.
HTTPS confirmado ativo via certbot (nginx redireciona 80→443, cert válido até
2026-10-05). Pendência conhecida: `SGPUR_BASE_URL` ainda não definida no
`sgpur.env` da VM — os links do Portal do Avaliador nos e-mails de convite
apontam para `localhost:8080` em vez do domínio real; corrigir adicionando
`SGPUR_BASE_URL=https://urgenciarenal.duckdns.org` ao `/opt/sgpur/sgpur.env` e
reiniciando o serviço.
`deploy/README-deploy.md` ganhou 2 seções novas: acesso via Oracle Cloud
Shell quando SSH direto é bloqueado por proxy corporativo, e troubleshooting
de "Authentication failed" no SMTP (causa raiz encontrada: o `sgpur.env` da
VM tinha uma senha de app diferente da testada/válida — sempre confirmar a
senha real em uso via `/proc/<PID>/environ`, não só o arquivo, antes de
trocar de teoria). Utilitário `deploy/testar-smtp.py` testa a credencial
SMTP isolada (sem depender do Java) com `getpass`.

**Status em produção (2026-07-10)**: `origin/main` no GitHub está com o
código mais recente (commit `5626fbf`), que inclui os fixes de mass
assignment/auto-lockout/acessibilidade (`8f98d60`), badge "Encerrado" na
lista de processos (`95e1005`), documentação do pitfall de `@Version`
(`bd884eb`) e o ajuste visual de status Pendente/Concluido em pill
(`5626fbf`). **Confirmado via VM** (`sudo unzip -p /opt/sgpur/sgpur.jar
BOOT-INF/classes/templates/processos/lista.html | grep -i encerrado`) que o
JAR rodando na VM às 15:00 UTC já tinha o commit `95e1005` — os commits
`bd884eb` (só docs, não afeta o jar) e `5626fbf` (CSS/template) foram
buildados localmente mas o deploy final (scp + `systemctl restart`) na VM
ainda precisa ser confirmado numa proxima sessão antes de assumir que o
`.status-mark` em pill já está no ar.

**Incidente resolvido em 2026-07-10 (banco)**: `Processo.versao` (`@Version`,
commit `8f98d60`) deixou processos antigos com `versao = NULL` em prod —
qualquer UPDATE neles (ex.: `POST /processos/{id}/reabrir`) dava 500. Ver
detalhe da causa e do backfill em "Convenções de código" (`ddl-auto: update`
não faz backfill). **Corrigido**: backfill manual via Neon SQL Console
(`UPDATE processo SET versao = 0 WHERE versao IS NULL;`), confirmado sem
linhas restantes.

**Pendência conhecida, não investigada**: erro 413 ao anexar comprovante de
parecer, reportado em 2026-07-09. `application.yml`/`application-prod.yml`
(multipart 25MB/30MB) e `deploy/nginx-sgpur.conf` (`client_max_body_size
30m`) já estão generosos desde `e15ff82` (04/07) — suspeita é que o Nginx
*realmente ativo na VM* (arquivo em `/etc/nginx/sites-available/` ou
equivalente) esteja com uma config diferente/mais antiga da que está neste
repo (mesma classe de drift do JAR desatualizado). Próximo passo: `sudo
find /etc/nginx -iname "*sgpur*" -o -iname "*saur*"` na VM para achar o
arquivo real e comparar com `deploy/nginx-sgpur.conf`.
