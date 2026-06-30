package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Membro avaliador da equipe de Urgencia Renal.
 * Ex.: "HCPA - Veronica Horbe". Cadastro gerenciavel (pode ser inativado).
 */
@Entity
@Table(name = "membro_urgencia_renal")
public class MembroUrgenciaRenal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sigla da instituicao do membro. Ex.: HCPA, ISCMPA, CET. */
    @NotBlank
    @Column(nullable = false, length = 30)
    private String instituicao;

    /** Nome do medico avaliador. */
    @NotBlank
    @Column(nullable = false, length = 150)
    private String nome;

    @Email
    @Column(length = 150)
    private String email;

    /** Membros inativos nao recebem novos processos para parecer. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("true")
    private boolean ativo = true;

    /** Coordenador CET-RS: seu voto favoravel DEFERE o processo independentemente dos demais pareceres. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean coordenador = false;

    public MembroUrgenciaRenal() {
    }

    public MembroUrgenciaRenal(String instituicao, String nome, String email) {
        this.instituicao = instituicao;
        this.nome = nome;
        this.email = email;
    }

    /** Rotulo de exibicao no formato "INSTITUICAO - Nome". */
    @Transient
    public String getRotulo() {
        return instituicao + " - " + nome;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInstituicao() {
        return instituicao;
    }

    public void setInstituicao(String instituicao) {
        this.instituicao = instituicao;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public boolean isCoordenador() {
        return coordenador;
    }

    public void setCoordenador(boolean coordenador) {
        this.coordenador = coordenador;
    }
}
