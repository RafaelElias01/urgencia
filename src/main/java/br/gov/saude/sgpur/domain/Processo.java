package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Processo de Urgencia Renal. Corresponde a uma linha da aba "CTEstadual"
 * da planilha original, acrescido dos campos administrativos exigidos no
 * documento de orientacoes (motivo/oficio de indeferimento, datas, anexos).
 */
@Entity
@Table(name = "processo")
public class Processo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Numero do processo no formato NN/AAAA. Ex.: 01/2026. */
    @NotBlank
    @Column(nullable = false, unique = true, length = 12)
    private String numero;

    @Column(nullable = false)
    private Integer ano;

    @Column(nullable = false)
    private Integer sequencial;

    // ----- Receptor (paciente) -----
    @NotBlank
    @Column(name = "paciente_nome", nullable = false, length = 200)
    private String pacienteNome;

    /** Registro RGCT / SNT do paciente. Obrigatorio via @NotBlank (validacao de formulario). */
    @NotBlank
    @Column(name = "paciente_rgct", length = 60)
    private String pacienteRgct;

    // ----- Equipe solicitante -----
    @NotBlank
    @Column(name = "solicitante_equipe", nullable = false, length = 200)
    private String solicitanteEquipe;

    @NotBlank
    @Email
    @Column(name = "solicitante_email", length = 150)
    private String solicitanteEmail;

    @NotNull
    @Column(name = "data_situacao_especial", nullable = false)
    private LocalDate dataSituacaoEspecial;

    // ----- Situacao / decisao -----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StatusProcesso status = StatusProcesso.SOLICITADO;

    @Column(name = "email_enviado_solicitante", nullable = false)
    private boolean emailEnviadoSolicitante = false;

    @Column(columnDefinition = "TEXT")
    private String observacoes;

    // ----- Indeferimento (obrigatorio quando INDEFERIDO) -----
    @Column(name = "motivo_indeferimento", columnDefinition = "TEXT")
    private String motivoIndeferimento;

    @Column(name = "data_emissao_oficio")
    private LocalDate dataEmissaoOficio;

    @Column(name = "data_envio_oficio")
    private LocalDate dataEnvioOficio;

    // ----- Auditoria -----
    @Column(name = "data_cadastro", nullable = false)
    private LocalDateTime dataCadastro = LocalDateTime.now();

    @Column(name = "data_decisao")
    private LocalDateTime dataDecisao;

    @OneToMany(mappedBy = "processo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Parecer> pareceres = new ArrayList<>();

    @OneToMany(mappedBy = "processo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dataUpload ASC")
    private List<Anexo> anexos = new ArrayList<>();

    /**
     * Controle de concorrencia otimista: o Hibernate incrementa a cada UPDATE e
     * detecta escritas concorrentes conflitantes (OptimisticLockException) em
     * vez de deixar "o ultimo que salva ganha" silenciosamente.
     */
    @Version
    @Column(name = "versao")
    private Long versao;

    public Processo() {
    }

    /**
     * Identificacao padrao do processo para exibicao em telas, PDFs e nomes
     * de pasta. Formato: "NN/AAAA - Nome do Paciente - RGCT XXXXXXXXX".
     * Quando o RGCT ainda nao foi preenchido, omite a parte do RGCT.
     */
    public String identificacao() {
        StringBuilder sb = new StringBuilder();
        sb.append(numero != null ? numero : "?/?");
        if (pacienteNome != null && !pacienteNome.isBlank()) {
            sb.append(" - ").append(pacienteNome);
        }
        if (pacienteRgct != null && !pacienteRgct.isBlank()) {
            sb.append(" - RGCT ").append(pacienteRgct);
        }
        return sb.toString();
    }

    public void addParecer(Parecer parecer) {
        parecer.setProcesso(this);
        this.pareceres.add(parecer);
    }

    public void addAnexo(Anexo anexo) {
        anexo.setProcesso(this);
        this.anexos.add(anexo);
    }

    // ----- getters / setters -----
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNumero() {
        return numero;
    }

    public void setNumero(String numero) {
        this.numero = numero;
    }

    public Integer getAno() {
        return ano;
    }

    public void setAno(Integer ano) {
        this.ano = ano;
    }

    public Integer getSequencial() {
        return sequencial;
    }

    public void setSequencial(Integer sequencial) {
        this.sequencial = sequencial;
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

    public StatusProcesso getStatus() {
        return status;
    }

    public void setStatus(StatusProcesso status) {
        this.status = status;
    }

    public boolean isEmailEnviadoSolicitante() {
        return emailEnviadoSolicitante;
    }

    public void setEmailEnviadoSolicitante(boolean emailEnviadoSolicitante) {
        this.emailEnviadoSolicitante = emailEnviadoSolicitante;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public void setObservacoes(String observacoes) {
        this.observacoes = observacoes;
    }

    public String getMotivoIndeferimento() {
        return motivoIndeferimento;
    }

    public void setMotivoIndeferimento(String motivoIndeferimento) {
        this.motivoIndeferimento = motivoIndeferimento;
    }

    public LocalDate getDataEmissaoOficio() {
        return dataEmissaoOficio;
    }

    public void setDataEmissaoOficio(LocalDate dataEmissaoOficio) {
        this.dataEmissaoOficio = dataEmissaoOficio;
    }

    public LocalDate getDataEnvioOficio() {
        return dataEnvioOficio;
    }

    public void setDataEnvioOficio(LocalDate dataEnvioOficio) {
        this.dataEnvioOficio = dataEnvioOficio;
    }

    public LocalDateTime getDataCadastro() {
        return dataCadastro;
    }

    public void setDataCadastro(LocalDateTime dataCadastro) {
        this.dataCadastro = dataCadastro;
    }

    public LocalDateTime getDataDecisao() {
        return dataDecisao;
    }

    public void setDataDecisao(LocalDateTime dataDecisao) {
        this.dataDecisao = dataDecisao;
    }

    public List<Parecer> getPareceres() {
        return pareceres;
    }

    public void setPareceres(List<Parecer> pareceres) {
        this.pareceres = pareceres;
    }

    public List<Anexo> getAnexos() {
        return anexos;
    }

    public void setAnexos(List<Anexo> anexos) {
        this.anexos = anexos;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}
