package br.gov.saude.sgpur.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limit para o "esqueci minha senha" ({@code POST /usuarios/esqueci-senha}),
 * rota publica sem login. Sem limite, qualquer pessoa que conheca um login valido
 * poderia bombear o reset repetidamente: cada chamada gera uma senha nova (derruba
 * a sessao/senha atual do usuario legitimo) e dispara um e-mail (spam).
 *
 * <p>Mesma ideia/janela deslizante em memoria do {@link LoginAttemptService}, mas
 * como classe dedicada porque a semantica e diferente: aqui contamos TODAS as
 * tentativas de reset para o username (nao so falhas de autenticacao), e o
 * bloqueio expira sozinho ao fim da janela (nao exige um "sucesso" para resetar
 * a contagem, ja que resetarSenha() sempre "sucede" do ponto de vista do
 * chamador anonimo). Estado em memoria (nao persistido): reinicia a cada
 * restart - aceitavel para o volume deste sistema (poucos usuarios, uso interno).
 */
@Service
public class PasswordResetAttemptService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetAttemptService.class);

    private static final int MAX_TENTATIVAS = 3;
    private static final Duration JANELA = Duration.ofMinutes(15);

    private record Estado(int tentativas, Instant inicioJanela) {}

    private final ConcurrentHashMap<String, Estado> tentativasPorUsuario = new ConcurrentHashMap<>();

    /**
     * Registra mais uma tentativa de reset para o username e informa se ela e
     * permitida. Quando o limite ja foi atingido dentro da janela atual, a
     * tentativa NAO e contada novamente (fica travada ate a janela expirar) e o
     * metodo retorna false - o chamador deve entao recusar silenciosamente
     * (nao resetar a senha, nao enviar e-mail), sem revelar o motivo.
     */
    public boolean tentarRegistrar(String username) {
        String chave = chave(username);
        Instant agora = Instant.now();
        boolean[] permitido = new boolean[1];
        tentativasPorUsuario.compute(chave, (key, atual) -> {
            if (atual == null || agora.isAfter(atual.inicioJanela().plus(JANELA))) {
                permitido[0] = true;
                return new Estado(1, agora);
            }
            if (atual.tentativas() >= MAX_TENTATIVAS) {
                permitido[0] = false;
                return atual;
            }
            permitido[0] = true;
            return new Estado(atual.tentativas() + 1, atual.inicioJanela());
        });
        if (!permitido[0]) {
            log.warn("PasswordResetAttemptService: reset de senha bloqueado por excesso de "
                + "tentativas para '{}' (limite de {} a cada {} min).",
                username, MAX_TENTATIVAS, JANELA.toMinutes());
        }
        return permitido[0];
    }

    private String chave(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }
}
