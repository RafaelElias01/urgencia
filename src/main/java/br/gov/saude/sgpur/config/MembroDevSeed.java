package br.gov.saude.sgpur.config;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Popula 3 membros avaliadores de teste (banco H2 em memoria) so no perfil
 * dev, todos com o mesmo e-mail de teste usado pelo override de envio
 * (app.mail.override-recipient) - evita cadastrar isso manualmente pela
 * tela a cada restart. So age se a tabela membro_urgencia_renal esta vazia,
 * igual ao AdminBootstrap. NUNCA roda em prod (perfil dev exigido).
 */
@Component
@Profile("dev")
@Order(3)
public class MembroDevSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MembroDevSeed.class);
    private static final String EMAIL_TESTE = "rafaelioppi@gmail.com";

    private final MembroUrgenciaRenalRepository membroRepository;

    public MembroDevSeed(MembroUrgenciaRenalRepository membroRepository) {
        this.membroRepository = membroRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (membroRepository.count() > 0) {
            log.debug("MembroDevSeed: ja existem membros cadastrados, nao vou semear.");
            return;
        }
        MembroUrgenciaRenal coordenador = new MembroUrgenciaRenal("CET-RS", "Coordenador Teste", EMAIL_TESTE);
        coordenador.setCoordenador(true);
        membroRepository.save(coordenador);
        membroRepository.save(new MembroUrgenciaRenal("HCPA", "Avaliador HCPA Teste", EMAIL_TESTE));
        membroRepository.save(new MembroUrgenciaRenal("ISCMPA", "Avaliador ISCMPA Teste", EMAIL_TESTE));
        log.info("MembroDevSeed: 3 membros avaliadores de teste criados (banco estava vazio), "
            + "todos com e-mail '{}'.", EMAIL_TESTE);
    }
}
