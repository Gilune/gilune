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
# Windows Installer records every installed product under its owner's SID:
# the machine's (S-1-5-18) for an all-users install, the installing user's
# for a per-user one. A per-user product has no entry under the user's own
# Uninstall key, so that is not where to look for it.
$userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function ProductsFor([string]$sid) {
    @(Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\UserData\$sid\Products\*\InstallProperties" -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'Gilune' })
}
function MachineProducts { ProductsFor 'S-1-5-18' }
function UserProducts { ProductsFor $userSid }
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
# The same operation without administrator rights: the in-app update, or a
# person installing with no administrator to ask. The runner's account is an
# administrator, so the run takes the Basic User trust level (`runas
# /trustlevel`, same account, same profile, the Administrators group deny-only)
# and records that it really had no rights before msiexec runs. runas does not
# wait, so the wrapper writes msiexec's exit code to a file this polls for.
# Its files live in the account's own temp folder: an ACE for Administrators
# grants a deny-only token nothing, so the results folder may be read-only to it.
# The hosted image sets DisableMsi=1 ("non-managed installs need an
# administrator"), which refuses every install without rights with 1625 before
# the package is read. A laptop without that policy is the case under test, so
# the runner's value is recorded and lifted for this run.
$installerPolicy = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\Installer'
if ($perUser) {
    $hosted = (Get-ItemProperty -Path $installerPolicy -Name DisableMsi -ErrorAction SilentlyContinue).DisableMsi
    "DisableMsi on the hosted image: $(if ($null -eq $hosted) { 'not set' } else { $hosted })" | Set-Content -LiteralPath (Join-Path $results 'installer-policy.txt')
    New-Item -Path $installerPolicy -Force | Out-Null
    Set-ItemProperty -Path $installerPolicy -Name DisableMsi -Value 0 -Type DWord
}
$withoutRights = (New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) 'gilune-without-rights') -Force).FullName
function MsiWithoutRights([string]$operation, [string]$path, [string]$label, [switch]$ExpectFailure) {
    $log = Join-Path $withoutRights "$label.log"
    $exit = Join-Path $withoutRights "$label.exit"
    $wrapper = Join-Path $withoutRights "$label.cmd"
    if ($wrapper.Contains(' ')) { throw "runas cannot start a wrapper at a path with spaces: $wrapper" }
    $isAdmin = '([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)'
    @(
        '@echo off'
        "`"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`" -NoProfile -NonInteractive -Command `"if ($isAdmin) { exit 1 }`""
        "if errorlevel 1 (>`"$exit.tmp`" echo administrator& move /y `"$exit.tmp`" `"$exit`" >nul& exit /b)"
        "`"%SystemRoot%\System32\msiexec.exe`" $operation `"$path`" /qn /norestart /l*v `"$log`""
        ">`"$exit.tmp`" echo %errorlevel%"
        "move /y `"$exit.tmp`" `"$exit`" >nul"
    ) | Set-Content -LiteralPath $wrapper -Encoding ascii
    Remove-Item -LiteralPath $exit -ErrorAction SilentlyContinue
    & "$env:SystemRoot\System32\runas.exe" /trustlevel:0x20000 $wrapper
    if ($LASTEXITCODE -ne 0) { throw "runas could not start $label without administrator rights" }
    $deadline = (Get-Date).AddMinutes(5)
    while (-not (Test-Path -LiteralPath $exit)) {
        if ((Get-Date) -gt $deadline) { throw "$label did not finish within five minutes" }
        Start-Sleep -Seconds 2
    }
    $code = (Get-Content -LiteralPath $exit -Raw).Trim()
    if ($code -eq 'administrator') { throw "$label still had administrator rights; the run proves nothing" }
    if (Test-Path -LiteralPath $log) { Copy-Item -LiteralPath $log -Destination (Join-Path $results "$label.log") }
    $success = $code -in @('0', '3010')
    if ($ExpectFailure) {
        if ($success) { throw "$label unexpectedly succeeded" }
    } elseif (-not $success) { throw "$label failed with $code; see $label.log" }
}
function FirewallRules { @(Get-NetFirewallRule -DisplayName 'Gilune' -ErrorAction SilentlyContinue) }
# Every rule whose program lives under an install directory, whatever its name.
function ProgramRules {
    @(Get-NetFirewallApplicationFilter | Where-Object {
            $_.Program -and ($_.Program.StartsWith("$installed\", 'OrdinalIgnoreCase') -or
                $_.Program.StartsWith("$machineInstalled\", 'OrdinalIgnoreCase'))
        } | ForEach-Object { Get-NetFirewallRule -Name $_.InstanceID -ErrorAction SilentlyContinue })
}
# What a cancelled or non-administrator firewall prompt leaves behind: Block
# rows, path as Windows wrote it. On 1.1.0 they named the runtime's javaw.exe;
# for a per-user install they name the launcher itself.
function SeedBlockRule([string]$label) {
    $program = if ($perUser) { Join-Path $installed 'Gilune.exe' } else { Join-Path $machineInstalled 'runtime\bin\javaw.exe' }
    $program = $program.ToLowerInvariant()
    foreach ($profile in @('public', 'private')) {
        & netsh advfirewall firewall add rule name="OpenJDK Platform binary" dir=in action=block enable=yes profile=$profile program="$program" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not seed the $profile Block rule before $label" }
    }
    if (@(ProgramRules | Where-Object { $_.Action -eq 'Block' }).Count -ne 2) { throw "Seeding Block rules before $label did not take" }
}
# Exactly one rule: the installed launcher, inbound, allowed, Private only,
# and nothing else for the installed programs.
function VerifyRule([string]$when) {
    $launcher = Join-Path $installed 'Gilune.exe'
    $rules = FirewallRules
    if ($rules.Count -ne 1) { throw "$when`: expected exactly one Gilune firewall rule, found $($rules.Count)" }
    $rule = $rules[0]
    $program = ($rule | Get-NetFirewallApplicationFilter).Program
    if ($program -ne $launcher) { throw "$when`: firewall rule allows '$program', not '$launcher'" }
    if ($rule.Enabled -ne 'True' -or $rule.Direction -ne 'Inbound' -or $rule.Action -ne 'Allow' -or "$($rule.Profile)" -ne 'Private') {
        throw "$when`: firewall rule is $($rule.Enabled)/$($rule.Direction)/$($rule.Action)/$($rule.Profile); expected True/Inbound/Allow/Private"
    }
    # The install owns every rule for its programs: a Block row that a
    # dismissed prompt left would beat the Allow above.
    $others = @(ProgramRules | Where-Object { $_.Name -ne $rule.Name })
    if ($others.Count -ne 0) {
        throw "$when`: other firewall rules remain for the installed programs: $(($others | ForEach-Object { "$($_.DisplayName) [$($_.Action)]" }) -join ', ')"
    }
}
function VerifyNoRules([string]$when) {
    if ((FirewallRules).Count -ne 0 -or (ProgramRules).Count -ne 0) { throw "$when touched the firewall, yet rules exist for Gilune" }
}
# -NoRule: the install ran without administrator rights and must not have
# touched the firewall.
function VerifyCandidate([switch]$NoRule) {
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
    if ($NoRule) { VerifyNoRules 'An install without administrator rights' } else { VerifyRule 'The installed candidate' }
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

# Every Msi call here runs with the runner's administrator rights: for the
# per-user package, the first install on a managed laptop started as
# administrator, which adds the rule; MsiWithoutRights is everything after.
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
    VerifyNoRules 'Removing the all-users v1.0.3'
    # The managed laptop: installed once as administrator, then updated by
    # the app with no rights at all. The update must succeed over files the
    # elevated install wrote and leave the rule exactly as it was.
    Msi '/i' $previousUser[0].FullName 'previous-user-install'
    if ((UserProducts).Count -ne 1) { throw 'The older per-user stamp was not registered for this user' }
    VerifyRule 'The older per-user stamp installed as administrator'
    MsiWithoutRights '/i' $candidate[0].FullName 'upgrade'
    VerifyCandidate
    & "$PSScriptRoot/test-windows-launcher.ps1" -Bundle $installed -Results (Join-Path $results 'upgrade-launch') -SyncListener
    VerifyListener 'upgrade-launch'
    MsiWithoutRights '/i' $previousUser[0].FullName 'downgrade-rejected' -ExpectFailure
    VerifyCandidate
    # Removed without rights: the product goes, the rule cannot and stays,
    # naming a launcher that is no longer there.
    MsiWithoutRights '/x' $candidate[0].FullName 'upgrade-uninstall'
    if ((Products).Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $installed 'Gilune.exe'))) {
        throw 'Uninstall without rights left the registered application behind'
    }
    if ((FirewallRules).Count -ne 1) { throw 'Uninstall without rights should leave the one rule it cannot remove' }
    & netsh advfirewall firewall delete rule name="Gilune" | Out-Null
    VerifyRemoved 'Uninstall without rights, once its rule is cleared by hand'
    # No administrator ever: an install adds no rule (the app asks when sync
    # is switched on, which no run here does); a later install started as
    # administrator, upgrading it, adds the rule and leaves no Block behind.
    MsiWithoutRights '/i' $previousUser[0].FullName 'standard-install'
    if ((UserProducts).Count -ne 1) { throw 'An install without rights was not registered for this user' }
    VerifyNoRules 'An install without administrator rights'
    SeedBlockRule 'administrator upgrade'
    Msi '/i' $candidate[0].FullName 'administrator-upgrade'
    VerifyCandidate
    Msi '/x' $candidate[0].FullName 'administrator-uninstall'
    VerifyRemoved 'Uninstall as administrator'
    $pass = 'PASS (per-user): install as administrator over seeded Block rules adding one Private rule and leaving no Block, plain shortcut in the user Start menu, repair without a duplicate rule, one-process launch owning the sync port, uninstall removing every rule, refusal beside the all-users v1.0.3, administrator install then upgrade/downgrade-rejection/uninstall without rights keeping the rule, install without rights adding none, administrator upgrade adding it, preserved vaults'
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
