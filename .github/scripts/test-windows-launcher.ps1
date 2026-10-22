param(
    [Parameter(Mandatory)][string]$Bundle,
    [Parameter(Mandatory)][string]$Results,
    # Also start the app a second time with sync switched on and record
    # which process bound the sync port (listener.txt). Only for a
    # disposable machine: the bind raises the Windows firewall prompt
    # wherever no rule already allows it.
    [switch]$SyncListener
)
$ErrorActionPreference = 'Stop'
$bundleRoot = (Resolve-Path -LiteralPath $Bundle).Path
$resultRoot = [IO.Path]::GetFullPath($Results)
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
$vault = Join-Path $resultRoot 'vault with spaces and é'
New-Item -ItemType Directory -Path $vault -Force | Out-Null
Set-Content -LiteralPath (Join-Path $vault 'Keep.md') -Value 'Installer smoke test note'
$kernelDb = Join-Path $vault '.gilune/local/kernel.db'
$originalJavaOptions = $env:JAVA_TOOL_OPTIONS
$originalAppData = $env:LOCALAPPDATA

function OwnedProcesses([int]$rootId) {
    $rows = @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name)
    $owned = [Collections.Generic.HashSet[int]]::new()
    [void]$owned.Add($rootId)
    do {
        $before = $owned.Count
        foreach ($row in $rows) {
            if ($owned.Contains([int]$row.ParentProcessId)) { [void]$owned.Add([int]$row.ProcessId) }
        }
    } while ($owned.Count -ne $before)
    @($owned)
}

# Starts the launcher, waits for $Ready (given the launcher's process id)
# to return something, closes the window normally and returns what
# $Ready returned. The runtime is hosted inside Gilune.exe: the window
# must belong to the launcher's own process and no Java process may
# appear beneath it.
function Launch([scriptblock]$Ready, [string]$waitingFor) {
    $process = Start-Process -FilePath (Join-Path $bundleRoot 'Gilune.exe') -WorkingDirectory $resultRoot -WindowStyle Hidden -PassThru
    try {
        $deadline = (Get-Date).AddSeconds(45)
        $state = $null
        do {
            if ($process.HasExited) { throw "Launcher exited early: $($process.ExitCode)" }
            $owned = OwnedProcesses $process.Id
            $java = @(Get-Process -Id $owned -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -in @('java', 'javaw') })
            if ($java.Count -gt 0) { throw "The launcher started a Java process ($($java[0].Path)); the runtime must run inside Gilune.exe" }
            $window = @(Get-Process -Id $owned -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq 'Gilune' })
            if ($window.Count -gt 0) { $state = & $Ready $process.Id }
            if (-not $state) { Start-Sleep -Milliseconds 500 }
        } while (-not $state -and (Get-Date) -lt $deadline)
        if (-not $state) { throw "Launcher did not render a window and $waitingFor within 45 seconds" }
        if ($window[0].Id -ne $process.Id) {
            throw "The window belongs to process $($window[0].Id) ($($window[0].Path)), not to Gilune.exe"
        }
        foreach ($item in $window) { [void]$item.CloseMainWindow() }
        if (-not $process.WaitForExit(15000)) { throw 'Application did not close normally' }
        if ($process.ExitCode -ne 0) { throw "Application exited with $($process.ExitCode)" }
        return $state
    } finally {
        if (-not $process.HasExited) { & taskkill /PID $process.Id /T /F | Out-Null }
        $process.Dispose()
    }
}

try {
    $env:JAVA_TOOL_OPTIONS = '"-Dgilune.vault=' + $vault + '"'
    $env:LOCALAPPDATA = Join-Path $resultRoot 'local-app-data'
    New-Item -ItemType Directory -Path $env:LOCALAPPDATA -Force | Out-Null
    [void](Launch { Test-Path -LiteralPath $kernelDb } 'open the isolated vault')
    'PASS: bundled Java hosted in Gilune.exe, rendered window, native vault open and normal close' |
        Set-Content -LiteralPath (Join-Path $resultRoot 'launcher.txt')
    if ($SyncListener) {
        # Sync is remembered per device beside the identity, keyed by the
        # vault's device id; the first run minted that id into kernel.db.
        # Switching it on there and starting again is what a user's second
        # launch with the switch left on does.
        $latin1 = [Text.Encoding]::GetEncoding(28591)
        $text = $latin1.GetString([IO.File]::ReadAllBytes($kernelDb))
        if (Test-Path -LiteralPath "$kernelDb-wal") { $text += $latin1.GetString([IO.File]::ReadAllBytes("$kernelDb-wal")) }
        $match = [regex]::Match($text, 'device_id([0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})')
        if (-not $match.Success) { throw 'The vault records no device id to switch sync on for' }
        $syncDir = Join-Path $env:LOCALAPPDATA 'Gilune/sync'
        New-Item -ItemType Directory -Path $syncDir -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $syncDir ($match.Groups[1].Value + '.sync.json')), '{"enabled":true}')
        $listener = Launch {
            param($launcherId)
            $rows = @(Get-NetTCPConnection -State Listen -OwningProcess $launcherId -ErrorAction SilentlyContinue)
            if ($rows.Count -gt 0) { $rows[0] }
        } 'bind the sync port'
        $owner = Get-Process -Id $listener.OwningProcess
        @{ pid = $listener.OwningProcess; exe = $owner.Path; name = $owner.ProcessName; port = $listener.LocalPort } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $resultRoot 'listener.txt')
        if ($owner.ProcessName -ne 'Gilune') { throw "The sync port is owned by $($owner.Path), not Gilune.exe" }
    }
} finally {
    $env:JAVA_TOOL_OPTIONS = $originalJavaOptions
    $env:LOCALAPPDATA = $originalAppData
}
