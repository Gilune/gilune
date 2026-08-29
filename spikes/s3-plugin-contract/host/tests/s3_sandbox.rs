//! S3 sandbox harness: the same sample processors as Spike S2, compiled to a
//! WebAssembly component, attacked through the host. Each run gets a fresh
//! store with fuel and memory limits; the component and engine must survive
//! everything the guest does.

use spike_s3_host::{Grants, RunConfig, RunOutcome, Sandbox};
use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;

fn component_path() -> PathBuf {
    static BUILT: OnceLock<PathBuf> = OnceLock::new();
    BUILT
        .get_or_init(|| {
            let guest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../guest");
            let status = Command::new("cargo")
                .args(["build", "--release", "--target", "wasm32-wasip2"])
                .current_dir(&guest_dir)
                .status()
                .expect("spawn cargo for guest build");
            assert!(status.success(), "guest component build failed");
            guest_dir.join("target/wasm32-wasip2/release/spike_s3_guest.wasm")
        })
        .clone()
}

fn sandbox() -> &'static Sandbox {
    static SANDBOX: OnceLock<Sandbox> = OnceLock::new();
    SANDBOX.get_or_init(|| Sandbox::load(&component_path()).expect("load component"))
}

const SOURCE_TEXT: &str = "Met Priya about the launch.\nTODO: send her the draft budget";

fn full_grants() -> Grants {
    let mut g = Grants {
        allow_read_source: true,
        allow_read_payload: true,
        ..Grants::default()
    };
    g.sources.insert(
        "src-1".into(),
        (SOURCE_TEXT.as_bytes().to_vec(), "text/plain".into()),
    );
    g.sources
        .insert("src-audio".into(), (vec![1u8, 2, 3], "audio/wav".into()));
    g.payloads.insert(
        "rev-1".into(),
        serde_json::json!({ "text": SOURCE_TEXT }).to_string(),
    );
    g
}

fn cfg(grants: Grants) -> RunConfig {
    RunConfig {
        grants,
        ..RunConfig::default()
    }
}

// ------------------------------------------------------------------ parity

#[test]
fn transcribe_component_matches_bundled_implementation() {
    // The bundled implementation (spike-s2 processors.rs) produces
    // serde_json::json!({"text": <text>}) for a text source; the component
    // must produce the identical payload from the identical contract.
    let report = sandbox()
        .run(cfg(full_grants()), "fake-transcribe", "src-1", "-")
        .unwrap();
    let RunOutcome::Completed(Some(out)) = report.outcome else {
        panic!("expected completed output");
    };
    assert_eq!(out.type_id, "org.memory.transcription/transcript");
    assert_eq!(out.class, "artifact");
    assert_eq!(
        out.payload_json,
        serde_json::json!({ "text": SOURCE_TEXT }).to_string(),
        "component payload must be byte-identical to the bundled one"
    );
    eprintln!(
        "transcribe: {:?}, fuel {:?}",
        report.elapsed, report.fuel_used
    );
}

#[test]
fn audio_transcribe_matches_bundled_digest_format() {
    use sha2::Digest;
    let report = sandbox()
        .run(cfg(full_grants()), "fake-transcribe", "src-audio", "-")
        .unwrap();
    let RunOutcome::Completed(Some(out)) = report.outcome else {
        panic!("expected completed output");
    };
    let sha = hex::encode(sha2::Sha256::digest([1u8, 2, 3]));
    let expected = format!("(untranscribable 3 bytes, sha {})", &sha[..12]);
    assert_eq!(
        out.payload_json,
        serde_json::json!({ "text": expected }).to_string()
    );
}

#[test]
fn task_component_matches_bundled_implementation() {
    let report = sandbox()
        .run(cfg(full_grants()), "fake-task", "-", "rev-1")
        .unwrap();
    let RunOutcome::Completed(Some(out)) = report.outcome else {
        panic!("expected completed output");
    };
    assert_eq!(out.type_id, "org.memory.tasks/task");
    assert_eq!(out.class, "proposal");
    assert_eq!(
        out.payload_json,
        serde_json::json!({
            "title": "TODO: send her the draft budget",
            "status": "proposed"
        })
        .to_string()
    );
}

#[test]
fn duplicate_delivery_is_deterministic() {
    let a = sandbox()
        .run(cfg(full_grants()), "fake-transcribe", "src-1", "-")
        .unwrap();
    let b = sandbox()
        .run(cfg(full_grants()), "fake-transcribe", "src-1", "-")
        .unwrap();
    match (a.outcome, b.outcome) {
        (RunOutcome::Completed(Some(x)), RunOutcome::Completed(Some(y))) => {
            assert_eq!(x.payload_json, y.payload_json);
            assert_eq!(x.type_id, y.type_id);
        }
        _ => panic!("both runs should complete"),
    }
}

// -------------------------------------------------------------- capabilities

#[test]
fn capability_denied_is_an_error_not_a_trap() {
    let grants = Grants {
        allow_read_source: false,
        ..full_grants()
    };
    let report = sandbox()
        .run(cfg(grants), "fake-transcribe", "src-1", "-")
        .unwrap();
    let RunOutcome::HandlerError(e) = report.outcome else {
        panic!("denial must surface as a handler error");
    };
    assert!(e.contains("capability denied"), "got: {e}");
    assert!(
        report.audit.iter().any(|a| a.contains("deny")),
        "denial must be audited: {:?}",
        report.audit
    );

    // The sandbox is unharmed: the same component still serves granted work.
    let ok = sandbox()
        .run(cfg(full_grants()), "fake-transcribe", "src-1", "-")
        .unwrap();
    assert!(matches!(ok.outcome, RunOutcome::Completed(Some(_))));
}

#[test]
fn out_of_scope_object_is_denied_even_with_the_capability() {
    // The guest deliberately asks for an object its grant does not cover.
    let report = sandbox()
        .run(cfg(full_grants()), "undeclared-read", "-", "-")
        .unwrap();
    let RunOutcome::HandlerError(e) = report.outcome else {
        panic!("out-of-scope read must be denied");
    };
    assert!(e.contains("host denied as expected"), "got: {e}");
    assert!(report
        .audit
        .iter()
        .any(|a| a.contains("deny read-source-bytes (out of scope)")));
}

// --------------------------------------------------------------- containment

#[test]
fn guest_crash_is_contained_and_sandbox_reusable() {
    let report = sandbox().run(cfg(full_grants()), "crash", "-", "-").unwrap();
    assert!(
        matches!(report.outcome, RunOutcome::Trapped(_)),
        "a panicking guest must trap, not corrupt the host"
    );
    let ok = sandbox()
        .run(cfg(full_grants()), "fake-transcribe", "src-1", "-")
        .unwrap();
    assert!(matches!(ok.outcome, RunOutcome::Completed(Some(_))));
}

#[test]
fn infinite_loop_is_bounded_by_fuel() {
    let started = std::time::Instant::now();
    let config = RunConfig {
        grants: full_grants(),
        fuel: 50_000_000,
        ..RunConfig::default()
    };
    let report = sandbox().run(config, "spin", "-", "-").unwrap();
    let RunOutcome::Trapped(t) = report.outcome else {
        panic!("spin must trap on fuel exhaustion");
    };
    assert!(t.to_lowercase().contains("fuel"), "trap should name fuel: {t}");
    assert!(
        started.elapsed() < std::time::Duration::from_secs(5),
        "fuel must bound wall-clock time, took {:?}",
        started.elapsed()
    );
}

#[test]
fn memory_hog_is_bounded_by_store_limit() {
    let config = RunConfig {
        grants: full_grants(),
        fuel: 2_000_000_000,
        memory_limit_bytes: 48 * 1024 * 1024,
    };
    let report = sandbox().run(config, "hog", "-", "-").unwrap();
    assert!(
        matches!(report.outcome, RunOutcome::Trapped(_)),
        "unbounded allocation must trap at the memory limit"
    );
    let fuel_used = report.fuel_used.unwrap_or(0);
    assert!(
        fuel_used < 1_000_000_000,
        "memory, not fuel, should have been the binding limit (used {fuel_used})"
    );
}

// ------------------------------------------------------------- output policy

#[test]
fn oversized_output_is_rejected_by_host_policy() {
    let report = sandbox()
        .run(cfg(full_grants()), "huge-output", "-", "-")
        .unwrap();
    let RunOutcome::PolicyViolation(p) = report.outcome else {
        panic!("oversized payload must be a policy violation");
    };
    assert!(p.contains("too large"), "got: {p}");
}

#[test]
fn malformed_json_output_is_rejected_by_host_policy() {
    let report = sandbox()
        .run(cfg(full_grants()), "bad-json", "-", "-")
        .unwrap();
    let RunOutcome::PolicyViolation(p) = report.outcome else {
        panic!("malformed payload must be a policy violation");
    };
    assert!(p.contains("not valid JSON"), "got: {p}");
}

#[test]
fn unknown_handler_is_a_clean_error() {
    let report = sandbox()
        .run(cfg(full_grants()), "no-such-handler", "-", "-")
        .unwrap();
    assert!(matches!(report.outcome, RunOutcome::HandlerError(_)));
}

// ---------------------------------------------------------------- economics

#[test]
fn instantiation_and_call_cost_smoke() {
    // Not a pass/fail budget yet — record the numbers the ADR 0003 gate
    // needs. Generous ceiling only to catch pathology.
    let load_start = std::time::Instant::now();
    let sb = Sandbox::load(&component_path()).unwrap();
    let load = load_start.elapsed();
    let report = sb
        .run(cfg(full_grants()), "fake-transcribe", "src-1", "-")
        .unwrap();
    eprintln!(
        "component load+compile: {load:?}; instantiate+call: {:?}; fuel: {:?}",
        report.elapsed, report.fuel_used
    );
    assert!(matches!(report.outcome, RunOutcome::Completed(Some(_))));
    assert!(load < std::time::Duration::from_secs(10));
    assert!(report.elapsed < std::time::Duration::from_secs(2));
}
