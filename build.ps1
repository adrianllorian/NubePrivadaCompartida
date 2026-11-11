$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host '==> Compilando interfaz Angular...'
Push-Location (Join-Path $root 'ui')
npm install
npm run build
Pop-Location

$static = Join-Path $root 'backend\src\main\resources\static'
Remove-Item (Join-Path $static '*') -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $static | Out-Null
Copy-Item (Join-Path $root 'ui\dist\ui\browser\*') $static -Recurse -Force

Write-Host '==> Empaquetando backend...'
Push-Location (Join-Path $root 'backend')
mvn clean package -DskipTests
Pop-Location

Write-Host 'Listo: backend/target/NubePrivadaCompartida.jar'
