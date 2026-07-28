package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "mensagem_solicitacao")
public class MensagemSolicitacao {

    public enum RemetenteMensagem {
        SOLICITANTE, OPERADOR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solicitacao_online_id", nullable = false)
    private SolicitacaoOnline solicitacaoOnline;

    @Enumerated(EnumType.STRING)
    @Column(name = "remetente", nullable = false, length = 20)
    private RemetenteMensagem remetente;

    @Column(name = "remetente_id", nullable = false)
    private Long remetenteId;

    @Column(name = "texto", columnDefinition = "TEXT", nullable = false)
    private String texto;

    @Column(name = "data_envio", nullable = false)
    private LocalDateTime dataEnvio;

    @Column(name = "lida", nullable = false)
    private boolean lida;

    @Version
    private Long versao;

    public MensagemSolicitacao() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SolicitacaoOnline getSolicitacaoOnline() {
        return solicitacaoOnline;
    }

    public void setSolicitacaoOnline(SolicitacaoOnline solicitacaoOnline) {
        this.solicitacaoOnline = solicitacaoOnline;
    }

    public RemetenteMensagem getRemetente() {
        return remetente;
    }

    public void setRemetente(RemetenteMensagem remetente) {
        this.remetente = remetente;
    }

    public Long getRemetenteId() {
        return remetenteId;
    }

    public void setRemetenteId(Long remetenteId) {
        this.remetenteId = remetenteId;
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }

    public LocalDateTime getDataEnvio() {
        return dataEnvio;
    }

    public void setDataEnvio(LocalDateTime dataEnvio) {
        this.dataEnvio = dataEnvio;
    }

    public boolean isLida() {
        return lida;
    }

    public void setLida(boolean lida) {
        this.lida = lida;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}
