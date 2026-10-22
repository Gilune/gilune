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
    # The install owns every rule for its programs: a Block row that a
    # dismissed prompt left for javaw.exe would beat the Allow above.
    $others = @(ProgramRules | Where-Object { $_.Name -ne $rule.Name })
    if ($others.Count -ne 0) {
        throw "Other firewall rules remain for the installed programs: $(($others | ForEach-Object { "$($_.DisplayName) [$($_.Action)]" }) -join ', ')"
    }
}
function FirewallRules { @(Get-NetFirewallRule -DisplayName 'Gilune' -ErrorAction SilentlyContinue) }
# Every rule whose program lives under the install directory, whatever its name.
function ProgramRules {
    @(Get-NetFirewallApplicationFilter | Where-Object { $_.Program -and $_.Program.StartsWith("$installed\", 'OrdinalIgnoreCase') } |
        ForEach-Object { Get-NetFirewallRule -Name $_.InstanceID -ErrorAction SilentlyContinue })
}
# What a cancelled or non-administrator firewall prompt left behind on 1.1.0:
# Block rows for the runtime's javaw.exe, path as Windows wrote it.
function SeedBlockRule([string]$label) {
    $javaw = (Join-Path $installed 'runtime\bin\javaw.exe').ToLowerInvariant()
    foreach ($profile in @('public', 'private')) {
        & netsh advfirewall firewall add rule name="OpenJDK Platform binary" dir=in action=block enable=yes profile=$profile program="$javaw" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not seed the $profile Block rule before $label" }
    }
    if (@(ProgramRules | Where-Object { $_.Action -eq 'Block' }).Count -ne 2) { throw "Seeding Block rules before $label did not take" }
}
function VerifyRemoved([string]$what) {
    if ((Products).Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $installed 'Gilune.exe'))) {
        throw "$what left the registered application behind"
    }
    if ((FirewallRules).Count -ne 0) { throw "$what left the Gilune firewall rule behind" }
    if ((ProgramRules).Count -ne 0) { throw "$what left firewall rules for the installed programs behind" }
}
function VerifyListener([string]$phase) {
    # The launcher test records which process bound the sync port. It must
    # be the launcher the firewall rule names: javaw.exe would have been
    # asked about by Windows as "OpenJDK Platform binary".
    $listener = Get-Content -LiteralPath (Join-Path $results "$phase/listener.txt") -Raw | ConvertFrom-Json
    if ($listener.exe -ne (Join-Path $installed 'Gilune.exe')) { throw "Sync port is owned by '$($listener.exe)'" }
}
if ((FirewallRules).Count -ne 0 -or (ProgramRules).Count -ne 0) { throw 'Runner already has firewall rules for Gilune; refusing to judge the installer by them' }
SeedBlockRule 'clean install'
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
# The owner's notebook: an earlier release installed, its prompt dismissed.
SeedBlockRule 'upgrade'
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
'PASS: clean install over seeded Block rules, plain shortcut, one firewall rule and no Block left, repair without a duplicate rule, one-process launch owning the sync port, upgrade from v1.0.3 over seeded Block rules, downgrade rejection, uninstall removing every rule, preserved vaults' |
    Set-Content -LiteralPath (Join-Path $results 'lifecycle.txt')
