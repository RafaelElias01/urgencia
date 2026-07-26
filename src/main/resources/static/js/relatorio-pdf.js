// === SAUR - Abrir PDF de relatorio em nova aba ===
// Compartilhado por relatorio-anual.js e relatorio-avaliador.js, que so
// diferem na quantidade de segmentos de URL (ano; ano+membro).
window.abrirRelatorioPdf = function (base, partes) {
    var url = base + '/' + partes.join('/') + '/pdf';
    var aba = window.open(url, '_blank', 'noopener,noreferrer');
    if (!aba && typeof mostrarToast === 'function') {
        mostrarToast('Nao foi possivel abrir o PDF - verifique se o navegador bloqueou o pop-up.', 'error');
    }
};
