$Dir = [System.IO.Path]::GetFullPath(".")
function New-Pdf($path, $text) {
    $stream = @"
BT /F1 14 Tf 50 750 Td ($text) Tj ET
"@
    $streamLen = [System.Text.Encoding]::ASCII.GetBytes($stream).Length
    $header = @"
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>endobj
4 0 obj<</Length $streamLen>>stream
"@
    $footer = @"
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
xref
0 6
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
0000000266 00000 n 
0000000373 00000 n 
trailer<</Size 6/Root 1 0 R>>
startxref
459
%%%%EOF
"@
    $content = $header + $stream + "`n" + $footer
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($content)
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
