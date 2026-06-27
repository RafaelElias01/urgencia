# Sobe o SGPUR (Windows / PowerShell).
# Uso:  .\start.ps1            -> perfil dev (H2)
#       .\start.ps1 prod       -> perfil prod (PostgreSQL/Neon via application-local.yml ou env vars)
param([string]$Perfil = "dev")

$ErrorActionPreference = "Stop"

# --- Java 21 ---
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = "C:\Users\rafae\Tools\jdk-21.0.11+10"
    if (Test-Path "$jdk\bin\java.exe") { $env:JAVA_HOME = $jdk }
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;" + $env:Path }

# --- Maven (PATH ou caminho conhecido) ---
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) {
    $cand = "C:\Users\rafae\Tools\apache-maven-3.9.6\bin\mvn.cmd"
    if (Test-Path $cand) { $mvn = $cand }
}
if (-not $mvn) { Write-Error "Maven nao encontrado. Instale o Maven ou ajuste o caminho em start.ps1."; exit 1 }

Write-Host "==> Subindo SGPUR | perfil: $Perfil | JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
& $mvn -DskipTests "-Dspring-boot.run.profiles=$Perfil" spring-boot:run
