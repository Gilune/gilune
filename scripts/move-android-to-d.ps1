<#
.SYNOPSIS
  Moves this workstation's Android SDK and emulator data from C: to D:.

.DESCRIPTION
  The normal run copies data, rewrites the AVD and Gilune project paths, and
  updates environment variables. It deliberately leaves C:\Android in place.
  Run real migrations from an Administrator PowerShell after closing Android
  Studio and every emulator. -WhatIf is safe to run without elevation.

  Run scripts/verify-android-on-d.ps1 after restarting Android Studio and any
  terminals. Only use -Finalize after verification succeeds; that mode syncs
  the source one final time, verifies every source file by relative path and
  length, and then asks before deleting C:\Android.

.EXAMPLE
  pwsh scripts/move-android-to-d.ps1 -WhatIf

.EXAMPLE
  pwsh scripts/move-android-to-d.ps1 -UpdateMachineEnvironment

.EXAMPLE
  pwsh scripts/move-android-to-d.ps1 -InstallSdkPackages -UpdateMachineEnvironment

.EXAMPLE
  pwsh scripts/move-android-to-d.ps1 -Finalize -UpdateMachineEnvironment
#>

[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'Medium')]
param(
    [string] $DestinationRoot = 'D:\Android',
    [switch] $InstallSdkPackages,
    [switch] $UpdateMachineEnvironment,
    [switch] $Finalize,
    [string[]] $SdkPackage = @(
        'platform-tools',
        'emulator',
        'platforms;android-36',
        'build-tools;36.0.0',
        'ndk;30.0.16138531',
        'system-images;android-36.1;google_apis;x86_64'
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$sourceRoot = [IO.Path]::GetFullPath('C:\Android').TrimEnd('\')
$destinationRoot = [IO.Path]::GetFullPath($DestinationRoot).TrimEnd('\')
$sourceSdk = Join-Path $sourceRoot 'android-sdk'
$destinationSdk = Join-Path $destinationRoot 'Sdk'
$sourceAndroidHome = Join-Path $sourceRoot '.android'
$destinationAndroidHome = Join-Path $destinationRoot '.android'
$destinationAvdHome = Join-Path $destinationAndroidHome 'avd'
$destinationBuild = Join-Path $destinationRoot 'build\gilune-android'
$repoRoot = Split-Path $PSScriptRoot -Parent
$verificationScript = Join-Path $PSScriptRoot 'verify-android-on-d.ps1'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-SafePaths {
    if ($sourceRoot -ne 'C:\Android') {
        throw "Unexpected source path: $sourceRoot"
    }

    if ([IO.Path]::GetPathRoot($destinationRoot) -eq 'C:\') {
        throw "Destination must be on a drive other than C:. Received: $destinationRoot"
    }

    if ($sourceRoot -eq $destinationRoot) {
        throw 'Source and destination resolve to the same directory.'
    }
}

function Assert-AndroidAppsStopped {
    $blocked = Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -eq 'studio64' -or
        $_.ProcessName -eq 'emulator' -or
        $_.ProcessName -like 'qemu-system-*'
    }

    if ($blocked) {
        $summary = ($blocked | ForEach-Object { "$($_.ProcessName) (PID $($_.Id))" }) -join ', '
        if ($WhatIfPreference) {
            Write-Warning "A real run will stop here until these applications are closed: $summary"
            return
        }
        throw "Close Android Studio and every emulator before continuing. Still running: $summary"
    }

    $adbProcesses = Get-Process -Name adb -ErrorAction SilentlyContinue
    foreach ($process in $adbProcesses) {
        if ($PSCmdlet.ShouldProcess("adb PID $($process.Id)", 'Stop ADB before copying Android data')) {
            Stop-Process -Id $process.Id -Force
        }
    }
}

function Get-TreeStats {
    param([Parameter(Mandatory)][string] $Path)

    $traversalErrors = @()
    $files = @(Get-ChildItem -LiteralPath $Path -File -Recurse -Force `
        -ErrorAction SilentlyContinue -ErrorVariable traversalErrors)
    if ($traversalErrors.Count -gt 0) {
        if (-not $WhatIfPreference) {
            throw "Could not inspect every file under $Path. Run the script from an Administrator PowerShell."
        }
        Write-Warning "Dry-run size estimate skipped protected files under $Path."
    }
    $bytes = ($files | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $bytes) { $bytes = 0 }
    [PSCustomObject]@{ Files = $files.Count; Bytes = [int64]$bytes }
}

function Copy-Tree {
    param(
        [Parameter(Mandatory)][string] $Source,
        [Parameter(Mandatory)][string] $Destination
    )

    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "Source directory does not exist: $Source"
    }

    if (-not $PSCmdlet.ShouldProcess($Destination, "Copy $Source with Robocopy")) {
        return
    }

    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    & robocopy.exe $Source $Destination /E /COPY:DAT /DCOPY:DAT /R:2 /W:2 /XJ /SPARSE:Y /ZB /MT:8
    $robocopyExit = $LASTEXITCODE
    if ($robocopyExit -ge 8) {
        throw "Robocopy failed for $Source with exit code $robocopyExit."
    }
}

function Assert-SourceMirrored {
    param(
        [Parameter(Mandatory)][string] $Source,
        [Parameter(Mandatory)][string] $Destination
    )

    $missing = [Collections.Generic.List[string]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $Source -File -Recurse -Force) {
        $relative = $file.FullName.Substring($Source.Length).TrimStart('\')
        $copy = Join-Path $Destination $relative
        if (-not (Test-Path -LiteralPath $copy -PathType Leaf)) {
            $missing.Add("missing: $relative")
        }
        elseif ((Get-Item -LiteralPath $copy).Length -ne $file.Length) {
            $missing.Add("different length: $relative")
        }

        if ($missing.Count -ge 20) { break }
    }

    if ($missing.Count -gt 0) {
        throw "Destination verification failed:`n$($missing -join "`n")"
    }
}

function Set-EnvironmentValue {
    param(
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][string] $Value,
        [Parameter(Mandatory)][ValidateSet('User', 'Machine')][string] $Scope
    )

    if ($PSCmdlet.ShouldProcess("$Scope environment variable $Name", "Set to $Value")) {
        [Environment]::SetEnvironmentVariable($Name, $Value, $Scope)
    }
}

function Update-PathScope {
    param([Parameter(Mandatory)][ValidateSet('User', 'Machine')][string] $Scope)

    $oldAndroidEntries = @(
        'C:\Android',
        'C:\Android\android-sdk\tools',
        'C:\Android\android-sdk\tools\bin',
        'C:\Android\android-sdk\platform-tools'
    )
    $newAndroidEntries = @(
        (Join-Path $destinationSdk 'platform-tools'),
        (Join-Path $destinationSdk 'emulator'),
        (Join-Path $destinationSdk 'cmdline-tools\latest\bin')
    )
    $existing = [Environment]::GetEnvironmentVariable('Path', $Scope)
    $entries = @($existing -split ';' | Where-Object { $_ })
    $filtered = foreach ($entry in $entries) {
        $normalized = $entry.Trim().TrimEnd('\')
        if ($oldAndroidEntries -notcontains $normalized -and $newAndroidEntries -notcontains $normalized) {
            $entry
        }
    }
    $updated = (@($newAndroidEntries) + @($filtered)) -join ';'

    if ($PSCmdlet.ShouldProcess("$Scope PATH", 'Remove C:\Android entries and prepend D-drive Android tools')) {
        [Environment]::SetEnvironmentVariable('Path', $updated, $Scope)
    }
}

function Set-AndroidEnvironment {
    param([Parameter(Mandatory)][ValidateSet('User', 'Machine')][string] $Scope)

    Set-EnvironmentValue -Name 'ANDROID_HOME' -Value $destinationSdk -Scope $Scope
    # Deprecated, but kept equal to ANDROID_HOME for tools that still read it.
    Set-EnvironmentValue -Name 'ANDROID_SDK_ROOT' -Value $destinationSdk -Scope $Scope
    Set-EnvironmentValue -Name 'ANDROID_USER_HOME' -Value $destinationAndroidHome -Scope $Scope
    Set-EnvironmentValue -Name 'ANDROID_EMULATOR_HOME' -Value $destinationAndroidHome -Scope $Scope
    Set-EnvironmentValue -Name 'ANDROID_AVD_HOME' -Value $destinationAvdHome -Scope $Scope
    # Legacy tools derive .android from this directory.
    Set-EnvironmentValue -Name 'ANDROID_SDK_HOME' -Value $destinationRoot -Scope $Scope
    Update-PathScope -Scope $Scope
}

function Update-AvdPaths {
    if (-not (Test-Path -LiteralPath $destinationAvdHome)) { return }

    foreach ($ini in Get-ChildItem -LiteralPath $destinationAvdHome -Filter '*.ini' -File) {
        $avdDirectory = Join-Path $destinationAvdHome ($ini.BaseName + '.avd')
        if (-not (Test-Path -LiteralPath $avdDirectory -PathType Container)) { continue }

        $content = [IO.File]::ReadAllText($ini.FullName)
        $newPathLine = 'path=' + $avdDirectory
        if ($content -match '(?m)^path=.*$') {
            $updated = [Text.RegularExpressions.Regex]::Replace($content, '(?m)^path=.*$', $newPathLine)
        }
        else {
            $updated = $content.TrimEnd() + [Environment]::NewLine + $newPathLine + [Environment]::NewLine
        }

        if ($updated -ne $content -and $PSCmdlet.ShouldProcess($ini.FullName, "Set AVD path to $avdDirectory")) {
            [IO.File]::WriteAllText($ini.FullName, $updated, [Text.UTF8Encoding]::new($false))
        }
    }
}

function Update-LocalProperties {
    $propertiesFiles = @(
        (Join-Path $repoRoot 'app\local.properties'),
        (Join-Path $repoRoot 'spikes\s1-kernel-boundary\shell\local.properties')
    )
    $sdkLine = 'sdk.dir=' + $destinationSdk.Replace('\', '/')

    foreach ($file in $propertiesFiles) {
        if (Test-Path -LiteralPath $file) {
            $content = [IO.File]::ReadAllText($file)
            if ($content -match '(?m)^sdk\.dir=.*$') {
                $updated = [Text.RegularExpressions.Regex]::Replace($content, '(?m)^sdk\.dir=.*$', $sdkLine)
            }
            else {
                $updated = $content.TrimEnd() + [Environment]::NewLine + $sdkLine + [Environment]::NewLine
            }
        }
        else {
            $updated = $sdkLine + [Environment]::NewLine
        }

        if ($PSCmdlet.ShouldProcess($file, "Set $sdkLine")) {
            [IO.File]::WriteAllText($file, $updated, [Text.UTF8Encoding]::new($false))
        }
    }
}

function Install-RequiredSdkPackages {
    $legacyManager = Join-Path $destinationSdk 'tools\bin\sdkmanager.bat'
    $currentManager = Join-Path $destinationSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    $sdkManager = if (Test-Path -LiteralPath $currentManager) { $currentManager } else { $legacyManager }

    if (-not (Test-Path -LiteralPath $sdkManager -PathType Leaf)) {
        throw "sdkmanager.bat was not found under $destinationSdk. Install Android SDK Command-line Tools from Android Studio, then rerun this script with -InstallSdkPackages."
    }

    if ($sdkManager -eq $legacyManager -and $PSCmdlet.ShouldProcess($destinationSdk, 'Install current Android SDK Command-line Tools')) {
        & $legacyManager "--sdk_root=$destinationSdk" 'cmdline-tools;latest'
        if ($LASTEXITCODE -ne 0) { throw 'Installing cmdline-tools;latest failed.' }
        $sdkManager = $currentManager
    }

    if ($PSCmdlet.ShouldProcess($destinationSdk, "Install SDK packages: $($SdkPackage -join ', ')")) {
        Write-Host 'sdkmanager may ask you to review and accept Android SDK licences.'
        & $sdkManager "--sdk_root=$destinationSdk" @SdkPackage
        if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE." }
    }
}

Assert-SafePaths
if (-not $WhatIfPreference -and -not (Test-Administrator)) {
    throw 'Run this migration from PowerShell as Administrator so protected SDK files and machine configuration can be handled safely.'
}
Assert-AndroidAppsStopped

if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
    if ($Finalize) {
        throw "$sourceRoot no longer exists; there is nothing left to finalize."
    }
    throw "Android source directory was not found: $sourceRoot"
}

if (-not $Finalize) {
    $sourceStats = Get-TreeStats -Path $sourceRoot
    $destinationDriveName = ([IO.Path]::GetPathRoot($destinationRoot)).TrimEnd('\').TrimEnd(':')
    $destinationDrive = Get-PSDrive -Name $destinationDriveName
    if ($destinationDrive.Free -lt $sourceStats.Bytes) {
        throw "Not enough free space on $($destinationDrive.Root). Need $([math]::Round($sourceStats.Bytes / 1GB, 2)) GiB."
    }
}

Copy-Tree -Source $sourceSdk -Destination $destinationSdk
Copy-Tree -Source $sourceAndroidHome -Destination $destinationAndroidHome
Update-AvdPaths
Update-LocalProperties
Set-AndroidEnvironment -Scope User
Set-EnvironmentValue -Name 'GILUNE_ANDROID_TARGET_DIR' -Value $destinationBuild -Scope User

if ($UpdateMachineEnvironment) {
    Set-AndroidEnvironment -Scope Machine
}

if ($InstallSdkPackages) {
    Install-RequiredSdkPackages
}

if ($Finalize) {
    Assert-SourceMirrored -Source $sourceSdk -Destination $destinationSdk
    Assert-SourceMirrored -Source $sourceAndroidHome -Destination $destinationAndroidHome

    & $verificationScript -DestinationRoot $destinationRoot
    if ($LASTEXITCODE -ne 0) {
        throw 'D-drive verification failed. C:\Android has not been deleted.'
    }

    if ($PSCmdlet.ShouldProcess($sourceRoot, 'Permanently delete the verified C-drive Android source')) {
        if (-not $PSCmdlet.ShouldContinue(
            'The D-drive copy passed verification. Permanently delete C:\Android now?',
            'Final Android migration cleanup'
        )) {
            Write-Host 'C:\Android was retained.'
            return
        }
        $resolvedSource = (Resolve-Path -LiteralPath $sourceRoot).Path.TrimEnd('\')
        if ($resolvedSource -ne 'C:\Android') {
            throw "Deletion safety check rejected unexpected path: $resolvedSource"
        }
        Remove-Item -LiteralPath $resolvedSource -Recurse -Force
        Write-Host 'Removed C:\Android after copy and verification. This deletion is not recoverable from the Recycle Bin.'
    }
}
else {
    Write-Host ''
    if ($WhatIfPreference) {
        Write-Host 'Dry run complete. No files or environment variables were changed.'
        return
    }
    Write-Host 'Copy and configuration phase complete. C:\Android has NOT been deleted.'
    Write-Host 'Restart Android Studio and all terminals, install any missing SDK packages, then run:'
    Write-Host '  pwsh scripts/verify-android-on-d.ps1 -Build'
    Write-Host 'After verification succeeds, reclaim the C-drive space with:'
    Write-Host '  pwsh scripts/move-android-to-d.ps1 -Finalize -UpdateMachineEnvironment'
}
