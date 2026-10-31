# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The desktop window as a package installs it: njmule-gui.exe tells its version, opens on a profile of its own with
# the networks off and starts njmule.exe beside it, whose control endpoint answers; closed, it takes the engine with
# it. release.yml runs it on the files it packs and on the installed MSI; on failure it prints the engine's log.
#   desktop-smoke-test.ps1 <folder with njmule-gui.exe and njmule.exe>
param([Parameter(Mandatory = $true)] [string] $Folder)

$ErrorActionPreference = "Stop"
# Whole, as Windows names the running engine.
$Folder = (Resolve-Path $Folder).Path
$window = Join-Path $Folder "njmule-gui.exe"
$engine = Join-Path $Folder "njmule.exe"

$version = (& $window --version | Out-String).Trim()
"The window says: $version"
if ($version -notmatch "^njMule ") { throw "njmule-gui.exe --version wrote '$version'" }

$data = Join-Path ([IO.Path]::GetTempPath()) "njmule-desktop-smoke"
Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $data "config") | Out-Null
Set-Content -Encoding ascii (Join-Path $data "config\njmule.properties") `
    "net.ed2k.enabled=false`nnet.kad.enabled=false`nnet.upnp=false`nupdate.mode=OFF"

# The engine makes its log folder as it starts.
$logs = Join-Path $data "logs"
$gui = Start-Process $window -ArgumentList @("--config-dir", "`"$data`"") -PassThru
$started = $false
foreach ($i in 1..120) {
    if ((Test-Path $logs) -and (Select-String -Quiet "Control endpoint for the desktop app" (Join-Path $logs "*.log"))) {
        $started = $true
        break
    }
    Start-Sleep 1
}
if (-not $started) {
    if (Test-Path $logs) { Get-Content (Join-Path $logs "*.log") | Select-Object -Last 40 }
    Stop-Process -Id $gui.Id -Force -ErrorAction SilentlyContinue
    throw "the desktop window did not start the engine"
}
"The window started the engine"

# Closed, its input pipe to the engine closes too: the engine saves and stops.
Stop-Process -Id $gui.Id -Force
$stopped = $false
foreach ($i in 1..60) {
    $left = Get-Process njmule -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $engine }
    if (-not $left) {
        $stopped = $true
        break
    }
    Start-Sleep 1
}
if (-not $stopped) { throw "the engine did not stop with the window" }
"The engine stopped with the window"
Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue
