package br.gov.saude.sgpur.domain;

/**
 * Indica como o voto do medico avaliador foi registrado no sistema.
 *
 * <ul>
 *   <li>{@code OPERADOR_EMAIL} - o operador da secretaria lancou o resultado
 *       em nome do medico, apos receber a resposta por e-mail. Exige o anexo
 *       {@code TipoAnexo.RESPOSTA_AVALIADOR} como comprovante.</li>
 *   <li>{@code AVALIADOR_SISTEMA} - o proprio medico se autenticou no Portal
 *       do Avaliador (/avaliador) e registrou o voto diretamente. O registro
 *       autenticado (usuario + dataHoraVoto + IP no log de auditoria) substitui
 *       o anexo comprobatorio.</li>
 * </ul>
 *
 * Pareceres antigos com {@code origem = null} sao tratados como
 * {@code OPERADOR_EMAIL} (continuam exigindo o anexo).
 */
public enum OrigemParecer {

    OPERADOR_EMAIL("Lancado pelo operador via e-mail"),
    AVALIADOR_SISTEMA("Voto direto do avaliador autenticado");

    private final String descricao;

    OrigemParecer(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
