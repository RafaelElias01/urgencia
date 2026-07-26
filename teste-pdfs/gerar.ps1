$Dir = $PSScriptRoot
function New-Pdf($path, $text) {
    # Calcula os offsets de byte de verdade em vez de usar valores fixos -
    # o tamanho de $text varia por PDF, entao offsets hardcoded ficavam
    # errados (e o trailer tinha "%%%%EOF" em vez de "%%EOF"), gerando
    # PDFs com xref/EOF invalidos (bug encontrado e corrigido em 2026-07-26).
    $enc = [System.Text.Encoding]::ASCII
    $streamContent = "BT /F1 14 Tf 50 750 Td ($text) Tj ET`n"
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

Write-Host "=== Gerando PDFs de teste ==="
New-Pdf "$Dir/solicitacao-recebida.pdf" "SOLICITACAO RECEBIDA"
New-Pdf "$Dir/documento-clinico-1.pdf" "DOCUMENTO CLINICO 1"
New-Pdf "$Dir/documento-clinico-2.pdf" "DOCUMENTO CLINICO 2"
New-Pdf "$Dir/resposta-avaliador-1.pdf" "RESPOSTA AVALIADOR 1 - FAVORAVEL"
New-Pdf "$Dir/resposta-avaliador-2.pdf" "RESPOSTA AVALIADOR 2 - FAVORAVEL"
New-Pdf "$Dir/resposta-avaliador-3.pdf" "RESPOSTA AVALIADOR 3 - DESFAVORAVEL"
New-Pdf "$Dir/oficio-indeferimento.pdf" "OFICIO DE INDEFERIMENTO"
New-Pdf "$Dir/comprovante-snt.pdf" "COMPROVANTE SNT"
New-Pdf "$Dir/comprovante-envio.pdf" "COMPROVANTE DE ENVIO"
Write-Host "=== 9 PDFs gerados ==="
