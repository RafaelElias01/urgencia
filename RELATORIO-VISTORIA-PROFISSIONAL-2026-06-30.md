# Relatorio de Vistoria Profissional - SGPUR

Data da vistoria: 2026-06-30  
Responsavel: GitHub Copilot

## 1) Escopo e metodo

Esta vistoria cobriu:

- Integridade de build e testes
- Alertas de qualidade (Problems)
- Configuracao de ambiente e seguranca
- Dependencias e ciclo de suporte

Evidencias executadas:

- Build Maven com sucesso (skip tests)
- Suite de testes completa com sucesso
- Varredura de erros/avisos de projeto
- Leitura de arquivos de configuracao e seguranca

## 2) Resultado executivo

Status geral: BOM, com pontos de atencao estrategicos.

Resumo objetivo:

- Estabilidade atual: boa (67 testes passando).
- Seguranca funcional por papeis: boa.
- Risco principal: stack base em versao fora de suporte.
- Risco operacional: perfil padrao em desenvolvimento no arquivo base.

## 3) Evidencias principais

- Testes: 67 executados, 0 falhas, 0 erros.
- Build: sucesso.
- Working tree: limpo apos commit e push.
- Commit publicado: f64a454 em main.

Arquivos observados:

- [pom.xml](pom.xml)
- [src/main/resources/application.yml](src/main/resources/application.yml)
- [src/main/resources/application-prod.yml](src/main/resources/application-prod.yml)
- [src/main/java/br/gov/saude/sgpur/config/SecurityConfig.java](src/main/java/br/gov/saude/sgpur/config/SecurityConfig.java)
- [src/main/java/br/gov/saude/sgpur/web/GlobalModelAdvice.java](src/main/java/br/gov/saude/sgpur/web/GlobalModelAdvice.java)
- [src/main/java/br/gov/saude/sgpur/service/RelatorioService.java](src/main/java/br/gov/saude/sgpur/service/RelatorioService.java)

## 4) Achados (priorizados)

### Critico

1. Plataforma base fora da janela de suporte

- Evidencia: [pom.xml](pom.xml)
- Situacao: Spring Boot 3.3.5 com alerta de fim de suporte OSS/comercial.
- Impacto: risco de seguranca, compliance e manutencao no medio prazo.
- Recomendacao: planejar upgrade controlado para linha suportada (minimo 3.3.13 como patch imediato; ideal migrar para linha com suporte vigente).

### Alto

2. Perfil padrao ativo em desenvolvimento no arquivo base

- Evidencia: [src/main/resources/application.yml](src/main/resources/application.yml)
- Situacao: profile ativo padrao definido como dev.
- Impacto: risco de subida acidental com comportamento de desenvolvimento em ambiente indevido.
- Recomendacao: remover profile ativo do arquivo base e definir por variavel de ambiente no deploy.

### Medio

3. Open Session in View habilitado por padrao (nao explicitamente controlado)

- Evidencia: warning de teste e ausencia de propriedade explicita nos yml principais.
- Impacto: pode mascarar carregamentos tardios no render de view e elevar acoplamento de camada web com persistencia.
- Recomendacao: definir spring.jpa.open-in-view=false e ajustar pontos que dependam de lazy loading em view.

4. Excecao de seguranca para H2 console aplicada no SecurityConfig geral

- Evidencia: [src/main/java/br/gov/saude/sgpur/config/SecurityConfig.java](src/main/java/br/gov/saude/sgpur/config/SecurityConfig.java)
- Situacao: permissao e ignorar CSRF para /h2-console/\*\* no filtro geral.
- Observacao: em prod o H2 console esta desabilitado em [src/main/resources/application-prod.yml](src/main/resources/application-prod.yml), o que reduz risco.
- Recomendacao: condicionar essa regra ao perfil dev/desktop para endurecimento de postura de seguranca.

### Baixo

5. Pequenas pendencias de manutencao (imports nao usados)

- Evidencia:
  - [src/main/java/br/gov/saude/sgpur/web/GlobalModelAdvice.java](src/main/java/br/gov/saude/sgpur/web/GlobalModelAdvice.java)
  - [src/main/java/br/gov/saude/sgpur/service/RelatorioService.java](src/main/java/br/gov/saude/sgpur/service/RelatorioService.java)
- Impacto: tecnico baixo, mas gera ruido de qualidade.
- Recomendacao: limpeza simples no proximo commit de manutencao.

## 5) Pontos positivos

- Suite de testes consistente e cobrindo servicos e seguranca.
- Fluxo de negocio sensivel (urgencia renal) com regras claras e aparentemente bem preservadas.
- Separacao de papeis e rotas em seguranca bem definida.
- Perfil de producao ja explicita H2 console desabilitado.

## 6) Plano recomendado

### Curto prazo (1 a 7 dias)

- Corrigir profile ativo padrao no arquivo base.
- Limpar imports nao usados.
- Tornar regra de H2 console restrita a dev/desktop.

### Medio prazo (1 a 3 semanas)

- Definir e validar spring.jpa.open-in-view=false.
- Executar bateria de regressao em fluxos criticos (recebimento, envio, respostas, decisao, finalizacao).

### Estrategico (3 a 6 semanas)

- Plano de upgrade de Spring Boot para linha suportada, com janela de testes e homologacao.

## 7) Parecer final

O sistema esta operacional e estavel no estado atual, com boa base de testes.  
A principal prioridade profissional e tratar a defasagem da plataforma e consolidar controles de configuracao para reduzir risco operacional e de seguranca no ciclo de vida.
