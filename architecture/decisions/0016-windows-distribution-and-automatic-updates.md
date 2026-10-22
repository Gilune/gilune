# ADR 0016: Windows Distribution and Automatic Updates

Status: **Proposed** (owner direction to record the plan 2026-08-31)

Validation: the Windows distribution/update spike — a signed GitHub Actions
release from a clean checkout; fresh-install, oldest-supported → N and N-1 → N
tests on Windows; rejection tests for altered payloads, invalid update signatures
and the wrong Authenticode publisher; preservation tests for the vault and
device-local state. Passing the acceptance gate below, followed by owner
ratification, promotes this ADR to Accepted.

## Context

Windows is a co-primary initial client (constitution rule 3), but the current
distribution evidence stops at a self-contained Compose application image.
The S1 report explicitly leaves MSI/WiX packaging untested. There is no release
workflow, signing identity or application update channel, and a clean checkout
must generate and stage the matching UniFFI bindings and native library itself.

The wanted public path has two parts:

- a first installation simple enough to copy and paste into a Windows console;
- subsequent releases built by GitHub Actions and discovered by the installed
  application without requiring Winget or a store.

An application update is remote-code execution with the publisher's authority.
Transport security and a checksum beside the installer can detect accidental
corruption, but do not independently prove who authorised the bytes. The
update mechanism therefore needs a publisher signature, a key pinned into the
installed application, protected release automation, and an installer identity
that remains stable across versions.

Updates are platform lifecycle, not a domain feature. `02-kernel-contract.md`
already assigns packaging, signing and update channels to the platform shell.
The updater is consequently desktop-shell infrastructure: it is not a plugin,
does not cross the kernel FFI, and cannot be removed through the plugin manager.

The local-first and preservation-first rules still apply. A failed network
request must not delay the first usable window or capture. An update must not
discard an edit, stop an active capture, or make the vault depend on the
availability of GitHub.

## Proposed decision

### Release channel and scope

- **GitHub Releases is the canonical direct Windows release and update
  source.** Only a protected Git tag and the GitHub Actions release workflow
  may produce a public Windows release.
- Stable release assets and release notes are anonymously downloadable. A
  private repository or a feed that requires GitHub credentials is not this
  distribution profile: Gilune neither holds nor asks for a GitHub token.
- v0 has one **stable Windows x64 channel**. Its tags are strict
  `vMAJOR.MINOR.PATCH`; drafts and prereleases are not visible to stable
  installations. A beta feed or Windows ARM64 package needs a later decision.
- This ADR covers the full interactive Windows application. ADR 0008's future
  headless sync agent may have a different installation and service lifecycle
  and is not silently brought under this updater.
- Releases are full installers, not binary deltas. Delta updates may be added
  only after measurements show that their operational and recovery cost earns
  its place.

Each published release carries these stable asset names:

- `Gilune-x64.msi` — the per-user application installer;
- `appcast.xml` — the update feed consumed by installed applications;
- `SHA256SUMS` — human- and automation-readable payload hashes;
- `install.ps1` — the initial-install bootstrap tied to that release.

The installed application reads the stable feed URL:

```text
https://github.com/Gilune/gilune/releases/latest/download/appcast.xml
```

The appcast itself names the **tag-specific** MSI URL, length, application
version, release-notes URL, Windows x64 platform and update signature. It never
uses the moving `latest` MSI URL inside an item: a release changing during a
download must not pair one release's metadata with another release's bytes.

### Package and version identity

- Compose/jpackage produces a **per-user MSI**. Normal installation and update
  do not require administrator elevation.
- One application-release version, stored in a root `VERSION` file, supplies
  the Git tag check, embedded application version, MSI `ProductVersion` and
  appcast version. A disagreement fails the release before packaging.
- Windows versions use exactly three numeric components. The application may
  display other build metadata in development, but it never enters the MSI
  version comparison. Release validation also enforces the Windows Installer
  limits: major and minor are at most 255 and build/patch at most 65535.
- One randomly generated Windows `upgradeUuid` is committed before the first
  public installer and **never changes** for the Gilune application. Package
  name, publisher, installation scope and upgrade family are equally stable.
- Application, kernel, plugin and data-schema versions remain independent as
  `07-compatibility.md` requires. About shows the application release and the
  kernel version separately rather than implying they are one contract.
- The release job generates the desktop UniFFI bindings from the exact native
  library it packages. A local or previously staged DLL is never trusted by a
  clean release build, and a development `jna.library.path` is never embedded
  in the installed launcher.

### Update transport and application behaviour

The Windows desktop bundles **WinSparkle** and calls its C API through JNA.
WinSparkle owns appcast parsing, version selection, periodic checks, release
notes, download progress, Later/Skip behaviour, and verification of the update
signature. Gilune owns when checking may start and whether installation may
proceed.

- Initialisation happens only after the main window is usable. Automatic checks
  run at most daily, off the startup and capture paths; their preference and
  last-check state are device-local. They default **on** for a fresh install;
  the setting says that checking contacts GitHub and preserves an opt-out.
  About exposes the application version, automatic-check toggle, last check,
  and **Check for updates** action.
- An automatic check that is current or offline is silent. A manual check
  reports a useful current, available or failed result.
- v0 is **automatic discovery with user-approved installation**, not a forced
  silent updater. A found release presents its version and release notes, then
  offers Install, Later and Skip. Mandatory security releases are not a hidden
  exception; they require a future decision.
- Equal, older, malformed, prerelease and wrong-architecture items are ignored
  on the stable channel. There is no automatic downgrade or rollback in v0.
- Before installation, the shell runs the same preservation path as an ordinary
  window close. An active capture, a dirty buffer that cannot be flushed, or a
  second Gilune process from the same installation defers the update. No helper
  kills a process by image name.

A small signed **out-of-process updater/relauncher** takes ownership of the
downloaded MSI after WinSparkle has verified it. It copies itself outside the
installation being replaced, waits for the authorised Gilune processes to exit,
rechecks the MSI's Authenticode publisher, runs Windows Installer with visible
progress and no forced reboot, records the result, and relaunches the installed
application on ordinary success. Partial downloads are never executable;
failed installers and their logs remain available for diagnosis, while a
successful run cleans its temporary files.

Gilune registers WinSparkle's `win_sparkle_set_user_run_installer_callback` and
returns **handled** after synchronously handing the verified payload path to the
helper. WinSparkle's default installer launcher is never used. Preservation and
shutdown are marshalled onto Compose's UI thread, while the helper waits for the
process to finish; this makes "save, then replace" an ordering guarantee rather
than a race with WinSparkle's default shutdown callback. The JNA callback
objects remain strongly referenced for the updater's lifetime.

### Signing and release trust

Public updating uses two independent proofs over the final installer. Every
project-owned PE payload inside it — at minimum the application launcher,
native kernel library and updater helper — also carries valid Authenticode;
the pinned WinSparkle input is verified against its recorded release hash and
third-party notice.

1. **Authenticode** with a publicly trusted Gilune publisher identity, including
   a trusted timestamp. Windows and the initial-install path use this identity.
2. **WinSparkle EdDSA** over the exact, already-Authenticode-signed MSI. The
   application embeds the public key; only the protected release environment
   receives the private key.

The byte order is binding:

```text
build → sign/verify project PE files → package MSI → sign/verify MSI
      → hash → EdDSA-sign → publish
```

Nothing mutates the MSI after the first signature. A hash from GitHub is useful
evidence but is not a substitute for either signature, because metadata and
payload otherwise share one administrative trust boundary.

The exact Authenticode provider is operational, not architectural. Azure
Artifact Signing with GitHub OIDC is preferred because it avoids a long-lived
certificate private key in GitHub secrets; another publicly trusted provider is
acceptable if it meets the same publisher, timestamp and automation contract.

The release-signing environment is protected and unavailable to pull requests
and forks. Its GitHub token receives only the permissions needed by the signing
and publication steps. Third-party actions are pinned. The EdDSA private key has
an offline recovery copy, and key rotation and compromise response are
documented and exercised before the first public updater ships.

### GitHub Actions release path

Two workflows keep ordinary CI separate from publication:

- **CI** runs Rust and desktop tests, generates the target-matched bindings and
  native library, and smoke-packages Windows from a clean checkout. It holds no
  signing or release authority. The first-release dependency-licence audit
  required by ADR 0009 runs here, and every required third-party notice ships
  with its binary.
- **Windows release** accepts a protected `vMAJOR.MINOR.PATCH` tag (plus an
  explicitly authorised manual recovery run), verifies the version, builds and
  tests the release native library and updater, generates and stages bindings,
  packages the MSI, signs it, exercises install/upgrade, generates the appcast
  and bootstrap, and publishes.

Publication is **draft first**: create a draft release, upload every asset,
download and verify the candidate assets, then publish once. GitHub immutable
releases are enabled before public distribution so a published tag and its
assets cannot be silently replaced. A failed workflow leaves no release visible
to installed applications.

### Initial installation

The short documented bootstrap is:

```powershell
irm https://github.com/Gilune/gilune/releases/latest/download/install.ps1 | iex
```

The release-tied script contains the exact tag-specific MSI URL and expected
SHA-256. It downloads to a temporary file, verifies both the hash and the exact
Authenticode publisher, invokes the per-user MSI with visible progress and no
forced reboot, checks the Windows Installer result, and cleans up on success.

Piping a remote script to `iex` trusts GitHub's HTTPS and the repository's
release controls for the bootstrap code itself; the MSI checks do not erase
that fact. The download page therefore also offers the signed MSI directly and
an expanded PowerShell command that downloads the MSI first and verifies its
hash and publisher before execution. `install.ps1` is not presented as an
independent trust root. The one-liner is convenience, not a claim that trust
has disappeared.

### Data, migration and recovery

- The MSI owns application files only. The vault, device-local preferences,
  downloaded models, logs and recovery material remain outside uninstall and
  upgrade replacement.
- Installing N over N-1 leaves one registered application and preserves those
  user-owned locations. Changing per-user/per-machine context is not an update;
  it requires a migration decision.
- Before public 1.0, **every public Windows build containing this updater is a
  supported direct source for the latest release**: a user may skip releases
  without performing a chain of intermediate installations. Dropping an old
  source version or introducing a minimum update floor requires a later
  compatibility decision and an explicit re-bootstrap experience; it is not an
  unannounced appcast failure.
- A new application may run compatible, transactional kernel migrations on its
  next launch under `07-compatibility.md`. The updater does not mutate the vault
  and cannot call a successful installer a successful data migration.
- An installation failure must leave N-1 usable. v0 does not automatically
  downgrade after a successfully installed but bad release; recovery is a
  higher signed patch installed through the bootstrap or direct MSI path, which
  must work even when the bad application cannot launch. The release gate is
  the defence against needing that path.
- Uninstall removes the application, not the vault or other user-owned data.

## Why this is preferred

The MSI path extends the packaging mechanism already selected with Compose and
keeps a future Winget manifest pointed at the same installer. WinSparkle uses
the JNA boundary the Windows shell already carries and supplies the
security-sensitive feed, comparison, download and update UX rather than making
Gilune invent a second updater protocol. Its native Windows dialog is a small
visual inconsistency in exchange for a smaller, established update surface.

GitHub remains the only release service, while the key embedded in an installed
Gilune is an independent authorisation boundary. The design is therefore simple
enough for v0 without reducing "download and execute a new application" to
"trust whichever bytes the repository currently serves".

## Alternatives considered

### Manual downloads only

Smallest implementation and an acceptable emergency fallback. Rejected as the
normal path: users do not reliably discover security and compatibility fixes,
and it leaves each release to repeat a manual uninstall/install story.

### Winget as the first channel

Deferred, not rejected permanently. Winget can later publish the same signed
MSI and stable upgrade identity, but first publication has repository and review
workflow overhead and does not provide the in-application release notice asked
for here.

### A bespoke GitHub Releases client and updater

Would permit a fully Compose-styled banner and direct parsing of the Releases
API. Rejected for v0 because Gilune would own feed compatibility, semantic
version selection, retries, proxy behaviour, download resumption, signature
validation, installer hand-off and Skip/Later state. Those are the feature,
not product differentiation.

### MSIX with App Installer

The strongest Windows-native future alternative: Windows can own periodic
checks and activation. Deferred because Compose produces MSI/EXE directly,
MSIX introduces another packaging manifest and hosting constraints, and the
first target is a GitHub-hosted direct installer. Reconsider for Microsoft
Store distribution rather than making it a prerequisite for direct v0.

### In-process replacement or a portable ZIP

Rejected. A running Windows process and its runtime hold files that an updater
needs to replace, and a hand-written directory swap has weaker transactional
and uninstall semantics than Windows Installer. The external helper and MSI
make process exit and replacement separate facts.

### Machine-wide or forced silent installation

Rejected for v0. Machine-wide installation adds elevation friction to the
one-line path. Forced updates can interrupt capture and turn an optional
network service into a condition of using the local application. Visible,
user-approved per-user updates are the default.

### A checksum as the only update proof

Rejected. If the same compromised release account can replace both installer
and checksum, equality proves only that the attacker uploaded matching files.
Authenticode supplies publisher identity and the pinned EdDSA key supplies
update authorisation.

### The updater as a plugin

Rejected. Installation, shutdown and code-signing trust are shell lifecycle and
security responsibilities. Making the updater removable or downloadable through
the plugin system would invert the boundary in `02-kernel-contract.md` and make
the mechanism that installs trusted code depend on less-trusted code.

## Consequences

- The Windows application gains a native WinSparkle DLL and a small signed
  updater executable, both version-pinned release inputs.
- Release engineering gains a publicly trusted signing identity, a protected
  EdDSA key, immutable GitHub releases and a Windows install/upgrade test. A
  public release cannot be improvised from a developer machine.
- v0 downloads the complete MSI for each update. The size is accepted until
  measured usage justifies a delta format.
- The update prompt uses native Windows presentation rather than the Gilune
  palette. About remains the Gilune-owned control and status surface.
- Application release becomes another compatibility dimension alongside the
  independent dimensions in `07-compatibility.md`; that contract should gain
  the row when this ADR is accepted.
- A future Winget manifest may reuse the MSI. A store- or enterprise-managed
  distribution profile may disable application-owned checks so two update
  authorities do not compete.
- GitHub availability affects update discovery and initial direct installation,
  never opening the existing application, capture, browsing, export or recovery.

## Acceptance gate

This ADR is not Accepted and public one-click updating is not enabled until all
of the following are evidence, not assertions:

1. **One version fact:** tag, `VERSION`, embedded application version, MSI
   `ProductVersion` and appcast version agree; malformed, mismatched,
   non-monotonic or out-of-Windows-range versions fail before publication.
2. **Clean production build:** a clean Windows runner, with no generated Kotlin
   or staged native files from a developer checkout, builds, tests and packages
   the target-matched release.
3. **Two valid trust proofs:** every project-owned PE payload and the final MSI
   have valid Authenticode from the expected Gilune publisher, and the exact MSI
   bytes pass the EdDSA signature pinned in a supported prior release. An
   altered MSI, signature mismatch or wrong publisher fails closed. Malformed
   or hostile appcast metadata may fail or misdescribe a release, but it cannot
   authorise an unsigned payload and is never treated as a trust proof.
4. **Fresh per-user installation:** both the documented bootstrap and direct MSI
   path install and launch the advertised version without elevation, leaving
   one installed-application entry.
5. **Supported versions upgrade to N:** both N-1 and the oldest supported
   updater-bearing public installer upgrade directly in place; N launches; one
   application entry remains; stepwise migration fixtures pass; and a fixture
   vault, preferences and downloaded model survive byte-for-byte where they are
   not deliberately migrated.
6. **Preservation wins:** an active capture or an edit that cannot be flushed
   defers installation. Cancellation or installer failure leaves the previous
   application and user data usable.
7. **Multiple instances are safe:** another process from the same installation
   defers replacement; the helper neither kills by name nor writes over locked
   files.
8. **Offline is ordinary:** a missing, malformed, unavailable or rate-limited
   feed never blocks startup or capture. Automatic failure is quiet; manual
   failure is actionable; stable ignores equal, lower and prerelease versions.
   A fresh install defaults checks on, says that they contact GitHub, and an
   opt-out persists across launches.
9. **Interrupted bytes never run:** an interrupted or partial download cannot be
   selected as an installer, and a non-success installer result is logged and
   never followed by a false-success relaunch.
10. **Removal preserves ownership:** uninstall removes application files and
    registration without deleting the vault, preferences, models or recovery
    material.
11. **Offline repair works:** a higher signed MSI installs through the bootstrap
    and direct path over a deliberately non-launching prior version, preserving
    its user state without depending on that application's updater.
12. **Key operations exist:** the Authenticode identity and exact publisher
    match are recorded; the EdDSA private key has protected CI custody and an
    offline backup; rotation, revocation and compromise recovery are documented
    and exercised without weakening verification.
13. **Publication is atomic and public:** an intentionally failed release job
    exposes no new stable appcast; a successful immutable release exposes all
    four named assets and their tag-specific targets as the bytes CI verified.
    A clean signed-out Windows account with no GitHub CLI or browser credentials
    can fetch each of them.
14. **Licences travel with binaries:** the ADR 0009 dependency audit passes and
    the installed distribution carries every notice required by WinSparkle and
    the other packaged dependencies.

## Evidence and operational references

- [`spikes/s1-kernel-boundary/README.md`](../../spikes/s1-kernel-boundary/README.md)
  records the self-contained Windows app-image evidence and the honest MSI/WiX
  gap.
- [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)
  defines MSI output, per-user installation, version rules and `upgradeUuid`.
- [WinSparkle integration](https://winsparkle.org/guides/integrating-winsparkle/),
  [update publication](https://winsparkle.org/guides/publishing-updates/) and
  [security model](https://winsparkle.org/guides/getting-started/) define the
  DLL/API, appcast, installer arguments and EdDSA verification used here.
- [GitHub immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases)
  defines the draft-first publication and post-publication asset guarantees.
- [Windows Installer options](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/msiexec),
  [SignTool](https://learn.microsoft.com/en-us/windows/win32/seccrypto/signtool)
  and [Artifact Signing integrations](https://learn.microsoft.com/en-us/azure/artifact-signing/how-to-signing-integrations)
  define the installer, signature and preferred CI-signing mechanisms.

## Amendments

- **2026-10-22 (per-user install, again).** The Linux-built MSI that replaced
  jpackage's (`.github/linux-windows-build.md`) installed per-machine into
  `C:\Program Files\Gilune` with `InstallScope="perMachine"`, so every update
  the in-app updater ran asked for administrator approval — the elevation
  friction this ADR rejected machine-wide installation for. The owner asked
  for what other desktop apps do (2026-10-22: "Claude's Windows app doesn't
  ask"), and the package returns to this ADR's per-user decision:
  `InstallScope="perUser"`, files under `%LOCALAPPDATA%\Programs\Gilune`,
  components keyed on `HKCU` registry values as Windows Installer requires
  for profile folders, registration and the Start-menu shortcut in the user's
  own hives, and no custom action at all, so `msiexec /passive` runs without
  a prompt. Consequences recorded: a per-user package cannot remove an
  all-users product, so it refuses to install beside one (launch condition on
  the `HKLM\Software\Gilune\Installed` value every per-machine install
  wrote); the updater running inside an all-users install removes that copy
  first, with Windows's one prompt for administrator approval (a PowerShell
  script that re-starts itself `-Verb RunAs`), then installs per-user and
  reopens the new launcher — one last prompt, after which updates ask
  nothing; a declined removal reopens the old copy unchanged and the per-user
  app, when one exists beside a stale all-users copy, offers to remove it
  from About. Because the updater that performs that move must be installed
  before a per-user release is published, the release after this change is
  still per-machine (`scope` input of `windows-cross.yml`, default
  `per-machine`) and the one after it flips to per-user; the per-machine
  shape is then dead and to be removed. The firewall rule a per-machine
  installer added moves to the app (ADR 0019 amendment of the same date).
  Each Windows account on a shared machine installs its own copy, as this
  ADR always implied.
