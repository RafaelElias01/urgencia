package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Usado para impedir a exclusao/desativacao do ultimo ADMIN ativo (auto-lockout). */
    long countByPerfilAndAtivoTrue(Perfil perfil);
}
