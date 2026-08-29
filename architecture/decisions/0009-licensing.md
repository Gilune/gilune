# ADR 0009: Licensing

Status: **Proposed**  
Validation: owner ratification — this is a product decision, not a spike;
the draft exists so the choice is made deliberately rather than by default.

## Context

The product principles say “open source throughout; hosted server offering
can coexist with self-hosting”, and constitution rule 13 makes openness a
baseline. Licensing is where both become enforceable, and the project has
three surfaces with different pressures:

- **Contracts, SDK, and formats** — WIT packages, manifest schemas, the
  vault format, protocol test vectors. Third-party plugin authors,
  including commercial ones, must be able to build on these with zero
  copyleft anxiety, and a patent grant matters for anything contract-like.
- **Kernel and applications** — the invariant-bearing core and the shells.
  The goal is that improvements stay open without creating linking anxiety
  for plugin authors or store-distribution risk. (A)GPL applications have a
  troubled history with App Store terms; plugins, running sandboxed and at
  arm's length, would not be infected by a copyleft kernel either way, but
  perception alone chills ecosystems.
- **Server/relay** — where “hosted offering coexists with self-hosting”
  is decided. A permissive server invites closed hosted forks; strong
  network copyleft (AGPL) obliges anyone offering it as a service to
  publish their modifications — including us, which is aligned with the
  openness rule rather than a cost.

A related choice rides along: contribution terms. A CLA enables future
relicensing and dual-licensing but creates a rights asymmetry between the
project and contributors; a DCO keeps inbound = outbound.

## Proposed decision

- **Contracts, plugin SDK, format specifications, test vectors**:
  dual-licensed **MIT OR Apache-2.0** (the Rust ecosystem convention;
  Apache contributes the patent grant, MIT the maximal simplicity).
- **Kernel and applications**: **MPL-2.0** — file-level copyleft keeps
  changes to our files open, imposes nothing on plugins, other processes,
  or larger works, and has no linking ambiguity (Syncthing is the working
  precedent in exactly our category).
- **Server/relay**: **AGPL-3.0-only** — self-hosting stays first-class,
  and any hosted offering, ours included, publishes its server changes.
- **Contribution terms**: **DCO**, no CLA. The project binds itself to the
  same terms as everyone else; relicensing flexibility is deliberately
  given up.
- **Name and marks**: “Gilune” is protected by trademark policy, not by
  the code licenses; forks are welcome under a different name.
- SPDX headers throughout; per-directory LICENSE files; a dependency
  license audit (e.g. cargo-deny) in CI before the first release.

## Alternatives considered

### Permissive everywhere (MIT/Apache)

Maximises adoption and simplicity, but permits closed hosted forks of the
server, making “hosted offering coexists with self-hosting” a hope rather
than a guarantee.

### Strong copyleft everywhere (GPL/AGPL)

Strongest openness guarantee, but chills the plugin ecosystem the product
depends on, and (A)GPL application code creates real App Store friction
(the VLC/GPLv2 takedown is the canonical precedent). The sandbox makes
infection arguments weak, but ecosystems respond to perception.

### Source-available (BSL and similar)

Rejected outright: it contradicts constitution rule 13 and the product
principles. Openness is a goal, not a marketing asset with a timer.

### CLA with dual-licensing reserve

Kept available in theory by the copyright holder's own contributions, but
rejected as policy: the asymmetry undermines the openness stance, and the
AGPL server already provides the commercial protection a CLA would exist
to enable.

## Consequences

- The split must be visible in the repository layout (per-crate/dir
  licensing), and every contract artifact publishable to third parties
  carries the permissive pair.
- Dependency choices are constrained (no GPL-incompatible or
  source-available dependencies in MPL/AGPL components; audit in CI).
- The hosted service, when it exists, ships its server source as a matter
  of license, not goodwill.
- Changing any of this later is an explicit superseding ADR; loosening
  copyleft after external contributions arrive is effectively impossible
  without those contributors — by design.
