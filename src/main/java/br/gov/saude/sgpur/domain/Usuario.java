package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Usuario do sistema (servidor que opera o SAUR). Substitui o usuario
 * administrador em memoria - agora persistido no banco.
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 60)
    private String username;

    /** Senha codificada (BCrypt). Definida pelo UsuarioService, nao pelo bind do form. */
    @Column(nullable = false, length = 100)
    private String senha;

    @NotBlank
    @Column(nullable = false, length = 120)
    private String nome;

    /**
     * E-mail do usuario, usado para enviar a nova senha no fluxo "esqueci
     * minha senha". Obrigatorio no cadastro/edicao via UI (validado no
     * UsuarioController, como a senha), mas SEM @NotBlank na entidade: o admin
     * inicial do AdminBootstrap e usuarios legados/seed podem nao ter e-mail, e
     * a validacao de bean no persist quebraria o boot. Aqui so validamos o
     * formato quando preenchido.
     */
    @Email
    @Column(length = 150)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Perfil perfil = Perfil.OPERADOR;

    @Column(nullable = false)
    private boolean ativo = true;

    /**
     * Membro da Urgencia Renal vinculado a este usuario (nullable).
     * Preenchido apenas para perfil AVALIADOR: identifica qual medico este
     * login representa, para filtrar os processos do portal do avaliador.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membro_id")
    private MembroUrgenciaRenal membro;

    /**
     * Nome da equipe/hospital solicitante que este usuario representa.
     * Preenchido apenas para perfil SOLICITANTE (mesmo padrao condicional de
     * {@code membro} para AVALIADOR): identifica com qual equipe pre-preencher
     * o formulario de nova solicitacao online, sem deixar o proprio usuario
     * declarar outra equipe no formulario (o campo vem travado/somente-leitura
     * na tela, a partir daqui).
     */
    @Column(name = "equipe_solicitante", length = 200)
    private String equipeSolicitante;

    /**
     * Controle de concorrencia otimista: o Hibernate incrementa a cada UPDATE e
     * detecta escritas concorrentes conflitantes (OptimisticLockException) em
     * vez de deixar "o ultimo que salva ganha" silenciosamente - ex.: o ADMIN
     * editando o perfil/vinculo de um usuario enquanto o proprio usuario troca
     * a senha em /usuarios/minha-senha.
     *
     * <p>Como o {@code Processo.versao}, {@code Parecer.versao} e o
     * {@code MembroUrgenciaRenal.versao}, adicionar esta coluna numa tabela ja
     * populada exige <b>backfill manual em prod</b> logo apos o deploy
     * (nao ha Flyway/Liquibase neste projeto e o {@code ddl-auto: update} cria
     * a coluna com NULL nas linhas antigas, quebrando o proximo UPDATE nelas):
     * {@code UPDATE usuario SET versao = 0 WHERE versao IS NULL;}
     * Ver CLAUDE.md, "Convencoes de codigo".
     */
    @Version
    @Column(name = "versao")
    private Long versao;

    public Usuario() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSenha() {
        return senha;
    }

    public void setSenha(String senha) {
        this.senha = senha;
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

    public Perfil getPerfil() {
        return perfil;
    }

    public void setPerfil(Perfil perfil) {
        this.perfil = perfil;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public MembroUrgenciaRenal getMembro() {
        return membro;
    }

    public void setMembro(MembroUrgenciaRenal membro) {
        this.membro = membro;
    }

    public String getEquipeSolicitante() {
        return equipeSolicitante;
    }

    public void setEquipeSolicitante(String equipeSolicitante) {
        this.equipeSolicitante = equipeSolicitante;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}
