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

    // Nullable: MensagemSolicitacaoService.apagar() zera o texto ao apagar
    // (soft delete visivel via 'deletada') - antes disso a coluna era
    // NOT NULL e apagar mensagem quebrava com constraint violation (bug real
    // achado em 2026-07-28 rodando o fluxo AJAX contra um H2 de verdade; os
    // testes usam mock e nunca bateram nessa constraint).
    @Column(name = "texto", columnDefinition = "TEXT")
    private String texto;

    @Column(name = "data_envio", nullable = false)
    private LocalDateTime dataEnvio;

    @Column(name = "lida", nullable = false)
    private boolean lida;

    @Column(name = "deletada", nullable = false)
    private boolean deletada;

    @Column(name = "deletada_em")
    private LocalDateTime deletadaEm;

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

    public boolean isDeletada() {
        return deletada;
    }

    public void setDeletada(boolean deletada) {
        this.deletada = deletada;
    }

    public LocalDateTime getDeletadaEm() {
        return deletadaEm;
    }

    public void setDeletadaEm(LocalDateTime deletadaEm) {
        this.deletadaEm = deletadaEm;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}
