/**
 * Chat da SolicitacaoOnline (portal do solicitante, triagem do operador e
 * detalhe do processo) - poll AJAX periodico + envio/apagar sem recarregar
 * a pagina inteira. Antes de 2026-07-28 cada envio de mensagem era um
 * <form method="post"> classico (POST + redirect + GET da pagina inteira) e
 * so era possivel ver mensagem nova recarregando manualmente; ver CLAUDE.md
 * para o diagnostico completo.
 */
function iniciarChatSolicitacao(cfg) {
    var chatBox = document.querySelector(cfg.chatBoxSelector);
    var form = document.querySelector(cfg.formSelector);
    var input = form ? form.querySelector(cfg.inputSelector) : null;
    var emptyMsg = cfg.emptySelector ? document.querySelector(cfg.emptySelector) : null;
    var badgeTotal = cfg.badgeTotalSelector ? document.querySelector(cfg.badgeTotalSelector) : null;
    var badgeNaoLida = cfg.badgeNaoLidaSelector ? document.querySelector(cfg.badgeNaoLidaSelector) : null;

    var csrfMeta = document.querySelector('meta[name="_csrf"]');
    var csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
    var csrfToken = csrfMeta ? csrfMeta.content : null;
    var csrfHeader = csrfHeaderMeta ? csrfHeaderMeta.content : null;

    var idsRecebidosConhecidos = null; // null ate o 1o poll (evita notificar do que ja estava na tela)
    var pollAtivo = true;

    function escapeHtml(s) {
        var d = document.createElement('div');
        d.textContent = s == null ? '' : s;
        return d.innerHTML;
    }

    function estaProximoDoFim() {
        if (!chatBox) return true;
        return (chatBox.scrollHeight - chatBox.clientHeight - chatBox.scrollTop) < 80;
    }

    function aplicarTimestampsRelativos() {
        (chatBox ? chatBox.querySelectorAll('.ts-relative') : []).forEach(function (el) {
            var data = el.getAttribute('data-ts');
            if (!data) return;
            var partes = data.split(/[-\s:]/);
            var d = new Date(partes[0], partes[1] - 1, partes[2], partes[3], partes[4], partes[5]);
            var diff = Math.floor((Date.now() - d.getTime()) / 1000);
            var texto;
            if (diff < 60) texto = 'agora';
            else if (diff < 3600) texto = Math.floor(diff / 60) + ' min atras';
            else if (diff < 86400) texto = Math.floor(diff / 3600) + ' h atras';
            else if (diff < 172800) texto = 'ontem';
            else texto = el.getAttribute('data-formatada') || el.textContent;
            el.textContent = texto;
        });
    }

    function renderMensagem(msg) {
        var alinhamento = msg.deVoce ? 'justify-content-end' : 'justify-content-start';
        var corpo;
        if (msg.deletada) {
            var txt = msg.deVoce ? 'Voce apagou esta mensagem.' : ('Mensagem apagada ' + cfg.labelApagadaOutro + '.');
            corpo = '<div class="rounded-3 px-3 py-2 small text-muted fst-italic border" style="max-width:80%;">'
                + escapeHtml(txt) + '</div>';
        } else {
            var bolha = msg.deVoce ? 'bg-primary text-white' : 'bg-light border';
            var nomeCls = msg.deVoce ? '' : 'text-muted';
            var tsCls = msg.deVoce ? 'text-white-50' : 'text-muted';
            var apagarBtn = '';
            if (msg.podeApagar) {
                apagarBtn = '<button type="button" class="btn btn-sm p-0 border-0 bg-transparent text-danger btn-apagar-msg-chat" '
                    + 'data-id="' + msg.id + '" title="Apagar mensagem" aria-label="Apagar mensagem">'
                    + '<i class="bi bi-trash"></i></button>';
            }
            var check = '';
            if (msg.deVoce) {
                check = msg.lida
                    ? '<i class="bi bi-check-all" style="font-size:.75rem;"></i>'
                    : '<i class="bi bi-check" style="font-size:.75rem;"></i>';
            }
            corpo = '<div class="rounded-3 px-3 py-2 position-relative ' + bolha + '" style="max-width:80%;">'
                + '<div class="d-flex justify-content-between align-items-start gap-2">'
                + '<div class="small fw-semibold ' + nomeCls + '">' + escapeHtml(msg.nomeRemetente) + '</div>'
                + apagarBtn
                + '</div>'
                + '<div class="small" style="white-space: pre-wrap;">' + escapeHtml(msg.texto) + '</div>'
                + '<div class="small opacity-75 mt-1 d-flex align-items-center gap-1 ' + tsCls + '">'
                + '<span class="ts-relative" data-ts="' + msg.dataEnvioIso + '" data-formatada="' + escapeHtml(msg.dataEnvioFormatada) + '">'
                + escapeHtml(msg.dataEnvioFormatada) + '</span>'
                + check
                + '</div>'
                + '</div>';
        }
        return '<div class="mb-2 d-flex ' + alinhamento + '" data-msg-id="' + msg.id + '">' + corpo + '</div>';
    }

    function atualizarBadges(mensagens) {
        if (badgeTotal) {
            if (mensagens.length) {
                badgeTotal.textContent = mensagens.length + ' total';
                badgeTotal.classList.remove('d-none');
            } else {
                badgeTotal.classList.add('d-none');
            }
        }
        // O poll ja marca como lida no servidor - o badge de "nao lida" so
        // faz sentido no 1o carregamento (renderizado pelo servidor).
        if (badgeNaoLida) badgeNaoLida.classList.add('d-none');
    }

    function atualizarTela(mensagens) {
        var seguirFim = estaProximoDoFim();
        if (emptyMsg) emptyMsg.classList.toggle('d-none', mensagens.length > 0);
        if (chatBox) {
            chatBox.classList.toggle('d-none', mensagens.length === 0);
            chatBox.innerHTML = mensagens.map(renderMensagem).join('');
            aplicarTimestampsRelativos();
            if (seguirFim) chatBox.scrollTop = chatBox.scrollHeight;
        }
        atualizarBadges(mensagens);
    }

    function detectarNovasMensagens(mensagens) {
        var idsDoOutroLado = mensagens.filter(function (m) { return !m.deVoce && !m.deletada; })
            .map(function (m) { return m.id; });
        if (idsRecebidosConhecidos !== null) {
            var novas = idsDoOutroLado.filter(function (id) { return idsRecebidosConhecidos.indexOf(id) === -1; });
            if (novas.length > 0) {
                if (typeof tocarNotificacao === 'function') tocarNotificacao();
                if (typeof mostrarToast === 'function') mostrarToast(cfg.notifMensagem, 'info');
            }
        }
        idsRecebidosConhecidos = idsDoOutroLado;
    }

    function poll() {
        if (!pollAtivo) return;
        fetch(cfg.pollUrl, {headers: {'Accept': 'application/json'}, credentials: 'same-origin'})
            .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); })
            .then(function (data) {
                var mensagens = data.mensagens || [];
                detectarNovasMensagens(mensagens);
                atualizarTela(mensagens);
                if (form && data.podeEnviar === false) form.classList.add('d-none');
            })
            .catch(function () { /* falha de rede pontual - proxima tentativa em breve, sem incomodar o usuario */ });
    }

    if (form) {
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!input || !input.value.trim()) return;
            var texto = input.value;
            var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
            if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
            input.disabled = true;
            fetch(cfg.sendUrl, {method: 'POST', headers: headers, credentials: 'same-origin', body: 'texto=' + encodeURIComponent(texto)})
                .then(function (r) { return r.json().then(function (body) { return {ok: r.ok, body: body}; }); })
                .then(function (res) {
                    if (!res.ok) {
                        if (typeof mostrarToast === 'function') mostrarToast(res.body.erro || 'Nao foi possivel enviar a mensagem.', 'error');
                        return;
                    }
                    input.value = '';
                    poll();
                })
                .catch(function () {
                    if (typeof mostrarToast === 'function') mostrarToast('Falha de conexao ao enviar a mensagem.', 'error');
                })
                .finally(function () {
                    input.disabled = false;
                    input.focus();
                });
        });
    }

    if (chatBox) {
        chatBox.addEventListener('click', function (e) {
            var btn = e.target.closest('.btn-apagar-msg-chat');
            if (!btn) return;
            if (!confirm('Apagar esta mensagem?')) return;
            var headers = {};
            if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
            fetch(cfg.deleteUrlBase + btn.getAttribute('data-id') + '/apagar/ajax',
                {method: 'POST', headers: headers, credentials: 'same-origin'})
                .then(function (r) { return r.ok ? poll() : Promise.reject(new Error('HTTP ' + r.status)); })
                .catch(function () {
                    if (typeof mostrarToast === 'function') mostrarToast('Nao foi possivel apagar a mensagem.', 'error');
                });
        });
    }

    document.addEventListener('visibilitychange', function () {
        pollAtivo = !document.hidden;
        if (pollAtivo) poll();
    });

    if (chatBox) chatBox.scrollTop = chatBox.scrollHeight;
    poll();
    setInterval(poll, cfg.intervaloMs || 5000);
}
