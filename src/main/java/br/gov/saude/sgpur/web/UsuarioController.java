package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService service;
    private final AuditoriaService auditoria;
    private final MembroUrgenciaRenalRepository membroRepo;

    public UsuarioController(UsuarioService service, AuditoriaService auditoria,
                             MembroUrgenciaRenalRepository membroRepo) {
        this.service = service;
        this.auditoria = auditoria;
        this.membroRepo = membroRepo;
    }

    @ModelAttribute("perfis")
    public Perfil[] perfis() {
        return Perfil.values();
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String listar(Model model) {
        model.addAttribute("usuarios", service.listar());
        return "usuarios/lista";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("usuario", new Usuario());
        model.addAttribute("edicao", false);
        model.addAttribute("membros", membroRepo.findByAtivoTrueOrderByInstituicaoAsc());
        return "usuarios/form";
    }

    @GetMapping("/{id}/editar")
    @Transactional(readOnly = true)
    public String editar(@PathVariable Long id, Model model) {
        model.addAttribute("usuario", service.buscar(id));
        model.addAttribute("edicao", true);
        model.addAttribute("membros", membroRepo.findByAtivoTrueOrderByInstituicaoAsc());
        return "usuarios/form";
    }

    @PostMapping
    public String criar(@Valid @ModelAttribute("usuario") Usuario usuario, BindingResult result,
                        @RequestParam String senha,
                        @RequestParam(required = false) Long membroId,
                        Model model, RedirectAttributes ra) {
        if (senha == null || senha.isBlank()) {
            result.rejectValue("senha", "obrigatorio", "Informe a senha.");
        }
        if (usuario.getEmail() == null || usuario.getEmail().isBlank()) {
            result.rejectValue("email", "obrigatorio", "Informe o e-mail.");
        }
        if (result.hasErrors()) {
            model.addAttribute("edicao", false);
            model.addAttribute("membros", membroRepo.findByAtivoTrueOrderByInstituicaoAsc());
            return "usuarios/form";
        }
        try {
            service.criar(usuario, senha, membroId);
        } catch (IllegalArgumentException e) {
            model.addAttribute("edicao", false);
            model.addAttribute("membros", membroRepo.findByAtivoTrueOrderByInstituicaoAsc());
            model.addAttribute("erro", e.getMessage());
            return "usuarios/form";
        }
        auditoria.registrar("USUARIO_CRIADO", "Usuario " + usuario.getUsername());
        ra.addFlashAttribute("msg", "Usuario criado.");
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/editar")
    public String atualizar(@PathVariable Long id, @Valid @ModelAttribute("usuario") Usuario form,
                            BindingResult result,
                            @RequestParam(required = false) String senha,
                            @RequestParam(required = false) Long membroId,
                            Model model, RedirectAttributes ra) {
        if (form.getEmail() == null || form.getEmail().isBlank()) {
            result.rejectValue("email", "obrigatorio", "Informe o e-mail.");
        }
        if (result.hasErrors()) {
            model.addAttribute("edicao", true);
            model.addAttribute("membros", membroRepo.findByAtivoTrueOrderByInstituicaoAsc());
            return "usuarios/form";
        }
        try {
            service.atualizar(id, form, senha, membroId);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios/" + id + "/editar";
        }
        auditoria.registrar("USUARIO_EDITADO", "Usuario id " + id);
        ra.addFlashAttribute("msg", "Usuario atualizado.");
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/alternar-ativo")
    public String alternarAtivo(@PathVariable Long id, java.security.Principal principal,
                                RedirectAttributes ra) {
        try {
            service.alternarAtivo(id, principal == null ? null : principal.getName());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios";
        }
        ra.addFlashAttribute("msg", "Situacao do usuario atualizada.");
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id, java.security.Principal principal,
                          RedirectAttributes ra) {
        try {
            service.excluir(id, principal == null ? null : principal.getName());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios";
        }
        auditoria.registrar("USUARIO_EXCLUIDO", "Usuario id " + id);
        ra.addFlashAttribute("msg", "Usuario excluido.");
        return "redirect:/usuarios";
    }

    /**
     * Tela de troca da PROPRIA senha, disponivel para qualquer usuario logado
     * (ADMIN/OPERADOR/AVALIADOR) - diferente da edicao em /usuarios/{id}/editar,
     * que e exclusiva do ADMIN. A rota /usuarios/minha-senha e liberada para
     * autenticados no SecurityConfig, antes da regra geral /usuarios/** (ADMIN).
     */
    @GetMapping("/minha-senha")
    public String minhaSenha() {
        return "usuarios/minha-senha";
    }

    @PostMapping("/minha-senha")
    public String trocarMinhaSenha(java.security.Principal principal,
                                   @RequestParam String senhaAtual,
                                   @RequestParam String novaSenha,
                                   @RequestParam String confirmacao,
                                   RedirectAttributes ra) {
        try {
            service.alterarPropriaSenha(principal.getName(), senhaAtual, novaSenha, confirmacao);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios/minha-senha";
        }
        auditoria.registrar("SENHA_ALTERADA", "Usuario " + principal.getName());
        ra.addFlashAttribute("msg", "Senha alterada com sucesso.");
        return "redirect:/usuarios/minha-senha";
    }

    @GetMapping("/esqueci-senha")
    public String esqueciSenha() {
        return "usuarios/esqueci-senha";
    }

    /**
     * Sempre exibe a mesma mensagem neutra, exista ou nao o usuario e tenha
     * ou nao e-mail cadastrado - evita que a tela seja usada para descobrir
     * quais logins sao validos (enumeracao de usuarios).
     */
    @PostMapping("/esqueci-senha")
    public String redefinirSenha(@RequestParam String username, Model model) {
        service.resetarSenha(username);
        auditoria.registrar("SENHA_RESET_SOLICITADO", "Usuario " + username);
        model.addAttribute("sucesso", true);
        model.addAttribute("msgRedefinicao",
            "Se o login existir e tiver e-mail cadastrado, enviamos as instrucoes "
            + "de redefinicao para o e-mail cadastrado. Caso nao tenha e-mail "
            + "cadastrado, procure o administrador do sistema.");
        return "usuarios/esqueci-senha";
    }
}
