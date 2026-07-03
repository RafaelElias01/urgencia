package br.gov.saude.sgpur.config;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cadastra os membros avaliadores da equipe de Urgencia Renal (POP - Controle
 * das Urgencias Renais) quando a tabela esta vazia. So age se a tabela
 * membro_urgencia_renal NAO tem nenhum registro - nunca mexe num banco que
 * ja tem membros cadastrados (mesmo padrao do AdminBootstrap).
 *
 * <p>Rogerio Caruso Bezerra (CET-RS) e o coordenador: seu voto favoravel
 * DEFERE o processo independentemente dos demais pareceres.
 */
@Component
@Order(3)
public class MembroBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MembroBootstrap.class);

    private final MembroUrgenciaRenalRepository membroRepository;

    public MembroBootstrap(MembroUrgenciaRenalRepository membroRepository) {
        this.membroRepository = membroRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (membroRepository.count() > 0) {
            log.debug("MembroBootstrap: ja existem membros cadastrados, nao vou recriar a lista inicial.");
            return;
        }
        List<MembroUrgenciaRenal> membros = List.of(
            new MembroUrgenciaRenal("HBBL", "Marcia Abichequer", "abichequer@uol.com.br"),
            new MembroUrgenciaRenal("HNSP", "Cristiane Martins da Silveira Souto", "crismssouto@gmail.com"),
            new MembroUrgenciaRenal("HSLPUC", "Ivan Antonello", "Ivan.antonello@pucrs.br"),
            new MembroUrgenciaRenal("ISCMPA", "Clotilde Garcia", "cdruckgarcia@gmail.com"),
            new MembroUrgenciaRenal("HCPA", "Verônica Horbe", "horbe@cpovo.net"),
            coordenador(new MembroUrgenciaRenal("CET-RS", "Rogerio Caruso Bezerra", null)),
            new MembroUrgenciaRenal("Sem Hospital", "Marcelo Generali", "margenerali@uol.com.br"),
            new MembroUrgenciaRenal("HCI", "Ana Lúcia", "anacaetano.vascular@terra.com.br")
        );
        membroRepository.saveAll(membros);
        log.info("MembroBootstrap: {} membros avaliadores cadastrados (banco estava vazio).", membros.size());
    }

    private static MembroUrgenciaRenal coordenador(MembroUrgenciaRenal m) {
        m.setCoordenador(true);
        return m;
    }
}
