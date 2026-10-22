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
    # A plain shortcut names the installed launcher. An advertised one points
    # into C:\Windows\Installer, where Start shows no logo and offers no
    # "Run as administrator".
    $link = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut)
    $launcher = Join-Path $installed 'Gilune.exe'
    if ($link.TargetPath -ne $launcher) { throw "Shortcut targets '$($link.TargetPath)', not '$launcher'" }
    if ($link.WorkingDirectory.TrimEnd('\') -ne $installed) { throw "Shortcut working directory is '$($link.WorkingDirectory)'" }
    $iconFile = ($link.IconLocation -split ',')[0]
    if (-not $iconFile -or -not (Test-Path -LiteralPath $iconFile) -or [IO.Path]::GetExtension($iconFile) -ne '.ico') {
        throw "Shortcut icon '$($link.IconLocation)' is not an .ico file the shell can read"
    }
    $rules = FirewallRules
    if ($rules.Count -ne 1) { throw "Expected exactly one Gilune firewall rule, found $($rules.Count)" }
    $rule = $rules[0]
    $program = ($rule | Get-NetFirewallApplicationFilter).Program
    if ($program -ne $launcher) { throw "Firewall rule allows '$program', not '$launcher'" }
    if ($rule.Enabled -ne 'True' -or $rule.Direction -ne 'Inbound' -or $rule.Action -ne 'Allow' -or "$($rule.Profile)" -ne 'Private') {
        throw "Firewall rule is $($rule.Enabled)/$($rule.Direction)/$($rule.Action)/$($rule.Profile); expected True/Inbound/Allow/Private"
    }
}
function FirewallRules { @(Get-NetFirewallRule -DisplayName 'Gilune' -ErrorAction SilentlyContinue) }
function VerifyRemoved([string]$what) {
    if ((Products).Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $installed 'Gilune.exe'))) {
        throw "$what left the registered application behind"
    }
    if ((FirewallRules).Count -ne 0) { throw "$what left the Gilune firewall rule behind" }
}
function VerifyListener([string]$phase) {
    # The launcher test records which process bound the sync port. It must
    # be the launcher the firewall rule names: javaw.exe would have been
    # asked about by Windows as "OpenJDK Platform binary".
    $listener = Get-Content -LiteralPath (Join-Path $results "$phase/listener.txt") -Raw | ConvertFrom-Json
    if ($listener.exe -ne (Join-Path $installed 'Gilune.exe')) { throw "Sync port is owned by '$($listener.exe)'" }
}
if ((FirewallRules).Count -ne 0) { throw 'Runner already has a Gilune firewall rule; refusing to judge the installer by it' }
Msi '/i' $candidate[0].FullName 'clean-install'
VerifyCandidate
Remove-Item -LiteralPath (Join-Path $installed 'Gilune.exe')
Msi '/fa' $candidate[0].FullName 'repair'
VerifyCandidate
& "$PSScriptRoot/test-windows-launcher.ps1" -Bundle $installed -Results (Join-Path $results 'clean-launch') -SyncListener
VerifyListener 'clean-launch'
Msi '/x' $candidate[0].FullName 'clean-uninstall'
VerifyRemoved 'Uninstall'
Msi '/i' $previous[0].FullName 'previous-install'
if ((Products).Count -ne 1) { throw 'Previous release was not registered' }
Msi '/i' $candidate[0].FullName 'upgrade'
VerifyCandidate
& "$PSScriptRoot/test-windows-launcher.ps1" -Bundle $installed -Results (Join-Path $results 'upgrade-launch') -SyncListener
VerifyListener 'upgrade-launch'
Msi '/i' $previous[0].FullName 'downgrade-rejected' -ExpectFailure
VerifyCandidate
Msi '/x' $candidate[0].FullName 'upgrade-uninstall'
VerifyRemoved 'Uninstall of the upgraded product'
foreach ($phase in @('clean-launch', 'upgrade-launch')) {
    if (-not (Test-Path -LiteralPath (Join-Path $results "$phase/vault with spaces and é/Keep.md"))) {
        throw 'Uninstall removed user-created vault content'
    }
}
'PASS: clean install, plain shortcut, firewall rule, repair without a duplicate rule, one-process launch owning the sync port, upgrade from v1.0.3, downgrade rejection, uninstall removing the rule, preserved vaults' |
    Set-Content -LiteralPath (Join-Path $results 'lifecycle.txt')
