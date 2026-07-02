# SGPUR — Sistema de Gestão de Processos de Urgência Renal

[![CI](https://github.com/RafaelEliasIoppi/urgencia/actions/workflows/ci.yml/badge.svg)](https://github.com/RafaelEliasIoppi/urgencia/actions/workflows/ci.yml)

Sistema web que substitui a planilha Excel utilizada pela equipe de **Urgência
Renal** da Secretaria de Saúde, informatizando todo o fluxo de um processo —
do recebimento da solicitação até o deferimento ou indeferimento — de forma
segura, auditável e com **Relatório Final em PDF**.

## Funcionalidades

- **Cadastro de processos** de urgência renal (paciente, equipe solicitante,
  data da situação especial).
- **Envio a exatamente 3 médicos** avaliadores, com registro da data de envio.
- **Pareceres** dos médicos (Favorável / Não favorável / Solicita informação /
  Sem resposta).
- **Regra de decisão automática (sugestão):** 2 de 3 favoráveis defere o
  processo; caso contrário, indefere (exige ofício com o motivo). A decisão
  final é registrada manualmente pelo servidor.
- **Checklist visual em tempo real** do andamento (recebimento → envio →
  respostas → decisão → ofício → resposta ao solicitante), com barra de
  progresso e indicação do que falta.
- **Textos de e-mail prontos** (copiar/colar) por etapa — no envio aos médicos
  os dados pessoais do paciente são omitidos.
- **Anexos** de cópias de e-mails e documentos em cada etapa.
- **Relatório Final em PDF** (documento oficial para arquivamento e auditoria).
- **Gestão de membros** da Urgência Renal e **de usuários** (login via banco,
  perfis Administrador/Operador).

## Stack

- Java 21, Spring Boot 3.3 (Web, Data JPA, Thymeleaf, Security, Validation)
- PostgreSQL (Neon) em produção · H2 em desenvolvimento
- Thymeleaf + Bootstrap 5 · OpenPDF (relatório)
- Maven

## Como rodar

Requisitos: **JDK 21** e **Maven**.

### Desenvolvimento (H2, sem banco externo)
```bash
mvn -DskipTests spring-boot:run
```
Acesse http://localhost:8080 — login inicial **admin / admin123**, criado
automaticamente por `AdminBootstrap` na primeira subida (só quando a tabela
`usuario` está vazia — não sobrescreve usuários já cadastrados).
Console do H2 em `/h2-console`.

### Produção / banco PostgreSQL (Neon)
As credenciais ficam em `src/main/resources/application-local.yml`
(**não versionado** — veja `.gitignore`) ou em variáveis de ambiente:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>/<db>?sslmode=require&prepareThreshold=0
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<senha>
SPRING_PROFILES_ACTIVE=prod
```
```bash
mvn -DskipTests -Dspring-boot.run.profiles=prod spring-boot:run
```

### Testes
```bash
mvn test
```

## Configuração

| Variável | Padrão | Descrição |
|---|---|---|
| `SGPUR_ADMIN_USER` | `admin` | login do administrador inicial |
| `SGPUR_ADMIN_PASSWORD` | `admin123` em dev/desktop; **obrigatória em prod** (sem default, boot falha se ausente) | senha do administrador inicial |
| `app.anexos.dir` | `./data/anexos` | diretório dos anexos |

> Em produção, **defina `SGPUR_ADMIN_PASSWORD`** antes do primeiro deploy —
> sem ela a aplicação não sobe (não há fallback para a senha padrão em
> `application-prod.yml`). `AdminBootstrap` só cria o admin se a tabela
> `usuario` estiver vazia. Nunca versione segredos.

## Estrutura

```
domain/      entidades JPA (Processo, MembroUrgenciaRenal, Parecer, Anexo,
             Usuario) e enums (StatusProcesso, ResultadoParecer, TipoAnexo, Perfil)
repository/  repositórios Spring Data
service/     regras de negócio (ProcessoService, FluxoProcessoService,
             EmailTemplateService, RelatorioService, AnexoStorageService, Usuario*)
web/         controllers MVC
config/      segurança (SecurityConfig), migração de schema (SchemaMigration)
             e bootstrap do admin inicial (AdminBootstrap)
templates/   páginas Thymeleaf · static/ CSS
```

## Regras de negócio (resumo)

- Cada processo vai para **3 médicos**; **2 favoráveis = Deferido**.
- Status: `Em análise` → `Deferido` / `Indeferido` / `Cancelado`.
- Indeferimento **exige** motivo + ofício + data de emissão.
- Numeração `NN/AAAA`: **manual em 2026**, **automática a partir de 2027**.

---
Documento oficial gerado pelo sistema: **Relatório Final do Processo de
Urgência Renal**.
