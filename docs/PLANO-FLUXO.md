# Plano do Fluxo — SAUR (Urgência Renal)

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

## As 6 etapas do checklist (fluxo dividido em 6 abas)

| # | Etapa | Controller (endpoint) | Service | Template |
|---|---|---|---|---|
| 1 | Recebimento | `ProcessoDetalheController.recebimento` | `ProcessoService.registrarRecebimento` | `detalhe.html#recebimento` |
| 2 | Envio (documentos clínicos + comprovante + registrar) | `ProcessoDecisaoController.registrarEnvio` / `.anexarDocumentoClinico` / `.anexarComprovanteEnvioAvaliadores` | `SolicitacaoAvaliadorService.consolidar` + `carimbarCabecalho` / `ProcessoService.registrarEnvio` | `detalhe.html#envio` |
| 3 | Respostas (pareceres) | `ProcessoDecisaoController.salvarPareceres` / `.respostaAvaliador` | `ProcessoService.atualizarStatusPorPareceres` + `tentarDecisaoAutomatica` | `detalhe.html#respostas` |
| 4 | Decisão | `ProcessoDecisaoController.decidir` | `ProcessoService.decidir` + `DecisaoFinalService.gerarDocumentos` | `detalhe.html#decisao` |
| 5 | Ofício/Comprovante | `ProcessoAnexoController.uploadOficio` / `.uploadComprovanteSnt` / `.finalizacao` | `AnexoStorageService` | `detalhe.html#finalizacao` |
| 6 | Resposta ao solicitante | `ProcessoAnexoController.respostaSolicitante` / `ProcessoDecisaoController.enviarEmailPronto` | `EmailSenderService` + `EmailTemplateService` | `detalhe.html#finalizacao` |

- O controller monolítico `ProcessoController` foi dividido em:
  `ProcessoListaController` (busca/filtro/paginação), `ProcessoDetalheController`
  (detalhe/recebimento), `ProcessoDecisaoController` (envio/pareceres/decisão/
  e-mails/lembretes) e `ProcessoAnexoController` (upload/download/exclusão de
  anexos, ofício, comprovantes, relatório).
- Operador acessa o processo via `GET /processos/{id}` (`ProcessoDetalheController`),
  que monta o modelo completo com abas, checklist, pareceres, anexos e textos de
  e-mail. Cada aba chama seu próprio endpoint POST.
- A decisão automática (`tentarDecisaoAutomatica`) é chamada após salvar
  pareceres, retomar análise e na resposta-avaliador — não precisa mais de um
  passo separado do operador para maioria simples.

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

- **Capa do processo não é gerada automaticamente:** o método
  `RelatorioService.gerarCapaProcesso` não é chamado por nenhum controller —
  a `CAPA_PROCESSO` só existe se alguém anexar manualmente. Avaliar se vale
  automatizar no `registrarRecebimento`.
- A transição para SOLICITA_INFORMACAO/ENVIADO é recalculada ao salvar
  pareceres; nunca rebaixa um processo já finalizado.

## Painel (Tailwind CSS estático/offline)

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
