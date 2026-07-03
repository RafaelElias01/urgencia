# Membros da Urgência Renal — lista de avaliadores

> Documento de **referência** dos médicos avaliadores da equipe de Urgência
> Renal (POP — Controle das Urgências Renais). **Somente registro** — não é
> fonte de importação automática. A criação real dos membros no banco é feita
> pelo `MembroBootstrap` **apenas quando a tabela `membro_urgencia_renal` está
> vazia**; depois disso, quem cadastra/edita/inativa é o OPERADOR pela tela
> `/membros`. Alterar esta lista **não** altera o banco.

## Lista oficial (8 membros)

| # | Hospital / Sigla | Médico(a)                          | E-mail (produção)                 | Papel        |
|---|------------------|------------------------------------|-----------------------------------|--------------|
| 1 | HBBL             | Marcia Abichequer                  | abichequer@uol.com.br             | Avaliador    |
| 2 | HNSP             | Cristiane Martins da Silveira Souto| crismssouto@gmail.com             | Avaliador    |
| 3 | HSLPUC           | Ivan Antonello                     | Ivan.antonello@pucrs.br           | Avaliador    |
| 4 | ISCMPA           | Clotilde Garcia                    | cdruckgarcia@gmail.com            | Avaliador    |
| 5 | HCPA             | Verônica Horbe                     | horbe@cpovo.net                   | Avaliador    |
| 6 | CET-RS           | Rogerio Caruso Bezerra             | *(sem e-mail cadastrado)*         | **Coordenador** |
| 7 | Sem Hospital     | Marcelo Generali                   | margenerali@uol.com.br            | Avaliador    |
| 8 | HCI              | Ana Lúcia                          | anacaetano.vascular@terra.com.br  | Avaliador    |

- **Coordenador (CET-RS):** Rogerio Caruso Bezerra. Regra de negócio: se o
  coordenador votar **Favorável**, o processo é **Deferido com esse único
  voto**, sem esperar os outros 2 pareceres. A regra de Indeferido continua
  exigindo ≥2 desfavoráveis (o coordenador não tem peso especial para
  indeferir). Só **um** membro deve ter `coordenador = true` por vez.

## Observação sobre e-mails em ambiente de teste

No **banco de desenvolvimento** (`jdbc:h2:file:./data/sgpur`, arquivo
`data/sgpur.mv.db`, gitignored) os e-mails de vários médicos podem estar
**todos iguais** (ex.: apontando para uma única caixa de teste) — isso é
**intencional**, para que os e-mails disparados em teste cheguem a uma caixa
única e possam ser conferidos. **Não reflete os e-mails reais de produção**,
que são os da tabela acima. O banco dev é persistido em arquivo e sobrevive
entre execuções, então essas alterações de teste permanecem até serem
desfeitas manualmente.

## Fonte

Valores de produção conforme
`src/main/java/br/gov/saude/sgpur/config/MembroBootstrap.java` (seed inicial).
Este documento é uma cópia legível para referência; a fonte de verdade do seed
continua sendo o código.
