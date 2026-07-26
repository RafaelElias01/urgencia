(function () {
    var form = document.getElementById('formAvaliador');
    var base = form ? form.dataset.base : null;
    var btn = document.getElementById('btnGerar');
    var ano = document.getElementById('anoSelect');
    var membro = document.getElementById('membroSelect');
    if (base && btn && ano && membro) {
        btn.addEventListener('click', function () {
            if (ano.value && membro.value) {
                window.open(base + '/' + ano.value + '/' + membro.value + '/pdf', '_blank');
            }
        });
    }
})();
