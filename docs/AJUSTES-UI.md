# Ajustes de UI/UX — SAUR

Realizados em 2026-07-09 (commits `4b0b210` e `3bfba9b`). Resolve os problemas
identificados na vistoria profissional de UI/UX.

---

## 1. Paleta dupla eliminada — dashboard migrado de Tailwind para Bootstrap

### Problema
O `dashboard.html` usava Tailwind CSS (slate/amber/emerald/rose/indigo/sky)
enquanto o resto do sistema usava Bootstrap + paleta institucional
(`--rs-blue`, `--rs-green`, `--rs-red`, `--rs-gold`). O dashboard parecia
"outro sistema" — inclusive o botão "Novo processo" tinha gradiente
sky-to-indigo (#sky-600 → #indigo-600) diferente do `btn-primary` institucional
(#1a4d8f → #0f3163).

### O que foi feito
- **`dashboard.html` reescrito** usando Bootstrap grid (`row-cols-2 ... row-cols-lg-6`)
  e classes Bootstrap (`card`, `table`, `btn-primary`, `badge`)
- **Tailwind removido**: o `<link>` para `tailwind-dashboard.css` e a classe
  `tw-scope` foram removidos. O dashboard agora carrega só `app.css` como todo
  o resto do sistema.
- **Novas classes `stat-card-*`** adicionadas ao `app.css` — 7 variantes que
  usam as `--rs-*` CSS variables:
  - `stat-card-total` — cinza neutro
  - `stat-card-andamento` — dourado (`--rs-gold`)
  - `stat-card-deferido` — verde (`--rs-green`)
  - `stat-card-indeferido` — vermelho (`--rs-red`)
  - `stat-card-cancelado` — cinza claro
  - `stat-card-membros` — azul (`--rs-blue`)
  - `stat-card-tempo` — dinâmico (verde se dentro do prazo, vermelho se fora)
- **Cada stat-card** usa `var(--stat-bg)`, `var(--stat-border)`, `var(--stat-text)`,
  `var(--stat-icon-bg)`, `var(--stat-icon-color)` — todas definidas pela variante.
- A seção de **"Solicitações e situação dos pareceres"** foi convertida para
  `<table class="table table-hover">` em vez de `<table class="w-full ...">` Tailwind.

### Arquivos alterados
- `src/main/resources/static/css/app.css` — adicionado bloco `stat-card-*` (~50 linhas)
- `src/main/resources/templates/dashboard.html` — reescrito (Bootstrap, sem Tailwind)

### Observação
O arquivo `static/css/tailwind-dashboard.css` ainda existe no repositório mas
não é mais referenciado por nenhum template. Pode ser removido em uma limpeza
futura quando a equipe confirmar que ninguém mais usa. O procedimento de
regeneração descrito em `docs/PLANO-FLUXO.md` não é mais necessário.

---

## 2. JavaScript extraído — inline → arquivo separado

### Problema
O `detalhe.html` continha ~290 linhas de JavaScript inline (fetch API, modais
dinâmicos, integração com IA Gemini, clipboard, renderização de templates de
e-mail). Isso é difícil de manter, testar e depurar.

### O que foi feito
- Criado `static/js/processo-detalhe.js` com **todo** o JavaScript que estava
  no bloco `<script>` do `detalhe.html`
- O template agora faz:
  ```html
  <script th:src="@{/js/processo-detalhe.js}"></script>
  ```
- Toda a lógica foi movida: toggle do motivo de indeferimento, confirmação de
  parecer, cópia de e-mail, assistência IA, preview/envio de e-mail,
  lembretes individuais e em lote.

### Arquivos
- `src/main/resources/static/js/processo-detalhe.js` (criado, ~320 linhas)
- `src/main/resources/templates/processos/detalhe.html` (bloco `<script>` inline
  substituído por referência ao arquivo externo, ~290 linhas a menos)

### Próximo passo recomendado
Considerar quebrar `processo-detalhe.js` em módulos menores se o arquivo
crescer além de ~400 linhas.

---

## 3. `alert()` substituído por toast estilizado

### Problema
As chamadas `alert()` nativas do navegador nos blocos de IA e envio de e-mail
eram feias, sem estilo e sem alinhamento visual com o resto do sistema.

### O que foi feito
- **Sistema de toast** adicionado ao `app.css`:
  - `toast-container-sgpur` — container fixo no canto superior direito
  - `toast-sgpur` — card com borda lateral colorida (verde=sucesso,
    vermelho=erro, azul=info)
  - Animação `toastIn` (slide-in da direita)
  - Auto-dismiss após 5s + botão de fechar
- Função `mostrarToast(mensagem, tipo)` no JS: cria o toast, exibe, remove
  automaticamente
- Todos os `alert()` nos blocos de IA (`chamarIa`) e de envio de e-mail
  (`chamarAcao`, `abrirPreviewEmail`) foram substituídos por `mostrarToast()`

### Arquivos
- `src/main/resources/static/css/app.css` — bloco `.toast-sgpur` (~40 linhas)
- `src/main/resources/static/js/processo-detalhe.js` — função `mostrarToast()`

---

## 4. `login.html` e `error.html` com identidade visual consistente

### Problema
- `login.html` usava `style="background: linear-gradient(135deg, #1a4d8f...)"`
  com hex hardcoded em vez das `--rs-*` CSS variables. O botão de submit tinha
  `style="background:linear-gradient(135deg,#1a4d8f,#0f3163)"` redundante com
  o `btn-primary`.
- `error.html` tinha CSS inline separado, sem o `layout.html`, perdendo toda a
  identidade visual — fonte, cores, footer, navbar.

### O que foi feito
- **`login.html`**: cores hardcoded substituídas por `var(--rs-blue)`,
  `var(--rs-blue-dark)`, `var(--rs-gold)`. Botão submit agora usa
  `btn-primary rounded-pill shadow-sm` sem inline style redundante.
- **`error.html`**: reescrito para usar `layout.html` — agora tem navbar,
  footer, fonte Inter, flash messages e a paleta institucional. O card de erro
  usa `.card` e `--rs-*` cores em vez de CSS inline.

### Arquivos
- `src/main/resources/templates/login.html`
- `src/main/resources/templates/error.html`

---

## 5. Wizard responsivo melhorado em mobile

### Problema
Em telas <768px a linha conectora (`wizard::before`) sumia (`display: none`),
o que tirava a noção de progresso linear. Os labels ficavam muito pequenos e
não havia indicação de que haviam mais passos para scrollar.

### O que foi feito
- Adicionada classe `wizard-wrapper` ao redor do `.wizard` no `detalhe.html`
- No CSS mobile (`@media max-width: 768px`):
  - Wizard ganha `scroll-snap-type: x mandatory` com `scroll-snap-align: start`
    nos passos — scroll suave e preciso
  - Cada wizard-step tem `flex: 0 0 auto; min-width: 85px` para não encolher
  - Círculos reduzem para 36px
  - Labels ganham `white-space: nowrap; overflow: hidden; text-overflow: ellipsis`
  - `wizard-wrapper::after` — sombra gradiente na borda direita indicando que
    há mais passos (opacity 0 → 1 via JS)
- JavaScript verifica se o wizard tem scroll (`scrollWidth > clientWidth`) e
  adiciona/remove a classe `can-scroll` no wrapper — quando chega no final
  do scroll a sombra some.

### Arquivos
- `src/main/resources/static/css/app.css` — bloco `@media (max-width: 768px)`
  refeito
- `src/main/resources/templates/processos/detalhe.html` — adicionado
  `div.wizard-wrapper`
- `src/main/resources/static/js/processo-detalhe.js` — scroll detection no final

---

## Resumo de arquivos alterados/criados

| Arquivo | Ação | Linhas |
|---|---|---|
| `static/css/app.css` | Alterado | +95 |
| `static/js/processo-detalhe.js` | **Criado** | +324 |
| `templates/dashboard.html` | Alterado (reescrito) | +311 / -490 (vs antigo Tailwind) |
| `templates/error.html` | Alterado (reescrito) | +55 |
| `templates/login.html` | Alterado | +11 |
| `templates/processos/detalhe.html` | Alterado | -290 (JS inline removido) |

**Total:** 6 arquivos, +600 linhas adicionadas, -490 removidas.
**Testes:** 142/142 passando.
**Commit:** `3bfba9b`
