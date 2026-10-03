param([Parameter(Mandatory)][string]$Artifacts)
$ErrorActionPreference = 'Stop'
# This sequence intentionally replaces an installed product. Only use a fresh
# disposable CI runner; local validation uses MSI administrative extraction.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'Installer lifecycle validation requires a disposable Windows Actions runner'
}
$root = (Resolve-Path -LiteralPath $Artifacts).Path
$results = Join-Path $root 'results/installer'
New-Item -ItemType Directory -Path $results -Force | Out-Null
$candidate = @(Get-ChildItem -LiteralPath (Join-Path $root 'package') -Filter '*.msi')
$previous = @(Get-ChildItem -LiteralPath (Join-Path $root 'previous') -Filter '*.msi')
if ($candidate.Count -ne 1 -or $previous.Count -ne 1) { throw 'Expected one candidate and one previous MSI' }
$manifest = Get-Content -LiteralPath (Join-Path $root 'package/Gilune/build-manifest.json') -Raw | ConvertFrom-Json
$installed = Join-Path $env:ProgramFiles 'Gilune'
function Products {
    @(Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' |
        Where-Object { $_.DisplayName -eq 'Gilune' })
}
if ((Products).Count -ne 0 -or (Test-Path -LiteralPath $installed)) {
    throw 'Runner already contains Gilune; refusing to replace an unrelated installation'
}
function Msi([string]$operation, [string]$path, [string]$label, [switch]$ExpectFailure) {
    $log = Join-Path $results "$label.log"
    $process = Start-Process msiexec.exe -WindowStyle Hidden -Wait -PassThru -ArgumentList @(
        $operation, ('"' + $path + '"'), '/qn', '/norestart', '/l*v', ('"' + $log + '"'))
    $success = $process.ExitCode -in @(0, 3010)
    if ($ExpectFailure) {
        if ($success) { throw "$label unexpectedly succeeded" }
    } elseif (-not $success) { throw "$label failed with $($process.ExitCode); see $log" }
}
function VerifyCandidate {
    $products = Products
    if ($products.Count -ne 1 -or $products[0].DisplayVersion -ne $manifest.version) {
        throw 'Expected exactly one installed Gilune product at the candidate version'
    }
    foreach ($entry in $manifest.files.PSObject.Properties) {
        $path = Join-Path $installed $entry.Name
        if (-not (Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $entry.Value) {
            throw "Installed payload differs: $($entry.Name)"
        }
    }
    $shortcut = Join-Path $env:ProgramData 'Microsoft/Windows/Start Menu/Programs/Gilune/Gilune.lnk'
    if (-not (Test-Path -LiteralPath $shortcut)) { throw 'Missing Start menu shortcut' }
}
Msi '/i' $candidate[0].FullName 'clean-install'
VerifyCandidate
Remove-Item -LiteralPath (Join-Path $installed 'Gilune.exe')
Msi '/fa' $candidate[0].FullName 'repair'
VerifyCandidate
& "$PSScriptRoot/test-windows-launcher.ps1" -Bundle $installed -Results (Join-Path $results 'clean-launch')
Msi '/x' $candidate[0].FullName 'clean-uninstall'
if ((Products).Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $installed 'Gilune.exe'))) {
    throw 'Uninstall left the registered application behind'
}
Msi '/i' $previous[0].FullName 'previous-install'
if ((Products).Count -ne 1) { throw 'Previous release was not registered' }
Msi '/i' $candidate[0].FullName 'upgrade'
VerifyCandidate
& "$PSScriptRoot/test-windows-launcher.ps1" -Bundle $installed -Results (Join-Path $results 'upgrade-launch')
Msi '/i' $previous[0].FullName 'downgrade-rejected' -ExpectFailure
VerifyCandidate
Msi '/x' $candidate[0].FullName 'upgrade-uninstall'
if ((Products).Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $installed 'Gilune.exe'))) {
    throw 'Upgraded product was not uninstalled'
}
foreach ($phase in @('clean-launch', 'upgrade-launch')) {
    if (-not (Test-Path -LiteralPath (Join-Path $results "$phase/vault with spaces and é/Keep.md"))) {
        throw 'Uninstall removed user-created vault content'
    }
}
'PASS: clean install, repair, upgrade from v1.0.3, downgrade rejection, uninstall, preserved vaults' |
    Set-Content -LiteralPath (Join-Path $results 'lifecycle.txt')
