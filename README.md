# SAUR — Sistema de Gestão de Processos de Urgência Renal

[![CI](https://github.com/RafaelEliasIoppi/urgencia/actions/workflows/ci.yml/badge.svg)](https://github.com/RafaelEliasIoppi/urgencia/actions/workflows/ci.yml)

Sistema web que substitui a planilha Excel utilizada pela equipe de **Urgência
Renal** da Secretaria de Saúde, informatizando todo o fluxo de um processo —
do recebimento da solicitação até o deferimento ou indeferimento — de forma
segura, auditável e com **Relatório Final em PDF**.

## Funcionalidades

- **Cadastro de processos** de urgência renal (paciente, equipe solicitante,
  data da situação especial).
- **Envio a exatamente 3 médicos** avaliadores, com registro da data de envio.
- **Pareceres** dos médicos (Favorável / Não favorável / Solicita informação).
- **Regra de decisão:** 2 de 3 favoráveis = Deferido; 2 de 3 desfavoráveis =
  Indeferido. **Coordenador CET-RS defere sozinho** com voto favorável.
- **Sugestão automática** de decisão por maioria simples.
- **Fluxo em 6 etapas:** Recebimento → Envio → Respostas → Decisão →
  Ofício/Comprovante → Resposta ao solicitante (checklist visual).
- **Textos de e-mail prontos** (copiar/colar) por etapa — envio real de
  e-mail via SMTP (Gmail) disponível.
- **Portal do Avaliador** (`/avaliador`): médico autenticado vota diretamente
  no sistema (não-repúdio com auditoria de IP).
- **Anexos** de cópias de e-mails e documentos em cada etapa.
- **Relatório Final em PDF** (documento oficial para arquivamento e auditoria).
- **Gestão de membros** da Urgência Renal e **de usuários** (login via banco,
  perfis Administrador/Operador/Avaliador).
- **Assistência por IA (Gemini):** resumo de anexos PDF e sugestão de motivo
  de indeferimento (opcional, configurável).
- **Indicador de tempo de resposta** dos avaliadores (média em dias, fora do
  prazo configurável).

## Stack

- Java 21, Spring Boot 3.5 (Web, Data JPA, Thymeleaf, Security, Validation)
- PostgreSQL (Neon) em produção · H2 em desenvolvimento
- Thymeleaf + Bootstrap 5 · OpenPDF (relatórios)
- Maven (artifactId `saur`)

## Como rodar

Requisitos: **JDK 21** e **Maven**.

### Desenvolvimento (H2, sem banco externo)
```powershell
.\start.ps1
```
Acesse http://localhost:8080 — login inicial **admin / admin123**, criado
automaticamente na primeira subida (só quando a tabela `usuario` está vazia).
Console do H2 em `/h2-console`.

### Produção (Neon)
As credenciais ficam em `application-local.yml` (não versionado) ou no
`deploy/sgpur.env` via variáveis de ambiente:
```powershell
.\start.ps1 prod
```

### Testes
```powershell
.\test.ps1
```
142 testes, sempre com JDK 21.

## Modo teste de e-mail

Em dev, `app.mail.override-recipient` (default `rafaelioppi@gmail.com`)
redireciona **todo** e-mail enviado para esse endereço, com prefixo
`[TESTE - para: ...]` no assunto. Em prod fica vazio (envio real).

## Configuração

| Variável | Padrão | Descrição |
|---|---|---|
| `SGPUR_ADMIN_USER` | `admin` | login do administrador inicial |
| `SGPUR_ADMIN_PASSWORD` | `admin123` (dev) ou **obrigatória em prod** | senha do administrador inicial |
| `SGPUR_BASE_URL` | `http://localhost:8080` | URL base para links nos e-mails (portal avaliador) |
| `SGPUR_MAIL_HOST` | `smtp.gmail.com` | servidor SMTP |
| `SGPUR_MAIL_PORT` | `587` | porta SMTP |
| `SGPUR_MAIL_USER` | — | usuário SMTP |
| `SGPUR_MAIL_PASS` | — | senha SMTP (app password) |
| `SGPUR_MAIL_FROM` | — | remetente dos e-mails |
| `SPRING_DATASOURCE_URL` | — | JDBC URL do PostgreSQL (Neon) |
| `SPRING_DATASOURCE_USERNAME` | — | usuário do banco |
| `SPRING_DATASOURCE_PASSWORD` | — | senha do banco |
| `app.anexos.dir` | `./data/anexos` | diretório de armazenamento dos anexos |
| `app.avaliador.prazo-dias` | `7` | prazo-meta em dias para resposta dos avaliadores |
| `app.gemini.api-key` | — | chave da API Google Gemini (IA) |

> Em produção, defina **`SGPUR_ADMIN_PASSWORD`** e as credenciais de banco/SMTP
> antes do primeiro deploy. `AdminBootstrap` só cria o admin se a tabela
> `usuario` estiver vazia. Nunca versione segredos.

## Estrutura

```
domain/      entidades JPA (Processo, MembroUrgenciaRenal, Parecer, Anexo,
             Usuario, ControleUrgencia) e enums (StatusProcesso,
             ResultadoParecer, TipoAnexo, Perfil, OrigemParecer)
repository/  repositórios Spring Data
service/     regras de negócio (ProcessoService, FluxoProcessoService,
             EmailTemplateService, EmailSenderService, RelatorioService,
             AnexoStorageService, DecisaoFinalService, TempoRespostaService,
             RelatorioAnualService, RelatorioAvaliadorService, GeminiService,
             SolicitacaoAvaliadorService, OficioService, UsuarioService,
             LoginAttemptService, ControleUrgenciaService, ConflitoEquipeMatcher)
web/         controllers MVC (vários controllers, não um monolítico)
config/      segurança (SecurityConfig), migração de schema (SchemaMigration),
             bootstrap do admin inicial (AdminBootstrap), email (EmailProperties)
templates/   páginas Thymeleaf · static/ CSS
```

## Regras de negócio (resumo)

- Cada processo vai para **3 médicos**; **2 favoráveis = Deferido**;
  **2 desfavoráveis = Indeferido** (exige ofício + motivo).
- **Coordenador CET-RS defere sozinho** com voto favorável (não para indeferir).
- Status: `Solicitado` → `Enviado` → `Deferido` / `Indeferido` /
  `Solicita informação` (pausa) / `Cancelado`. Finais: Deferido/Indeferido/Cancelado.
- Indeferimento **exige** motivo + ofício + data de emissão + envio ao solicitante.
- Deferido **exige** comprovante de inserção no SNT antes de concluir.
- Processo encerrado (final) **trava edição** das etapas 1-4; papelada pós-decisão
  (ofício, SNT, confirmar resposta) continua liberada. Só ADMIN reabre.
- Numeração `NN/AAAA`: **manual em 2026**, **automática a partir de 2027**.
- Toda resposta de médico recebida precisa do anexo comprobatório antes de
  deferir/indeferir (garante ≥2 anexos).
- "Membros da Urgência Renal" (nunca "Câmara Técnica").
- **Não há mais empacotamento desktop** (só web desde jul/2026).

---
Documento oficial gerado pelo sistema: **Relatório Final do Processo de
Urgência Renal**.
