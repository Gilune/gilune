# ADR 0004: Durable Event Processing

Status: **Accepted for v0**  
Validation: Spike S2

## Context

Capture must remain safe when transcription, AI, sync, plugins, the network, or
the process fails. An in-memory event bus would create a gap between committed
source data and downstream work. Exactly-once execution cannot be guaranteed
across crashes and external services.

## Decision

- Commit data changes, sync operations, factual events, and subscription jobs in
  the same local transaction.
- Deliver subscription jobs at least once through leases and durable attempts.
- Require idempotency keys and atomic output/processing-run commits.
- Keep event payloads small and reference-based.
- Run external calls outside database transactions.
- Quarantine exhausted or invalid work without blocking capture or unrelated
  queues.
- Use platform schedulers only to wake the kernel; kernel job state remains the
  authority.

## Alternatives considered

### In-memory publish/subscribe

Low latency and simple, but loses work on termination and cannot support reliable
offline/background processing.

### Exactly-once processing

Not a credible end-to-end guarantee once network providers and external side
effects are involved. Attempting it would hide duplicates instead of designing
handlers to tolerate them.

### Polling objects without events

Recoverable but inefficient and loses explicit causation, correlation, versioned
subscription, and failure visibility.

## Consequences

- The kernel maintains job, lease, attempt, and quarantine state.
- Processors must be designed and tested for duplication.
- Event schema compatibility becomes public architecture.
- Operational state needs retention and compaction policy.
- Immediate in-process handling remains an optimisation and cannot bypass the
  durable record.

## Validation

S2 must kill execution at every data/event/job/output boundary and replay
deliveries in duplicate and reverse order. Passing the spike validates the
implementation, not the need for this decision.

