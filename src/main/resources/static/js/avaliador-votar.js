// === SAUR - Portal do Avaliador: confirmacao explicita do voto ===
// O voto e definitivo (nao ha edicao posterior). Em vez do confirm() nativo
// do navegador - facil de clicar sem ler -, mostra um modal que repete a
// escolha feita, exige um checkbox explicito de ciencia, e so entao envia
// o formulario de verdade.
(function () {
    var form = document.getElementById('formVotoAvaliador');
    if (!form || typeof bootstrap === 'undefined') return;

    var modalEl = document.getElementById('modalConfirmarVoto');
    var modal = new bootstrap.Modal(modalEl);
    var resumoResultado = document.getElementById('resumoResultadoConfirmacao');
    var checkConfirma = document.getElementById('checkConfirmaVoto');
    var btnConfirmar = document.getElementById('btnConfirmarVotoFinal');

    form.addEventListener('submit', function (ev) {
        // So chega aqui se os campos "required" nativos ja passaram - o
        // formulario permanece intacto, so adiamos o envio ate a confirmacao.
        ev.preventDefault();

        var radioMarcado = form.querySelector('input[name="resultado"]:checked');
        if (!radioMarcado) return;
        var label = form.querySelector('label[for="' + radioMarcado.id + '"]');
        resumoResultado.innerHTML = label ? label.innerHTML : radioMarcado.value;

        checkConfirma.checked = false;
        btnConfirmar.disabled = true;
        modal.show();
    });

    checkConfirma.addEventListener('change', function () {
        btnConfirmar.disabled = !checkConfirma.checked;
    });

    btnConfirmar.addEventListener('click', function () {
        modal.hide();
        // form.submit() nao redispara o evento 'submit' (nem a validacao),
        // entao nao reentra no listener acima - segue direto para o POST.
        form.submit();
    });

    // Se o avaliador fechar o modal (Cancelar/X/Esc) sem confirmar, o voto
    // nao e enviado - ele volta para a tela e pode revisar a escolha.
})();
