//! Developer CLI over the kernel. Every command opens the store fresh —
//! running startup recovery — so the crash-matrix harness can kill a
//! command at any persistence boundary and assert the next command
//! recovers. Output is JSON on stdout; failures exit non-zero.

use gilune_kernel::store::Store;
use std::process::ExitCode;

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("error: {message}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut args = args.iter().map(String::as_str);

    let usage = "usage: gilune-cli --vault <dir> <init|capture --text <text>|work|\
                 external [--at <ms>]|apply --file <path>|project|reconcile|\
                 reconcile-path <rel>|changes [--since <epoch:seq>] [--limit <n>]|status|\
                 verify|backfill|alerts|plugins [enable|disable|remove <id>]|\
                 index [status|rebuild [<namespace>]]|facts <rel>|\
                 query <constraint>... [--scope line|note] [--limit <n>]>\n\
                 a constraint is text:<words>, body:/name:/path:<words>, or one of \
                 <predicate>=<value>, !=, ~ (contains), ^ (prefix), <, <=, >, >=, \
                 <predicate>? (exists), <predicate>! (does not exist)";
    let (Some("--vault"), Some(vault)) = (args.next(), args.next()) else {
        return Err(usage.to_string());
    };
    let store = Store::open(vault).map_err(|e| e.to_string())?;
    // Every host of this kernel carries the vault projection, so every
    // host declares it — the app, this tool and the agent then agree on
    // what is installed (ADR 0011).
    store
        .register_plugin(&gilune_kernel::vault::plugin_descriptor())
        .map_err(|e| e.to_string())?;

    match args.next() {
        Some("init") => {
            println!(
                "{}",
                serde_json::json!({
                    "vault": store.vault().display().to_string(),
                    "schema_version": store.schema_version().map_err(|e| e.to_string())?,
                    "device_id": store.device_id().map_err(|e| e.to_string())?,
                })
            );
            Ok(())
        }
        Some("capture") => {
            let (Some("--text"), Some(text)) = (args.next(), args.next()) else {
                return Err(usage.to_string());
            };
            let receipt = store
                .capture_bytes("txt", text.as_bytes())
                .map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "saved": true,
                    "source_id": receipt.source_id,
                    "rel_path": receipt.rel_path,
                    "sha256": receipt.sha256,
                    "bytes": receipt.bytes,
                })
            );
            Ok(())
        }
        Some("status") => {
            let s = store.status().map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "sources": s.sources,
                    "blobs_ready": s.blobs_ready,
                    "blobs_committed": s.blobs_committed,
                    "events": s.events,
                    "jobs_pending": s.jobs_pending,
                    // A subset of jobs_pending: work whose plugin is
                    // switched off. Waiting because nothing may take it,
                    // as against waiting for its turn.
                    "jobs_parked": s.jobs_parked,
                    "jobs_quarantined": s.jobs_quarantined,
                    "operations": s.operations,
                    "blobs_awaiting": s.blobs_awaiting,
                    "alerts": s.alerts,
                    "projections": s.projections,
                    "proposals_open": s.proposals_open,
                    "quarantined_files": s.quarantined_files,
                    "import_candidates": s.import_candidates,
                })
            );
            Ok(())
        }
        Some("work") => {
            // Dev tool: runs the deterministic stand-in transcriber, the
            // same one the crash harness aborts mid-commit. Registering it
            // is what subscribes it, so disabling it here behaves exactly
            // as disabling a real provider in the app does.
            store
                .register_plugin(&gilune_kernel::processor::fake_transcriber_descriptor())
                .map_err(|e| e.to_string())?;
            let s = store
                .run_pending_jobs(
                    &[&gilune_kernel::processor::FakeTranscriber],
                    gilune_kernel::ids::now_ms(),
                    30_000,
                )
                .map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "completed": s.completed,
                    "skipped_duplicate": s.skipped_duplicate,
                    "skipped_moved": s.skipped_moved,
                    "failed": s.failed,
                    "outputs": s.outputs,
                })
            );
            Ok(())
        }
        Some("external") => {
            // Dev tool: drives the FFI processor route's kernel half
            // (ADR 0018 § 2) as a host application drives it — claim,
            // stream the subject, submit. The crash harness aborts this
            // command between those steps and asserts the vault converges
            // on exactly one output or a cleanly re-claimable job.
            store
                .register_plugin(&gilune_kernel::external::fake_external_descriptor())
                .map_err(|e| e.to_string())?;
            // `--at <ms>` lets the harness step past a dead claimant's
            // lease without reaching into SQL, the same way the kernel's
            // own tests supply `now_ms` (jobs.rs).
            let now_ms = match (args.next(), args.next()) {
                (None, _) => gilune_kernel::ids::now_ms(),
                (Some("--at"), Some(ms)) => ms.parse().map_err(|_| usage.to_string())?,
                _ => return Err(usage.to_string()),
            };
            let delivered =
                gilune_kernel::external::run_external_once(&store, now_ms, 30_000)
                    .map_err(|e| e.to_string())?;
            println!(
                "{}",
                match delivered {
                    Some(d) => serde_json::json!({
                        "claimed": true,
                        "skipped_duplicate": d.skipped_duplicate,
                        "outputs": d.outputs,
                    }),
                    None => serde_json::json!({ "claimed": false }),
                }
            );
            Ok(())
        }
        Some("apply") => {
            // Dev tool: applies one operation envelope from a file, the
            // way a transport hands one over. The crash harness aborts
            // this command at apply's commit boundaries and asserts the
            // next command finds either nothing or exactly one.
            let (Some("--file"), Some(path)) = (args.next(), args.next()) else {
                return Err(usage.to_string());
            };
            let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
            let applied = store
                .apply_operation("cli", &bytes, gilune_kernel::limits::Limits::DEFAULT)
                .map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "op_id": applied.op_id,
                    "device_id": applied.device_id,
                    "op_seq": applied.op_seq,
                    "duplicate": applied.duplicate,
                    "uninterpreted": applied.uninterpreted,
                    "blob_wanted": applied.blob_wanted.map(|b| b.blob_id),
                })
            );
            Ok(())
        }
        Some("alerts") => {
            let listed: Vec<_> = store
                .integrity_alerts()
                .map_err(|e| e.to_string())?
                .into_iter()
                .map(|a| {
                    serde_json::json!({
                        "kind": a.kind,
                        "subject": a.subject,
                        "detail": a.detail,
                        "raised_ms": a.raised_ms,
                        // The kernel says which conditions are damage, so
                        // no reader keeps its own copy of the kind set.
                        "damage": a.damage,
                    })
                })
                .collect();
            println!("{}", serde_json::json!({ "alerts": listed }));
            Ok(())
        }
        Some("project") => {
            let p = store.project().map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "written": p.written,
                    "unchanged": p.unchanged,
                    "held_back": p.held_back,
                })
            );
            Ok(())
        }
        Some("reconcile") => {
            let r = store.reconcile().map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "edits": r.edits,
                    "renames": r.renames,
                    "tombstone_proposals": r.tombstone_proposals,
                    "imports": r.imports,
                    "quarantined": r.quarantined,
                })
            );
            Ok(())
        }
        Some("reconcile-path") => {
            let Some(rel) = args.next() else { return Err(usage.to_string()) };
            let r = store.reconcile_path(rel).map_err(|e| e.to_string())?;
            println!(
                "{}",
                serde_json::json!({
                    "edits": r.edits,
                    "renames": r.renames,
                    "tombstone_proposals": r.tombstone_proposals,
                    "imports": r.imports,
                    "quarantined": r.quarantined,
                })
            );
            Ok(())
        }
        Some("changes") => {
            // The vault change feed, for a person (ADR 0020 § 8.9).
            // `--since` takes a cursor as this command prints it —
            // `<epoch>:<seq>` — and defaults to the beginning, which is
            // the sanctioned full read.
            let mut cursor = store.change_origin().map_err(|e| e.to_string())?;
            let mut limit = 100u32;
            loop {
                match (args.next(), args.next()) {
                    (None, _) => break,
                    (Some("--since"), Some(value)) => {
                        let (epoch, seq) = value.rsplit_once(':').ok_or_else(|| usage.to_string())?;
                        cursor = gilune_kernel::changes::Cursor {
                            epoch: epoch.to_string(),
                            seq: seq.parse().map_err(|_| usage.to_string())?,
                        };
                    }
                    (Some("--limit"), Some(value)) => {
                        limit = value.parse().map_err(|_| usage.to_string())?
                    }
                    _ => return Err(usage.to_string()),
                }
            }
            let page = store.changes_since(&cursor, limit).map_err(|e| e.to_string())?;
            let listed: Vec<_> = page
                .changes
                .iter()
                .map(|c| {
                    serde_json::json!({
                        "seq": c.seq,
                        "kind": c.kind.as_str(),
                        "rel_path": c.rel_path,
                        "previous_rel_path": c.previous_rel_path,
                        "object_id": c.object_id,
                        "revision_id": c.revision_id,
                        // Best-effort by contract: exact where the
                        // kernel knows, never something to branch on.
                        "cause": c.cause.as_str(),
                        "created_ms": c.created_ms,
                    })
                })
                .collect();
            println!(
                "{}",
                serde_json::json!({
                    "changes": listed,
                    "next": format!("{}:{}", page.next.epoch, page.next.seq),
                    "complete": page.complete,
                })
            );
            Ok(())
        }
        Some("backfill") => {
            let queued = store.backfill_jobs().map_err(|e| e.to_string())?;
            println!("{}", serde_json::json!({ "queued": queued }));
            Ok(())
        }
        Some("plugins") => {
            match (args.next(), args.next()) {
                (None, _) => {}
                (Some("enable"), Some(id)) => {
                    store.set_plugin_enabled(id, true).map_err(|e| e.to_string())?
                }
                (Some("disable"), Some(id)) => {
                    store.set_plugin_enabled(id, false).map_err(|e| e.to_string())?
                }
                (Some("remove"), Some(id)) => {
                    store.remove_plugin(id).map_err(|e| e.to_string())?
                }
                _ => return Err(usage.to_string()),
            }
            let listed: Vec<_> = store
                .installed_plugins()
                .map_err(|e| e.to_string())?
                .into_iter()
                .map(|p| {
                    serde_json::json!({
                        "plugin_id": p.descriptor.plugin_id,
                        "name": p.descriptor.name,
                        "class": p.descriptor.class.as_str(),
                        "profile": p.descriptor.profile.as_str(),
                        "state": p.state.as_str(),
                        "required": p.descriptor.required,
                        "subscribes": p.descriptor.subscribes,
                        "granted": p.granted,
                        "ungranted": p.ungranted(),
                    })
                })
                .collect();
            println!("{}", serde_json::json!({ "plugins": listed }));
            Ok(())
        }
        Some("index") => {
            // The system index, for a person (ADR 0020 § 8.9). This tool
            // registers no indexer, so `rebuild <namespace>` empties that
            // namespace and puts its cursor back to the beginning — the
            // process that carries the implementation derives it again on
            // its next pass. `rebuild` with no namespace does the same for
            // every one of them and rebuilds the kernel's own text index
            // here and now.
            match (args.next(), args.next()) {
                (None, _) | (Some("status"), None) => {}
                (Some("rebuild"), namespace) => {
                    store
                        .rebuild_index(namespace)
                        .map_err(|e| e.to_string())?;
                }
                _ => return Err(usage.to_string()),
            }
            let status = store.index_status().map_err(|e| e.to_string())?;
            let namespaces: Vec<_> = status
                .namespaces
                .iter()
                .map(|n| {
                    serde_json::json!({
                        "namespace": n.namespace,
                        "version": n.version,
                        "cursor_seq": n.cursor_seq,
                        "facts": n.facts,
                        // Whether this process carries an implementation
                        // of it. One that does not leaves it alone.
                        "registered": n.registered,
                        "last_error": n.last_error,
                    })
                })
                .collect();
            println!(
                "{}",
                serde_json::json!({
                    "schema_version": status.schema_version,
                    "epoch": status.epoch,
                    "db_bytes": status.db_bytes,
                    "notes": status.notes,
                    "lines": status.lines,
                    "namespaces": namespaces,
                })
            );
            Ok(())
        }
        Some("facts") => {
            let Some(rel) = args.next() else { return Err(usage.to_string()) };
            let listed: Vec<_> = store
                .facts_of(rel)
                .map_err(|e| e.to_string())?
                .into_iter()
                .map(|f| {
                    serde_json::json!({
                        "namespace": f.namespace,
                        "line": f.line,
                        "predicate": f.predicate,
                        "value": f.value,
                    })
                })
                .collect();
            println!("{}", serde_json::json!({ "rel_path": rel, "facts": listed }));
            Ok(())
        }
        Some("query") => {
            let mut constraints = Vec::new();
            let mut scope = gilune_kernel::index::QueryScope::Line;
            let mut limit = 50u32;
            while let Some(token) = args.next() {
                match token {
                    "--scope" => {
                        scope = match args.next() {
                            Some("line") => gilune_kernel::index::QueryScope::Line,
                            Some("note") => gilune_kernel::index::QueryScope::Note,
                            _ => return Err(usage.to_string()),
                        }
                    }
                    "--limit" => {
                        let Some(value) = args.next() else { return Err(usage.to_string()) };
                        limit = value.parse().map_err(|_| usage.to_string())?;
                    }
                    _ => constraints.push(parse_constraint(token)?),
                }
            }
            if constraints.is_empty() {
                return Err(usage.to_string());
            }
            let anchors = store
                .query(&constraints, scope, limit)
                .map_err(|e| e.to_string())?;
            let listed: Vec<_> = anchors
                .iter()
                .map(|a| serde_json::json!({ "rel_path": a.rel_path, "line": a.line }))
                .collect();
            println!("{}", serde_json::json!({ "anchors": listed }));
            Ok(())
        }
        Some("verify") => {
            let problems = store.verify().map_err(|e| e.to_string())?;
            for problem in &problems {
                eprintln!("verify-problem: {problem}");
            }
            if problems.is_empty() {
                println!("{}", serde_json::json!({ "ok": true }));
                Ok(())
            } else {
                Err(format!("{} integrity problem(s)", problems.len()))
            }
        }
        _ => Err(usage.to_string()),
    }
}

/// One query term as a person types it. Deliberately terse rather than a
/// second query language: the DSL a person actually uses lives in the
/// shells (ADR 0015), and this is a way to look at what the kernel holds.
fn parse_constraint(token: &str) -> Result<gilune_kernel::index::Constraint, String> {
    use gilune_kernel::index::{Constraint, ConstraintOp, TextTarget};
    for (prefix, target) in [
        ("text:", TextTarget::Any),
        ("body:", TextTarget::Body),
        ("name:", TextTarget::Name),
        ("path:", TextTarget::Path),
    ] {
        if let Some(query) = token.strip_prefix(prefix) {
            return Ok(Constraint::Text {
                target,
                query: query.to_string(),
            });
        }
    }
    // Longest operator first, or `a!=b` parses as `a!` with rubbish after
    // it and `a>=b` as `a > =b`.
    for (symbol, op) in [
        ("!=", ConstraintOp::Ne),
        (">=", ConstraintOp::Ge),
        ("<=", ConstraintOp::Le),
        ("=", ConstraintOp::Eq),
        ("~", ConstraintOp::Contains),
        ("^", ConstraintOp::Prefix),
        (">", ConstraintOp::Gt),
        ("<", ConstraintOp::Lt),
    ] {
        if let Some((predicate, value)) = token.split_once(symbol) {
            if !predicate.is_empty() {
                return Ok(Constraint::Fact {
                    predicate: predicate.to_string(),
                    op,
                    value: value.to_string(),
                });
            }
        }
    }
    for (suffix, op) in [("?", ConstraintOp::Exists), ("!", ConstraintOp::NotExists)] {
        if let Some(predicate) = token.strip_suffix(suffix) {
            if !predicate.is_empty() {
                return Ok(Constraint::Fact {
                    predicate: predicate.to_string(),
                    op,
                    value: String::new(),
                });
            }
        }
    }
    Err(format!("{token} is not a constraint"))
}
