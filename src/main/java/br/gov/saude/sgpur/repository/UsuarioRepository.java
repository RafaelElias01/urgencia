package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Usado para impedir a exclusao/desativacao do ultimo ADMIN ativo (auto-lockout). */
    long countByPerfilAndAtivoTrue(Perfil perfil);

    /**
     * Usuarios ativos de um dos perfis informados - usado para notificar
     * ADMIN/OPERADOR quando chega uma nova SolicitacaoOnline (ver
     * SolicitacaoOnlineService).
     */
    List<Usuario> findByPerfilInAndAtivoTrue(List<Perfil> perfis);
}
