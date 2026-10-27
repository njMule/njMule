# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The real Windows service, run as an administrator (the CI runners are): njmule --service=install sets it up on
# %ProgramData%\njMule with its networks off, as in the Docker smoke test; its web interface answers; and
# --service=uninstall stops njMule cleanly, without the service host ending it, and removes it. build.yml runs it
# on pull requests and release.yml before packing; on failure it prints why.
#   windows-service-test.ps1 <njmule.exe, with njmule-service.exe beside it>
param([Parameter(Mandatory = $true)] [string] $Program)

$ErrorActionPreference = "Stop"
# How long njmule-service.exe waits for njMule to stop before it ends it (STOP_WAIT_MILLIS of its service.c).
$stopLimit = [TimeSpan]::FromSeconds(60)
$exe = (Resolve-Path $Program).Path
$data = Join-Path $env:ProgramData njMule
$config = Join-Path $data config
$password = (New-TemporaryFile).FullName
$started = Get-Date
$removal = $null
try {
    New-Item -ItemType Directory -Force $config | Out-Null
    Set-Content -Encoding ascii (Join-Path $config njmule.properties) "net.ed2k.enabled=false`nnet.kad.enabled=false`nnet.upnp=false"
    Set-Content -Encoding ascii $password "service-test-password"
    & $exe --service=install --webui-password-file $password
    if ($LASTEXITCODE -ne 0) { throw "the service was not installed" }
    $answered = $false
    foreach ($i in 1..60) {
        try { Invoke-WebRequest -UseBasicParsing http://127.0.0.1:4711/login | Out-Null; $answered = $true; break } catch { Start-Sleep 1 }
    }
    & $exe --service=status
    if ($LASTEXITCODE -ne 0) { throw "the service does not run" }
    if (-not $answered) { throw "the service's web interface does not answer" }
    $removal = [Diagnostics.Stopwatch]::StartNew()
    & $exe --service=uninstall
    $removal.Stop()
    if ($LASTEXITCODE -ne 0) { throw "the service was not removed" }
    & $exe --service=status
    if ($LASTEXITCODE -ne 1) { throw "the service is still there" }
    if (-not (Select-String -Quiet "Stopping njMule" (Join-Path $data "logs\*.log"))) { throw "njMule did not stop cleanly" }
    if ($removal.Elapsed -ge $stopLimit) { throw "njMule did not stop within $($stopLimit.TotalSeconds) s and was ended" }
} catch {
    $failure = $_
    # Whatever can still be read, without hiding the failure.
    $ErrorActionPreference = "Continue"
    if ($removal) {
        Write-Host ("--service=uninstall took {0:N1} s." -f $removal.Elapsed.TotalSeconds)
    } else {
        Write-Host "--service=uninstall did not run."
    }
    $log = Get-ChildItem (Join-Path $data logs) -Filter *.log -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 1
    if ($log) {
        Write-Host "The last lines of $($log.FullName):"
        Get-Content -Tail 60 $log.FullName | Out-Host
    } else {
        Write-Host "njMule wrote no log in $(Join-Path $data logs)."
    }
    # The service's starts, stops and failures, with the error code it ended with.
    Get-WinEvent -ErrorAction SilentlyContinue -FilterHashtable @{
        LogName = "System"; ProviderName = "Service Control Manager"; StartTime = $started } |
        Where-Object Message -Match njMule | Sort-Object TimeCreated | Format-List TimeCreated, Id, Message | Out-Host
    throw $failure
} finally {
    Remove-Item -Force -ErrorAction SilentlyContinue $password
}
Write-Host ("--service=uninstall took {0:N1} s." -f $removal.Elapsed.TotalSeconds)
Remove-Item -Recurse -Force $data
exit 0
