# Engineering Conventions

Status: **Accepted** (owner ratification 2026-08-30)

The constitution says what the system must guarantee; the contracts say
where the boundaries are; the ADRs say which technologies. This document
says what the code itself looks like. It binds product code; spike code
keeps its own rule (clarity over polish, boundaries still respected).

## Structure follows the boundaries

- The dependency direction is law: **contracts ← kernel ← everything
  else** (platform adapters, shell, plugins, tools). The kernel names no
  domain type; domain behaviour is plugin territory. Nothing imports
  "upward".
- Extension happens at named seams — a new processor behind the contract,
  a new renderer registration, a new capability adapter — never by
  widening the kernel. A feature that requires editing kernel modules is
  a design smell that must justify itself in review.
- One module, one reason to change. Kernel modules own invariants, not
  features; if a module's doc header needs the word "and", it is usually
  two modules.

## Self-documenting code, sparse comments

- Names carry the meaning. A comment is owed only for what code cannot
  say: an invariant, a platform constraint, a deliberate
  why-this-not-that. Never narrate the next line, never leave
  changelog or authorship comments, never commit commented-out code —
  delete it, git remembers.
- Doc comments (`///`, KDoc) state **contracts** on everything public
  across a boundary: effects, failure modes, idempotency, cancellation
  behaviour. A module starts with 2–5 lines saying why it exists (the
  established house style).
- Test names are specifications:
  `external_edit_becomes_revision_and_projection_canonicalises`, never
  `test_edit_2`. If the name cannot state the behaviour, the test is
  testing the wrong thing.
- `TODO` only with an owner: a linked open question, ADR, or issue.
  British spelling throughout, code identifiers included.

## DRY applies to knowledge, not keystrokes

- Deduplicate **facts**, not shapes. Every invariant has exactly one
  home: the schema, the idempotency inputs, the portable rel-path rule,
  the chunk size. One fact in two places is a corruption bug waiting for
  a divergent edit.
- Two similar-looking functions may be honest coincidence. Abstraction
  must earn its keep: rule of three, and prefer visible duplication over
  the wrong abstraction — a copied block is deletable, a premature
  interface propagates.

## SOLID, translated for this codebase

Applied as boundary discipline, not object-orientation ritual:

- **Single responsibility** → the module rule above.
- **Open/closed** → extend at seams; the kernel changes for invariants,
  not features.
- **Liskov substitution** → contract fidelity: an implementation honours
  its contract's documented *semantics*, not merely its signature —
  handlers are idempotent and at-least-once tolerant, boundary calls are
  cancellation-safe, whichever side of the FFI or WIT they live on.
- **Interface segregation** → narrow seams: hand a component the least
  capability that works. `ProcessorCtx` is the precedent — a scoped read
  grant, never a database connection.
- **Dependency inversion** → the kernel depends on capability traits;
  platform adapters implement them. Rust never calls a platform API
  directly (ADR 0001).

## Rust (kernel, components, tools)

- Typed errors at every public boundary (error enums with meaning per
  variant); `anyhow` only inside binaries and spikes. No
  `unwrap`/`expect` on fallible paths in kernel code: a kernel panic is a
  bug, and the panic path stays diagnosable (hook + report, per S1).
- `unsafe` budget: none outside generated bindings. Any exception
  carries a `// SAFETY:` comment and survives review on its own merits.
- Platform differences live in dedicated helpers or modules
  (`fsync_dir` precedent), never inline `cfg` scattered through logic.
- `rustfmt` defaults; clippy clean with warnings denied in CI.

## Kotlin (shell)

- The shell renders and forwards; it holds no invariants and makes no
  domain decisions. If the shell must *know* something for correctness,
  that knowledge belongs in the kernel or a contract.
- Composables are render + event dispatch; state hoisted; long work in
  structured coroutines whose cancellation is safe across the boundary
  (S1 proves the drop crosses — code may rely on it).
- ktlint defaults.

## Tests

- Test the invariant, not the implementation: crash matrices, duplicate
  and reordered delivery, convergence, quarantine — the spike style is
  the product style.
- Every bug fix lands with the test that would have caught it.
- Coverage of invariants beats coverage of lines. No test theatre:
  asserting that a mock was called proves nothing a contract cares
  about.
