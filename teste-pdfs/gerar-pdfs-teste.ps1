param([string]$Dir = ".")

function New-MinimalPdf {
    param([string]$Path, [string]$Title)
    $content = @"
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
4 0 obj<</Length $([System.Text.Encoding::UTF8.GetBytes("BT /F1 16 Tf 50 750 Td ($Title) Tj ET").Length)>>stream
BT /F1 16 Tf 50 750 Td ($Title) Tj ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
6 0 obj<</Length 60>>stream
BT /F1 10 Tf 50 700 Td (SAUR - Documento de teste gerado em $(Get-Date -Format 'dd/MM/yyyy HH:mm')) Tj ET
endstream
endobj
xref
0 7
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
0000000266 00000 n 
0000000373 00000 n 
0000000477 00000 n 
trailer<</Size 7/Root 1 0 R>>
startxref
607
%%EOF
"@
    # Calcula offsets corretos pro xref
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($content)
    # Recalcula xref com offsets reais
    $lines = $content -split "`n"
    $xrefOffset = $content.IndexOf("xref")
    $newContent = @"
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>endobj
4 0 obj<</Length $([System.Text.Encoding::UTF8.GetBytes("BT /F1 16 Tf 50 750 Td ($Title) Tj ET").Length)>>stream
BT /F1 16 Tf 50 750 Td ($Title) Tj ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
xref
0 6
0000000000 65535 f 
$("{0:D10}" -f 9) 00000 n 
$("{0:D10}" -f 58) 00000 n 
$("{0:D10}" -f 115) 00000 n 
$("{0:D10}" -f 266) 00000 n 
$("{0:D10}" -f 373) 00000 n 
trailer<</Size 6/Root 1 0 R>>
startxref
$([byte[]][char[]]"x")0000
%%%%EOF
"@
    # Offset simples
    [System.IO.File]::WriteAllText($Path, $newContent, [System.Text.Encoding]::ASCII)
    Write-Host "  [+] $Path"
}

Write-Host "=== Gerando PDFs de teste para o SAUR ===" -ForegroundColor Green
Write-Host ""

# Passo 1 - Recebimento
New-MinimalPdf -Path (Join-Path $Dir "solicitacao-recebida.pdf") -Title "SOLICITACAO RECEBIDA - Processo CET-RS"
Write-Host "  (anexar como SOLICITACAO_RECEBIDA no Passo 1)" -ForegroundColor Cyan

# Passo 2 - Envio (documentos clinicos)
New-MinimalPdf -Path (Join-Path $Dir "documento-clinico-1.pdf") -Title "DOCUMENTO CLINICO 1 - Relatorio medico"
New-MinimalPdf -Path (Join-Path $Dir "documento-clinico-2.pdf") -Title "DOCUMENTO CLINICO 2 - Exames laboratoriais"
Write-Host "  (anexar como DOCUMENTO_CLINICO_AVALIADOR no Passo 2)" -ForegroundColor Cyan

# Passo 3 - Respostas dos avaliadores
New-MinimalPdf -Path (Join-Path $Dir "resposta-avaliador-1.pdf") -Title "RESPOSTA AVALIADOR 1 - PARECER FAVORAVEL"
New-MinimalPdf -Path (Join-Path $Dir "resposta-avaliador-2.pdf") -Title "RESPOSTA AVALIADOR 2 - PARECER FAVORAVEL"
New-MinimalPdf -Path (Join-Path $Dir "resposta-avaliador-3.pdf") -Title "RESPOSTA AVALIADOR 3 - PARECER DESFAVORAVEL"
Write-Host "  (anexar como RESPOSTA_AVALIADOR no Passo 3)" -ForegroundColor Cyan

# Passo 5 - Finalizacao
New-MinimalPdf -Path (Join-Path $Dir "oficio-indeferimento.pdf") -Title "OFICIO DE INDEFERIMENTO - Processo CET-RS"
Write-Host "  (anexar como OFICIO_INDEFERIMENTO - para INDEFERIDO)" -ForegroundColor Cyan

New-MinimalPdf -Path (Join-Path $Dir "comprovante-snt.pdf") -Title "COMPROVANTE SNT - Insercao Urgencia Renal"
Write-Host "  (anexar como COMPROVANTE_SNT - para DEFERIDO)" -ForegroundColor Cyan

# Passo 6 - Resposta ao solicitante
New-MinimalPdf -Path (Join-Path $Dir "comprovante-envio.pdf") -Title "COMPROVANTE DE ENVIO - Resposta ao solicitante"
Write-Host "  (anexar como COMPROVANTE_ENVIO_SOLICITANTE no Passo 6)" -ForegroundColor Cyan

Write-Host ""
Write-Host "Total: 8 PDFs gerados em: $Dir" -ForegroundColor Green
Write-Host ""
Write-Host "=== FLUXO DE TESTE ===" -ForegroundColor Yellow
Write-Host "1. Crie um processo (Status: SOLICITADO)"
Write-Host "2. Passo 1: anexe solicitacao-recebida.pdf"
Write-Host "3. Passo 2: anexe os 2 documentos clinicos + registre envio"
Write-Host "4. Passo 3: anexe as 3 respostas dos avaliadores"
Write-Host "5. Passo 4: decida (DEFERIDO - precisa de 2 favoraveis)"
Write-Host "6. Passo 5 (DEFERIDO): anexe comprovante-snt.pdf"
Write-Host "7. Passo 6: anexe comprovante-envio.pdf"
Write-Host ""
Write-Host "Ou teste INDEFERIDO: nas respostas, faca 2 desfavoraveis"
Write-Host "e no Passo 5 anexe oficio-indeferimento.pdf"
