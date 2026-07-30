package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Atributos de model disponiveis em TODAS as views.
 *
 * Expoe {@code pendentesAvaliador} (contagem de processos que aguardam o voto
 * do medico logado, usada pelo sino da navbar) e {@code pendentesTriagemOnline}
 * (contagem de solicitacoes do Portal do Solicitante aguardando triagem,
 * mesmo padrao visual, restrito a ADMIN/OPERADOR).
 *
 * IMPARCIALIDADE: o contador de avaliador e apenas um numero — nunca expoe
 * nome de paciente, equipe solicitante ou co-avaliadores. So e calculado
 * quando o usuario tem ROLE_AVALIADOR e possui membro vinculado; para
 * ADMIN/OPERADOR fica em 0 e o badge nao e renderizado.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final UsuarioRepository usuarioRepo;
    private final ParecerRepository parecerRepo;
    private final SolicitacaoOnlineService solicitacaoOnlineService;
    /**
     * Opcional de proposito: {@code @Nullable} num construtor com um unico
     * candidato e o idioma oficial do Spring para injecao de dependencia
     * opcional (equivalente ao antigo campo {@code @Autowired(required =
     * false)}, sem precisar de reflection de campo). Continua {@code null}
     * quando o bean nao existe no contexto - a maioria dos {@code @WebMvcTest}
     * que carregam este advice nao mocka {@code MensagemSolicitacaoService}.
     */
    private final MensagemSolicitacaoService mensagemService;
    private final boolean solicitanteHabilitado;

    public GlobalModelAdvice(UsuarioRepository usuarioRepo, ParecerRepository parecerRepo,
            SolicitacaoOnlineService solicitacaoOnlineService,
            @Nullable MensagemSolicitacaoService mensagemService,
            @Value("${app.solicitante.habilitado:true}") boolean solicitanteHabilitado) {
        this.mensagemService = mensagemService;
        this.usuarioRepo = usuarioRepo;
        this.parecerRepo = parecerRepo;
        this.solicitacaoOnlineService = solicitacaoOnlineService;
        this.solicitanteHabilitado = solicitanteHabilitado;
    }

    /**
     * Kill-switch do modulo experimental "Solicitacao Online" (ver
     * docs/PLANO-SOLICITANTE.md), exposto ao layout para esconder os links
     * de navegacao quando desligado (os controllers ja nem sao registrados
     * nesse caso - isto so evita mostrar um link morto).
     */
    @ModelAttribute("solicitanteHabilitado")
    public boolean solicitanteHabilitado() {
        return solicitanteHabilitado;
    }

    // @Transactional necessario: par.getProcesso() em pendentesDoMembro() e LAZY
    // (Parecer.processo), e este @ModelAttribute de @ControllerAdvice roda fora
    // do @Transactional do controller de destino. Sem isso,
    // LazyInitializationException
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

    /**
     * Contagem de solicitacoes online aguardando triagem, para o badge do
     * link "Solicitacoes online" na navbar - mesmo padrao visual do sino do
     * avaliador. So calculado para ADMIN/OPERADOR (perfis que acessam a
     * triagem) e quando o modulo esta habilitado; caso contrario fica em 0 e
     * o badge nao e renderizado.
     */
    @ModelAttribute("pendentesTriagemOnline")
    @Transactional(readOnly = true)
    public long pendentesTriagemOnline() {
        if (!solicitanteHabilitado) {
            return 0;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelOperadorOuAdmin(auth)) {
            return 0;
        }
        return solicitacaoOnlineService.contarPendentesTriagem();
    }

    /**
     * Contagem de mensagens de solicitantes ainda nao lidas por nenhum
     * ADMIN/OPERADOR, para o badge do link "Solicitacoes online" na navbar.
     */
    @ModelAttribute("mensagensNaoLidasOperador")
    @Transactional(readOnly = true)
    public long mensagensNaoLidasOperador() {
        if (mensagemService == null || !solicitanteHabilitado) {
            return 0;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelOperadorOuAdmin(auth)) {
            return 0;
        }
        return mensagemService.contarNaoLidasOperador();
    }

    private boolean temPapelOperadorOuAdmin(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority()) || "ROLE_OPERADOR".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
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
