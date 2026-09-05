<#
.SYNOPSIS
  Verifies that Gilune's Android SDK, emulator, and project use D:\Android.

.EXAMPLE
  pwsh scripts/verify-android-on-d.ps1

.EXAMPLE
  pwsh scripts/verify-android-on-d.ps1 -Build
#>

[CmdletBinding()]
param(
    [string] $DestinationRoot = 'D:\Android',
    [switch] $Build
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$destinationRoot = [IO.Path]::GetFullPath($DestinationRoot).TrimEnd('\')
$sdk = Join-Path $destinationRoot 'Sdk'
$androidHome = Join-Path $destinationRoot '.android'
$avdHome = Join-Path $androidHome 'avd'
$repoRoot = Split-Path $PSScriptRoot -Parent
$failures = [Collections.Generic.List[string]]::new()

function Test-RequiredPath {
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][string] $Description,
        [ValidateSet('Leaf', 'Container')][string] $PathType = 'Container'
    )

    if (-not (Test-Path -LiteralPath $Path -PathType $PathType)) {
        $failures.Add("Missing $Description`: $Path")
        return $false
    }
    Write-Host "OK  $Description`: $Path"
    return $true
}

function Test-EnvironmentValue {
    param(
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][string] $Expected
    )

    $actual = [Environment]::GetEnvironmentVariable($Name, 'User')
    if ($actual -ne $Expected) {
        $failures.Add("User environment variable $Name is '$actual'; expected '$Expected'.")
    }
    else {
        Write-Host "OK  $Name=$actual"
    }
}

Test-EnvironmentValue -Name 'ANDROID_HOME' -Expected $sdk
Test-EnvironmentValue -Name 'ANDROID_USER_HOME' -Expected $androidHome
Test-EnvironmentValue -Name 'ANDROID_AVD_HOME' -Expected $avdHome

$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
Test-RequiredPath -Path $adb -Description 'ADB' -PathType Leaf | Out-Null
Test-RequiredPath -Path $emulator -Description 'Android emulator' -PathType Leaf | Out-Null
Test-RequiredPath -Path (Join-Path $sdk 'platforms\android-36\android.jar') -Description 'Android 36 platform' -PathType Leaf | Out-Null
Test-RequiredPath -Path (Join-Path $sdk 'system-images\android-36.1\google_apis\x86_64') -Description 'gilune-s1 system image' | Out-Null
Test-RequiredPath -Path (Join-Path $sdk 'ndk') -Description 'Android NDK directory' | Out-Null

$avdIni = Join-Path $avdHome 'gilune-s1.ini'
if (Test-RequiredPath -Path $avdIni -Description 'gilune-s1 AVD definition' -PathType Leaf) {
    $expectedAvdPath = Join-Path $avdHome 'gilune-s1.avd'
    $avdContent = [IO.File]::ReadAllText($avdIni)
    if ($avdContent -notmatch "(?m)^path=$([Regex]::Escape($expectedAvdPath))\r?$") {
        $failures.Add("$avdIni does not point to $expectedAvdPath")
    }
    else {
        Write-Host "OK  gilune-s1 data path: $expectedAvdPath"
    }
}

$propertiesFiles = @(
    (Join-Path $repoRoot 'app\local.properties'),
    (Join-Path $repoRoot 'spikes\s1-kernel-boundary\shell\local.properties')
)
$expectedSdkLine = 'sdk.dir=' + $sdk.Replace('\', '/')
foreach ($file in $propertiesFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        $failures.Add("Missing project SDK configuration: $file")
        continue
    }
    $content = [IO.File]::ReadAllText($file)
    if ($content -notmatch "(?m)^$([Regex]::Escape($expectedSdkLine))\r?$") {
        $failures.Add("$file does not contain '$expectedSdkLine'.")
    }
    else {
        Write-Host "OK  $file"
    }
}

if (Test-Path -LiteralPath $adb -PathType Leaf) {
    & $adb version
    if ($LASTEXITCODE -ne 0) { $failures.Add('The D-drive adb executable failed.') }
}

if (Test-Path -LiteralPath $emulator -PathType Leaf) {
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:ANDROID_USER_HOME = $androidHome
    $env:ANDROID_EMULATOR_HOME = $androidHome
    $env:ANDROID_AVD_HOME = $avdHome
    $avds = @(& $emulator -list-avds)
    if ($LASTEXITCODE -ne 0) {
        $failures.Add('The D-drive emulator failed to list AVDs.')
    }
    elseif ($avds -notcontains 'gilune-s1') {
        $failures.Add("The D-drive emulator did not find gilune-s1. Found: $($avds -join ', ')")
    }
    else {
        Write-Host 'OK  emulator found gilune-s1'
    }
}

if ($Build -and $failures.Count -eq 0) {
    $gradleWrapper = Join-Path $repoRoot 'app\gradlew.bat'
    Push-Location (Split-Path $gradleWrapper -Parent)
    try {
        & $gradleWrapper ':composeApp:assembleDebug'
        if ($LASTEXITCODE -ne 0) {
            $failures.Add("Gradle Android build failed with exit code $LASTEXITCODE.")
        }
        else {
            Write-Host 'OK  :composeApp:assembleDebug'
        }
    }
    finally {
        Pop-Location
    }
}

if ($failures.Count -gt 0) {
    Write-Error ("Android migration verification failed:`n- " + ($failures -join "`n- "))
    exit 1
}

Write-Host ''
Write-Host 'Android migration verification passed.'
exit 0
