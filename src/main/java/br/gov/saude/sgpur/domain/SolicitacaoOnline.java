package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pedido de urgencia renal enviado pelo Portal do Solicitante (modulo
 * experimental), ANTES de virar um {@link Processo} de verdade.
 *
 * Deliberadamente desacoplada de {@code Processo}: nao compartilha a mesma
 * tabela nem o mesmo ciclo de vida. O operador faz a triagem e "converte"
 * este registro num {@code Processo} chamando o fluxo de cadastro normal
 * ({@code ProcessoService.cadastrar}), que continua escolhendo os 3 medicos
 * avaliadores e atribuindo o numero oficial - nada aqui contorna essas
 * regras. Ver docs/PLANO-SOLICITANTE.md.
 */
@Entity
@Table(name = "solicitacao_online")
public class SolicitacaoOnline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Usuario (perfil SOLICITANTE) que enviou o pedido. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_solicitante_id", nullable = false)
    private Usuario usuarioSolicitante;

    @NotBlank
    @Size(max = 200, message = "Nome do paciente muito longo (maximo 200 caracteres).")
    @Column(name = "paciente_nome", nullable = false, length = 200)
    private String pacienteNome;

    @NotBlank
    @Size(max = 60, message = "Registro RGCT/SNT muito longo (maximo 60 caracteres).")
    @Column(name = "paciente_rgct", length = 60)
    private String pacienteRgct;

    @NotBlank
    @Size(max = 200, message = "Nome da equipe solicitante muito longo (maximo 200 caracteres).")
    @Column(name = "solicitante_equipe", nullable = false, length = 200)
    private String solicitanteEquipe;

    @NotBlank
    @Email
    @Size(max = 150, message = "E-mail do solicitante muito longo (maximo 150 caracteres).")
    @Column(name = "solicitante_email", length = 150)
    private String solicitanteEmail;

    @NotNull
    @Column(name = "data_situacao_especial", nullable = false)
    private LocalDate dataSituacaoEspecial;

    /**
     * Justificativa clinica do pedido, escrita pelo proprio solicitante.
     * No fluxo por e-mail essa informacao vinha implicita no corpo do
     * e-mail/anexos; aqui precisa de campo proprio.
     */
    @NotBlank
    @Column(name = "justificativa_clinica", columnDefinition = "TEXT", nullable = false)
    private String justificativaClinica;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusSolicitacaoOnline status = StatusSolicitacaoOnline.ENVIADA;

    @Column(name = "data_envio", nullable = false)
    private LocalDateTime dataEnvio = LocalDateTime.now();

    /** Preenchido apenas quando status == CONVERTIDA. Link de rastreabilidade/auditoria. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processo_gerado_id")
    private Processo processoGerado;

    /** Nota do operador ao devolver o pedido para correcao (status == DEVOLVIDA). */
    @Column(name = "observacoes_triagem", columnDefinition = "TEXT")
    private String observacoesTriagem;

    @OneToMany(mappedBy = "solicitacaoOnline", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<AnexoSolicitacaoOnline> anexos = new ArrayList<>();

    @Version
    @Column(name = "versao")
    private Long versao;

    public SolicitacaoOnline() {
    }

    public void addAnexo(AnexoSolicitacaoOnline anexo) {
        anexo.setSolicitacaoOnline(this);
        this.anexos.add(anexo);
    }

    /** Identificacao curta para telas/logs: "Nome do paciente - RGCT XXXXXXXXX". */
    public String identificacao() {
        StringBuilder sb = new StringBuilder(pacienteNome != null ? pacienteNome : "?");
        if (pacienteRgct != null && !pacienteRgct.isBlank()) {
            sb.append(" - RGCT ").append(pacienteRgct);
        }
        return sb.toString();
    }

    // ----- getters / setters -----

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Usuario getUsuarioSolicitante() {
        return usuarioSolicitante;
    }

    public void setUsuarioSolicitante(Usuario usuarioSolicitante) {
        this.usuarioSolicitante = usuarioSolicitante;
    }

    public String getPacienteNome() {
        return pacienteNome;
    }

    public void setPacienteNome(String pacienteNome) {
        this.pacienteNome = pacienteNome;
    }

    public String getPacienteRgct() {
        return pacienteRgct;
    }

    public void setPacienteRgct(String pacienteRgct) {
        this.pacienteRgct = pacienteRgct;
    }

    public String getSolicitanteEquipe() {
        return solicitanteEquipe;
    }

    public void setSolicitanteEquipe(String solicitanteEquipe) {
        this.solicitanteEquipe = solicitanteEquipe;
    }

    public String getSolicitanteEmail() {
        return solicitanteEmail;
    }

    public void setSolicitanteEmail(String solicitanteEmail) {
        this.solicitanteEmail = solicitanteEmail;
    }

    public LocalDate getDataSituacaoEspecial() {
        return dataSituacaoEspecial;
    }

    public void setDataSituacaoEspecial(LocalDate dataSituacaoEspecial) {
        this.dataSituacaoEspecial = dataSituacaoEspecial;
    }

    public String getJustificativaClinica() {
        return justificativaClinica;
    }

    public void setJustificativaClinica(String justificativaClinica) {
        this.justificativaClinica = justificativaClinica;
    }

    public StatusSolicitacaoOnline getStatus() {
        return status;
    }

    public void setStatus(StatusSolicitacaoOnline status) {
        this.status = status;
    }

    public LocalDateTime getDataEnvio() {
        return dataEnvio;
    }

    public void setDataEnvio(LocalDateTime dataEnvio) {
        this.dataEnvio = dataEnvio;
    }

    public Processo getProcessoGerado() {
        return processoGerado;
    }

    public void setProcessoGerado(Processo processoGerado) {
        this.processoGerado = processoGerado;
    }

    public String getObservacoesTriagem() {
        return observacoesTriagem;
    }

    public void setObservacoesTriagem(String observacoesTriagem) {
        this.observacoesTriagem = observacoesTriagem;
    }

    public List<AnexoSolicitacaoOnline> getAnexos() {
        return anexos;
    }

    public void setAnexos(List<AnexoSolicitacaoOnline> anexos) {
        this.anexos = anexos;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}
