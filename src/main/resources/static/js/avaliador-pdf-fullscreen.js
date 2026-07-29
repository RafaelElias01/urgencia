// === SAUR - Portal do Avaliador: modo tela cheia do PDF em analise ===
// Documento clinico pede atencao total; o botao usa a Fullscreen API nativa
// do navegador diretamente sobre o iframe (sem lib externa). Falha
// silenciosamente se o navegador nao suportar (raro) - o link "Abrir em
// nova aba" ao lado continua funcionando como alternativa.
(function () {
    document.querySelectorAll('.btn-tela-cheia').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var alvoId = btn.getAttribute('data-iframe-alvo');
            var iframe = alvoId ? document.getElementById(alvoId) : null;
            if (!iframe) return;
            if (iframe.requestFullscreen) {
                iframe.requestFullscreen().catch(function () {});
            } else if (iframe.webkitRequestFullscreen) {
                iframe.webkitRequestFullscreen();
            }
        });
    });
})();
