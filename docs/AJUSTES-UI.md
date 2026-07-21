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

## 6. Correções de responsividade mobile

Realizadas em 2026-07-09 (commit `1a06043`). 13 correções implementadas,
divididas em 2 críticas, 4 graves e 7 menores.

### Críticas (C1, C2) — Tabelas sem `table-responsive`

**Problema:** As listas de Membros e Usuários não tinham `table-responsive`.
Em telas < 768px as tabelas extrapolavam o container e quebravam o layout da
página inteira.

**O que foi feito:**
- **`membros/lista.html`**: `<table>` envolvida em `<div class="table-responsive">`
- **`usuarios/lista.html`**: mesma correção

### Graves (G1-G4)

#### G1 — Tabela de pareceres com `min-width: 360px`
**`app.css`**: Adicionado no `@media (max-width: 768px)`:
```css
.tabela-pareceres th:nth-child(6),
.tabela-pareceres td:nth-child(6) {
    min-width: 220px;
    width: auto;
}
```
Reduz a largura mínima da coluna "Ação" em mobile, evitando scroll horizontal
excessivo.

#### G2 — Sidebar antes do conteúdo principal (corrigido em 2026-07-09)
**`detalhe.html`**: Havia sido adicionado `order-lg-2` na sidebar (`col-lg-3`) e
`order-lg-1` no conteúdo principal (`col-lg-9`) para que, em mobile, o operador
visse primeiro o wizard com as abas de trabalho. Efeito colateral não previsto:
`order-lg-*` também vale em desktop (≥992px), o que jogou o card "Progresso"
para o lado **direito** da tela em telas grandes — regressão percebida pelo
usuário. Corrigido trocando para `order-2 order-md-1` na sidebar (`col-lg-3`) e
`order-1 order-md-2` no conteúdo (`col-lg-9`): abaixo de 768px o wizard
continua aparecendo primeiro (mobile-first preservado), e a partir de 768px
(tablet/desktop) o Progresso volta para a esquerda, antes do conteúdo — sem
depender do breakpoint `lg`, que é o que causava o vazamento para desktop.

#### G3 — Botão "Enviar" em `col-md-1`
**`detalhe.html`**: Alterado para `col-md-2`. O botão agora tem espaço adequado
em desktop sem comprimir o texto. Removido `w-100` que não era mais necessário.

#### G4 — Breakpoint para celulares pequenos
**`app.css`**: Adicionado `@media (max-width: 576px)` com:
- Stat-cards: `font-size: 1.25rem` e ícones reduzidos para 36px
- Toast: `left: .5rem; right: .5rem` ocupando largura total
- Card-body: padding reduzido para `.75rem`
- Badges `fs-5` no controle-urgencias: reduzidos para `1rem`

### Menores (M1-M7)

#### M1 — `max-height: 70vh` cortando tabela em mobile
**`dashboard.html` + `app.css`**: A classe `dashboard-tabela-scroll` foi criada
com `max-height: 70vh; overflow-y: auto` escopada a `@media (min-width: 768px)`.
Em mobile a tabela ocupa a altura natural sem corte.

#### M2 — Controle de Urgências: 8 colunas em mobile
**`controle-urgencias/lista.html`**: Colunas RGCT, ABO e Última renovação
ganharam `d-none d-md-table-cell` — somem em telas < 768px, reduzindo para
5 colunas essenciais (Nome, Equipe, Vencimento, Situação, Ações).

#### M3 — Auditoria: coluna "Detalhe" sem truncamento
**`auditoria/lista.html`**: Adicionado `text-truncate` com `max-width: 240px`
e `th:title` na célula de detalhe. Textos longos são cortados com ellipsis;
o conteúdo completo aparece ao tocar/ passar o mouse.

#### M4 — Navbar colapsando cedo demais em tablets
**`layout.html`**: `navbar-expand-lg` → `navbar-expand-md`. Tablets entre
768px-992px agora veem o menu horizontal completo em vez do hamburguer.

#### M5 — Wizard circles abaixo do touch target mínimo
**`app.css`**: Aumentado de 36px para 40px no breakpoint mobile (o máximo que
cabe no layout sem desalinhar). Acima dos 36px anteriores, ainda abaixo dos
44px recomendados pela WCAG, mas com ganho significativo.

#### M6 — Scrollbar fina difícil de agarrar em touch
**`app.css`**: `scrollbar-width: thin` e `::-webkit-scrollbar` escopados em
`@media (min-width: 768px)`. Em mobile, as scrollbars voltam ao tamanho
nativo do sistema, mais fáceis de usar com o dedo.

#### M7 — Toast vazando da tela em viewports muito estreitos
**`app.css`**: No breakpoint 576px, `.toast-container-sgpur` ganha
`left: .5rem; right: .5rem` e `.toast-sgpur` ganha `max-width: 100%;
min-width: 0`, ocupando a largura total da tela com margem.

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

**Total (1a rodada):** 6 arquivos, +600 linhas adicionadas, -490 removidas.
**Total (2a rodada — responsividade):** 8 arquivos, +59 linhas, -22 removidas.
**Testes:** 142/142 passando.
**Commits:** `3bfba9b` (UI), `1a06043` (responsividade)
