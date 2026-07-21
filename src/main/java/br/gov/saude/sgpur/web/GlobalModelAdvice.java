package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Atributos de model disponiveis em TODAS as views.
 *
 * Hoje expoe apenas {@code pendentesAvaliador}: a contagem de processos que
 * aguardam o voto do medico logado, usada pelo badge da navbar (sino + numero).
 *
 * IMPARCIALIDADE: o contador e apenas um numero — nunca expoe nome de paciente,
 * equipe solicitante ou co-avaliadores. So e calculado quando o usuario tem
 * ROLE_AVALIADOR e possui membro vinculado; para ADMIN/OPERADOR fica em 0 e o
 * badge nao e renderizado.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final UsuarioRepository usuarioRepo;
    private final ParecerRepository parecerRepo;

    public GlobalModelAdvice(UsuarioRepository usuarioRepo, ParecerRepository parecerRepo) {
        this.usuarioRepo = usuarioRepo;
        this.parecerRepo = parecerRepo;
    }

    // @Transactional necessario: par.getProcesso() em pendentesDoMembro() e LAZY
    // (Parecer.processo), e este @ModelAttribute de @ControllerAdvice roda fora
    // do @Transactional do controller de destino. Sem isso, LazyInitializationException
    // ("no session") em QUALQUER tela para um usuario AVALIADOR com pareceres
    // pendentes - com open-in-view=false nao ha sessao Hibernate aberta aqui.
    @ModelAttribute("pendentesAvaliador")
    @Transactional(readOnly = true)
    public int pendentesAvaliador() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelAvaliador(auth)) {
            return 0;
        }

        return usuarioRepo.findByUsername(auth.getName())
            .map(Usuario::getMembro)
            .map(MembroUrgenciaRenal::getId)
            .map(membroId -> AvaliadorController.pendentesDoMembro(parecerRepo, membroId).size())
            .orElse(0);
    }

    private boolean temPapelAvaliador(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_AVALIADOR".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
