(function () {
    var form = document.getElementById('formAnual');
    var base = form ? form.dataset.base : null;
    var btn = document.getElementById('btnGerar');
    var sel = document.getElementById('anoSelect');
    if (base && btn && sel) {
        btn.addEventListener('click', function () {
            var ano = sel.value;
            if (ano) {
                window.abrirRelatorioPdf(base, [ano]);
            }
        });
    }
})();
