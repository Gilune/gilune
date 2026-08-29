# Transactions, Events, and Processing

Status: **Draft v0**  
Decision: durable at-least-once processing is accepted for v0.

## Transaction rule

A kernel transaction atomically commits:

- object, relationship, provenance, and blob-reference changes;
- corresponding local/sync operations;
- emitted factual events;
- any jobs that are a direct consequence of the transaction.

An event cannot describe a change that failed to commit. A committed change
cannot depend on an in-memory event to become visible to processors.

External network calls, model inference, audio recording, and notification
delivery do not occur while a database transaction is held open.

## Events are facts

Events use namespaced, versioned types such as:

- `memory.kernel/object-created@1`;
- `memory.kernel/object-revised@1`;
- `memory.capture/source-ready@1`;
- `org.memory.transcription/transcript-ready@1`.

An event envelope includes:

```text
event_id
event_type and schema_version
transaction_id
producer kernel/platform/plugin identity
origin_device
subject object/revision IDs
causation_event_id, if any
correlation_id
committed logical/causal position
small schema-validated payload
```

Events contain references rather than copying sensitive source bodies or blob
bytes. A subscriber must possess the capability to read the referenced data.

There is no global total ordering promise. The kernel preserves causal order for
operations on the same subject and records concurrency explicitly.

## Subscriptions create durable jobs

When a transaction emits an event, each compatible enabled subscription creates
a durable job. Job delivery is **at least once**.

Each job has:

- event and subscription IDs;
- plugin and handler version;
- idempotency key;
- run-location policy;
- status, lease, attempt count, and next eligible time;
- redacted last error and quarantine reason;
- output processing-run ID when completed.

A claimed job uses a time-bounded lease. If the process dies, the lease expires
and another execution can retry it.

## Idempotency

The default processing idempotency key is derived from:

```text
input revision set + plugin ID + processor ID + processor/config version
```

The input revision set is canonically ordered (sorted by revision ID) before
hashing so the key is stable regardless of delivery order.

The kernel records the completed processing run and output IDs against this key
in the same transaction that commits the outputs. A repeated delivery therefore
returns the prior result or safely completes the same logical work.

Processors that cause non-repeatable external effects must use a capability that
supports an external idempotency key or must create a user-confirmed proposal
instead of executing the effect automatically.

## Retry and quarantine

Failures are classified as:

- **transient** — retry with bounded exponential backoff and jitter;
- **blocked** — wait for a capability, secret, network, model, or user decision;
- **permanent input** — quarantine this job without disabling unrelated work;
- **plugin fault** — count toward plugin health and possible automatic disable;
- **kernel fault** — stop the affected queue and surface an integrity alert.

Retry limits are policy, not data loss. Exhausted jobs remain inspectable and can
be retried after configuration or plugin changes.

## Loop and amplification protection

Every emitted event inherits a correlation ID and identifies its cause. The
kernel enforces configurable limits for:

- maximum causal depth;
- repeated event/subject/processor combinations;
- events and jobs produced per transaction;
- CPU time, memory, output bytes, and network use for sandboxed plugins.

A detected loop is quarantined with its chain intact for diagnosis.

## Device execution policy

Subscriptions declare one of these policies:

- `origin-device` — process only where the initiating operation was created;
- `coordinator` — process on the currently elected/hosted coordinator;
- `every-device` — maintain a local-only result such as an index;
- `manual` — create a visible pending action and do not run automatically.

The default for creating syncable derived user data is `origin-device`. This
prevents every device from independently transcribing or asking a model about
the same capture. Deterministic idempotency keys still protect against failover
or concurrent coordination.

Execution policy is stored with the subscription and processing run so changing
it later does not disguise how earlier outputs were produced.

## Mobile background execution

The kernel owns durable job state; the platform shell owns scheduling.

On Android, the shell maps eligible durable work to WorkManager-compatible units
and may resume it after application or device restart. Jobs must checkpoint or
fit platform execution limits. Immediate post-capture work can begin in-process,
but its durable job remains authoritative if the process is stopped.

Equivalent adapters use the supported background mechanisms on iOS and desktop.
No plugin assumes that its process or thread remains alive.

## Router placement

The optional tiny intent router is a normal processor capability between a
transcript/source-ready event and downstream commands.

It may return:

- a high-confidence proposed capability invocation;
- structured arguments and confidence;
- a request for larger-model escalation;
- no decision.

The kernel validates the target capability and arguments independently. Routing
failure or low confidence leaves the capture untouched and visible. The router
cannot bypass permission prompts or convert an uncertain interpretation into an
irreversible external side effect.

## Recovery checks

On startup the kernel:

1. runs storage integrity and migration-state checks;
2. reconciles staged and committed blob state;
3. expires abandoned job leases;
4. ensures every committed event has the expected subscription jobs;
5. resumes eligible work through the platform scheduler;
6. reports quarantined work without blocking capture or read access.

