# Instala el centro de control "casa" en Windows para que el Asistente de la Anbernic
# pueda controlar esta PC. Ejecutar en PowerShell COMO ADMINISTRADOR desde esta carpeta:
#   powershell -ExecutionPolicy Bypass -File .\instalar-windows.ps1

$ErrorActionPreference = "Stop"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $dir

# 1. uv (gestor de Python)
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    Write-Host "Instalando uv..."
    powershell -ExecutionPolicy Bypass -Command "irm https://astral.sh/uv/install.ps1 | iex"
    $env:Path = "$env:USERPROFILE\.local\bin;$env:Path"
}

# 2. Dependencias
Write-Host "Instalando dependencias..."
uv sync

# 3. Firewall: permitir el puerto 8800 en la red local
if (-not (Get-NetFirewallRule -DisplayName "Asistente casa" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName "Asistente casa" -Direction Inbound -Protocol TCP -LocalPort 8800 -Action Allow -Profile Private,Domain | Out-Null
}

# 4. Arranque automático al iniciar sesión (oculto)
$uv = (Get-Command uv).Source
$accion = New-ScheduledTaskAction -Execute $uv -Argument "run --directory `"$dir`" pythonw casa.py" -WorkingDirectory $dir
$disparo = New-ScheduledTaskTrigger -AtLogOn
$ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "Asistente casa" -Action $accion -Trigger $disparo -Settings $ajustes -Force | Out-Null
Start-ScheduledTask -TaskName "Asistente casa"
Start-Sleep 6

# 5. Wake-on-LAN: que la PC se pueda prender desde la consola
$red = Get-NetAdapter -Physical | Where-Object { $_.Status -eq "Up" } | Sort-Object -Property @{Expression={$_.MediaType -ne "802.3"}} | Select-Object -First 1
Get-NetAdapter -Physical | ForEach-Object {
    try { Set-NetAdapterPowerManagement -Name $_.Name -WakeOnMagicPacket Enabled -ErrorAction Stop } catch {}
    try { Set-NetAdapterAdvancedProperty -Name $_.Name -DisplayName "Wake on Magic Packet" -DisplayValue "Enabled" -ErrorAction Stop } catch {}
}
# El "inicio rápido" de Windows impide prender por red cuando la PC está apagada
powercfg /h on | Out-Null
reg add "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Power" /v HiberbootEnabled /t REG_DWORD /d 0 /f | Out-Null

# 5. Datos para la app de la consola
$token = (Get-Content "$dir\.env" | Where-Object { $_ -like "CASA_TOKEN=*" }) -replace "CASA_TOKEN=", ""
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.PrefixOrigin -eq "Dhcp" } | Select-Object -First 1).IPAddress
$linea = "windows|http://${ip}:8800/mcp|$token"
$mac = ($red.MacAddress -replace "-", ":")
$lineaWol = "pc|$mac|$ip"
Set-Content -Path "$dir\linea-para-la-consola.txt" -Value @($linea, $lineaWol)
Write-Host ""
Write-Host "Listo. En la app Monki de la consola:" -ForegroundColor Green
Write-Host "1) Servidores MCP externos:" -ForegroundColor Green
Write-Host "   $linea" -ForegroundColor Yellow
Write-Host "2) Computadoras para prender:" -ForegroundColor Green
Write-Host "   $lineaWol" -ForegroundColor Yellow
Write-Host "Conectada por: $($red.Name) ($($red.MediaType)). Para prender desde apagada funciona mejor por cable." -ForegroundColor Cyan
Write-Host "Si no prende, activa 'Wake on LAN' / 'Power On By PCI-E' en la BIOS." -ForegroundColor Cyan
