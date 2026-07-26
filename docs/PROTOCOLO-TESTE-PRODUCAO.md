# Protocolo de teste manual em produção — SAUR

Roteiro para o usuário executar manualmente contra
**https://urgenciarenal.duckdns.org/** (produção real, dados de saúde/LGPD).
Cobre as regras de negócio documentadas em `CLAUDE.md` na ordem em que
aparecem no fluxo. Marque cada caixa `[ ]` conforme for testando.

## 0. Antes de começar — leia isto

- **Não há modo teste de e-mail em produção.** `app.mail.override-recipient`
  só existe em dev (`application.yml`) — em prod
  (`application-prod.yml`) é explicitamente vazio, então **todo e-mail
  disparado neste teste vai para o destinatário real calculado pelo
  sistema** (avaliadores/solicitantes de verdade), sem redirecionamento.
- **Use dados de paciente claramente fictícios**, nunca um paciente real:
  - Nome: `TESTE QA APAGAR` (ou similar, óbvio de identificar depois)
  - RGCT: `000000-0` (ou qualquer número que não exista de verdade)
  - Equipe solicitante: pode usar uma equipe real (só afeta o campo
    "solicitante", não recebe e-mail automaticamente neste fluxo, exceto se
    você testar "Solicita informação" ou "Resposta ao solicitante" — nesses
    casos, veja o próximo ponto).
- **Antes de decidir quem serão os "3 médicos avaliadores" e o
  "e-mail do solicitante"**, escolha uma destas opções:
  - **(A) Recomendado:** cadastre um membro temporário em `/membros` com um
    e-mail que você mesmo controla (ex.: um alias seu), marque-o como
    coordenador se quiser testar aquele caminho, e use esse membro nos 3
    slots de avaliador do processo de teste. Assim nenhum e-mail de teste
    cai na caixa de um médico de verdade.
  - **(B)** Use avaliadores reais, mas avise-os antes por fora do sistema
    que vão receber um e-mail de teste (o assunto **não** tem prefixo
    `[TESTE]` em produção, diferente do dev).
- **Limpeza ao final:** o processo de teste fica no banco de produção real.
  Ao terminar, **exclua o processo** (ADMIN, botão de exclusão — ver
  CLAUDE.md, restrito a ADMIN) ou pelo menos deixe claro no nome do
  paciente que é lixo de teste, para não confundir estatísticas/relatórios
  reais (Relatório Anual, tempo de resposta).
- **PDFs de anexo prontos:** use os arquivos em `teste-pdfs/` (gerados por
  `teste-pdfs/gerar.ps1`, corrigidos e validados em 2026-07-26). Cada um já
  mapeia para uma etapa do fluxo — a tabela abaixo mostra qual usar onde.
  Se precisar de mais cópias (ex.: testar upload de vários documentos
  clínicos), rode `.\teste-pdfs\gerar.ps1` de novo ou duplique os arquivos.

| Arquivo | Tipo de anexo | Usado no passo |
|---|---|---|
| `solicitacao-recebida.pdf` | Solicitação recebida | 1. Recebimento |
| `documento-clinico-1.pdf`, `documento-clinico-2.pdf` | Documento clínico | 2. Envio |
| `email-enviado-avaliadores.pdf` | Comprovante de envio aos avaliadores | 2. Envio |
| `resposta-avaliador-1/2/3.pdf` | Resposta do avaliador | 3. Respostas |
| `info-complementar.pdf` | Pedido de informação complementar | Fluxo "Solicita informação" (opcional) |
| `oficio-indeferimento.pdf` | Ofício de indeferimento | 5. Ofício (só se Indeferido) |
| `comprovante-snt.pdf` | Comprovante SNT | 5. Comprovante SNT (só se Deferido) |
| `comprovante-envio.pdf` | Comprovante de envio ao solicitante | 6. Resposta ao solicitante |

---

## 1. Login e perfis

- [ ] Login como `admin` (a senha atual é a que você definiu em
      2026-07-26 — não está mais documentada aqui de propósito).
- [ ] Confirme que o menu do ADMIN mostra todas as áreas: Processos,
      Controle de Urgências, Membros, Usuários, Auditoria, Relatórios.
- [ ] Em `/usuarios/minha-senha`, teste trocar a própria senha e depois
      volte pra senha que você quer manter (ou deixe a nova, sua escolha).
- [ ] Crie (ou confirme que já existe) um usuário `OPERADOR` e um
      `AVALIADOR` de teste. Faça logout e login como cada um, confirmando:
  - [ ] OPERADOR **não** vê `/usuarios` nem `/auditoria` no menu.
  - [ ] AVALIADOR só vê `/avaliador`, nada de área operacional.
  - [ ] Tentar acessar `/usuarios` ou `/auditoria` direto pela URL como
        OPERADOR/AVALIADOR deve dar 403.

## 2. Cadastro do processo de teste

- [ ] Login como ADMIN ou OPERADOR.
- [ ] `/processos/novo` — cadastre um processo com paciente
      `TESTE QA APAGAR`, RGCT `000000-0`, situação especial hoje, e
      **3 avaliadores** (o membro de teste da seção 0, repetido ou com
      3 membros de teste, dependendo do que você preparou). Se for testar
      a exceção do coordenador, um dos 3 deve ter `coordenador = true`
      marcado em `/membros`.
- [ ] Confirme que o processo nasce com status `Solicitado`.

## 3. Passo 1 — Recebimento

- [ ] Anexe `solicitacao-recebida.pdf` como "Solicitação recebida".
- [ ] Confirme que a **capa do processo** é gerada automaticamente ao
      registrar o recebimento (não precisa anexar manualmente — regra
      corrigida em 2026-07-09, ver CLAUDE.md).
- [ ] Confirme que a etapa "Recebimento" na timeline só fica verde depois
      dos dois anexos (solicitação + capa) existirem.
- [ ] Confirme que a aba "Envio" só libera depois disso.

## 4. Passo 2 — Envio

- [ ] Anexe `documento-clinico-1.pdf` e `documento-clinico-2.pdf` como
      documento clínico.
- [ ] Anexe `email-enviado-avaliadores.pdf` como comprovante de envio aos
      avaliadores.
- [ ] **Teste o bloqueio:** tente registrar o envio *antes* de anexar os
      documentos clínicos — deve dar erro e não efetivar. Idem tentando sem
      o comprovante de envio.
- [ ] Registre o envio. Confirme:
  - [ ] Processo muda para `Enviado`.
  - [ ] O PDF único anonimizado (`Processo CET-RS NN-2026 - Paciente T.Q.A.pdf`
        ou similar, com iniciais) é gerado, mesclando os documentos clínicos
        com o cabeçalho carimbado.
  - [ ] O nome do arquivo gerado usa **iniciais**, nunca o nome completo.
- [ ] **Teste o aviso de conflito de equipe** (não bloqueia): se algum dos
      3 avaliadores de teste for da mesma instituição da equipe
      solicitante escolhida, deve aparecer um aviso não-bloqueante na tela.

## 5. Passo 3 — Respostas dos avaliadores

Duas formas de registrar o parecer — teste as duas:

### 5a. Via operador (e-mail)
- [ ] Em `/processos/{id}`, registre o parecer de 1 avaliador manualmente
      (resultado Favorável/Desfavorável), anexando `resposta-avaliador-1.pdf`
      como comprovante.
- [ ] **Teste o bloqueio:** tente marcar um resultado sem anexar o
      comprovante — deve ser rejeitado (regra: toda resposta recebida
      precisa do anexo antes de Deferir/Indeferir).

### 5b. Via Portal do Avaliador (login autenticado)
- [ ] Faça logout, login como o `AVALIADOR` de teste vinculado a um dos
      outros 2 membros do processo.
- [ ] Em `/avaliador`, confirme que o processo aparece na lista **só com
      iniciais** do paciente, sem nome completo, sem nome da equipe
      solicitante, sem ver quem são os outros avaliadores.
- [ ] Vote (Favorável/Desfavorável) direto pelo portal — sem precisar
      anexar nada (o voto autenticado substitui o anexo).
- [ ] Confirme em `/auditoria` (como ADMIN) que o voto ficou registrado com
      IP e `origem = AVALIADOR_SISTEMA`.
- [ ] Registre o parecer do 3º avaliador (qualquer uma das duas formas)
      para fechar 3 de 3.

### 5c. Fluxo "Solicita informação" (opcional, mas recomendado testar 1x)
- [ ] Em vez de Favorável/Desfavorável, marque um parecer como
      **Solicita informação**.
- [ ] Confirme que o processo entra em status `Solicita informação` e a
      aba "Decisão" fica **bloqueada** (tentar Deferir/Indeferir deve dar
      erro).
- [ ] Confirme que o e-mail pronto "Pedido de informação complementar"
      aparece com o **nome completo** do paciente (diferente do material
      dos avaliadores).
- [ ] Registre o reenvio (anexando `info-complementar.pdf` como
      `INFO_COMPLEMENTAR`) e depois "retomar a análise".
- [ ] Confirme que o processo volta para `Enviado` e o parecer que estava
      "Solicita informação" foi reaberto para voto definitivo.

## 6. Passo 4 — Decisão

Teste os 3 caminhos possíveis (pode ser em 3 processos de teste diferentes,
ou reaproveitando anexos/membros do mesmo conjunto):

- [ ] **Maioria simples Deferido** (2 de 3 favoráveis): confirme sugestão
      automática de Deferido, decida, confirme bloqueio se tentar decidir
      com só 1 favorável.
- [ ] **Indeferido** (2 de 3 desfavoráveis): confirme que decidir Indeferido
      **exige** motivo preenchido.
- [ ] **Exceção do coordenador**: com o membro marcado `coordenador = true`
      votando Favorável sozinho (sem esperar os outros 2), confirme que o
      processo já sugere/permite Deferir com esse único voto, e que o
      detalhe mostra o badge "Deferido pelo Coordenador da CET-RS".
      Confirme também que esse mesmo coordenador **não** tem poder especial
      para Indeferir sozinho (ainda exige 2 desfavoráveis).

## 7. Passo 5 — Ofício / Comprovante SNT

- [ ] Se Indeferido: anexe `oficio-indeferimento.pdf`. Confirme que a etapa
      só fecha depois disso.
- [ ] Se Deferido: anexe `comprovante-snt.pdf`. Confirme que a etapa só
      fecha depois disso, e que o e-mail de resposta ao solicitante inclui
      esse comprovante.

## 8. Passo 6 — Resposta ao solicitante

- [ ] Gere o e-mail pronto de resposta (Deferido/Indeferido) e confirme que
      leva o **nome completo** do paciente (não iniciais).
- [ ] Anexe `comprovante-envio.pdf` como comprovante de envio ao
      solicitante.
- [ ] Confirme a resposta. Processo deve virar status final
      (Deferido/Indeferido).

## 9. Processo encerrado — trava de edição

- [ ] Com o processo já em status final, tente editar dados básicos,
      re-anexar documento clínico, ou redecidir — tudo deve ser **rejeitado**
      com a mensagem de processo encerrado.
- [ ] Confirme que **ainda é possível**: anexar ofício/comprovante SNT (se
      faltou), gerar/reenviar e-mail de resposta, fazer downloads/relatórios.
- [ ] Como ADMIN, use "Reabrir processo" e confirme que volta para
      `Enviado` e a edição é liberada de novo.

## 10. Cancelamento

- [ ] Em outro processo de teste (ou reaproveitando), teste `Cancelado` e
      confirme que também é um status final (mesma trava de edição).

## 11. Relatórios e indicadores

- [ ] `/relatorios` (Relatório Final de um processo) — confirme o PDF gerado.
- [ ] `/relatorios/anual` — gere o relatório anual, confirme que o processo
      de teste aparece (e lembre de filtrar/desconsiderar depois de
      limpar).
- [ ] `/relatorios/avaliador` — confirme que aparece o indicador por
      avaliador.
- [ ] Painel (`/`) — confirme o card "Tempo de resposta" (média geral +
      contagem fora do prazo) refletindo os pareceres de teste.
- [ ] `/membros` — confirme a coluna de tempo de resposta por avaliador.

## 12. Controle de Urgências (módulo separado)

- [ ] `/controle-urgencias` — cadastre um registro de teste, edite,
      confirme responsividade da lista (`table-responsive`, botões de ação
      não estourando em mobile).

## 13. Portal do Solicitante (só se habilitado)

Hoje **desligado em produção** (`SGPUR_SOLICITANTE_HABILITADO` não definida
= `false`). Só teste esta seção se você decidir ligar a flag antes:

- [ ] Confirme que `/solicitante` dá 404 com a flag desligada (comportamento
      atual).
- [ ] Se ligar a flag para testar: cadastre uma solicitação online, confirme
      a triagem em `/processos/solicitacoes-online`, converta em processo, e
      confirme que os dados vieram pré-preenchidos em `/processos/novo`.

## 14. Responsividade (mobile)

- [ ] Abra o site no celular (ou DevTools em modo responsivo, ~375-390px):
      navbar, detalhe do processo, listas — confirme que nada corta ou
      estoura horizontalmente (achados de 2026-07-26 já corrigidos, mas
      vale confirmar visualmente de verdade em produção).

## 15. Limpeza final

- [ ] Exclua (ADMIN) todos os processos de teste criados (`TESTE QA
      APAGAR`), ou documente claramente que ainda existem para não confundir
      relatórios reais.
- [ ] Remova/inative os membros e usuários de teste criados na seção 0/1,
      se não forem reaproveitáveis.
- [ ] Confirme em `/auditoria` que as ações de teste ficaram registradas
      (é esperado — não precisa limpar o log de auditoria).
