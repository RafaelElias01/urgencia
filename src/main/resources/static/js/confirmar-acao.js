// === SAUR - Confirmacao de acoes destrutivas via modal (nao confirm() nativo) ===
// Qualquer <form> ou <a> com o atributo data-confirm-msg="..." passa por um
// modal Bootstrap generico em vez do confirm() bloqueavel do navegador,
// mantendo o padrao visual consistente com o resto do sistema. Se o modal ou
// o Bootstrap nao estiverem disponiveis por algum motivo, cai para confirm()
// nativo como rede de seguranca - a acao nunca deve seguir sem nenhuma
// confirmacao.
//
// window.confirmarAcao(mensagem) tambem fica exposta globalmente, retornando
// uma Promise<boolean>, para telas que precisam decidir programaticamente
// (ex.: reverter um <select> se o usuario nao confirmar) em vez de um
// form/link simples.
(function () {
    var modal = null;
    var mensagemEl = null;
    var btnConfirmar = null;
    var resolverPendente = null;

    function inicializar() {
        var modalEl = document.getElementById('modalConfirmarAcao');
        mensagemEl = document.getElementById('modalConfirmarAcaoMensagem');
        btnConfirmar = document.getElementById('btnConfirmarAcaoFinal');
        modal = (typeof bootstrap !== 'undefined' && modalEl)
            ? new bootstrap.Modal(modalEl) : null;

        if (btnConfirmar) {
            btnConfirmar.addEventListener('click', function () {
                modal.hide();
                if (resolverPendente) {
                    var resolver = resolverPendente;
                    resolverPendente = null;
                    resolver(true);
                }
            });
        }
        if (modalEl) {
            modalEl.addEventListener('hidden.bs.modal', function () {
                // Fechado sem clicar em Confirmar (Cancelar/X/Esc): nega.
                if (resolverPendente) {
                    var resolver = resolverPendente;
                    resolverPendente = null;
                    resolver(false);
                }
            });
        }

        document.querySelectorAll('[data-confirm-msg]').forEach(function (el) {
            var isForm = el.tagName === 'FORM';
            var evento = isForm ? 'submit' : 'click';

            el.addEventListener(evento, function (ev) {
                if (el.dataset.confirmed === 'true') {
                    el.dataset.confirmed = '';
                    return; // ja confirmado pelo modal, deixa a acao prosseguir
                }

                var mensagem = el.dataset.confirmMsg;

                if (!modal || !mensagemEl || !btnConfirmar) {
                    if (!window.confirm(mensagem)) {
                        ev.preventDefault();
                    }
                    return;
                }

                ev.preventDefault();
                window.confirmarAcao(mensagem).then(function (confirmado) {
                    if (!confirmado) return;
                    el.dataset.confirmed = 'true';
                    if (el.tagName === 'FORM') {
                        el.submit();
                    } else {
                        el.click();
                    }
                });
            });
        });
    }

    // API programatica: window.confirmarAcao('mensagem').then(function (ok) {...})
    window.confirmarAcao = function (mensagem) {
        if (!modal || !mensagemEl) {
            return Promise.resolve(window.confirm(mensagem));
        }
        return new Promise(function (resolve) {
            resolverPendente = resolve;
            mensagemEl.textContent = mensagem;
            modal.show();
        });
    };

    document.addEventListener('DOMContentLoaded', inicializar);
})();
