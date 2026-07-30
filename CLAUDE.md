# CLAUDE.md — Guia do projeto SAUR

Sistema de Gestão de Processos de Urgência Renal (SAUR). Substitui a planilha
Excel da equipe de Urgência Renal da Secretaria de Saúde.

## Stack
Java 21 · Spring Boot 3.5.16 (web, data-jpa, thymeleaf, security, validation) ·
PostgreSQL (prod — rodando na própria VM Oracle desde 2026-07-25, ver seção
Deploy; usou Neon até essa data) e H2 (dev) · Thymeleaf + Bootstrap ·
OpenPDF · Maven.
Pacote base `br.gov.saude.sgpur` e env vars `SGPUR_*` (mantidos por enquanto,
não renomeados no rebrand SAUR). `artifactId` do Maven é `saur` (gera
`target/saur-0.0.1-SNAPSHOT.jar`).

## Toolchain (Windows desta máquina)
- JDK 21: `C:\Users\rafae\Tools\jdk-21.0.11+10` (NÃO usar o Java 17 do sistema).
- Maven: `C:\Users\rafae\Tools\apache-maven-3.9.6`.

## Como rodar / testar
```powershell
.\start.ps1            # dev (H2) — sandbox de teste
.\start.ps1 prod       # prod (Postgres) — usa application-local.yml (gitignored)
```
- App em http://localhost:3000 (porta trocada de 8080 para 3000 no commit
  `93debdf`, 2026-07-21; `start.ps1` **não abre o navegador sozinho**, precisa
  acessar manualmente após o boot) · login inicial `admin` / `Admin123!`
  (criado automaticamente por `AdminBootstrap` só quando a tabela `usuario`
  está vazia; em prod exige `SGPUR_ADMIN_PASSWORD` via env var, sem default).
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
- **Parecer só entra no sistema pelo Portal do Avaliador (mudança de
  2026-07-27).** O médico se autentica em `/avaliador` e vota ele mesmo
  (`origem = AVALIADOR_SISTEMA`) — não existe mais nenhum caminho para o
  OPERADOR lançar/editar manualmente o resultado de um parecer. Os dois
  endpoints que faziam isso (`POST /processos/{id}/resposta-avaliador` e
  `POST /processos/{id}/pareceres`, em `ProcessoDecisaoController`) e os
  respectivos forms na tela de detalhe (card "Respostas dos Avaliadores")
  foram **removidos**. `OrigemParecer.OPERADOR_EMAIL` e o requisito de anexo
  `TipoAnexo.RESPOSTA_AVALIADOR` **continuam existindo só para leitura**
  (badge "Origem: Operador", coluna "Resposta anexada" etc.) de processos
  antigos já registrados assim antes dessa mudança — não removidos do enum
  nem da lógica de exibição, só deixaram de ser um caminho ativo. O card de
  Respostas na tela de detalhe agora só acompanha o resultado de cada
  avaliador e permite enviar lembrete por e-mail
  (`POST /processos/{id}/lembrete-avaliador`/`lembrete-pendentes`, que
  continuam existindo — só avisam o avaliador para ir votar no portal, não
  registram parecer). Como a decisão exige ≥2 pareceres e o voto autenticado
  dispensa anexo, a regra antiga "resposta recebida exige anexo antes de
  decidir" só se aplica hoje a pareceres legados com `origem =
  OPERADOR_EMAIL`/`null` (`pareceresRecebidosSemAnexo`, ainda impõe isso no
  serviço e no controller do endpoint `/decidir`).
- **Deferido exige anexar o comprovante de inserção da urgência renal no SNT**
  (`TipoAnexo.COMPROVANTE_SNT`) e enviá-lo junto na resposta ao solicitante; a
  etapa "Comprovante SNT" bloqueia a conclusão até o anexo existir (simétrico
  ao ofício no indeferimento). O comprovante é gerado fora do sistema.
  **Desde 2026-07-27, o Portal do Solicitante também exibe o resultado final**
  (`/solicitante/{id}`): quando o `Processo` gerado está Deferido/Indeferido/
  Cancelado, a tela mostra a decisão e, se o anexo já existir, um botão de
  download do comprovante SNT (Deferido) ou do ofício de indeferimento
  (Indeferido) via `GET /solicitante/{id}/processo-anexo/{anexoId}`
  (`SolicitanteController.baixarAnexoProcesso`) — endpoint com whitelist
  explícita de `TipoAnexo` (só esses dois) e checagem de posse, nunca serve
  qualquer anexo do processo por ID. Isso é **além** do e-mail com
  comprovante de envio, que continua obrigatório para concluir o processo
  (não foi substituído).
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
  o nome completo; só o material dos avaliadores usa iniciais). Como **todo**
  `Processo` nasce de uma `SolicitacaoOnline` convertida, o solicitante envia a
  informação complementar diretamente pelo **Portal do Solicitante**
  (`POST /solicitante/{id}/informacao-complementar`,
  `SolicitacaoOnlineService.enviarInformacaoComplementar`) — vira o anexo
  `TipoAnexo.INFO_COMPLEMENTAR` no `Processo`, visível ao operador no card de
  Respostas (o operador pode reforçar o pedido pelo e-mail pronto acima, se
  quiser, mas isso é só o texto/e-mail — não existe mais nenhum endpoint para
  "registrar" esse reenvio manualmente). Na aba **3. Respostas**, quando a
  resposta chegar, o operador **registra o recebimento + retoma a análise**
  (`POST /processos/{id}/retomar-analise` →
  `ProcessoService.retomarAposInformacao`): o processo **volta para `Enviado`**,
  os pareceres marcados como *Solicita informação* são **reabertos** (resultado
  limpo) para o voto definitivo, e o fluxo de Respostas/Decisão é liberado. O
  solicitante só ENVIA; retomar a análise continua exclusivo do OPERADOR via
  `retomarAposInformacao`.
- **Solicitante pode cancelar até a decisão final (desde 2026-07-29).** Antes, o
  Portal do Solicitante só permitia cancelar enquanto a solicitação estava
  `ENVIADA` (não triada). Agora `SolicitacaoOnlineService.podeCancelar` abre
  duas janelas: `ENVIADA`, **ou** `CONVERTIDA` com o `Processo` gerado **ainda
  não decidido** (`StatusProcesso.isFinalizado() == false`) — casos reais:
  paciente transplantado, óbito, ou pedido aberto por engano enquanto os 3
  médicos já analisam. Depois de Deferido/Indeferido **não cancela mais**.
  `podeCancelar` é **fonte única**: a tela pergunta a ele se mostra o botão e
  `cancelar` pergunta a ele antes de efetivar — não duplicar a condição no
  template. Quando já há processo, `cancelar` **delega a
  `ProcessoService.decidir(id, CANCELADO, null)`** em vez de trocar status na
  mão (mesmo caminho do cancelamento pelo operador, mesmas travas,
  `dataDecisao` gravada) e devolve o `processoId`; sem processo, devolve
  `null`. Os **avaliadores pendentes são avisados por e-mail**
  (`EmailTemplateService.emailCancelamentoAvaliador`, só iniciais) via
  `notificarAvaliadoresCancelamento`, chamado pelo controller **depois** do
  commit e **nunca** dentro da transação — falha de SMTP vira flash `aviso`,
  jamais um rollback que "descancelaria" o processo (mesmo contrato do convite
  automático ao registrar envio). Auditoria:
  `CANCELAMENTO_AVISO_AVALIADOR_ENVIADO`/`_FALHA`.
- **`decidir` espelha `CANCELADO` como `StatusSolicitacaoOnline.CANCELADA`**
  (corrigido em 2026-07-29). Antes, o `switch` mandava tudo que não fosse
  `DEFERIDO` para `REPROVADA` — um processo cancelado aparecia como
  "Reprovada" no Portal do Solicitante, dizendo que a equipe analisou e negou
  o pedido quando ele só foi cancelado (às vezes pelo próprio solicitante).
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
   `ProcessoDetalheController.registrarRecebimento` chamava
   `RelatorioService.gerarCapaProcesso` automaticamente, gerando a capa
   sempre que o recebimento era registrado. **Esse endpoint e esse método
   foram removidos em 2026-07-27** (ver bullet "Passo 1 (Recebimento)"
   abaixo) — o parágrafo acima é histórico, mantido para quem for procurar o
   contexto do bug de 2026-07-09, mas o comportamento descrito já não existe
   mais no código.
- **Passo 1 (Recebimento): SEMPRE automático desde 2026-07-27.** Criação
  manual de processo "do zero" deixou de existir — `GET/POST /processos`
  (`ProcessoDetalheController.novo`/`salvar`) agora **exigem**
  `origemSolicitacaoOnlineId` (rejeita com flash de erro e redireciona para
  `/processos/solicitacoes-online` se vier nulo/ausente; se o módulo do
  Portal estiver desligado via `app.solicitante.habilitado=false`, redireciona
  para `/processos` com mensagem própria, já que a fila de triagem nem está
  registrada nesse caso). Como **todo** `Processo` agora nasce de uma
  `SolicitacaoOnline` convertida, a distinção "veio do portal ou não" deixou
  de ter efeito prático no Passo 1: `FluxoProcessoService.montarEtapas`
  marca a etapa Recebimento como **sempre `CONCLUIDA`**, incondicionalmente,
  sem checar nenhum anexo (`SOLICITACAO_RECEBIDA`/`CAPA_PROCESSO`); mesmo
  vale para `calcularGating` (`recebimentoFeito = true` sempre). O antigo
  endpoint `POST /processos/{id}/recebimento`
  (`ProcessoDetalheController.registrarRecebimento`, upload da solicitação
  original + geração da `CAPA_PROCESSO` via `RelatorioService.
  gerarCapaProcesso`) foi **removido** — sem cadastro manual não sobrou
  nenhum processo real que precisasse dele. `FluxoProcessoService.
  veioDoPortal(p)` **continua existindo** (não foi removido), mas hoje serve
  só para achar o `solicitacaoOnlineOrigemId` e exibir o link "Ver
  solicitação original" no card de Recebimento da tela de detalhe — não
  influencia mais nenhum gating. Os valores de enum `TipoAnexo.
  SOLICITACAO_RECEBIDA`/`CAPA_PROCESSO` e o método `PdfRelatorioBuilder.
  adicionarCapa` (reaproveitado pelo Relatório Final, com outros parâmetros)
  continuam no código para leitura de processos antigos.
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
  avaliadores (`EMAIL_ENVIADO_AVALIADORES`) deixou de ser exigido em
  2026-07-27** (fluxo antigo, do tempo em que o envio aos avaliadores era só
  por e-mail): os avaliadores hoje votam autenticados no Portal do Avaliador
  (`/avaliador`), que nunca dependeu desse anexo (`AvaliadorController` só
  checa o vínculo do parecer, nunca `EMAIL_ENVIADO_AVALIADORES`) — o
  requisito era um gate sem função posterior. `registrarEnvio` e
  `ProcessoValidator.validarRegistroEnvio` não checam mais esse anexo, e a
  seção "Anexar comprovante de envio" e o endpoint
  `POST /processos/{id}/comprovante-envio-avaliadores` foram removidos da
  aba Envio. O enum `TipoAnexo.EMAIL_ENVIADO_AVALIADORES` **permanece** (só
  não é mais exigido) — processos antigos em produção já têm esse anexo
  gravado, removê-lo do enum quebraria o carregamento deles. Os 2 sub-passos
  restantes da aba Envio (documentos clínicos, registrar envio) continuam
  obrigatórios. O método legado `SolicitacaoAvaliadorService.gerar`
  (folha-rosto) foi **removido em 2026-07-27** por falta de qualquer
  chamador (`consolidar`/`carimbarCabecalho`/`nomeArquivoOficial` continuam
  ativos na mesma classe). Os documentos clínicos são anexados na
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

**Atualização de 2026-07-27: o "modelo híbrido" original foi encerrado.**
Antes, convivia o voto pelo operador (e-mail) e o voto autenticado do próprio
médico no sistema. Agora **parecer só é registrado pelo avaliador autenticado
no Portal** (`origem = AVALIADOR_SISTEMA`) — o operador não lança/edita mais
resultado de parecer manualmente por nenhum caminho. `OrigemParecer.
OPERADOR_EMAIL` e o requisito de anexo `TipoAnexo.RESPOSTA_AVALIADOR`
permanecem no código **só para leitura/histórico** de pareceres já
registrados assim antes dessa mudança (nada foi apagado do banco nem do
enum) — não é mais um caminho ativo de escrita. Ver detalhe da remoção em
"Regras de negócio" acima.

### Perfil AVALIADOR
- Novo valor `Perfil.AVALIADOR` em `domain/Perfil.java`.
- `Usuario.membro` (`@ManyToOne`, nullable): vincula o login ao
  `MembroUrgenciaRenal` que ele representa. Obrigatório para AVALIADOR;
  ADMIN/OPERADOR devem ter `membro = null`.
- `UsuarioService` valida e persiste o vínculo (sobrecarga `criar/atualizar` com
  `membroId`). `UsuarioController` passa a lista de membros ao form.
- Seed dev-only: `avaliador1` / `avaliador123`, vinculado ao primeiro membro ativo.

### OrigemParecer (domain/OrigemParecer.java)
- `OPERADOR_EMAIL` — **legado, só leitura.** Identificava o parecer lançado
  pelo operador após receber a resposta por e-mail, exigindo
  `TipoAnexo.RESPOSTA_AVALIADOR` como comprovante. Desde 2026-07-27 não há
  mais nenhum endpoint/form que grave esse valor — só existe em pareceres
  antigos já persistidos, exibidos normalmente (badge "Origem: Operador").
- `AVALIADOR_SISTEMA` — **único caminho ativo hoje.** Médico se autentica no
  portal e vota diretamente; o registro autenticado (usuario + `dataHoraVoto`
  + IP no log de auditoria) substitui o anexo. `pareceresRecebidosSemAnexo`
  ignora esses pareceres.
- Origem `null` (legado, anterior a essa coluna existir) equivale a
  `OPERADOR_EMAIL` — mesma regra de só leitura.
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
- `app.base-url` configurável em `application.yml` (default `http://localhost:3000`,
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
- **NUNCA aninhar expressoes ternarias em mais de 3 niveis** em atributos Thymeleaf (`th:classappend`, `th:class`, `th:style`). O parser do Thymeleaf quebra com multi-line ternaries aninhados. Preferir `th:switch` ou `th:with` para pre-calcular valores complexos. Exemplo RUIM: `th:classappend="${a} ? x : (${b} ? y : (${c} ? z : w))"` (4 niveis, risco de quebra). Exemplo BOM: `th:classappend="${a} ? x : (${b} ? y : 'default')"` (max 2 niveis, seguro). **Nunca** usar `th:if` + `th:unless` no mesmo elemento — combinar numa unica expressao `th:if="${cond and !outra}"`.
- **`/*[[expr]]*/` (natural templating em JS) EXIGE `th:inline="javascript"` na tag `<script>`.** Sem esse atributo, Thymeleaf NAO reconhece o padrao de comentario e so substitui o `[[expr]]` interno, deixando os delimitadores `/* */` como comentario JS literal ao redor — o navegador enxerga so o fallback depois do comentario (`/*valorreal*/ 'fallback'` vira, na pratica, `'fallback'`, porque o `/* ... */` vira comentario de verdade e e ignorado). Bug real descoberto em 2026-07-28 rodando o chat AJAX contra um servidor de verdade: `pollUrl: /*[[@{...}]]*/ ''` estava renderizando como string vazia (fallback) em produtos, nao a URL real, porque as 3 tags `<script>` do chat nao tinham `th:inline="javascript"`. Corrigido nas 3. **Suspeita:** o mesmo padrao usado antes (`/*[[${temMsgNaoLida}]]*/ false` da vistoria de notificacao de 2026-07-28, ja removido/substituido nesta sessao) tambem nunca tinha `th:inline="javascript"` — a feature de notificacao ao carregar a pagina pode nunca ter disparado de verdade em producao, sempre caindo no fallback `false`. Nenhum teste (`@WebMvcTest`/`MockMvc`) pega isso porque eles testam status/model attributes, nao o JS renderizado de fato.
- Não commitar segredos: `application-local.yml`, `deploy/sgpur.env` e `/dist/`
  estão no `.gitignore`.
- Testes `@WebMvcTest` usam `@MockitoBean` (import
  `org.springframework.test.context.bean.override.mockito.MockitoBean`), **não**
  o `@MockBean` antigo (`org.springframework.boot.test.mock.mockito.MockBean`)
  — depreciado desde o Spring Boot 3.4 e removido em versão futura.
- **Rota que grava algo irreversível exige um teste do CAMINHO DE FALHA, sem
  mock do serviço.** `@WebMvcTest` + `@MockitoBean` no service **não consegue**
  pegar erro de transação: sem o proxy do Spring não existe transação, então a
  classe inteira de bug é *inexprimível* nesse tipo de teste. Foi assim que
  passaram despercebidos, com a suíte verde, o voto do avaliador sendo perdido
  (`AvaliadorController`, 2026-07-29) e ~15 endpoints devolvendo "Erro interno"
  no lugar da mensagem de negócio. Para voto, decisão, envio de e-mail oficial,
  exclusão e qualquer escrita irreversível: escreva ao menos um
  `@SpringBootTest` (H2 real, serviço real) que **force a falha do
  pós-processamento** e comprove que a escrita principal sobreviveu e que o
  usuário recebeu erro tratado — não 500. Ver
  `AvaliadorVotoTransacaoIntegrationTest` como modelo.
- **Teste de atualização deve reler do banco e conferir campo a campo.** Os
  métodos `atualizar()` copiam campo a campo; esquecer um campo faz o usuário
  salvar, ver "sucesso" e perder o dado **sem nenhum erro**. Já aconteceu 3x:
  `UsuarioService.atualizar` (e-mail), `MembroController.salvar`
  (`persist` em vez de `merge`) e `ControleUrgenciaService.atualizar`
  (`dataVencimento`, achado em 2026-07-29 — o form oferecia o campo e o
  serviço o ignorava). O teste que impede a recaída altera **todos** os campos
  editáveis com valores distintos, salva, **relê a entidade do banco** e
  asseve **cada campo**; com mock do repositório ele passa mesmo com o bug,
  que foi exatamente como a família escapou.
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
  **Backfill de `Usuario.versao` feito (2026-07-29).** `@Version` foi
  adicionado a `domain/Usuario.java` (era a única entidade "quente" sem lock
  otimista; Processo, Parecer, MembroUrgenciaRenal, SolicitacaoOnline e
  MensagemSolicitacao já tinham). Rodado em prod logo após o deploy do commit
  `b34643a` (2026-07-29 17:09 UTC): `UPDATE usuario SET versao = 0 WHERE
  versao IS NULL;` — 8 linhas corrigidas, confirmado 0 nulos restantes.
- **`ddl-auto: update` também não atualiza CHECK constraints de enum.**
  Mesma classe do pitfall acima, mas para colunas de enum
  (`@Enumerated(EnumType.STRING)`), que ganham uma constraint
  `CHECK (coluna IN (...))` com a lista de valores **congelada no momento em
  que a tabela foi criada**. Adicionar um valor novo ao enum Java **não**
  propaga pra essa constraint: o `ddl-auto: update` só faz `ALTER TABLE ADD
  COLUMN`, nunca `DROP/ADD CONSTRAINT`.

  **Correção de premissa (verificada em 2026-07-29):** versões anteriores
  deste arquivo diziam que essas constraints eram "criadas fora do
  Hibernate, em algum momento do histórico do banco". **É falso** — quem as
  cria é o próprio Hibernate. Gerando o DDL Postgres do schema atual
  (Hibernate 6.6 + `PostgreSQLDialect`) saem CHECKs para **todas as 8
  colunas `@Enumerated(STRING)`** do projeto (`anexo.tipo`,
  `usuario.perfil`, `processo.status`, `solicitacao_online.status`,
  `parecer.resultado`, `parecer.origem`, `controle_urgencia.situacao`,
  `mensagem_solicitacao.remetente`). Ou seja, o incidente de 2026-07-27 não
  foi um caso isolado de dívida histórica: é o comportamento padrão, e vale
  para **toda tabela nova** criada pelo Hibernate em produção daqui pra
  frente.

  Incidente original: `StatusSolicitacaoOnline.PROCESSO_EXCLUIDO` (commits
  `a7f9974`/`18ec060`, 2026-07-27) funcionava em dev/H2 (schema recriado do
  zero a cada teste, sempre com a lista atual) mas quebrava em prod com
  `violates check constraint "solicitacao_online_status_check"` — qualquer
  exclusão de processo originado do Portal falhava. Corrigido com
  `ALTER TABLE ... DROP/ADD CONSTRAINT` manual na VM.

  **Estado real de produção em 2026-07-29** (consultado no Postgres da VM,
  não presumido): sobraram apenas **2** dessas constraints, ambas completas
  e corretas — `controle_urgencia_situacao_check` (4 valores) e
  `mensagem_solicitacao_remetente_check` (2 valores). As colunas de `anexo`,
  `usuario`, `parecer`, `processo` e `solicitacao_online` **não têm CHECK
  nenhuma** hoje (a de `solicitacao_online` foi derrubada na correção de
  27/07 e nunca recriada, e as demais nunca chegaram a existir nessas
  tabelas). Por isso `TipoAnexo.ANEXO_AVALIADOR` (2026-07-27) e
  `Perfil.SOLICITANTE` (2026-07-25) **não** causam erro em produção, apesar
  de terem sido adicionados depois — é sorte estrutural, não garantia.

  **Proteção automática:** existe um verificador que roda no boot e compara
  cada CHECK de enum do banco com os valores do enum Java, avisando (sem
  derrubar a aplicação) quando divergem — ver `EnumCheckConstraintValidator`
  na seção de configuração. Ele torna desnecessário lembrar dessa regra na
  mão, mas o diagnóstico manual continua sendo:
  ```sql
  SELECT conrelid::regclass AS tabela, conname, pg_get_constraintdef(oid)
  FROM pg_constraint WHERE contype = 'c'
    AND conrelid = '<tabela>'::regclass;
  ```
  Nenhum teste local pega isso sozinho: só se manifesta contra o Postgres
  real.

## Sessão de 2026-07-28 (correções na VM)
Todas as pendências de infra resolvidas neste ciclo:

1. **Vistoria operacional/infra**: criado `/etc/logrotate.d/sgpur` (weekly,
   rotate 4, compress), com rotina de backup de anexos adicionada ao
   `backup-db.sh` (rclone sync para Google Drive, pasta `sgpur-backups/anexos/`
   com archive versionado em `anexos-archive/`). O script roda via crontab do
   postgres (`0 3 * * *`). Backup de DB já existia (pg_dump, 14d retenção,
   rclone para `gdrive:sgpur-backups/`). **Anexos antes sem backup ← corrigido**.
2. **Jars de backup**: script `rotacionar-backups-jar.sh` (KEEP=3) já existia
   mas sem cron; adicionado ao crontab do root (`0 5 * * 0`). Rodado
   manualmente: 29 jars antigos (~70MB cada) removidos, 3 mais recentes
   mantidos. Liberados ~2GB em disco (agora 37G livres de 45G).
3. **Erro 413**: nginx real na VM (`/etc/nginx/sites-available/sgpur`) tem
   `client_max_body_size 30m;` idêntico ao `deploy/nginx-sgpur.conf` do repo.
   Nenhum log de 413 encontrado no journalctl. **Suspeita descartada** — se
   o erro reaparecer, investigar o multipart do Spring Boot
   (`spring.servlet.multipart.max-file-size`/`max-request-size`) que estava em
   25MB/30MB (application.yml) antes do commit `e15ff82` (04/07).
4. **Backfill parecer.versao**: `UPDATE parecer SET versao = 0 WHERE versao IS
   NULL` — 0 linhas afetadas (já estavam OK).
5. **Backfill membro_urgencia_renal.versao**: `UPDATE membro_urgencia_renal SET
   versao = 0 WHERE versao IS NULL` — 8 linhas corrigidas.
6. **Upgrades de dependência (patches)**: `postgresql 42.7.13`, `bootstrap
   5.3.8`, `h2 2.4.240` — atualizados no pom.xml, build OK.

7. **Backfill solicitacao_online.status**: `UPDATE solicitacao_online s SET
   status = CASE WHEN p.status = 'DEFERIDO' THEN 'APROVADA' WHEN p.status IN
   ('INDEFERIDO','CANCELADO') THEN 'REPROVADA' ELSE s.status END FROM processo
   p WHERE s.processo_gerado_id = p.id AND s.status = 'CONVERTIDA'` — 1 linha
   corrigida. A propagação CONVERTIDA->APROVADA/REPROVADA já existe no código
   desde o commit que adicionou `ProcessoService.decidir()` (linhas 422-428),
   mas dados históricos anteriores a essa implementação ficavam travados.
8. **Portal do Solicitante (timeline)**: passo 3 da timeline consulta
   `processoGerado.status` quando `solicitacao.status==CONVERTIDA`, exibindo
   o resultado (Deferido/Indeferido) em vez de "Aguardando decisao". Badge do
   topo também reflete a decisão real. Passo 4 redundante removido.
9. **Correção bootstrap 5.3.8**: `layout.html` tinha caminho hardcoded
   `/webjars/bootstrap/5.3.3/...`; atualizado para 5.3.8 após o upgrade no
   pom.xml (commit anterior). App ficou sem CSS/JS no login até esta correção.

**Vistoria geral de segurança (2026-07-28)**: auditoria completa de
autenticação, exposição de dados, IDOR e configuração. 6 correções aplicadas:
- OpenPDF 1.3.30 → 1.3.34 (CVE XXE crítico — processa PDFs de terceiros)
- GeminiService default `enabled=false` (antes `true`); prod já tinha
  kill-switch, mas default no código agora é seguro também
- Password policy: mínimo 8 chars + maiúscula + minúscula + número + especial
  (aplicado em criar, editar e alterar própria senha)
- Login audit trail: toda tentativa de login (sucesso e falha) logada com IP
  (antes só logava o bloqueio de 15 min). **Atualização mesmo dia (commit
  `cfc9f86`): o bloqueio de 15 min foi removido** — decisão deliberada de
  produto (não bug), o log de auditoria com IP continua sendo a defesa
  usada. `LoginAttemptService.estaBloqueado` agora sempre retorna `false`.
  **Limpeza de 2026-07-29:** o código morto que sobrou dessa remoção foi
  apagado — `estaBloqueado()` (e a contagem de falhas em memória que só ele
  lia), o `LockedException` inalcançável do `UsuarioDetailsService`, o ramo
  `LockedException → /login?bloqueado` do `SecurityConfig.loginFailureHandler`
  e o alerta `th:if="${param.bloqueado}"` do `login.html`. `LoginAttemptService`
  continua existindo e intacto no que importa: é o `Filter` que captura o IP
  + os `@EventListener` que logam sucesso/falha de login. Se o bloqueio um dia
  voltar, os 4 pontos precisam voltar juntos (documentado no javadoc da classe).
- LogAuditoria.PROCESSO_CADASTRADO: usa `Iniciais.de()` (antes nome completo
  do paciente no detalhe, visível na tela de auditoria ADMIN)
- Session management: timeout 30m explícito + `maxSessions=1` (concorrência
  bloqueada por usuário)

Nenhuma falha estrutural de IDOR encontrada (AvaliadorController e
SolicitanteController têm verificação de posse rigorosa; controllers de
processo usam controle por role, que é o design pretendido).

**Demais upgrades** (Spring Boot 4, Spring Security 7, OpenPDF >=1.3.35):
continuam pendentes para sessão dedicada (major version, risco de breaking
change).

**Vistoria de conformidade de regras de negócio concluída em 2026-07-24**
(cada regra da seção "Regras de negócio" deste arquivo vs. o código real,
service+controller, cobrindo maioria simples/coordenador, anexos
obrigatórios, processo encerrado, pausa "Solicita informação", fluxo de 6
passos, Recebimento/Envio, Portal do Avaliador e imparcialidade). Nenhuma
violação de regra de negócio encontrada. 4 gaps estruturais corrigidos no
mesmo dia:
- `ProcessoService.confirmarRespostaSolicitante` criado como fonte única da
  regra "Deferido exige comprovante SNT" (antes vivia duplicada em 3
  lugares: só no controller, sem espelho no service, mais uma cópia inline
  em `prepararEmailPronto`).
- `MembroController.salvar` ganhou `@Transactional` (fechava a janela de
  race condition ao desmarcar outros coordenadores CET-RS).
- `atualizarStatusPorPareceres`/`tentarDecisaoAutomatica`/
  `retomarAposInformacao` (ProcessoService) agora lançam
  `IllegalStateException` em vez de no-op silencioso quando chamados sobre
  processo já finalizado (todos os call-sites reais já garantiam isso
  antes; só torna bugs futuros ruidosos em vez de mascarados).
- `AvaliadorController.votar()` passou a expor `ProcessoVotoView`/
  `ParecerVotoView` (DTOs projetados) ao template em vez da entidade
  `Processo`/`Parecer` inteira, fechando por design o risco de um `th:text`
  futuro vazar `pacienteNome` (quebraria a regra de imparcialidade).
Suíte completa validada após as correções: **418 testes, 0 falhas** (JDK 21).

Nota: o **deploy automático via GitHub Actions foi corrigido em 2026-07-10**
(o secret `SAUR_ORACLE_SSH_KEY` estava vazio/malformado desde 21/07, todo
`Deploy` falhava; corrigido + também corrigido um falso-negativo no
health-check que tinha timeout curto demais pro boot real de ~76s contra o
Neon). Confirmado funcionando ponta a ponta (CI -> Deploy) - **não é mais
pendência**, já está no ar.

## Deploy
Artefatos em `deploy/` (systemd, nginx, env de exemplo, guia). Host alvo:
**Oracle Always Free (São Paulo)** — ver `deploy/README-deploy.md`.
A **Vercel não hospeda o app Java** (histórico: só servia como front pro
Neon, que nem é mais o banco de produção — ver status abaixo).

**Status em produção (2026-07-26)**: SAUR está no ar em
https://urgenciarenal.duckdns.org/, envio de e-mail (SMTP Gmail) funcionando.
HTTPS confirmado ativo via certbot (nginx redireciona 80→443, cert válido até
2026-10-05). Banco: **Postgres local na própria VM** (`localhost:5432`, db
`sgpur`, usuário `sgpur`) — migrado do Neon em 2026-07-25 depois que o Neon
estourou a cota gratuita e o app caiu (ver memória do projeto
`incidente-neon-cota-migracao-postgres-vm-2026-07-25`); não usar mais o Neon
SQL Console para nada de produção atual, é histórico. `SGPUR_BASE_URL` já
está corretamente configurada em `/opt/sgpur/sgpur.env`
(`https://urgenciarenal.duckdns.org`) — a pendência antiga sobre isso (abaixo,
descrita como aberta em versões anteriores deste arquivo) **já foi
resolvida**, confirmado em 2026-07-26.
`deploy/README-deploy.md` ganhou 2 seções novas: acesso via Oracle Cloud
Shell quando SSH direto é bloqueado por proxy corporativo, e troubleshooting
de "Authentication failed" no SMTP (causa raiz encontrada: o `sgpur.env` da
VM tinha uma senha de app diferente da testada/válida — sempre confirmar a
senha real em uso via `/proc/<PID>/environ`, não só o arquivo, antes de
trocar de teoria). Utilitário `deploy/testar-smtp.py` testa a credencial
SMTP isolada (sem depender do Java) com `getpass`.

## Sessão de 2026-07-27 (sistema de mensagens + notificações)

1. **Entidade `MensagemSolicitacao`**: `id`, `solicitacaoOnline` (FK),
   `remetente` (SOLICITANTE/OPERADOR), `remetenteId`, `texto`, `dataEnvio`,
   `lida`, `versao`. Repository com queries JPQL para contagem de não lidas
   e `@Query` para IDs distintos de solicitações com mensagens não lidas.
2. **Service + Controller**: `MensagemSolicitacaoService` (enviar, listar,
   marcar lidas, contar não lidas). Endpoints `POST /{id}/mensagem` em ambos
   `SolicitanteController` e `SolicitacaoOnlineTriagemController`.
3. **Indicador na lista de triagem**: `SolicitacaoOnlineTriagemController.lista()`
   passa `idsComMsgNaoLidaSolicitante` (`Set<Long>`) ao modelo; template
   `solicitacoes-online-lista.html` exibe badge amarelo `bi-chat-dots-fill` ao
   lado do botão "Ver" quando o solicitante enviou mensagem não lida.
4. **Cards separados "Em análise" / "Decididas"**: `SolicitacaoOnlineService.Resumo`
   record alterado de 4 campos para 5 (`aguardandoTriagem`, `emAnalise`,
   `decididas`, `devolvidas`). Solicitante vê 5 cards no dashboard.
5. **Sound + Toast notification**: fragmento `notificacaoSonora` no
   `layout.html` — função `tocarNotificacao()` (2 tons, 600Hz+900Hz, Web Audio
   API) + `mostrarToast()` que exibe toast não intrusivo. Disparados nos
   templates de detalhe quando `temMsgNaoLida` é true (mensagens não lidas da
   outra parte — só ao *receber*, nunca ao enviar). Toast some em 5s.
6. **Auto-scroll chat**: `id="chatBox"` nos dois detalhes + JS
   `chatBox.scrollTop = chatBox.scrollHeight` no final da página.
7. **Chat melhorado**: balões com nome do remetente ("Você"/"Solicitante"/
   "Equipe CET-RS"), `white-space: pre-wrap`, contador de mensagens no header.
8. **Fix overlap badge navbar**: badges de "Solicitações online" trocados de
   `position-absolute top-0 start-100 translate-middle` para `badge` inline
   com `gap-1` no link (`d-inline-flex align-items-center gap-1`). Só o badge
   do sino do avaliador (lado direito, sem adjacentes) manteve
   `position-absolute`. Resolve colisão visual com "Usuarios"/"Auditoria".
9. **Password default dev**: `application.yml` alterado de `admin123` para
   `Admin123!` para satisfazer a password policy (8 chars + maiúscula +
   minúscula + número + especial). Testes `UsuarioServiceTest` atualizados.
   `AdminBootstrapTest` corrigido. 526 testes, 0 falhas.

10. **Fix notificação falsa positiva (2026-07-28)**: notificação (som+toast)
    disparava em toda visita ao detalhe mesmo sem mensagens novas. Causas e
    correções:

    **Hipótese A — BFcache (back-forward cache):** navegador restaura HTML
    cacheado com `temMsgNaoLida=true` estale ao voltar (back/forward).
    *Correção:* `pageshow` + `location.reload()` força reload do servidor
    quando `event.persisted` é true, garantindo estado fresco.

    **Hipótese B — sessionStorage sem limpeza:** chave `notif_*` no
    `sessionStorage` nunca era limpa em carregamentos frescos, suprimindo
    notificações de mensagens realmente novas.
    *Correção:* `PerformanceNavigationTiming.type` detecta carregamento
    fresco (`type !== 'back_forward'`) e limpa a chave antes de checar
    `temMsgNaoLida`; em BFcache (`type === 'back_forward'`) a chave é
    preservada para suprimir notificação estale até o reload.

    **Hipótese C — JPQL separada para `temMsgNaoLida`:** controlador
    computava `temMsgNaoLida` via `idsSolicitacoesComMsgNaoLidaSolicitante()
    .contains(id)` — JPQL independente que podia divergir da lista de
    mensagens carregada na mesma requisição.
    *Correção:* `temMsgNaoLida` é computado em Java a partir da mesma
    `List<MensagemSolicitacao>` carregada via `listarPorSolicitacao(id)`,
    garantindo consistência com as mensagens exibidas no chat.

    **Hipótese D — `th:if` impedia registro de handler:** o script de
    notificação estava envolvido em `<script th:if="${temMsgNaoLida}">`,
    então o handler `pageshow` (quando existia) só era registrado se
    houvesse mensagem não lida no momento do carregamento — em BFcache
    o script nem existia no HTML para reagir.
    *Correção:* `<script>` agora SEMPRE renderizado (sem `th:if`), usa
    Thymeleaf inlining (`/*[[${temMsgNaoLida}]]*/`) para condicionar
    apenas o disparo da notificação, mantendo handlers e lógica BFcache
    ativos em qualquer estado.

    **Padrão para futuras páginas sensíveis a BFcache:** sempre usar
    `PerformanceNavigationTiming` (fresh vs BFcache) + `pageshow` reload
    como segurança suplementar; NUNCA usar `th:if` em scripts que
    registram event listeners; computar flags de estado no servidor a
    partir dos mesmos dados carregados (evitar JPQLs independentes).
    Ver templates `solicitacoes-online-detalhe.html` e `detalhe.html`
    (solicitante) para o padrão completo.

## Sessão de 2026-07-28 (logo Gota+Cruz + chat)

1. **Logo Gota+Cruz implementado**: substituiu `bi-droplet-fill` por SVG próprio
   nos templates. Gota dourada (`--rs-gold`) com cruz ao centro. Onde:
   - `layout.html` — navbar (20×20, cruz `--rs-blue-dark`)
   - `login.html` e `esqueci-senha.html` — telas de login (36×36, cruz `rgba(255,255,255,.2)`)
2. **Chat recolhível**: cabeçalho do chat clicável com `data-bs-toggle="collapse"`,
   chevron com `rotate(180deg)` via classe `.chevron-collapse` em `app.css`.
3. **Apagar mensagem**: endpoint `POST /{id}/mensagem/{mensagemId}/apagar` nos
   3 controllers (`ProcessoDetalheController`, `SolicitacaoOnlineTriagemController`,
   `SolicitanteController`). `MensagemSolicitacao` ganhou campos `deletada`/`deletadaEm`
   (soft delete): texto apagado (vira `null`), mas a mensagem permanece na base.
4. **Timestamps relativos**: todas as mensagens exibem "agora", "X min atras",
   "X h atras", "ontem" ou a data normal via JS `ts-relative` nos 3 templates de chat.
5. **Chat no detalhe do processo**: `ProcessoDetalheController` passou a carregar
   as mensagens da `SolicitacaoOnline` de origem e exibir o chat na tela de detalhe do
   processo (`/processos/{id}`), com campo de resposta e badge de não lidas.
6. **Badge e chevron**: os 3 templates de chat ganharam badge de contagem de mensagens
   e classe `chevron-collapse` para animação do chevron ao recolher.
7. **Fix scroll do chat ao enviar mensagem** (commit `3ba402f`): o scroll suave
   simples (`chatBox.scrollTo(...)`) rodava só no load inicial e não
   sobrevivia ao POST+redirect do formulário de mensagem, deixando o chat
   "pulando" pro topo depois de enviar. Substituído por um mecanismo baseado
   em `sessionStorage` nos 3 templates de chat
   (`processos/detalhe.html`, `processos/solicitacoes-online-detalhe.html`,
   `solicitante/detalhe.html`): ao submeter o form de mensagem, salva se o
   usuário estava perto do fim (`estaProximoDoFim`, threshold 80px) e a
   posição de scroll da página; no próximo load (`load` + `pageshow`, cobre
   BFcache — mesmo padrão da Hipótese A/D da seção de notificação acima)
   restaura scroll pro fim do chat se estava seguindo a conversa, ou
   preserva a posição se o usuário tinha rolado pra cima pra ler histórico.
   `history.scrollRestoration = 'manual'` evita o navegador brigar com essa
   lógica.

## Sessão de 2026-07-28 (ajustes finais do dia)

1. **Fix N+1 evitável no detalhe do processo** (commit `9989945`):
   `ProcessoDetalheController` buscava a `SolicitacaoOnline` de origem
   (`findByProcessoGeradoId`, entidade completa) incondicionalmente pra
   achar só o ID a exibir no link "Ver solicitação original" — mesmo em
   processos que não vieram do portal. Trocado por
   `findIdByProcessoGeradoId` (projeção só do ID) e a query só roda quando
   `processoVeioDoPortal` é `true`.
2. **Fix: chat da tela de detalhe do processo não notificava (som/toast)**.
   Quando o chat foi trazido pra `/processos/{id}` na sessão anterior (ver
   "logo Gota+Cruz + chat" acima), só o badge de contagem
   (`msgNaoLidas`) foi portado — `temMsgNaoLida`, o fragmento
   `layout :: notificacaoSonora` e a chamada de `tocarNotificacao()`/
   `mostrarToast()` nunca foram adicionados a essa tela (só existiam em
   `solicitacoes-online-detalhe.html` e `solicitante/detalhe.html`).
   `ProcessoDetalheController.detalhe` agora seta `temMsgNaoLida` (mesmo
   padrão das outras duas telas: `msgNaoLidas > 0`, calculado a partir da
   mesma lista de mensagens já carregada, `false` nos ramos sem origem de
   portal) e `processos/detalhe.html` ganhou o mesmo script anti-BFcache
   (`pageshow` + `PerformanceNavigationTiming` + `sessionStorage`) das
   outras duas telas de chat.
   **Superado pelo rework abaixo, no mesmo dia** — o mecanismo baseado em
   `temMsgNaoLida`/recarregar página inteira foi inteiramente substituído
   por polling AJAX; ver próxima seção.

## Sessão de 2026-07-28 (rework do chat: polling AJAX)

Usuário reportou o chat "horrível, demora, sem notificação" nas 3 telas
(`solicitante/detalhe.html`, `processos/detalhe.html`,
`processos/solicitacoes-online-detalhe.html`). Causa raiz: o chat era
**100% server-rendered** — cada mensagem enviada/apagada era um
`<form method="post">` clássico (POST + redirect + GET da página inteira,
não só do chat) e a notificação só rodava uma vez no `load` da página.
Não existia polling nem WebSocket — só recarregando manualmente é que uma
mensagem nova aparecia.

**Reescrito para polling AJAX** (sem WebSocket, ~5s de intervalo):
- `MensagemSolicitacaoService.MensagemChatView` (record) + `paraChat(...)`:
  projeta as mensagens já relativas a quem está vendo (`deVoce`,
  `nomeRemetente`, `podeApagar` calculados no service, evitando duplicar
  "de quem é essa mensagem" em 3 templates). `podeApagar` agora exige
  `remetenteId` exato (antes o botão de apagar aparecia pra qualquer
  OPERADOR em mensagem de outro operador e falhava no clique — corrigido
  de graça por essa reprojeção).
- Cada controller (`SolicitanteController`, `ProcessoDetalheController`,
  `SolicitacaoOnlineTriagemController`) ganhou 3 endpoints novos, paralelos
  aos clássicos (que continuam existindo, sem uso pelo JS novo):
  `GET .../mensagens` (JSON, poll — já marca como lida),
  `POST .../mensagem/ajax` e `POST .../mensagem/{id}/apagar/ajax`
  (`ResponseEntity<Map>`, 200/400 JSON em vez de redirect).
- `static/js/chat-solicitacao.js` (novo, `iniciarChatSolicitacao(cfg)`):
  módulo único usado pelas 3 telas — poll periódico, renderiza os balões via
  JS (nada de `th:each` de mensagem nos templates agora — só um `<div
  id="chatBox">` vazio), detecta mensagem nova do outro lado comparando IDs
  entre polls pra disparar `tocarNotificacao()`/`mostrarToast()` **mesmo com
  a aba já aberta** (o problema original), envia/apaga via `fetch` com o
  header CSRF (mesmo padrão de `processo-detalhe.js`), pausa o poll com
  `visibilitychange` quando a aba fica em background.
- Os 3 templates perderam o JS duplicado inline (scroll anti-BFcache,
  ts-relative, notificação por load) — tudo isso morreu junto com o
  mecanismo antigo, substituído pela única chamada a
  `iniciarChatSolicitacao({...})`.

**Bug real corrigido (achado pela auditoria, não pelo usuário):**
`ProcessoDetalheController.detalhe()` era a ÚNICA das 3 telas que carregava
as mensagens mas nunca chamava `marcarComoLidas` — o badge/notificação
ficava preso pra sempre em quem só usa essa tela. A rota deixou de poder
ser `@Transactional(readOnly = true)` (só liberado antes por ser "a única
leitura pura do controller" — comentário desatualizado, removido).

**Bug real corrigido (achado rodando o fluxo contra um H2 de verdade, não
pelos 526 testes — eles usam `@MockitoBean`, nunca tocam o banco real):**
`MensagemSolicitacao.texto` era `nullable = false`, mas
`MensagemSolicitacaoService.apagar()` sempre fazia `msg.setTexto(null)` no
soft-delete — **apagar mensagem sempre quebrava** com
`DataIntegrityViolationException` (23502, "NULL not allowed"), nas 3 telas,
nos endpoints clássicos E nos novos. Corrigido tirando `nullable = false` da
entidade. Como esperado (mesma classe de pitfall do `@Version`/CHECK de
enum documentados acima), o `ddl-auto: update` não relaxou a constraint
`NOT NULL` sozinha no Postgres de prod — confirmado via
`information_schema.columns` (`is_nullable = NO`) logo após o deploy.
**Corrigido manualmente em produção em 2026-07-28** com
`ALTER TABLE mensagem_solicitacao ALTER COLUMN texto DROP NOT NULL;`
(rodado pelo usuário via Oracle Cloud Shell, confirmado `is_nullable = YES`
depois). Apagar mensagem funciona em produção desde então.

**Gotcha do Thymeleaf que causou uma segunda rodada de bug** (ver também a
entrada em "Convenções de código"): as 3 chamadas
`iniciarChatSolicitacao({pollUrl: /*[[@{...}]]*/ '', ...})` só funcionaram
depois de adicionar `th:inline="javascript"` na tag `<script>` — sem isso,
Thymeleaf não reconhece o padrão de comentário e as URLs renderizavam como
string vazia (o fallback), não a URL real. Só foi percebido inspecionando o
HTML renderizado via `curl`, não pelos testes automatizados nem pela
primeira rodada de testes manuais (que só bateu nos endpoints JSON
diretamente, não no HTML final).

**Validação:** 526 testes (suite completa, sem regressão) + fluxo manual
completo via `curl` contra um `mvn spring-boot:run` real (H2 de arquivo
limpo): criar usuário SOLICITANTE, enviar solicitação, poll, enviar
mensagem AJAX, poll do lado do operador (confirma `marcarComoLidas`),
responder, poll do solicitante, apagar mensagem, converter solicitação em
processo e repetir o poll em `/processos/{id}` (confirma o fix do
`marcarComoLidas` que faltava nessa tela especificamente).

**Bug pós-deploy (2026-07-28, mesmo dia): som da notificação não tocava do
lado do operador.** A notificação (toast + som) do polling dispara sozinha
via `setInterval`, sem nenhum gesto do usuário no momento — navegadores
mantêm a Web Audio API (`AudioContext`) suspensa até um clique/tecla/toque
direto na página, e `tocarNotificacao()` rodava sem erro (o `catch`
mascarava) mas sem som nenhum. Corrigido em `layout.html`
(`notificacaoSonora`): uma `AudioContext` compartilhada é criada e
"destravada" (`ctx.resume()`) no primeiro gesto do usuário na página
(`click`/`keydown`/`touchstart`, listener `{once:true}`), reaproveitada em
vez de criar uma nova a cada notificação. **Limitação que continua
existindo:** a notificação que chega ANTES de qualquer gesto do usuário na
página ainda pode tocar sem som (só o toast visual aparece) — é uma
restrição de segurança do navegador, não contornável em JS; a partir do
primeiro clique/tecla na página, todo som seguinte funciona normalmente.

**Bug reportado logo em seguida (mesmo dia): notificação só existia DENTRO
das 3 telas de chat.** Em qualquer outra tela (Painel, listas), pra
qualquer um dos dois lados (operador OU solicitante), nada disparava —
porque `iniciarChatSolicitacao()` só era chamado nessas 3 telas. Resolvido
com um poll GLOBAL, leve (só contagem, a cada 20s, nunca marca como lida —
isso continua sendo exclusivo de abrir a conversa de fato):
- Novo endpoint `GET /processos/solicitacoes-online/nao-lidas-count`
  (`{"total": N}`, reaproveita `contarNaoLidasOperador()` já usado pelo
  badge estático da navbar) e `GET /solicitante/nao-lidas-count` (novo
  método `MensagemSolicitacaoService.contarNaoLidasParaSolicitante` +
  `MensagemSolicitacaoRepository.
  countByRemetenteAndLidaFalseAndSolicitacaoOnlineUsuarioSolicitanteId` —
  soma TODAS as solicitações do usuário, não só uma).
- Dois blocos `<script>` novos em `layout.html`, dentro do fragment
  `navbar` (não depois dele — fragments do Thymeleaf só copiam o elemento
  marcado com `th:fragment`, não os irmãos seguintes; erro cometido e
  corrigido na hora antes de subir), um por papel
  (`sec:authorize="hasAnyRole('ADMIN','OPERADOR')"` /
  `sec:authorize="hasRole('SOLICITANTE')"`), comparando a contagem contra
  `sessionStorage` a cada poll — só notifica se **subiu** desde o poll
  anterior (primeiro poll da sessão só define a base, nunca notifica
  sozinho, mesmo padrão do `chat-solicitacao.js`).
- `layout :: notificacaoSonora` passou a ser incluído automaticamente
  dentro do fragment `navbar` (antes cada uma das 3 telas de chat incluía
  na mão) — as 25 telas que já usam `layout :: navbar` ganham
  `tocarNotificacao()`/`mostrarToast()` de graça.
- **Evita notificação duplicada**: as 3 telas de chat (que já têm seu
  próprio poll de 5s) setam `model.addAttribute("chatAtivoNestaTela",
  true)`, e os dois scripts globais usam `th:unless="${chatAtivoNestaTela}"`
  pra não rodar nelas.
- Badge da navbar (`#navBadgeMsgNaoLida`, ao lado de "Solicitações online")
  atualiza ao vivo pro lado do operador junto com o poll global; o lado do
  solicitante não tem badge equivalente na navbar (não criado, fora de
  escopo — só som/toast).
- **Validado manualmente** (não só pelos 526 testes) com `mvn spring-boot:run`
  real: mensagem do solicitante → contador do operador sobe em qualquer
  tela E fica parado (não marca lida) até abrir o detalhe de fato; mesma
  coisa na direção operador→solicitante; as 3 telas de chat confirmadas
  SEM o script global (evita duplicar) mas COM `tocarNotificacao` definido.

