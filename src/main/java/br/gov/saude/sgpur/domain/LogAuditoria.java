package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Registro de auditoria de uma acao relevante no sistema (quem, o que, quando).
 * Apoia o requisito de o processo ser auditavel.
 */
@Entity
@Table(name = "log_auditoria", indexes = @Index(name = "idx_log_data", columnList = "data_hora"))
public class LogAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data_hora", nullable = false)
    private LocalDateTime dataHora = LocalDateTime.now();

    /** Login do usuario que executou a acao (ou "sistema"). */
    @Column(nullable = false, length = 60)
    private String usuario;

    /** Codigo da acao. Ex.: PROCESSO_CADASTRADO, PROCESSO_DECIDIDO. */
    @Column(nullable = false, length = 40)
    private String acao;

    @Column(length = 400)
    private String detalhe;

    /**
     * Endereco IP do cliente que executou a acao (nullable — nem toda acao
     * tem IP disponivel, ex.: tarefas internas do sistema). Comporta IPv6 (45 chars).
     * Usado para nao-repudio no voto autenticado do Portal do Avaliador.
     */
    @Column(length = 45)
    private String ip;

    public LogAuditoria() {
    }

    public LogAuditoria(String usuario, String acao, String detalhe) {
        this.usuario = usuario;
        this.acao = acao;
        this.detalhe = detalhe;
    }

    public LogAuditoria(String usuario, String acao, String detalhe, String ip) {
        this.usuario = usuario;
        this.acao = acao;
        this.detalhe = detalhe;
        this.ip = ip;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getDataHora() {
        return dataHora;
    }

    public void setDataHora(LocalDateTime dataHora) {
        this.dataHora = dataHora;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getAcao() {
        return acao;
    }

    public void setAcao(String acao) {
        this.acao = acao;
    }

    public String getDetalhe() {
        return detalhe;
    }

    public void setDetalhe(String detalhe) {
        this.detalhe = detalhe;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }
}
