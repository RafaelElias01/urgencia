// === SAUR - Portal do Avaliador: confirmacao explicita do voto ===
// O voto e definitivo (nao ha edicao posterior). Em vez do confirm() nativo
// do navegador - facil de clicar sem ler -, mostra um modal que repete a
// escolha feita, exige um checkbox explicito de ciencia, e so entao envia
// o formulario de verdade. Se o Bootstrap ou algum elemento do modal nao
// estiver disponivel por qualquer motivo, cai para o confirm() nativo como
// rede de seguranca - o voto (irreversivel) nunca deve poder ser enviado
// sem NENHUMA confirmacao.
(function () {
    var form = document.getElementById('formVotoAvaliador');
    if (!form) return;

    var modalEl = document.getElementById('modalConfirmarVoto');
    var resumoResultado = document.getElementById('resumoResultadoConfirmacao');
    var checkConfirma = document.getElementById('checkConfirmaVoto');
    var btnConfirmar = document.getElementById('btnConfirmarVotoFinal');

    if (typeof bootstrap === 'undefined' || !modalEl || !resumoResultado || !checkConfirma || !btnConfirmar) {
        console.error('SAUR: modal de confirmacao do voto indisponivel (Bootstrap ou elemento do DOM ausente); usando confirm() nativo como rede de seguranca.');
        form.addEventListener('submit', function (ev) {
            if (!confirm('Confirmar o registro do seu voto? Esta acao nao pode ser desfeita.')) {
                ev.preventDefault();
            }
        });
        return;
    }

    var modal = new bootstrap.Modal(modalEl);

    form.addEventListener('submit', function (ev) {
        // So chega aqui se os campos "required" nativos ja passaram - o
        // formulario permanece intacto, so adiamos o envio ate a confirmacao.
        ev.preventDefault();

        var radioMarcado = form.querySelector('input[name="resultado"]:checked');
        if (!radioMarcado) return;
        var label = form.querySelector('label[for="' + radioMarcado.id + '"]');
        // textContent (nao innerHTML): o resumo e so texto, nunca deve
        // interpretar o conteudo do label como HTML.
        resumoResultado.textContent = label ? label.textContent.trim() : radioMarcado.value;

        checkConfirma.checked = false;
        btnConfirmar.disabled = true;
        modal.show();
    });

    checkConfirma.addEventListener('change', function () {
        btnConfirmar.disabled = !checkConfirma.checked;
    });

    btnConfirmar.addEventListener('click', function () {
        // Desabilita imediatamente: um duplo clique rapido (ou uma rede
        // lenta) nao deve conseguir disparar duas submissoes do voto antes
        // da pagina navegar para longe.
        btnConfirmar.disabled = true;
        modal.hide();
        // form.submit() nao redispara o evento 'submit' (nem a validacao),
        // entao nao reentra no listener acima - segue direto para o POST.
        form.submit();
    });

    // Se o avaliador fechar o modal (Cancelar/X/Esc) sem confirmar, o voto
    // nao e enviado - ele volta para a tela e pode revisar a escolha.
})();
