// === SAUR - Cadastro de processo: limite de medicos avaliadores selecionados ===
(function () {
    var container = document.getElementById('listaMedicos');
    var contador = document.getElementById('contador');
    if (!container || !contador) return;

    var max = parseInt(container.dataset.maxAvaliadores, 10);
    if (isNaN(max)) {
        console.error('SAUR: data-max-avaliadores ausente ou invalido; usando 3 (regra de negocio: sempre 3 avaliadores).');
        max = 3;
    }
    var checks = document.querySelectorAll('.medico-check');

    function atualizar() {
        var marcados = document.querySelectorAll('.medico-check:checked').length;
        contador.textContent = marcados;
        checks.forEach(function (c) {
            c.disabled = (!c.checked && marcados >= max);
        });
    }

    checks.forEach(function (c) { c.addEventListener('change', atualizar); });
    atualizar();
})();
