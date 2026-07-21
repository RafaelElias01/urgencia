package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Parecer de um membro da Urgencia Renal sobre um processo.
 *
 * Regra de negocio: todos os membros ativos avaliam o processo, EXCETO
 * quando o membro for o proprio solicitante daquele processo (conflito de
 * interesse) - nesse caso "impedido = true" e nao ha resultado.
 */
@Entity
@Table(
    name = "parecer",
    uniqueConstraints = @UniqueConstraint(columnNames = {"processo_id", "membro_id"})
)
public class Parecer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "processo_id", nullable = false)
    private Processo processo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "membro_id", nullable = false)
    private MembroUrgenciaRenal membro;

    /** Resultado do parecer; nulo enquanto o membro nao respondeu. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ResultadoParecer resultado;

    /** Membro impedido por ser o solicitante do processo (conflito). */
    @Column(nullable = false)
    private boolean impedido = false;

    @Column(name = "data_envio")
    private LocalDate dataEnvio;

    @Column(name = "data_resposta")
    private LocalDate dataResposta;

    /**
     * Como o voto foi registrado: pelo operador (via e-mail) ou pelo proprio
     * avaliador autenticado no portal. Nulo equivale a OPERADOR_EMAIL (legado).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OrigemParecer origem;

    /** Data e hora exatos do voto (preenchido pelo portal do avaliador). */
    @Column(name = "data_hora_voto")
    private LocalDateTime dataHoraVoto;

    /**
     * Username de quem registrou o voto (para nao-repudio). Operador que lancou
     * o resultado em nome do medico, ou o proprio medico autenticado.
     */
    @Column(name = "votado_por", length = 120)
    private String votadoPor;

    /**
     * Justificativa / observacoes clinicas que o avaliador digitou ao votar no
     * portal. Material INTERNO do operador para subsidiar a decisao — NUNCA e
     * exibida a outros avaliadores (imparcialidade do julgamento). Nula quando
     * o medico nao escreveu nada.
     */
    @Column(name = "justificativa", columnDefinition = "TEXT")
    private String justificativa;

    public Parecer() {
    }

    public Parecer(MembroUrgenciaRenal membro) {
        this.membro = membro;
    }

    @Transient
    public boolean isRespondido() {
        return resultado != null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Processo getProcesso() {
        return processo;
    }

    public void setProcesso(Processo processo) {
        this.processo = processo;
    }

    public MembroUrgenciaRenal getMembro() {
        return membro;
    }

    public void setMembro(MembroUrgenciaRenal membro) {
        this.membro = membro;
    }

    public ResultadoParecer getResultado() {
        return resultado;
    }

    public void setResultado(ResultadoParecer resultado) {
        this.resultado = resultado;
    }

    public boolean isImpedido() {
        return impedido;
    }

    public void setImpedido(boolean impedido) {
        this.impedido = impedido;
    }

    public LocalDate getDataEnvio() {
        return dataEnvio;
    }

    public void setDataEnvio(LocalDate dataEnvio) {
        this.dataEnvio = dataEnvio;
    }

    public LocalDate getDataResposta() {
        return dataResposta;
    }

    public void setDataResposta(LocalDate dataResposta) {
        this.dataResposta = dataResposta;
    }

    public OrigemParecer getOrigem() {
        return origem;
    }

    public void setOrigem(OrigemParecer origem) {
        this.origem = origem;
    }

    public LocalDateTime getDataHoraVoto() {
        return dataHoraVoto;
    }

    public void setDataHoraVoto(LocalDateTime dataHoraVoto) {
        this.dataHoraVoto = dataHoraVoto;
    }

    public String getVotadoPor() {
        return votadoPor;
    }

    public void setVotadoPor(String votadoPor) {
        this.votadoPor = votadoPor;
    }

    public String getJustificativa() {
        return justificativa;
    }

    public void setJustificativa(String justificativa) {
        this.justificativa = justificativa;
    }
}
