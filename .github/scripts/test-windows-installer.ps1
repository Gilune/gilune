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
# The published v1.0.3: an all-users install every candidate must deal with.
$previous = @(Get-ChildItem -LiteralPath (Join-Path $root 'previous') -Filter '*.msi')
if ($candidate.Count -ne 1 -or $previous.Count -ne 1) { throw 'Expected one candidate and one previous MSI' }
$manifest = Get-Content -LiteralPath (Join-Path $root 'package/Gilune/build-manifest.json') -Raw | ConvertFrom-Json
# The package says where it installs; the checks follow it.
$perUser = switch ($manifest.scope) {
    'per-user' { $true }
    'per-machine' { $false }
    default { throw "The build manifest names no known scope: '$($manifest.scope)'" }
}
# A per-user candidate also brings an older stamp of itself, the only
# per-user product there is to upgrade from until one is published.
$previousUser = @(Get-ChildItem -LiteralPath (Join-Path $root 'previous-user') -Filter '*.msi' -ErrorAction SilentlyContinue)
if ($perUser -and $previousUser.Count -ne 1) { throw 'A per-user candidate needs one older-stamped per-user MSI in previous-user/' }
$machineInstalled = Join-Path $env:ProgramFiles 'Gilune'
$installed = if ($perUser) { Join-Path $env:LOCALAPPDATA 'Programs\Gilune' } else { $machineInstalled }
$shortcut = if ($perUser) {
    Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Gilune\Gilune.lnk'
} else {
    Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\Gilune\Gilune.lnk'
}
function ProductsIn([string]$hive) {
    @(Get-ItemProperty "${hive}:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*" -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'Gilune' })
}
function MachineProducts { ProductsIn 'HKLM' }
function UserProducts { ProductsIn 'HKCU' }
function Products { @(MachineProducts) + @(UserProducts) }
if ((Products).Count -ne 0 -or (Test-Path -LiteralPath $installed) -or (Test-Path -LiteralPath $machineInstalled)) {
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
function FirewallRules { @(Get-NetFirewallRule -DisplayName 'Gilune' -ErrorAction SilentlyContinue) }
# Every rule whose program lives under an install directory, whatever its name.
function ProgramRules {
    @(Get-NetFirewallApplicationFilter | Where-Object {
            $_.Program -and ($_.Program.StartsWith("$installed\", 'OrdinalIgnoreCase') -or
                $_.Program.StartsWith("$machineInstalled\", 'OrdinalIgnoreCase'))
        } | ForEach-Object { Get-NetFirewallRule -Name $_.InstanceID -ErrorAction SilentlyContinue })
}
# What a cancelled or non-administrator firewall prompt left behind on 1.1.0:
# Block rows for the runtime's javaw.exe, path as Windows wrote it.
function SeedBlockRule([string]$label) {
    $javaw = (Join-Path $machineInstalled 'runtime\bin\javaw.exe').ToLowerInvariant()
    foreach ($profile in @('public', 'private')) {
        & netsh advfirewall firewall add rule name="OpenJDK Platform binary" dir=in action=block enable=yes profile=$profile program="$javaw" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not seed the $profile Block rule before $label" }
    }
    if (@(ProgramRules | Where-Object { $_.Action -eq 'Block' }).Count -ne 2) { throw "Seeding Block rules before $label did not take" }
}
function VerifyCandidate {
    $products = if ($perUser) { UserProducts } else { MachineProducts }
    if ($products.Count -ne 1 -or $products[0].DisplayVersion -ne $manifest.version) {
        throw "Expected exactly one Gilune product at the candidate version in the $(if ($perUser) { 'user' } else { 'machine' }) hive"
    }
    if ((Products).Count -ne 1) { throw 'A product is registered in the other hive as well' }
    foreach ($entry in $manifest.files.PSObject.Properties) {
        $path = Join-Path $installed $entry.Name
        if (-not (Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $entry.Value) {
            throw "Installed payload differs: $($entry.Name)"
        }
    }
    if (-not (Test-Path -LiteralPath $shortcut)) { throw "Missing Start menu shortcut at $shortcut" }
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
    if ($perUser) {
        # No elevated moment: the install adds no rule. The app asks for it
        # when sync is switched on, which no run here does.
        if ((FirewallRules).Count -ne 0 -or (ProgramRules).Count -ne 0) {
            throw 'A per-user install must not touch the firewall, yet rules exist for Gilune'
        }
        return
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
function VerifyRemoved([string]$what) {
    if ((Products).Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $installed 'Gilune.exe'))) {
        throw "$what left the registered application behind"
    }
    if ((FirewallRules).Count -ne 0) { throw "$what left the Gilune firewall rule behind" }
    if ((ProgramRules).Count -ne 0) { throw "$what left firewall rules for the installed programs behind" }
}
function VerifyListener([string]$phase) {
    # The launcher test records which process bound the sync port. It must
    # be the installed launcher: javaw.exe would have been asked about by
    # Windows as "OpenJDK Platform binary".
    $listener = Get-Content -LiteralPath (Join-Path $results "$phase/listener.txt") -Raw | ConvertFrom-Json
    if ($listener.exe -ne (Join-Path $installed 'Gilune.exe')) { throw "Sync port is owned by '$($listener.exe)'" }
}
if ((FirewallRules).Count -ne 0 -or (ProgramRules).Count -ne 0) { throw 'Runner already has firewall rules for Gilune; refusing to judge the installer by them' }

if (-not $perUser) { SeedBlockRule 'clean install' }
Msi '/i' $candidate[0].FullName 'clean-install'
VerifyCandidate
Remove-Item -LiteralPath (Join-Path $installed 'Gilune.exe')
Msi '/fa' $candidate[0].FullName 'repair'
VerifyCandidate
& "$PSScriptRoot/test-windows-launcher.ps1" -Bundle $installed -Results (Join-Path $results 'clean-launch') -SyncListener
VerifyListener 'clean-launch'
Msi '/x' $candidate[0].FullName 'clean-uninstall'
VerifyRemoved 'Uninstall'

if ($perUser) {
    # A per-user package cannot remove an all-users copy, so it refuses to
    # install beside one; the app's updater removes that copy first.
    Msi '/i' $previous[0].FullName 'previous-install'
    if ((MachineProducts).Count -ne 1) { throw 'Previous release was not registered for all users' }
    Msi '/i' $candidate[0].FullName 'refused-beside-all-users' -ExpectFailure
    $refusal = Get-Content -LiteralPath (Join-Path $results 'refused-beside-all-users.log') -Raw
    if ($refusal -notmatch 'installed for all users of this computer') { throw 'The refusal did not say why; see refused-beside-all-users.log' }
    if ((UserProducts).Count -ne 0 -or (Test-Path -LiteralPath $installed)) { throw 'The refused install left a per-user product behind' }
    Msi '/x' $previous[0].FullName 'previous-uninstall'
    if ((Products).Count -ne 0) { throw 'The previous release was not removed' }
    # Upgrade from the older per-user stamp, downgrade refused, removed.
    Msi '/i' $previousUser[0].FullName 'previous-user-install'
    if ((UserProducts).Count -ne 1) { throw 'The older per-user stamp was not registered for this user' }
    Msi '/i' $candidate[0].FullName 'upgrade'
    VerifyCandidate
    & "$PSScriptRoot/test-windows-launcher.ps1" -Bundle $installed -Results (Join-Path $results 'upgrade-launch') -SyncListener
    VerifyListener 'upgrade-launch'
    Msi '/i' $previousUser[0].FullName 'downgrade-rejected' -ExpectFailure
    VerifyCandidate
    Msi '/x' $candidate[0].FullName 'upgrade-uninstall'
    VerifyRemoved 'Uninstall of the upgraded product'
    $pass = 'PASS (per-user): clean install without elevation or firewall rules, plain shortcut in the user Start menu, repair, one-process launch owning the sync port, refusal beside the all-users v1.0.3, upgrade from an older per-user stamp, downgrade rejection, uninstall, preserved vaults'
} else {
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
    $pass = 'PASS (per-machine): clean install over seeded Block rules, plain shortcut, one firewall rule and no Block left, repair without a duplicate rule, one-process launch owning the sync port, upgrade from v1.0.3 over seeded Block rules, downgrade rejection, uninstall removing every rule, preserved vaults'
}
foreach ($phase in @('clean-launch', 'upgrade-launch')) {
    if (-not (Test-Path -LiteralPath (Join-Path $results "$phase/vault with spaces and é/Keep.md"))) {
        throw 'Uninstall removed user-created vault content'
    }
}
$pass | Set-Content -LiteralPath (Join-Path $results 'lifecycle.txt')
