package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UsuarioService {

    private final UsuarioRepository repo;
    private final PasswordEncoder encoder;
    private final MembroUrgenciaRenalRepository membroRepo;

    public UsuarioService(UsuarioRepository repo, PasswordEncoder encoder,
                          MembroUrgenciaRenalRepository membroRepo) {
        this.repo = repo;
        this.encoder = encoder;
        this.membroRepo = membroRepo;
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
        u.setPerfil(form.getPerfil());
        u.setAtivo(form.isAtivo());
        aplicarMembro(u, membroId);
        if (senhaPura != null && !senhaPura.isBlank()) {
            u.setSenha(encoder.encode(senhaPura));
        }
        return repo.save(u);
    }

    @Transactional
    public void alternarAtivo(Long id) {
        Usuario u = buscar(id);
        u.setAtivo(!u.isAtivo());
        repo.save(u);
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
