$Dir = $PSScriptRoot

function Escape-PdfText($t) {
    return $t.Replace('\', '\\').Replace('(', '\(').Replace(')', '\)')
}

# Gera um PDF com varias linhas de texto (simulando um documento real).
# Calcula os offsets de byte de verdade em vez de usar valores fixos - o
# tamanho do conteudo varia por PDF, entao offsets hardcoded ficavam errados
# (e o trailer tinha "%%%%EOF" em vez de "%%EOF"), gerando PDFs com xref/EOF
# invalidos (bug encontrado e corrigido em 2026-07-26). Sem acentos de
# proposito - a fonte Helvetica padrao (sem /Encoding explicito) nao cobre
# bem Latin-1, e o arquivo e escrito em ASCII puro.
function New-PdfMultiline($path, [string[]]$lines, [int]$fontSize = 11, [int]$startY = 760, [int]$lineHeight = 15) {
    $enc = [System.Text.Encoding]::ASCII

    $streamBody = New-Object System.Text.StringBuilder
    [void]$streamBody.Append("BT`n/F1 $fontSize Tf`n50 $startY Td`n")
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $txt = Escape-PdfText $lines[$i]
        if ($i -gt 0) { [void]$streamBody.Append("0 -$lineHeight Td`n") }
        [void]$streamBody.Append("($txt) Tj`n")
    }
    [void]$streamBody.Append("ET`n")
    $streamContent = $streamBody.ToString()
    $streamLen = $enc.GetBytes($streamContent).Length

    $sb = New-Object System.Text.StringBuilder
    $offsets = @(0,0,0,0,0,0)

    [void]$sb.Append("%PDF-1.4`n")
    $offsets[1] = $enc.GetBytes($sb.ToString()).Length
    [void]$sb.Append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj`n")
    $offsets[2] = $enc.GetBytes($sb.ToString()).Length
    [void]$sb.Append("2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj`n")
    $offsets[3] = $enc.GetBytes($sb.ToString()).Length
    [void]$sb.Append("3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>endobj`n")
    $offsets[4] = $enc.GetBytes($sb.ToString()).Length
    [void]$sb.Append("4 0 obj<</Length $streamLen>>stream`n")
    [void]$sb.Append($streamContent)
    [void]$sb.Append("endstream`nendobj`n")
    $offsets[5] = $enc.GetBytes($sb.ToString()).Length
    [void]$sb.Append("5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj`n")

    $xrefOffset = $enc.GetBytes($sb.ToString()).Length
    [void]$sb.Append("xref`n0 6`n0000000000 65535 f `n")
    foreach ($i in 1..5) {
        [void]$sb.Append(("{0:D10} 00000 n `n" -f $offsets[$i]))
    }
    [void]$sb.Append("trailer<</Size 6/Root 1 0 R>>`nstartxref`n$xrefOffset`n%%EOF")

    $bytes = $enc.GetBytes($sb.ToString())
    [System.IO.File]::WriteAllBytes($path, $bytes)
    Write-Host "  OK: $path"
}

# Atalho pra um "documento" de 1 linha (mantido por compatibilidade).
function New-Pdf($path, $text) {
    New-PdfMultiline $path @($text)
}

Write-Host "=== Gerando PDFs de teste (dados fictícios - TESTE QA APAGAR) ==="

New-PdfMultiline "$Dir/solicitacao-recebida.pdf" @(
    "HOSPITAL DE CLINICAS DE PORTO ALEGRE",
    "Servico de Nefrologia",
    "",
    "Porto Alegre, 18 de julho de 2026.",
    "",
    "Ao Comite de Urgencia Renal - CET-RS",
    "",
    "Solicitamos avaliacao de URGENCIA RENAL para o(a) paciente:",
    "",
    "Nome: TESTE QA APAGAR",
    "RGCT: 000000-0",
    "Data de nascimento: 01/01/1990",
    "Diagnostico: Insuficiencia Renal Cronica Agudizada",
    "",
    "Justificativa clinica: paciente em fila de transplante renal ha 18",
    "meses, com piora subita da funcao renal (creatinina 6.8 mg/dL) e",
    "necessidade de hemodialise de urgencia 3x/semana. Solicita-se",
    "avaliacao prioritaria conforme protocolo estadual.",
    "",
    "Atenciosamente,",
    "Dr. Joao Teste da Silva - CRM/RS 00000",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)"
) -fontSize 11

New-PdfMultiline "$Dir/documento-clinico-1.pdf" @(
    "LAUDO DE EXAME LABORATORIAL",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)",
    "",
    "Paciente: TESTE QA APAGAR",
    "Data da coleta: 17/07/2026",
    "",
    "Creatinina serica ......... 6.8 mg/dL   (VR: 0.6 - 1.2)",
    "Ureia ...................... 145 mg/dL   (VR: 15 - 45)",
    "Potassio ................... 5.9 mEq/L   (VR: 3.5 - 5.0)",
    "TFG estimada ............... 8 mL/min/1.73m2",
    "",
    "Conclusao: funcao renal gravemente comprometida, compativel",
    "com necessidade de terapia renal substitutiva de urgencia.",
    "",
    "Dr. Joao Teste da Silva - CRM/RS 00000"
)

New-PdfMultiline "$Dir/documento-clinico-2.pdf" @(
    "LAUDO DE ULTRASSONOGRAFIA RENAL",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)",
    "",
    "Paciente: TESTE QA APAGAR",
    "Data do exame: 18/07/2026",
    "",
    "Rins topicos, de dimensoes reduzidas (rim direito 7.8cm,",
    "rim esquerdo 7.5cm), com perda da diferenciacao corticomedular,",
    "compativel com nefropatia cronica em fase avancada. Sem sinais",
    "de dilatacao do sistema pielocalicial.",
    "",
    "Dra. Maria Teste Souza - CRM/RS 00001"
)

New-PdfMultiline "$Dir/email-enviado-avaliadores.pdf" @(
    "De: centraldetransplante@gmail.com",
    "Para: avaliador1@teste.saur, avaliador2@teste.saur,",
    "      avaliador3@teste.saur",
    "Data: 19/07/2026 14:32",
    "Assunto: Processo CET-RS 00-2026 - Solicitacao de parecer",
    "",
    "Prezados avaliadores,",
    "",
    "Encaminhamos em anexo a documentacao anonimizada do processo",
    "CET-RS 00-2026 (paciente T.Q.A.) para emissao de parecer de",
    "urgencia renal. Favor responder no prazo de 7 dias.",
    "",
    "Atenciosamente,",
    "Central de Transplantes do Estado do Rio Grande do Sul",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)"
)

New-PdfMultiline "$Dir/resposta-avaliador-1.pdf" @(
    "PARECER DE AVALIACAO - URGENCIA RENAL",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)",
    "",
    "Processo: CET-RS 00-2026",
    "Paciente: T.Q.A. (iniciais)",
    "Avaliador: Dr. Avaliador Um Teste - CRM/RS 10001",
    "",
    "Resultado: FAVORAVEL",
    "",
    "Justificativa: quadro compativel com necessidade de terapia",
    "renal substitutiva de urgencia, conforme criterios do",
    "protocolo estadual.",
    "",
    "Data: 20/07/2026"
)

New-PdfMultiline "$Dir/resposta-avaliador-2.pdf" @(
    "PARECER DE AVALIACAO - URGENCIA RENAL",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)",
    "",
    "Processo: CET-RS 00-2026",
    "Paciente: T.Q.A. (iniciais)",
    "Avaliador: Dra. Avaliadora Dois Teste - CRM/RS 10002",
    "",
    "Resultado: FAVORAVEL",
    "",
    "Justificativa: exames laboratoriais e de imagem confirmam",
    "quadro de urgencia renal, favoravel a inclusao no protocolo.",
    "",
    "Data: 20/07/2026"
)

New-PdfMultiline "$Dir/resposta-avaliador-3.pdf" @(
    "PARECER DE AVALIACAO - URGENCIA RENAL",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)",
    "",
    "Processo: CET-RS 00-2026",
    "Paciente: T.Q.A. (iniciais)",
    "Avaliador: Dr. Avaliador Tres Teste - CRM/RS 10003",
    "",
    "Resultado: DESFAVORAVEL",
    "",
    "Justificativa: quadro nao preenche integralmente os criterios",
    "de urgencia do protocolo vigente; indica-se reavaliacao",
    "ambulatorial em 30 dias.",
    "",
    "Data: 21/07/2026"
)

New-PdfMultiline "$Dir/info-complementar.pdf" @(
    "De: centraldetransplante@gmail.com",
    "Para: nefrologia.hcpa@teste.saur",
    "Data: 22/07/2026",
    "Assunto: Processo CET-RS 00-2026 - Informacao complementar",
    "",
    "Prezada equipe solicitante,",
    "",
    "Para prosseguir com a avaliacao do processo CET-RS 00-2026,",
    "referente ao paciente TESTE QA APAGAR, solicitamos o envio do",
    "exame de clearance de creatinina atualizado.",
    "",
    "Atenciosamente,",
    "Central de Transplantes do Estado do Rio Grande do Sul",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)"
)

New-PdfMultiline "$Dir/oficio-indeferimento.pdf" @(
    "CENTRAL DE TRANSPLANTES DO ESTADO DO RIO GRANDE DO SUL",
    "OFICIO DE INDEFERIMENTO",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)",
    "",
    "Processo: CET-RS 00-2026",
    "Paciente: TESTE QA APAGAR",
    "",
    "Comunicamos que, apos avaliacao por 3 (tres) medicos membros",
    "da Urgencia Renal, o pedido de URGENCIA RENAL foi INDEFERIDO",
    "por maioria, conforme pareceres em anexo.",
    "",
    "Motivo: quadro clinico nao preenche os criterios de urgencia",
    "previstos no protocolo estadual vigente.",
    "",
    "Porto Alegre, 23 de julho de 2026."
)

New-PdfMultiline "$Dir/comprovante-snt.pdf" @(
    "SISTEMA NACIONAL DE TRANSPLANTES - SNT",
    "Comprovante de insercao em URGENCIA RENAL",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)",
    "",
    "Paciente: TESTE QA APAGAR",
    "RGCT: 000000-0",
    "Data de insercao: 24/07/2026",
    "Situacao: URGENCIA RENAL - ATIVO",
    "",
    "Protocolo gerado automaticamente pelo sistema."
)

New-PdfMultiline "$Dir/comprovante-envio.pdf" @(
    "De: centraldetransplante@gmail.com",
    "Para: nefrologia.hcpa@teste.saur",
    "Data: 25/07/2026",
    "Assunto: Processo CET-RS 00-2026 - Resposta - DEFERIDA",
    "",
    "Prezada equipe solicitante,",
    "",
    "Informamos que o processo CET-RS 00-2026, referente ao",
    "paciente TESTE QA APAGAR, foi DEFERIDO. Segue em anexo o",
    "comprovante de insercao no SNT.",
    "",
    "Atenciosamente,",
    "Central de Transplantes do Estado do Rio Grande do Sul",
    "(DOCUMENTO SIMULADO PARA FINS DE TESTE)"
)

Write-Host "=== 11 PDFs gerados ==="
