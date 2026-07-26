package br.gov.saude.sgpur.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Fallback para /solicitante quando o modulo (SolicitanteController) esta
 * desligado ({@code app.solicitante.habilitado=false}). Sem isso, um usuario
 * com perfil SOLICITANTE cadastrado (ex.: criado antes do time decidir ligar
 * o modulo) caia num 404 cru ao logar - e como ele nao tem acesso a nenhuma
 * outra rota (o "/" e restrito a ADMIN/OPERADOR), ficava sem nenhum lugar
 * navegavel no sistema (bug real encontrado em producao em 2026-07-26).
 * Ativo exatamente quando o SolicitanteController NAO esta (condicao
 * espelhada/negada), para nunca haver ambiguidade de mapeamento no mesmo
 * path.
 */
@Controller
@RequestMapping("/solicitante")
@ConditionalOnExpression("!${app.solicitante.habilitado:false}")
public class SolicitanteIndisponivelController {

    @GetMapping({"", "/**"})
    public String indisponivel() {
        return "solicitante/indisponivel";
    }
}
