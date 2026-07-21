package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UsuarioService {

    private static final Logger log = LoggerFactory.getLogger(UsuarioService.class);

    private final UsuarioRepository repo;
    private final PasswordEncoder encoder;
    private final MembroUrgenciaRenalRepository membroRepo;
    private final EmailSenderService emailSenderService;
    private final PasswordResetAttemptService passwordResetAttemptService;

    public UsuarioService(UsuarioRepository repo, PasswordEncoder encoder,
                          MembroUrgenciaRenalRepository membroRepo,
                          EmailSenderService emailSenderService,
                          PasswordResetAttemptService passwordResetAttemptService) {
        this.repo = repo;
        this.encoder = encoder;
        this.membroRepo = membroRepo;
        this.emailSenderService = emailSenderService;
        this.passwordResetAttemptService = passwordResetAttemptService;
    }

    public List<Usuario> listar() {
        return repo.findAll();
    }

    public Usuario buscar(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado: " + id));
    }

    /** Cria um novo usuario, codificando a senha. */
    @Transactional
    public Usuario criar(Usuario u, String senhaPura) {
        return criar(u, senhaPura, null);
    }

    /**
     * Cria usuario com membro vinculado (para perfil AVALIADOR).
     * Valida: AVALIADOR exige membroId; ADMIN/OPERADOR nao devem ter membro.
     */
    @Transactional
    public Usuario criar(Usuario u, String senhaPura, Long membroId) {
        if (repo.existsByUsername(u.getUsername())) {
            throw new IllegalArgumentException("Ja existe um usuario com este login.");
        }
        aplicarMembro(u, membroId);
        u.setSenha(encoder.encode(senhaPura));
        return repo.save(u);
    }

    /** Atualiza dados; troca a senha apenas se 'senhaPura' for informada. */
    @Transactional
    public Usuario atualizar(Long id, Usuario form, String senhaPura) {
        return atualizar(id, form, senhaPura, null);
    }

    /**
     * Atualiza dados com suporte ao membro vinculado (para perfil AVALIADOR).
     */
    @Transactional
    public Usuario atualizar(Long id, Usuario form, String senhaPura, Long membroId) {
        Usuario u = buscar(id);
        if (!u.getUsername().equals(form.getUsername())) {
            if (repo.existsByUsername(form.getUsername())) {
                throw new IllegalArgumentException("Ja existe um usuario com este login.");
            }
            u.setUsername(form.getUsername());
        }
        u.setNome(form.getNome());
        u.setEmail(form.getEmail());
        u.setPerfil(form.getPerfil());
        u.setAtivo(form.isAtivo());
        aplicarMembro(u, membroId);
        if (senhaPura != null && !senhaPura.isBlank()) {
            u.setSenha(encoder.encode(senhaPura));
        }
        return repo.save(u);
    }

    /**
     * @deprecated use {@link #alternarAtivo(Long, String)} - esta sobrecarga nao
     * protege contra auto-desativacao nem contra desativar o ultimo ADMIN ativo.
     */
    @Deprecated
    @Transactional
    public void alternarAtivo(Long id) {
        alternarAtivo(id, null);
    }

    /**
     * Ativa/desativa o usuario. Bloqueia a operacao (IllegalStateException) quando
     * ela desativaria a propria conta logada ({@code usernameLogado}) ou quando
     * desativaria o ultimo ADMIN ativo do sistema - evita auto-lockout do acesso a
     * /usuarios e /auditoria, ja que o AdminBootstrap so recria o admin inicial
     * quando a tabela 'usuario' esta totalmente vazia.
     */
    @Transactional
    public void alternarAtivo(Long id, String usernameLogado) {
        Usuario u = buscar(id);
        boolean vaiDesativar = u.isAtivo();
        if (vaiDesativar) {
            validarNaoAutoGerenciamento(u, usernameLogado, "desativar");
            validarNaoUltimoAdminAtivo(u, "desativar");
        }
        u.setAtivo(!u.isAtivo());
        repo.save(u);
    }

    /**
     * @deprecated use {@link #excluir(Long, String)} - esta sobrecarga nao protege
     * contra auto-exclusao nem contra excluir o ultimo ADMIN ativo.
     */
    @Deprecated
    @Transactional
    public void excluir(Long id) {
        excluir(id, null);
    }

    /**
     * Exclui o usuario. Bloqueia a operacao (IllegalStateException) quando o alvo e
     * a propria conta logada ({@code usernameLogado}) ou o ultimo ADMIN ativo do
     * sistema - evita auto-lockout do acesso a /usuarios e /auditoria.
     */
    @Transactional
    public void excluir(Long id, String usernameLogado) {
        Usuario u = buscar(id);
        validarNaoAutoGerenciamento(u, usernameLogado, "excluir");
        validarNaoUltimoAdminAtivo(u, "excluir");
        repo.delete(u);
    }

    private void validarNaoAutoGerenciamento(Usuario u, String usernameLogado, String acao) {
        if (usernameLogado != null && u.getUsername() != null
                && u.getUsername().equalsIgnoreCase(usernameLogado)) {
            throw new IllegalStateException(
                "Voce nao pode " + acao + " a propria conta. Para trocar sua senha, use 'Minha senha'.");
        }
    }

    private void validarNaoUltimoAdminAtivo(Usuario u, String acao) {
        if (u.getPerfil() == Perfil.ADMIN && u.isAtivo()
                && repo.countByPerfilAndAtivoTrue(Perfil.ADMIN) <= 1) {
            throw new IllegalStateException(
                "Nao e possivel " + acao + " o unico administrador ativo do sistema.");
        }
    }

    /**
     * Redefine a senha do usuario (se existir e tiver e-mail cadastrado) e
     * envia a nova senha temporaria por e-mail - NUNCA expoe a senha em texto
     * puro na tela. Sempre retorna sem lancar excecao, mesmo quando o usuario
     * nao existe ou nao tem e-mail cadastrado, para o chamador poder exibir
     * uma mensagem neutra e evitar enumeracao de usuarios validos. Tambem retorna
     * silenciosamente (sem alterar nada) quando o rate-limit de tentativas de
     * reset para este username foi excedido ({@link PasswordResetAttemptService}) -
     * protege contra "bombear" reset de senha/e-mail de um login conhecido.
     */
    @Transactional
    public void resetarSenha(String username) {
        if (!passwordResetAttemptService.tentarRegistrar(username)) {
            return;
        }
        Usuario u = repo.findByUsername(username).orElse(null);
        if (u == null) {
            log.debug("resetarSenha: usuario '{}' nao encontrado.", username);
            return;
        }
        if (u.getEmail() == null || u.getEmail().isBlank()) {
            log.warn("resetarSenha: usuario '{}' nao tem e-mail cadastrado - "
                + "senha NAO foi alterada. Peca ao ADMIN redefinir manualmente.", username);
            return;
        }
        String novaSenha = gerarSenhaTemporaria();
        String corpo = """
            Ola, %s,

            Sua senha de acesso ao SAUR foi redefinida a seu pedido.

            Nova senha temporaria: %s

            Recomendamos alterar esta senha apos o proximo login.

            Se voce nao solicitou esta redefinicao, entre em contato com o
            administrador do sistema imediatamente.

            Atenciosamente,
            Equipe SAUR - Secretaria de Saude
            """.formatted(u.getNome(), novaSenha);
        boolean enviado = emailSenderService.enviar(u.getEmail(), "SAUR - Redefinicao de senha", corpo);
        if (!enviado) {
            log.warn("resetarSenha: falha ao enviar e-mail para '{}' - senha NAO foi alterada.", username);
            return;
        }
        u.setSenha(encoder.encode(novaSenha));
        repo.save(u);
    }

    /**
     * Permite que o proprio usuario logado troque sua senha, informando a
     * senha atual (verificada) e a nova. Disponivel para todos os perfis
     * (ADMIN/OPERADOR/AVALIADOR), ao contrario da edicao em /usuarios que e
     * exclusiva do ADMIN. Lanca IllegalArgumentException com mensagem amigavel
     * quando a senha atual esta errada ou a nova e invalida.
     */
    @Transactional
    public void alterarPropriaSenha(String username, String senhaAtual,
                                    String novaSenha, String confirmacao) {
        Usuario u = repo.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado."));
        if (senhaAtual == null || !encoder.matches(senhaAtual, u.getSenha())) {
            throw new IllegalArgumentException("Senha atual incorreta.");
        }
        if (novaSenha == null || novaSenha.length() < 6) {
            throw new IllegalArgumentException("A nova senha deve ter ao menos 6 caracteres.");
        }
        if (!novaSenha.equals(confirmacao)) {
            throw new IllegalArgumentException("A confirmacao nao confere com a nova senha.");
        }
        if (encoder.matches(novaSenha, u.getSenha())) {
            throw new IllegalArgumentException("A nova senha deve ser diferente da atual.");
        }
        u.setSenha(encoder.encode(novaSenha));
        repo.save(u);
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private String gerarSenhaTemporaria() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Aplica a regra de membro vinculado: AVALIADOR exige membro; outros perfis
     * nao devem ter membro (limpa o campo para evitar estado inconsistente).
     */
    private void aplicarMembro(Usuario u, Long membroId) {
        if (u.getPerfil() == Perfil.AVALIADOR) {
            if (membroId == null) {
                throw new IllegalArgumentException(
                    "Perfil Avaliador exige um membro da Urgencia Renal vinculado.");
            }
            MembroUrgenciaRenal membro = membroRepo.findById(membroId)
                .orElseThrow(() -> new IllegalArgumentException(
                    "Membro nao encontrado: " + membroId));
            u.setMembro(membro);
        } else {
            // ADMIN e OPERADOR nao tem membro vinculado
            u.setMembro(null);
        }
    }
}
