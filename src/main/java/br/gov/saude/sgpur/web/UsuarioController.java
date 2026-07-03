package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
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
    public String atualizar(@PathVariable Long id, @ModelAttribute("usuario") Usuario form,
                            @RequestParam(required = false) String senha,
                            @RequestParam(required = false) Long membroId,
                            RedirectAttributes ra) {
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
    public String alternarAtivo(@PathVariable Long id, RedirectAttributes ra) {
        service.alternarAtivo(id);
        ra.addFlashAttribute("msg", "Situacao do usuario atualizada.");
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id, RedirectAttributes ra) {
        service.excluir(id);
        auditoria.registrar("USUARIO_EXCLUIDO", "Usuario id " + id);
        ra.addFlashAttribute("msg", "Usuario excluido.");
        return "redirect:/usuarios";
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
