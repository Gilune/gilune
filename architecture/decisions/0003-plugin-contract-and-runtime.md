# ADR 0003: Plugin Contract and Runtime

Status: **Proposed**  
Validation: Spikes S3 and S5

## Context

Plugins need stable language-neutral contracts, capability isolation, controlled
resource use, and crash containment. First-party features must exercise the same
contracts, but mobile distribution rules and runtime maturity prevent assuming
one binary packaging model on every platform.

## Proposed decision

- Define host/plugin contracts in WIT packages.
- Generate bindings/adapters for bundled implementations from the same contract
  definitions.
- Use WebAssembly components as the preferred untrusted installable runtime on
  desktop and server.
- Evaluate Wasmtime as the initial component host, with explicit CPU/fuel,
  memory, output, and capability limits.
- Statically compile or bundle mobile-store plugin implementations while keeping
  their manifest, grants, schemas, and conformance tests identical.
- Reserve out-of-process trusted native bridges for exceptional desktop OS/SDK
  integration.

WIT defines language-neutral component contracts:
<https://component-model.bytecodealliance.org/design/wit.html>. The component
model restricts interaction to explicit imports/exports and supports capability
reasoning: <https://component-model.bytecodealliance.org/design/why-component-model.html>.

Wasmtime is a candidate rather than an accepted mobile runtime because its own
documentation describes Android and iOS as supported but less well tested:
<https://docs.wasmtime.dev/stability-platform-support.html>.

## Alternatives considered

### Native shared-library ABI

Offers performance and native SDK access but weak isolation, difficult
cross-language stability, platform-specific packaging, and high crash/security
risk.

### JSON-RPC to child processes

Provides strong desktop crash isolation and language flexibility, but child
processes are awkward or unavailable on mobile and capability control becomes an
OS sandbox/configuration problem. It remains appropriate for trusted desktop
bridges.

### Embedded JavaScript or Lua

Portable and approachable, but adds a language/runtime-specific API, makes type
and capability contracts less rigorous, and creates a larger policy and supply-
chain surface for remotely supplied scripts.

### In-language plugin interfaces only

Simple for first-party code but prevents a credible third-party, cross-language,
and cross-platform contract.

## Consequences

- WIT packages become public compatibility assets and require conformance tests.
- Not every library or language can compile cleanly to a component.
- Host calls must be coarse enough to avoid excessive boundary overhead.
- Component startup, async streams, blob I/O, and debugging require validation.
- “Same API” no longer falsely implies “same install mechanism.”

## Acceptance gate

S3 must run one realistic processor through:

- a sandboxed desktop component;
- a bundled Android implementation generated from the same contract;
- capability grant and denial paths;
- unknown optional API feature negotiation;
- duplicate event delivery and deterministic output;
- CPU/memory exhaustion, crash, quarantine, upgrade, disable, and uninstall;
- streaming blob input without unbounded memory use.

S5 must confirm that the proposed mobile packaging and remotely supplied
declarative content comply with the intended store distribution channels.

