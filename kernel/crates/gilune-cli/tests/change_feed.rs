//! The vault change feed as a person and as concurrent processes meet
//! it (ADR 0020 § 8.1, § 8.2, § 8.9).
//!
//! Two things only this level can prove. A human can read the feed —
//! `gilune-cli changes` prints it and its cursor round-trips through
//! `--since`, which is the gate's "so a human can read both". And the
//! counter is dense and unique across **operating-system processes**:
//! multi-instance is supported use, SQLite's single writer is what makes
//! the counter safe, and no in-process test can show that.

use std::path::Path;
use std::process::{Child, Command, Output};

fn cli(vault: &Path, args: &[&str]) -> Output {
    let out = Command::new(env!("CARGO_BIN_EXE_gilune-cli"))
        .arg("--vault")
        .arg(vault)
        .args(args)
        .env_remove("GILUNE_CRASHPOINT")
        .output()
        .expect("spawn gilune-cli");
    assert!(
        out.status.success(),
        "gilune-cli {args:?} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    out
}

fn json(out: &Output) -> serde_json::Value {
    serde_json::from_slice(&out.stdout).expect("gilune-cli prints JSON")
}

fn spawn(vault: &Path, args: &[&str]) -> Child {
    Command::new(env!("CARGO_BIN_EXE_gilune-cli"))
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
fn a_person_can_read_the_feed_and_resume_from_where_they_stopped() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["capture", "--text", "the first thought"]);
    cli(dir.path(), &["capture", "--text", "the second thought"]);
    cli(dir.path(), &["project"]);

    let all = json(&cli(dir.path(), &["changes"]));
    let changes = all["changes"].as_array().expect("changes");
    assert_eq!(changes.len(), 2, "{all}");
    assert_eq!(changes[0]["kind"], "created");
    assert_eq!(changes[0]["cause"], "capture");
    assert!(changes[0]["rel_path"].as_str().expect("a path").ends_with(".md"));
    assert_eq!(all["complete"], true);

    // The cursor it printed is the cursor it takes.
    let first_page = json(&cli(dir.path(), &["changes", "--limit", "1"]));
    assert_eq!(first_page["changes"].as_array().expect("changes").len(), 1);
    assert_eq!(first_page["complete"], false);
    let next = first_page["next"].as_str().expect("a cursor").to_string();

    let rest = json(&cli(dir.path(), &["changes", "--since", &next]));
    assert_eq!(rest["changes"].as_array().expect("changes").len(), 1);
    assert_eq!(rest["complete"], true);

    // And a cursor this vault cannot answer from fails rather than
    // being answered with somebody else's history.
    let refused = Command::new(env!("CARGO_BIN_EXE_gilune-cli"))
        .arg("--vault")
        .arg(dir.path())
        .args(["changes", "--since", "another-vaults-epoch:1"])
        .output()
        .expect("spawn");
    assert!(!refused.status.success());
}

#[test]
fn reconcile_path_reaches_the_feed_from_the_command_line_too() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["capture", "--text", "before the edit"]);
    cli(dir.path(), &["project"]);
    let settled = json(&cli(dir.path(), &["changes"]))["next"]
        .as_str()
        .expect("a cursor")
        .to_string();

    let rel = json(&cli(dir.path(), &["changes"]))["changes"][0]["rel_path"]
        .as_str()
        .expect("a path")
        .to_string();
    let path = dir.path().join(&rel);
    let edited = std::fs::read_to_string(&path)
        .expect("read")
        .replace("before the edit", "after the edit");
    std::fs::write(&path, edited).expect("edit");

    let summary = json(&cli(dir.path(), &["reconcile-path", &rel]));
    assert_eq!(summary["edits"], 1);
    let page = json(&cli(dir.path(), &["changes", "--since", &settled]));
    let changes = page["changes"].as_array().expect("changes");
    assert_eq!(changes.len(), 1, "{page}");
    assert_eq!(changes[0]["kind"], "modified");
    assert_eq!(changes[0]["cause"], "external-edit");
}

#[test]
fn the_sequence_is_dense_and_unique_when_processes_race_the_counter() {
    // ADR 0020 § 2.1: SQLite's single writer is what makes the counter
    // safe across processes, and this is the only place that can be
    // shown. Twelve captures and four projections, all separate
    // processes, all writing the feed at once.
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["init"]);

    let mut racing: Vec<Child> = (0..12)
        .map(|i| spawn(dir.path(), &["capture", "--text", &format!("thought {i}")]))
        .collect();
    racing.extend((0..4).map(|_| spawn(dir.path(), &["project"])));
    for child in racing.iter_mut() {
        assert!(child.wait().expect("wait").success(), "a racing process failed");
    }
    // Whatever the racing projections did not reach.
    cli(dir.path(), &["project"]);

    let page = json(&cli(dir.path(), &["changes", "--limit", "1000"]));
    let changes = page["changes"].as_array().expect("changes");
    let seqs: Vec<i64> = changes
        .iter()
        .map(|c| c["seq"].as_i64().expect("a sequence number"))
        .collect();
    assert_eq!(
        seqs,
        (1..=seqs.len() as i64).collect::<Vec<i64>>(),
        "the counter is dense and unique across processes: {page}"
    );
    assert_eq!(page["complete"], true);

    // Every capture is announced, and every announcement is about a
    // file that is really there.
    let announced: std::collections::BTreeSet<&str> = changes
        .iter()
        .map(|c| c["rel_path"].as_str().expect("a path"))
        .collect();
    assert_eq!(announced.len(), 12, "every capture reached the feed: {page}");
    for rel in &announced {
        assert!(dir.path().join(rel).is_file(), "{rel} was announced and is not there");
    }

    // **Stated, because it is true rather than tidy.** Two processes
    // projecting the same object in the same instant both find no
    // record, both write the file, and both say so — the feed reports
    // what happened, and what happened is that the file was written
    // twice. Nothing is lost by it: a consumer re-derives from a path it
    // is told about, which is idempotent, and the sequence above is
    // still dense and unique. Only a projection that serialised its file
    // writes could avoid it, and that would cost multi-instance the
    // property that makes it work.
    assert!(changes.len() >= 12);
    assert!(
        changes.iter().all(|c| c["kind"] == "created" && c["cause"] == "capture"),
        "{page}"
    );
}
