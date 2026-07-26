(function () {
    var base = document.getElementById('formAnual') ? document.getElementById('formAnual').dataset.base : null;
    var btn = document.getElementById('btnGerar');
    var sel = document.getElementById('anoSelect');
    if (base && btn && sel) {
        btn.addEventListener('click', function () {
            var ano = sel.value;
            if (ano) {
                window.open(base + '/' + ano + '/pdf', '_blank');
            }
        });
    }
})();
