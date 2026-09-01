# ADR 0009: Licensing

Status: **Accepted** (owner ratification 2026-08-30, amending the draft;
amended 2026-09-01 — single licence, MIT)  
Validation: owner ratification — this is a product decision, not a spike;
the draft existed so the choice was made deliberately rather than by
default.

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

## Decision (ratified)

- **Everything — contracts, SDK, formats, kernel, applications,
  server/relay**: dual-licensed **MIT OR Apache-2.0** (the Rust ecosystem
  convention; Apache contributes the patent grant, MIT the maximal
  simplicity). One licence pair, repository-wide.
- **Contribution terms**: **DCO**, no CLA. The project binds itself to the
  same terms as everyone else; relicensing flexibility is deliberately
  given up.
- **Name and marks**: “Gilune” is protected by trademark policy, not by
  the code licenses; forks are welcome under a different name.
- Root `LICENSE-MIT` and `LICENSE-APACHE`; SPDX headers when files start
  travelling on their own; a dependency license audit (e.g. cargo-deny)
  in CI before the first release.

The owner's rationale, recorded for the future reader: the hosted
offering earns by being the maintained, convenient instance, not by
exclusivity. Third parties self-hosting — or even selling hosting of —
the server is accepted, welcomed, and not a threat the licence needs to
defend against. The drafted split (below) was therefore protection
against a consequence the owner does not consider harmful, at the price
of a three-licence surface.

## Alternatives considered

### The drafted split (MIT/Apache contracts, MPL-2.0 kernel, AGPL server)

The original proposal: permissive contracts for plugin authors, file-level
copyleft on the kernel (Syncthing precedent), network copyleft on the
server so hosted forks must publish changes. Rejected at ratification:
the AGPL protection guards against commercial hosted forks, which the
owner explicitly accepts; with that consequence embraced, the split's
extra licence surface (three licences, per-directory files, harder
dependency story) buys nothing the project wants. Comparables reviewed at
ratification: Cap (mostly AGPL-3.0 with MIT crates — effectively this
split) and FeatureHub (Apache-2.0 + Commons Clause — source-available,
which rule 13 forbids here).

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

- One licence pair repository-wide: root `LICENSE-MIT` and
  `LICENSE-APACHE`, no per-directory scheme.
- Anyone may run, modify, and commercially host the server without
  publishing changes; the project's hosted offering competes on being the
  maintained instance, not on licence terms.
- Dependency constraints are minimal (avoid copyleft-incompatible or
  source-available dependencies; audit in CI before first release).
- Tightening the licence later, after external contributions arrive under
  MIT OR Apache-2.0, is effectively impossible without those contributors
  — by design (DCO, inbound = outbound).

## Amendments

**2026-09-01 (owner):** one licence, not a pair — **MIT**, repository-wide,
chosen as the most open of the two. The dual form existed for Apache-2.0's
explicit patent grant; giving the pair up gives that grant up too, knowingly
— MIT's simplicity is the point, and nothing about the project's patent
posture warranted the extra licence surface. No contribution had yet been
accepted under the pair, so the simplification rewrites nothing anyone
relied on. DCO and the trademark note stand unchanged. Root `LICENSE-MIT`
becomes plain `LICENSE`; `LICENSE-APACHE` is removed.
