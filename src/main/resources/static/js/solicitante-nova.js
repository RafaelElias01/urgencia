// Feedback de selecao de arquivos no formulario de Nova solicitacao (Portal do
// Solicitante). So exibe a lista de nomes dos arquivos escolhidos no input
// multiple - nao valida tipo/tamanho (isso e feito no servidor).
document.addEventListener('DOMContentLoaded', function () {
    var input = document.getElementById('documentos');
    var resumo = document.getElementById('documentosSelecionados');
    if (!input || !resumo) {
        return;
    }
    input.addEventListener('change', function () {
        var arquivos = Array.prototype.slice.call(input.files || []);
        if (arquivos.length === 0) {
            resumo.textContent = '';
            return;
        }
        var nomes = arquivos.map(function (a) { return a.name; }).join(', ');
        resumo.textContent = arquivos.length + ' arquivo(s) selecionado(s): ' + nomes;
    });
});
