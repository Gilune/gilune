# ADR 0008: Headless Sync Agent and LAN-First Sync

Status: **Proposed**  
Validation: Spikes S1 and S4 (extended)

## Context

With the vault projection (ADR 0007), a desktop user can live entirely in a
third-party editor such as Obsidian. Requiring the full application on every
machine just to move data would reintroduce the lock-in the constitution now
rejects (rule 13). The phone is the primary capture device; the desktop machine
is typically mains-powered, on the same network for much of the day, and the
natural place to run heavier processing such as transcription.

Constitution rule 8 already makes sync meaning transport-independent, and the
sync contract lists peer-to-peer as a valid transport. What is undecided is
which transport ships first and in what packaging.

## Proposed decision

- Desktop ships in two profiles over the same kernel:
  - the **full application**: UI, editing, capture, and plugin management;
  - the **sync agent**: a thin headless service containing the kernel, vault
    projection, reconciler, and sync transport, with only pairing and status
    surfaces (tray or CLI). It has no editing UI; the user's chosen editor
    works on the vault.
- The first supported transport is **LAN peer-to-peer sync between paired
  devices**: local discovery, mutual device authentication under the existing
  device-identity model, encrypted transport, exchanging the operation
  envelopes defined by ADR 0005. Operation semantics are identical across
  transports.
- The hosted or self-hosted relay remains a later, optional transport for
  off-network sync. Nothing in v0 requires a server to exist.
- The desktop agent is an always-available listener; battery-powered devices
  initiate opportunistically within the platform background limits already
  described in the processing model.
- The agent is an eligible `coordinator` for heavy processing — for example,
  transcribing phone captures on the desktop — under the existing execution
  policy, claim/lease, and idempotency rules.
- Third-party editors over the vault are a supported first-class desktop
  experience, not a degraded mode. The two profiles must not fork behaviour:
  one kernel, one configuration model, one conformance suite.

## Alternatives considered

### Full application required on every device

Rejected: forces UI adoption where the user only wants their data present,
and contradicts the openness rule.

### Replicating the vault with third-party file sync

Rejected as a replication mechanism: file-level sync is last-write-wins over
mutable files, cannot carry operation history, and risks copying a live
kernel store — the failure ADR 0005 exists to prevent. Copying a closed vault
remains the supported backup and migration path.

### Relay-first sync

Rejected as the initial target: it adds server cost, account and encryption
surface before any two-device value is proven, and weakens the local-first
story. The relay remains the planned answer for off-network sync.

## Consequences

- Device pairing UX (existing-device approval with a short code or QR)
  becomes a v0 product surface rather than a hosted-sync question.
- Transport encryption and mutual device authentication are needed for v0
  even though the relay key hierarchy remains deferred.
- Windows service/tray packaging, autostart, and firewall behaviour join S1
  scope; Android participation must fit foreground-service and scheduling
  limits, so LAN sync on the phone is opportunistic, not continuous.
- The agent strengthens the case for the portable kernel (ADR 0001): it is
  the kernel plus a small host with no UI stack. If ADR 0001 is superseded,
  the agent profile must be re-costed.
- S4 gains LAN discovery, pairing, revocation, and no-relay scenarios; S1
  gains a headless kernel host.
- Marketplace and documentation must be honest that the agent processes and
  projects data but does not render or edit it.

## Acceptance gate

S1 additionally demonstrates the kernel running in a headless host with
vault projection and background operation on Windows. S4 additionally
demonstrates two devices discovering, pairing, syncing, and converging over
LAN with no relay present, an unpaired device being refused, a revoked device
being excluded, and sync resuming correctly when devices rejoin the network.
