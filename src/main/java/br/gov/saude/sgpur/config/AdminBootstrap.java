package br.gov.saude.sgpur.config;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.UsuarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Cria o primeiro usuario ADMIN quando o banco esta vazio (substitui o
 * DataSeed removido em e8449e9). So age se a tabela usuario NAO tem nenhum
 * registro - nunca mexe num banco que ja tem usuarios.
 *
 * <p>A senha vem de {@code app.admin.password} (env var SGPUR_ADMIN_PASSWORD).
 * No perfil {@code prod} essa propriedade NAO tem valor default em
 * {@code application-prod.yml} (so em dev) - se a env var nao estiver
 * setada, a resolucao do placeholder falha e o boot PARA antes mesmo deste
 * runner executar, em vez de subir com uma senha fraca.
 */
@Component
@Order(2)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UsuarioRepository usuarioRepository;
    private final UsuarioService usuarioService;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(UsuarioRepository usuarioRepository, UsuarioService usuarioService,
                          @Value("${app.admin.username}") String adminUsername,
                          @Value("${app.admin.password}") String adminPassword) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioService = usuarioService;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (usuarioRepository.count() > 0) {
            log.debug("AdminBootstrap: ja existem usuarios cadastrados, nao vou criar admin inicial.");
            return;
        }
        Usuario admin = new Usuario();
        admin.setUsername(adminUsername);
        admin.setNome("Administrador");
        admin.setPerfil(Perfil.ADMIN);
        admin.setAtivo(true);
        usuarioService.criar(admin, adminPassword);
        log.info("AdminBootstrap: usuario ADMIN inicial '{}' criado (banco estava vazio).", adminUsername);
    }
}
