//! Multi-instance is supported use (owner decision 2026-08-31): many OS
//! processes work one vault concurrently — the app, a second app window,
//! the agent, the CLI. This torture test spawns real processes: twenty
//! concurrent captures racing two projection loops, a reconcile loop,
//! and a processing loop, then asserts nothing was lost, doubled, or
//! left half-visible.

use std::path::Path;
use std::process::{Child, Command};

fn spawn(vault: &Path, args: &[&str]) -> Child {
    Command::new(support::cli())
        .arg("--vault")
        .arg(vault)
        .args(args)
        .env_remove("GILUNE_CRASHPOINT")
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .expect("spawn gilune-cli")
}

#[test]
fn twenty_concurrent_captures_across_processes_all_survive() {
    let dir = tempfile::tempdir().expect("tempdir");
    {
        // Subscribe up front so capture-created jobs exist for `work`.
        let store = gilune_kernel::store::Store::open(dir.path()).expect("open");
        store
            .subscribe("transcribe", gilune_kernel::capture::SOURCE_READY_EVENT)
            .expect("subscribe");
    }

    let mut captures: Vec<Child> = (0..20)
        .map(|i| spawn(dir.path(), &["capture", "--text", &format!("concurrent thought {i}")]))
        .collect();
    let mut churn: Vec<Child> = vec![
        spawn(dir.path(), &["project"]),
        spawn(dir.path(), &["project"]),
        spawn(dir.path(), &["reconcile"]),
        spawn(dir.path(), &["work"]),
    ];

    let mut failures = Vec::new();
    for child in captures.iter_mut() {
        let out = child.wait_with_output_ref();
        if !out.0 {
            failures.push(out.1);
        }
    }
    for child in churn.iter_mut() {
        let out = child.wait_with_output_ref();
        if !out.0 {
            failures.push(out.1);
        }
    }
    assert!(
        failures.is_empty(),
        "every concurrent process must succeed; failures:\n{}",
        failures.join("\n---\n")
    );

    let store = gilune_kernel::store::Store::open(dir.path()).expect("final open");
    let status = store.status().expect("status");
    assert_eq!(status.sources, 20, "no capture lost");
    assert_eq!(status.blobs_ready, 20, "every blob promoted");
    assert_eq!(status.blobs_committed, 0, "nothing half-promoted");
    assert_eq!(status.operations, 20, "one operation per capture");
    let problems = store.verify().expect("verify");
    assert!(problems.is_empty(), "integrity holds; problems: {problems:?}");

    store.project().expect("final project");
    assert_eq!(store.status().expect("status").projections, 20);
    assert!(
        !dir.path().join(".gilune/local/lost+found").exists(),
        "no live capture was stolen by a concurrent recovery"
    );
}

trait WaitOutput {
    fn wait_with_output_ref(&mut self) -> (bool, String);
}

impl WaitOutput for Child {
    fn wait_with_output_ref(&mut self) -> (bool, String) {
        let status = self.wait().expect("child wait");
        let mut err = String::new();
        if let Some(stderr) = self.stderr.take() {
            use std::io::Read;
            let mut reader = stderr;
            let _ = reader.read_to_string(&mut err);
        }
        (status.success(), err)
    }
}

mod support;
