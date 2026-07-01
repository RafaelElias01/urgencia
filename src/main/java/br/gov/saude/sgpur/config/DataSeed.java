package br.gov.saude.sgpur.config;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.ControleUrgenciaRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.ProcessoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;

/**
 * Carga inicial dos membros da Urgencia Renal (extraidos da planilha 2026).
 * So insere se a tabela estiver vazia.
 *
 * Os {@link CommandLineRunner} sao ordenados via {@link Order} para garantir que
 * os membros existam ANTES dos seeds dependentes (avaliador de exemplo e processo
 * de demonstracao). Sem a ordenacao explicita o Spring executa os runners em
 * ordem indeterminada, o que causava o aviso "nenhum membro ativo encontrado".
 */
@Configuration
public class DataSeed {

    private static final Logger log = LoggerFactory.getLogger(DataSeed.class);

    /** Numero do processo de demonstracao (perfil dev). Idempotencia por este numero. */
    private static final String NUMERO_PROCESSO_DEMO = "00/2026";

    @Bean
    @Order(1)
    CommandLineRunner seedMembros(MembroUrgenciaRenalRepository repo) {
        return args -> {
            if (repo.count() > 0) {
                return;
            }
            List<MembroUrgenciaRenal> membros = List.of(
                new MembroUrgenciaRenal("HBBL", "Marcia Abichequer", "abichequer@uol.com.br"),
                new MembroUrgenciaRenal("HNSP", "Cristiane Martins da Silveira Souto", "crismssouto@gmail.com"),
                new MembroUrgenciaRenal("HSLPUC", "Ivan Antonello", "ivan.antonello@pucrs.br"),
                new MembroUrgenciaRenal("ISCMPA", "Clotilde Garcia", "cdruckgarcia@gmail.com"),
                new MembroUrgenciaRenal("HCPA", "Veronica Horbe", "horbe@cpovo.net"),
                new MembroUrgenciaRenal("SGN", "Marcelo Generali da Costa", "margenerali@uol.com.br"),
                new MembroUrgenciaRenal("HCl", "Ana Lucia", "anacaetano.vascular@terra.com.br"),
                new MembroUrgenciaRenal("CET", "Rogerio Caruso Bezerra", null)
            );
            // CET - Rogerio Caruso Bezerra e o coordenador CET-RS
            membros.get(7).setCoordenador(true);
            repo.saveAll(membros);
        };
    }

    /** Cria o usuario administrador inicial (se nenhum usuario existir). */
    @Bean
    @Order(2)
    CommandLineRunner seedAdmin(UsuarioRepository usuarioRepo, PasswordEncoder encoder,
                                @Value("${app.admin.username}") String adminUser,
                                @Value("${app.admin.password}") String adminPassword) {
        return args -> {
            if (usuarioRepo.count() > 0) {
                return;
            }
            Usuario admin = new Usuario();
            admin.setUsername(adminUser);
            admin.setNome("Administrador");
            admin.setSenha(encoder.encode(adminPassword));
            admin.setPerfil(Perfil.ADMIN);
            admin.setAtivo(true);
            usuarioRepo.save(admin);
        };
    }

    /**
     * Cria um usuario AVALIADOR de exemplo vinculado ao primeiro membro ativo.
     * APENAS em perfil dev/H2 — nao executado em prod ou desktop.
     * Login: avaliador1 / avaliador123
     *
     * Roda DEPOIS de {@link #seedMembros} (Order 3 > 1), portanto sempre ha
     * membros ativos disponiveis para o vinculo.
     */
    @Bean
    @Order(3)
    @Profile("dev")
    CommandLineRunner seedAvaliadorExemplo(UsuarioRepository usuarioRepo,
                                           MembroUrgenciaRenalRepository membroRepo,
                                           PasswordEncoder encoder) {
        return args -> {
            if (usuarioRepo.findByUsername("avaliador1").isPresent()) {
                return; // ja existe, nao duplicar
            }
            List<MembroUrgenciaRenal> membros = membroRepo.findByAtivoTrueOrderByInstituicaoAsc();
            if (membros.isEmpty()) {
                log.warn("DataSeed: nenhum membro ativo encontrado, usuario avaliador1 nao criado.");
                return;
            }
            MembroUrgenciaRenal primeiroMembro = membros.get(0);
            Usuario avaliador = new Usuario();
            avaliador.setUsername("avaliador1");
            avaliador.setNome("Avaliador Exemplo (" + primeiroMembro.getNome() + ")");
            avaliador.setSenha(encoder.encode("avaliador123"));
            avaliador.setPerfil(Perfil.AVALIADOR);
            avaliador.setAtivo(true);
            avaliador.setMembro(primeiroMembro);
            usuarioRepo.save(avaliador);
            log.info("DataSeed: usuario avaliador1 criado e vinculado a {} - {}.",
                primeiroMembro.getInstituicao(), primeiroMembro.getNome());
        };
    }

    /**
     * Cria um PROCESSO de demonstracao para deixar o Portal do Avaliador navegavel
     * ja ao subir o ambiente. APENAS em perfil dev/H2 — nunca em prod/desktop.
     *
     * Roda por ultimo (Order 4): depende dos membros (Order 1) e do usuario
     * avaliador1 (Order 3) ja terem sido criados, pois o parecer pendente precisa
     * pertencer ao MESMO membro que avaliador1 representa.
     *
     * O que cria:
     * - 1 Processo {@code 00/2026} em status ENVIADO (dataEnvio dos pareceres
     *   preenchida), com paciente ficticio. As telas do avaliador exibem apenas
     *   iniciais, entao o nome completo aqui e inofensivo.
     * - 3 pareceres (um por membro), reaproveitando os 3 primeiros membros ativos
     *   — o mesmo conjunto a que avaliador1 pertence (1o membro ativo).
     * - O parecer do 1o membro (avaliador1) fica PENDENTE (resultado null) para
     *   aparecer na lista/badge/contadores do portal. O 2o membro tambem fica
     *   pendente; o 3o ja vota FAVORAVEL para o historico ter dado.
     *
     * Idempotencia: so cria se o processo {@code 00/2026} ainda nao existir.
     */
    @Bean
    @Order(4)
    @Profile("dev")
    CommandLineRunner seedProcessoDemo(ProcessoRepository processoRepo,
                                       MembroUrgenciaRenalRepository membroRepo,
                                       ProcessoService processoService) {
        return args -> {
            if (processoRepo.findByNumero(NUMERO_PROCESSO_DEMO).isPresent()) {
                return; // demo ja existe, nao duplicar
            }
            List<MembroUrgenciaRenal> ativos = membroRepo.findByAtivoTrueOrderByInstituicaoAsc();
            if (ativos.size() < ProcessoService.AVALIADORES_POR_PROCESSO) {
                log.warn("DataSeed: menos de {} membros ativos, processo de demonstracao nao criado.",
                    ProcessoService.AVALIADORES_POR_PROCESSO);
                return;
            }
            // Os 3 primeiros membros ativos (o 1o e o que avaliador1 representa).
            List<MembroUrgenciaRenal> tres =
                ativos.subList(0, ProcessoService.AVALIADORES_POR_PROCESSO);

            Processo demo = new Processo();
            demo.setNumero(NUMERO_PROCESSO_DEMO);
            demo.setPacienteNome("Paciente Demonstracao da Silva");
            demo.setPacienteRgct("SNT-DEMO-0000");
            demo.setSolicitanteEquipe("Equipe Solicitante (Demonstracao)");
            demo.setSolicitanteEmail("solicitante.demo@exemplo.gov.br");
            demo.setDataSituacaoEspecial(LocalDate.of(2026, 1, 15));
            demo.setObservacoes("Processo de demonstracao gerado automaticamente (perfil dev).");

            List<Long> medicoIds = tres.stream().map(MembroUrgenciaRenal::getId).toList();
            // cadastrar() valida os 3 medicos, define ano/sequencial e cria os
            // 3 pareceres pendentes — mesma logica de negocio da tela de cadastro.
            Processo salvo = processoService.cadastrar(demo, medicoIds);

            // Coloca o processo no status ENVIADO e registra o envio dos pareceres
            // (dataEnvio), criterio que o Portal do Avaliador usa para listar
            // pendencias. O parecer do 1o membro (avaliador1) e o 2o ficam
            // pendentes; o 3o ja vota FAVORAVEL para popular o historico.
            salvo.setStatus(StatusProcesso.ENVIADO);
            LocalDate hojeMenos2 = LocalDate.now().minusDays(2);
            Long terceiroMembroId = tres.get(2).getId();
            for (Parecer par : salvo.getPareceres()) {
                par.setDataEnvio(hojeMenos2);
                if (par.getMembro().getId().equals(terceiroMembroId)) {
                    par.setResultado(ResultadoParecer.FAVORAVEL);
                    par.setDataResposta(LocalDate.now().minusDays(1));
                }
            }
            processoRepo.save(salvo);

            log.info("DataSeed: processo de demonstracao {} criado (ENVIADO). "
                    + "Pendencias para avaliador1 ({}).",
                NUMERO_PROCESSO_DEMO, tres.get(0).getNome());
        };
    }

    /**
     * Carga inicial de registros de Controle de Urgencias (perfil dev).
     * Cria alguns pacientes com datas de vencimento variadas para testar
     * a listagem, os filtros e a renovacao.
     */
    @Bean
    @Order(5)
    @Profile("dev")
    CommandLineRunner seedControleUrgencias(ControleUrgenciaRepository repo) {
        return args -> {
            if (repo.count() > 0) {
                return;
            }
            LocalDate hoje = LocalDate.now();
            List<ControleUrgencia> registros = List.of(
                new ControleUrgencia("Joao Antonio Silva", "SNT-12345", "HCPA - Nefrologia", "O",
                    SituacaoUrgencia.ATIVA, hoje.plusDays(10)),
                new ControleUrgencia("Maria das Gracas Oliveira", "SNT-23456", "ISCMPA", "A",
                    SituacaoUrgencia.ATIVA, hoje.plusDays(25)),
                new ControleUrgencia("Carlos Alberto Pereira", "SNT-34567", "HNSP", "B",
                    SituacaoUrgencia.RENOVADA, hoje.plusDays(35)),
                new ControleUrgencia("Ana Beatriz Costa", "SNT-45678", "HSLPUC", "AB",
                    SituacaoUrgencia.ATIVA, hoje.minusDays(3)),
                new ControleUrgencia("Pedro Augusto Martins", "SNT-56789", "HBBL", "A",
                    SituacaoUrgencia.EXPIRADA, hoje.minusDays(15)),
                new ControleUrgencia("Lucia Helena Souza", "SNT-67890", "HCI", "O",
                    SituacaoUrgencia.CANCELADA, hoje.minusDays(60))
            );
            // Marcar data da ultima renovacao para as renovadas
            registros.get(2).setDataUltimaRenovacao(hoje.minusDays(5));
            repo.saveAll(registros);
            log.info("DataSeed: {} registros de controle de urgencias criados.", registros.size());
        };
    }
}
