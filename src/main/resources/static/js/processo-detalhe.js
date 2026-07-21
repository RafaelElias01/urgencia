// === SAUR - Processo Detalhe ===
// Funcoes da tela de detalhe do processo (wizard, pareceres, IA, e-mail).

// ===== Notificacao toast =====
function mostrarToast(mensagem, tipo) {
    tipo = tipo || 'info';
    var container = document.getElementById('toastContainer');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toastContainer';
        container.className = 'toast-container-sgpur';
        document.body.appendChild(container);
    }
    var icones = {success: 'bi-check-circle-fill text-success', error: 'bi-exclamation-triangle-fill text-danger', info: 'bi-info-circle-fill text-primary'};
    var toast = document.createElement('div');
    toast.className = 'toast-sgpur toast-' + tipo;
    toast.innerHTML = '<i class="bi toast-icon ' + (icones[tipo] || icones.info) + '"></i>' +
        '<div class="toast-body">' + mensagem + '</div>' +
        '<button class="toast-close" onclick="this.parentElement.remove()">&times;</button>';
    container.appendChild(toast);
    setTimeout(function () {
        toast.style.opacity = '0';
        toast.style.transition = 'opacity .3s';
        setTimeout(function () { toast.remove(); }, 300);
    }, 5000);
}

// Mostra/oculta o motivo conforme a decisao
(function () {
    var sel = document.getElementById('decisaoSelect');
    var box = document.getElementById('motivoBox');
    if (sel && box) {
        function toggleMotivo() {
            box.style.display = (sel.value === 'INDEFERIDO') ? '' : 'none';
        }
        sel.addEventListener('change', toggleMotivo);
        toggleMotivo();
    }
})();

// Confirmacao ao alterar parecer ja preenchido
document.querySelectorAll('.parecer-select').forEach(function (select) {
    select.addEventListener('change', function () {
        if (this.dataset.valorAnterior && this.dataset.valorAnterior !== this.value) {
            if (!confirm('Alterar o parecer deste avaliador? O resultado anterior sera substituido.')) {
                this.value = this.dataset.valorAnterior;
                return;
            }
        }
        this.dataset.valorAnterior = this.value;
    });
    select.dataset.valorAnterior = select.value;
});

// Confirmacao ao anexar resposta de avaliador
document.querySelectorAll('.btn-anexar-resposta').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
        var form = this.closest('form');
        var fileInput = form.querySelector('input[type="file"]');
        if (fileInput && fileInput.files.length > 0) {
            if (!confirm('Anexar este arquivo como resposta do avaliador?')) {
                e.preventDefault();
            }
        }
    });
});

// Copiar textos de e-mail
document.querySelectorAll('.btn-copiar').forEach(function (btn) {
    btn.addEventListener('click', function () {
        var alvo = document.getElementById(btn.getAttribute('data-alvo'));
        if (!alvo) return;
        navigator.clipboard.writeText(alvo.value).then(function () {
            var orig = btn.innerHTML;
            btn.innerHTML = '<i class="bi bi-check2"></i> Copiado!';
            setTimeout(function () { btn.innerHTML = orig; }, 1500);
        });
    });
});

// ===== Token CSRF =====
var csrfToken = document.querySelector('meta[name="_csrf"]').content;
var csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

// ===== Assistencia por IA (Gemini) =====
function chamarIa(btn, url, options, aoConcluir) {
    var orig = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Consultando IA...';
    var headers = Object.assign({}, options.headers || {});
    headers[csrfHeader] = csrfToken;
    fetch(url, Object.assign({}, options, {headers: headers}))
        .then(function (resp) { return resp.json(); })
        .then(function (data) {
            if (data.erro) {
                mostrarToast('Nao foi possivel obter a sugestao da IA: ' + data.erro, 'error');
            } else {
                aoConcluir(data.texto);
            }
        })
        .catch(function () {
            mostrarToast('Falha de comunicacao ao consultar a IA. Tente novamente.', 'error');
        })
        .finally(function () {
            btn.disabled = false;
            btn.innerHTML = orig;
        });
}

// 1) Sugerir motivo do indeferimento
var btnSugerirMotivo = document.getElementById('btnSugerirMotivo');
if (btnSugerirMotivo) {
    btnSugerirMotivo.addEventListener('click', function () {
        var processoId = this.getAttribute('data-processo-id');
        var campo = document.getElementById('motivoIndeferimentoInput');
        chamarIa(this, '/processos/' + processoId + '/sugestao-motivo', {method: 'POST'}, function (texto) {
            campo.value = texto;
        });
    });
}

// 2) Resumir documento clinico anexado
document.querySelectorAll('.btn-resumir-ia').forEach(function (btn) {
    btn.addEventListener('click', function () {
        var anexoId = this.getAttribute('data-anexo-id');
        var alvo = document.getElementById('resumo-ia-' + anexoId);
        chamarIa(this, '/processos/anexos/' + anexoId + '/resumo-ia', {method: 'GET'}, function (texto) {
            alvo.textContent = texto;
            alvo.classList.remove('d-none');
        });
    });
});

// 3) Revisar texto de e-mail
document.querySelectorAll('.btn-revisar-ia').forEach(function (btn) {
    btn.addEventListener('click', function () {
        var processoId = this.getAttribute('data-processo-id');
        var assunto = document.getElementById(this.getAttribute('data-assunto-id')).value;
        var corpoEl = document.getElementById(this.getAttribute('data-corpo-id'));
        var body = new URLSearchParams({assunto: assunto, corpo: corpoEl.value});
        chamarIa(this, '/processos/' + processoId + '/email/revisar-ia', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: body
        }, function (texto) {
            corpoEl.value = texto;
        });
    });
});

// ===== Envio real de e-mail (SMTP) - sempre disparo manual =====
function chamarAcao(btn, url, options, mensagemEspera) {
    var orig = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> ' + mensagemEspera;
    var headers = Object.assign({}, options.headers || {});
    headers[csrfHeader] = csrfToken;
    fetch(url, Object.assign({}, options, {headers: headers}))
        .then(function (resp) { return resp.json(); })
        .then(function (data) {
            mostrarToast(data.mensagem, data.mensagem && data.mensagem.includes('erro') ? 'error' : 'success');
        })
        .catch(function () {
            mostrarToast('Falha de comunicacao ao enviar o e-mail. Tente novamente.', 'error');
        })
        .finally(function () {
            btn.disabled = false;
            btn.innerHTML = orig;
        });
}

// ===== Modal de confirmacao de e-mail =====
var modalEmailEl = document.getElementById('modalConfirmaEmail');
var modalEmail = modalEmailEl ? new bootstrap.Modal(modalEmailEl) : null;
var btnConfirmaEnvio = document.getElementById('btnConfirmaEnvioEmail');
var envioPendente = null;

function renderMensagensEmail(mensagens) {
    var cont = document.getElementById('modalEmailMensagens');
    cont.innerHTML = '';
    var lote = mensagens.length > 1;
    mensagens.forEach(function (m, i) {
        var card = document.createElement('div');
        card.className = 'border rounded p-3 mb-3 bg-body-tertiary';
        if (lote) {
            var cab = document.createElement('div');
            cab.className = 'small fw-semibold text-muted mb-2';
            cab.textContent = 'Mensagem ' + (i + 1) + ' de ' + mensagens.length;
            card.appendChild(cab);
        }
        var dest = document.createElement('div');
        dest.className = 'mb-2';
        var destLabel = document.createElement('span');
        destLabel.className = 'badge bg-secondary me-1';
        destLabel.textContent = 'Para';
        var destVal = document.createElement('span');
        destVal.className = 'fw-semibold';
        destVal.textContent = m.destinatarios;
        dest.appendChild(destLabel);
        dest.appendChild(destVal);
        card.appendChild(dest);
        var assunto = document.createElement('div');
        assunto.className = 'mb-2';
        var asLabel = document.createElement('span');
        asLabel.className = 'text-muted small me-1';
        asLabel.textContent = 'Assunto:';
        var asVal = document.createElement('span');
        asVal.textContent = m.assunto;
        assunto.appendChild(asLabel);
        assunto.appendChild(asVal);
        card.appendChild(assunto);
        var corpo = document.createElement('pre');
        corpo.className = 'small mb-0 p-2 border rounded bg-white';
        corpo.style.whiteSpace = 'pre-wrap';
        corpo.style.wordBreak = 'break-word';
        corpo.textContent = m.corpo;
        card.appendChild(corpo);
        cont.appendChild(card);
    });
}

function abrirPreviewEmail(botao, previewParams, enviarFn) {
    var processoId = botao.getAttribute('data-processo-id');
    var orig = botao.innerHTML;
    botao.disabled = true;
    botao.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Preparando...';
    var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
    headers[csrfHeader] = csrfToken;
    fetch('/processos/' + processoId + '/email/preview', {
        method: 'POST',
        headers: headers,
        body: new URLSearchParams(previewParams)
    })
        .then(function (resp) { return resp.json(); })
        .then(function (data) {
            if (!data.ok) {
                mostrarToast(data.erro, 'error');
                return;
            }
            renderMensagensEmail(data.mensagens);
            document.getElementById('modalEmailTitulo').textContent = data.mensagens.length > 1
                ? 'Confirmar envio de ' + data.mensagens.length + ' e-mails'
                : 'Confirmar envio de e-mail';
            envioPendente = function () { enviarFn(botao); };
            modalEmail.show();
        })
        .catch(function () {
            mostrarToast('Falha ao preparar a pre-visualizacao do e-mail. Tente novamente.', 'error');
        })
        .finally(function () {
            botao.disabled = false;
            botao.innerHTML = orig;
        });
}

if (btnConfirmaEnvio) {
    btnConfirmaEnvio.addEventListener('click', function () {
        var fn = envioPendente;
        envioPendente = null;
        modalEmail.hide();
        if (fn) fn();
    });
}

// 4) Lembrete individual de avaliacao pendente
document.querySelectorAll('.btn-lembrete-avaliador').forEach(function (btn) {
    btn.addEventListener('click', function () {
        var parecerId = this.getAttribute('data-parecer-id');
        abrirPreviewEmail(this, {tipo: 'lembrete-avaliador', parecerId: parecerId}, function (b) {
            var processoId = b.getAttribute('data-processo-id');
            chamarAcao(b,
                '/processos/' + processoId + '/lembrete-avaliador?parecerId=' + parecerId,
                {method: 'POST'}, 'Enviando...');
        });
    });
});

// 5) Lembrete em lote para todos os pendentes
var btnLembretePendentes = document.getElementById('btnLembretePendentes');
if (btnLembretePendentes) {
    btnLembretePendentes.addEventListener('click', function () {
        abrirPreviewEmail(this, {tipo: 'lembrete-pendentes'}, function (b) {
            var processoId = b.getAttribute('data-processo-id');
            chamarAcao(b, '/processos/' + processoId + '/lembrete-pendentes', {method: 'POST'}, 'Enviando...');
        });
    });
}

// 6) Enviar e-mail pronto (accordion)
document.querySelectorAll('.btn-enviar-email').forEach(function (btn) {
    btn.addEventListener('click', function () {
        var chave = this.getAttribute('data-chave');
        var assunto = document.getElementById(this.getAttribute('data-assunto-id')).value;
        var corpo = document.getElementById(this.getAttribute('data-corpo-id')).value;
        abrirPreviewEmail(this, {tipo: 'pronto', chave: chave, assunto: assunto, corpo: corpo}, function (b) {
            var processoId = b.getAttribute('data-processo-id');
            var body = new URLSearchParams({chave: chave, assunto: assunto, corpo: corpo});
            chamarAcao(b, '/processos/' + processoId + '/email/enviar', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: body
            }, 'Enviando...');
        });
    });
});

// Scroll hint: mostra sombra no final do wizard-wrapper se houver scroll
var wizardWrapper = document.getElementById('wizardWrapper');
if (wizardWrapper) {
    var wizard = wizardWrapper.querySelector('.wizard');
    function checkScroll() {
        if (wizard && wizard.scrollWidth > wizard.clientWidth) {
            wizardWrapper.classList.add('can-scroll');
        } else {
            wizardWrapper.classList.remove('can-scroll');
        }
    }
    checkScroll();
    window.addEventListener('resize', checkScroll);
    wizard.addEventListener('scroll', function () {
        var atEnd = wizard.scrollLeft + wizard.clientWidth >= wizard.scrollWidth - 2;
        if (atEnd) wizardWrapper.classList.remove('can-scroll');
    });
}
