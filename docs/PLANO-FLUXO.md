# Plano do Fluxo — SGPUR (Urgência Renal)

Mapeia o fluxo real do usuário (10 etapas da planilha Excel) para o código, o
novo ciclo de status e o que ficou pendente.

## Ciclo de status (expandido)

```
            cadastro                 registrar envio
  (e-mail recebido)             (aos 3 médicos)
        │                              │
        ▼                              ▼
   SOLICITADO  ───────────────────►  ENVIADO ─────────────┐
                                       │                   │
                  médico pede info     │   2/3 favoráveis  │  senão
                       ▼               │        ▼          ▼
              SOLICITA_INFORMACAO ─────┤    DEFERIDO   INDEFERIDO
              (volta a ENVIADO quando  │   (final)     (final, exige
               a info é resolvida)     │                ofício+motivo)
                                       │
   CANCELADO (final) ◄─────────────────┘  a qualquer momento (manual)

  Legado: EM_ANALISE == sinônimo de ENVIADO (em andamento, não final).
```

- **Em andamento (não finalizado):** SOLICITADO, ENVIADO, EM_ANALISE,
  SOLICITA_INFORMACAO.
- **Finais:** DEFERIDO, INDEFERIDO, CANCELADO.
- Enum: `domain/StatusProcesso.java` — `isFinalizado()`, `isEmAndamento()`,
  e helpers de cor/ícone de badge (`getBadgeClasse`, `getBadgeIcone`,
  `getBootstrapBadge`).

### Decisão sobre `EM_ANALISE`
Mantido como **sinônimo legado de ENVIADO** (posicionado entre ENVIADO e a
decisão). Motivos:
- Compatibilidade de dados: processos antigos gravados como `EM_ANALISE`
  continuam válidos (o enum ainda aceita o valor; sem Flyway, não há migração).
- Compatibilidade de testes/templates que referenciam o valor.
- Semanticamente equivale a "já enviado, aguardando decisão", que é o papel de
  ENVIADO. Novos processos **não** nascem mais EM_ANALISE (nascem SOLICITADO).

### Migração de dados
Não há Flyway (dev=H2, prod=Neon). A expansão é **aditiva**: novos valores no
enum + coluna `status` ampliada para `length=30` (cabe `SOLICITA_INFORMACAO`).
Registros antigos permanecem `EM_ANALISE` e são tratados como "em andamento".

## As 10 etapas mapeadas no código

| # | Etapa (planilha) | Código (controller / service / template) | Status resultante |
|---|---|---|---|
| 1 | Recebimento por e-mail → registra processo c/ 3 médicos | `ProcessoController.salvar` → `ProcessoService.cadastrar` (cria 3 `Parecer`) · `processos/form.html` | **SOLICITADO** (default em `Processo`) |
| 2 | Cria pasta no computador (XX-2026 – Nome – RGCT) | `AnexoStorageService` (pasta por processo) | — |
| 3 | Gera capa PDF | `RelatorioService` (capa formal) · `ProcessoController.relatorio` | — |
| 4 | Prepara: só iniciais, junta anexos, envia aos 3 médicos | `Iniciais.java` · `SolicitacaoAvaliadorService` · `EmailTemplateService.emailMedicos` (oculta dados — LGPD) | — |
| 5 | Envio aos médicos | `ProcessoController.registrarEnvio` → `ProcessoService.registrarEnvio` (gera PDF de solicitação) | **ENVIADO** |
| 6 | Recebe pareceres (2/3 favoráveis = deferido; solicita info) | `ProcessoController.salvarPareceres` → `ProcessoService.atualizarStatusPorPareceres` + `sugerirDecisao` · `processos/detalhe.html#respostas` | **SOLICITA_INFORMACAO** se algum médico pediu info; senão permanece ENVIADO |
| 7 | Decisão final (manual, com sugestão automática) | `ProcessoController.decidir` → `ProcessoService.decidir` · `detalhe.html#decisao` | **DEFERIDO / INDEFERIDO** (ou CANCELADO) |
| 8 | Médico pede info → e-mail ao solicitante | `EmailTemplateService.emailSolicitaInfo` (chave `solicita-info`), exibido quando status=SOLICITA_INFORMACAO · `detalhe.html` (lista de e-mails) | mantém SOLICITA_INFORMACAO |
| 9 | Comunica decisão ao solicitante. **Se DEFERIDO:** obrigatório anexar o comprovante de inserção da urgência renal no SNT e enviá-lo junto. **Se INDEFERIDO:** ofício + motivo + data. | `OficioService` (ofício no indeferimento) · `EmailTemplateService.emailDeferido` (texto cita o comprovante SNT EM ANEXO) / `emailIndeferido` · etapa "Comprovante SNT" em `FluxoProcessoService` bloqueia até `temAnexo(COMPROVANTE_SNT)` · upload via `detalhe.html#anexos` (tipo `COMPROVANTE_SNT`) | — |
| 10 | Arquivamento final (relatório PDF) | `RelatorioService` (anexado automaticamente ao finalizar, guard `status.isFinalizado()`) | — |

## Regra de decisão (inalterada)
- Exatamente 3 médicos (`AVALIADORES_POR_PROCESSO = 3`).
- 2 favoráveis = DEFERIDO (`FAVORAVEIS_PARA_DEFERIR = 2`); senão INDEFERIDO.
- Decisão **manual** com **sugestão automática** (`sugerirDecisao`).
- INDEFERIDO exige motivo + ofício (gerado em `decidir`).
- **DEFERIDO exige** anexar o **comprovante de inserção da urgência renal no
  SNT** (`TipoAnexo.COMPROVANTE_SNT`) antes de concluir a comunicação ao
  solicitante. A etapa "Comprovante SNT" (`FluxoProcessoService`) bloqueia o
  fluxo enquanto o anexo faltar, de forma simétrica ao ofício no indeferimento.
  O comprovante é gerado fora do sistema (operador insere a urgência no SNT e
  salva o comprovante) e anexado ao processo.

## Painel (dashboard)
- `web/HomeController` monta a planilha (`PainelLinha`) com os 3 médicos e o
  status de cada parecer; card "Em andamento" soma todos os status não-finais.
- `templates/dashboard.html` usa os badges com as cores por status (slate,
  azul, âmbar, violeta, verde, vermelho, cinza escuro) via helpers do enum.
- `lista.html` e `detalhe.html` usam `status.bootstrapBadge`.

## Pendências / pontos de atenção
- **Tailwind ainda via Play CDN** no `dashboard.html` (exige internet em
  runtime). A migração para CSS estático local
  ficou **pendente**: tentei usar o binário standalone do Tailwind CLI (v3),
  mas a execução de binário externo recém-baixado foi bloqueada pelo sandbox
  por falta de autorização do usuário. Aguarda decisão do usuário (Opção A:
  binário standalone gerando `static/css/tailwind-dashboard.css`; Opção B:
  vendorizar um CSS pré-compilado).
- E-mails são apenas **textos prontos** (copiar/colar) + PDFs; o sistema não
  dispara e-mail de verdade. O template da etapa 8 segue esse padrão.
- A transição para SOLICITA_INFORMACAO/ENVIADO é recalculada ao salvar
  pareceres; nunca rebaixa um processo já finalizado.

## Painel (Tailwind CSS estático/offline)

O painel (`templates/dashboard.html`) usa **Tailwind CSS pré-compilado** servido
como estático em `static/css/tailwind-dashboard.css` (referenciado **só** no
painel, com `preflight:false`, para não afetar o Bootstrap das demais telas).
Não há mais dependência do Play CDN — funciona **offline**.

### Como regenerar o CSS (após mudar classes do painel)

1. Baixar o Tailwind CLI standalone (sem Node):
   `tailwindcss-windows-x64.exe` v3.4.17 — github.com/tailwindlabs/tailwindcss/releases
2. Config (`preflight:false`) com `content` apontando para **dois** arquivos:
   - `src/main/resources/templates/dashboard.html`
   - `src/main/java/br/gov/saude/sgpur/domain/StatusProcesso.java`
     (contém as classes dinâmicas dos badges de status — slate/blue/amber/
     violet/emerald/rose; o scanner não as veria só no HTML).
3. Input: `@tailwind base; @tailwind components; @tailwind utilities;`
4. `tailwindcss -c config.js -i input.css -o static/css/tailwind-dashboard.css --minify`

> Importante: ao adicionar novas classes (ou novos status com novas cores no
> enum), **regerar** o CSS, senão a classe não estará no arquivo estático.
