param(
    [Parameter(Mandatory)][string]$Bundle,
    [Parameter(Mandatory)][string]$Results
)
$ErrorActionPreference = 'Stop'
$bundleRoot = (Resolve-Path -LiteralPath $Bundle).Path
$resultRoot = [IO.Path]::GetFullPath($Results)
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
$vault = Join-Path $resultRoot 'vault with spaces and é'
New-Item -ItemType Directory -Path $vault -Force | Out-Null
Set-Content -LiteralPath (Join-Path $vault 'Keep.md') -Value 'Installer smoke test note'
$originalJavaOptions = $env:JAVA_TOOL_OPTIONS
$originalAppData = $env:LOCALAPPDATA
$process = $null
try {
    $env:JAVA_TOOL_OPTIONS = '"-Dgilune.vault=' + $vault + '"'
    $env:LOCALAPPDATA = Join-Path $resultRoot 'local-app-data'
    New-Item -ItemType Directory -Path $env:LOCALAPPDATA -Force | Out-Null
    $launchOptions = @{
        FilePath = Join-Path $bundleRoot 'Gilune.exe'
        WorkingDirectory = $resultRoot
        WindowStyle = 'Hidden'
        PassThru = $true
    }
    $process = Start-Process @launchOptions
    $deadline = (Get-Date).AddSeconds(45)
    $ready = $false
    do {
        if ($process.HasExited) { throw "Launcher exited early: $($process.ExitCode)" }
        $rows = @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name)
        $owned = [Collections.Generic.HashSet[int]]::new()
        [void]$owned.Add($process.Id)
        do {
            $before = $owned.Count
            foreach ($row in $rows) {
                if ($owned.Contains([int]$row.ParentProcessId)) { [void]$owned.Add([int]$row.ProcessId) }
            }
        } while ($owned.Count -ne $before)
        $window = @(foreach ($id in $owned) {
            Get-Process -Id $id -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq 'Gilune' }
        })
        $ready = $window.Count -gt 0 -and (Test-Path -LiteralPath (Join-Path $vault '.gilune/local/kernel.db'))
        if (-not $ready) { Start-Sleep -Milliseconds 500 }
    } while (-not $ready -and (Get-Date) -lt $deadline)
    if (-not $ready) { throw 'Launcher did not render a window and open the isolated vault within 45 seconds' }
    foreach ($item in $window) { [void]$item.CloseMainWindow() }
    if (-not $process.WaitForExit(15000)) { throw 'Application did not close normally' }
    if ($process.ExitCode -ne 0) { throw "Application exited with $($process.ExitCode)" }
    'PASS: bundled Java, launcher, rendered window, native vault open and normal close' |
        Set-Content -LiteralPath (Join-Path $resultRoot 'launcher.txt')
} finally {
    if ($process) {
        if (-not $process.HasExited) { & taskkill /PID $process.Id /T /F | Out-Null }
        $process.Dispose()
    }
    $env:JAVA_TOOL_OPTIONS = $originalJavaOptions
    $env:LOCALAPPDATA = $originalAppData
}
