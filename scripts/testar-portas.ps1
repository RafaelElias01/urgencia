# Testar portas disponiveis
Write-Host "=== Testando portas..." -ForegroundColor Cyan

3000, 2500, 8080, 8081, 9090, 8888, 9000, 4200, 5173, 3001, 3002 | ForEach-Object {
    $port = $_
    $result = Test-NetConnection -ComputerName localhost -Port $port -WarningAction SilentlyContinue
    if ($result.TcpTestSucceeded) {
        Write-Host "❌ Porta $port OCUPADA" -ForegroundColor Red
    } else {
        Write-Host "✅ Porta $port LIVRE" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "=== Ver processo na porta 3000 (se ocupada):" -ForegroundColor Cyan
netstat -ano | findstr :3000

Write-Host ""
Write-Host "=== Ver processo na porta 2500 (se ocupada):" -ForegroundColor Cyan
netstat -ano | findstr :2500
