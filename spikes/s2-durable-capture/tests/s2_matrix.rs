//! S2 fault harness: run the real binary as a subprocess, abort it at each
//! named crash point, restart, and assert the recovery invariants from
//! architecture/spikes/README.md.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

fn bin() -> &'static str {
    env!("CARGO_BIN_EXE_spike-s2")
}

struct Harness {
    vault: tempfile::TempDir,
}

impl Harness {
    fn new() -> Harness {
        let h = Harness {
            vault: tempfile::tempdir().expect("tempdir"),
        };
        let out = h.run(&["init"], None);
        assert!(out.status.success(), "init failed: {}", stderr(&out));
        h
    }

    fn vault_path(&self) -> &Path {
        self.vault.path()
    }

    fn run(&self, args: &[&str], crashpoint: Option<&str>) -> Output {
        let mut cmd = Command::new(bin());
        cmd.arg("--vault").arg(self.vault.path()).args(args);
        cmd.env_remove("GILUNE_CRASHPOINT");
        cmd.env_remove("GILUNE_FAIL_HANDLER");
        if let Some(cp) = crashpoint {
            cmd.env("GILUNE_CRASHPOINT", cp);
        }
        cmd.output().expect("spawn spike-s2")
    }

    fn run_env(&self, args: &[&str], envs: &[(&str, &str)]) -> Output {
        let mut cmd = Command::new(bin());
        cmd.arg("--vault").arg(self.vault.path()).args(args);
        for (key, value) in envs {
            cmd.env(key, value);
        }
        cmd.output().expect("spawn spike-s2")
    }

    fn ok(&self, args: &[&str]) -> String {
        let out = self.run(args, None);
        assert!(
            out.status.success(),
            "command {:?} failed: {}\n{}",
            args,
            stdout(&out),
            stderr(&out)
        );
        stdout(&out)
    }

    fn status(&self) -> HashMap<String, i64> {
        self.ok(&["status"])
            .lines()
            .filter_map(|l| {
                let (k, v) = l.split_once('=')?;
                Some((k.to_string(), v.parse().ok()?))
            })
            .collect()
    }

    fn verify_ok(&self) {
        let out = self.run(&["verify"], None);
        assert!(
            out.status.success(),
            "verify failed:\n{}\n{}",
            stdout(&out),
            stderr(&out)
        );
    }

    /// Full pipeline on a healthy store.
    fn pipeline(&self, text: &str) {
        self.ok(&["capture", "--text", text]);
        self.ok(&["work"]);
        self.ok(&["project"]);
    }

    fn md_files(&self) -> Vec<PathBuf> {
        let mut found = Vec::new();
        let mut stack = vec![self.vault_path().to_path_buf()];
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir).unwrap() {
                let entry = entry.unwrap();
                let path = entry.path();
                let name = entry.file_name().to_string_lossy().to_string();
                if path.is_dir() {
                    if !name.starts_with('.') {
                        stack.push(path);
                    }
                } else if name.ends_with(".md") {
                    found.push(path);
                }
            }
        }
        found.sort();
        found
    }
}

fn stdout(o: &Output) -> String {
    String::from_utf8_lossy(&o.stdout).to_string()
}
fn stderr(o: &Output) -> String {
    String::from_utf8_lossy(&o.stderr).to_string()
}

// ---------------------------------------------------------------- happy path

#[test]
fn happy_path_capture_to_task_to_vault_to_export() {
    let h = Harness::new();
    h.pipeline("Remember the milk.\nTODO: buy milk tomorrow");
    let s = h.status();
    assert_eq!(s["sources"], 1);
    assert_eq!(s["transcripts"], 1);
    assert_eq!(s["tasks"], 1, "TODO line should yield one task proposal");
    assert_eq!(s["pending_jobs"], 0);
    assert_eq!(s["alerts"], 0);
    h.verify_ok();

    // Vault holds capture + transcript + task markdown.
    assert_eq!(h.md_files().len(), 3);

    // Export while quiescent, then verify hashes independently.
    let dest = tempfile::tempdir().unwrap();
    let dest_str = dest.path().to_string_lossy().to_string();
    h.ok(&["export", &dest_str]);
    h.ok(&["verify-export", &dest_str]);
    // Device-local state must not travel.
    assert!(!dest.path().join(".gilune/local").exists());
}

#[test]
fn capture_without_processors_still_works_and_exports() {
    // Constitution rule 1/7: capture and export never depend on processing.
    let h = Harness::new();
    h.ok(&["capture", "--text", "unprocessed thought"]);
    h.ok(&["project"]);
    let s = h.status();
    assert_eq!(s["sources"], 1);
    assert_eq!(s["transcripts"], 0);
    let dest = tempfile::tempdir().unwrap();
    let dest_str = dest.path().to_string_lossy().to_string();
    h.ok(&["export", &dest_str]);
    h.ok(&["verify-export", &dest_str]);
    h.verify_ok();
}

// -------------------------------------------------------- termination matrix

/// The invariant for every capture crash point: if stdout said "saved", the
/// capture must be fully recoverable after restart; if it did not, the store
/// must be clean (no half-visible capture) — and in both cases verify passes.
#[test]
fn capture_termination_matrix() {
    for cp in [
        "stage.after_write",
        "stage.after_fsync",
        "commit.inside",
        "commit.after",
        "promote.after",
    ] {
        let h = Harness::new();
        let out = h.run(&["capture", "--text", "TODO: crash safely"], Some(cp));
        assert!(
            !out.status.success(),
            "{cp}: capture should have aborted, got: {}",
            stdout(&out)
        );
        let reported_saved = stdout(&out).contains("saved ");

        // Restart: any command runs recovery. Then the world must be sane.
        let s = h.status();
        h.verify_ok();
        match cp {
            "stage.after_write" | "stage.after_fsync" | "commit.inside" => {
                assert!(!reported_saved, "{cp}: must not report saved pre-commit");
                assert_eq!(s["sources"], 0, "{cp}: no half-visible capture");
            }
            _ => {
                // Committed: recoverable regardless of whether 'saved' was
                // printed before the abort.
                assert_eq!(s["sources"], 1, "{cp}: committed capture must survive");
            }
        }

        // Whatever happened, the pipeline completes cleanly afterwards.
        if s["sources"] == 1 {
            h.ok(&["work"]);
            h.ok(&["project"]);
            let s2 = h.status();
            assert_eq!(s2["transcripts"], 1, "{cp}");
            assert_eq!(s2["tasks"], 1, "{cp}");
            h.verify_ok();
        }
    }
}

#[test]
fn work_termination_matrix() {
    for cp in ["job.after_claim", "job.after_work", "output.commit.inside"] {
        let h = Harness::new();
        h.ok(&["capture", "--text", "TODO: survive worker crashes"]);
        let out = h.run(&["work"], Some(cp));
        assert!(!out.status.success(), "{cp}: work should have aborted");

        // Restart and finish. job.after_claim leaves a live lease; the spike
        // lease is 30s, so force expiry by re-running work after redelivery
        // is not needed — recovery expires stale leases only after deadline,
        // so instead assert the job is still tracked, then complete it.
        let out2 = h.run(&["work"], None);
        assert!(out2.status.success());
        let mut s = h.status();
        if s["transcripts"] == 0 || s["tasks"] == 0 {
            // The crashed job may still hold an unexpired lease; it must
            // never be lost, only delayed. Wait out the spike lease? No —
            // prove it is still present, then force redelivery to finish.
            assert!(
                s["leased_jobs"] + s["pending_jobs"] > 0,
                "{cp}: crashed job disappeared"
            );
            h.ok(&["redeliver-latest", "memory.capture/source-ready"]);
            h.ok(&["work"]);
            h.ok(&["redeliver-latest", "org.memory.transcription/transcript-ready"]);
            h.ok(&["work"]);
            s = h.status();
        }
        assert_eq!(s["transcripts"], 1, "{cp}: exactly one transcript");
        assert_eq!(s["tasks"], 1, "{cp}: exactly one task");
        h.verify_ok();
    }
}

#[test]
fn projection_and_reconcile_crash_points_converge() {
    // Two captures so the projection has several files to write.
    let h = Harness::new();
    h.ok(&["capture", "--text", "TODO: first"]);
    h.ok(&["capture", "--text", "TODO: second"]);
    h.ok(&["work"]);

    let out = h.run(&["project"], Some("vault.during_projection"));
    assert!(!out.status.success(), "projection should have aborted");
    // Rerun: idempotent completion.
    h.ok(&["project"]);
    let files = h.md_files();
    assert_eq!(files.len(), 6, "2 captures + 2 transcripts + 2 tasks");
    h.verify_ok();

    // Externally edit two transcripts, crash mid-reconcile, rerun, converge.
    let transcripts: Vec<PathBuf> = files
        .iter()
        .filter(|p| p.to_string_lossy().contains("Transcripts/"))
        .cloned()
        .collect();
    assert_eq!(transcripts.len(), 2);
    for t in &transcripts {
        let content = std::fs::read_to_string(t).unwrap();
        std::fs::write(t, content + "\nedited outside\n").unwrap();
    }
    let out = h.run(&["reconcile"], Some("reconcile.during"));
    assert!(!out.status.success(), "reconcile should have aborted");
    h.ok(&["reconcile"]);
    h.ok(&["project"]);
    let out = h.ok(&["reconcile"]);
    assert!(
        out.contains("edits=0"),
        "reconcile must converge, got: {out}"
    );
    h.verify_ok();
}

// ------------------------------------------------------- duplicate delivery

#[test]
fn duplicate_and_reordered_delivery_is_idempotent() {
    let h = Harness::new();
    h.pipeline("TODO: deliver twice");
    let before = h.status();
    assert_eq!(before["transcripts"], 1);
    assert_eq!(before["tasks"], 1);

    // Redeliver both events, downstream first (reordered), then work.
    h.ok(&["redeliver-latest", "org.memory.transcription/transcript-ready"]);
    h.ok(&["redeliver-latest", "memory.capture/source-ready"]);
    h.ok(&["work"]);
    h.ok(&["redeliver-latest", "memory.capture/source-ready"]);
    h.ok(&["work"]);

    let after = h.status();
    assert_eq!(after["transcripts"], 1, "no duplicate transcript");
    assert_eq!(after["tasks"], 1, "no duplicate task");
    assert_eq!(after["runs"], before["runs"], "no duplicate processing runs");
    h.verify_ok();
}

// ----------------------------------------------------- retry and quarantine

#[test]
fn failing_handler_retries_then_quarantines_without_blocking_capture() {
    let h = Harness::new();
    h.ok(&["capture", "--text", "TODO: poison"]);
    // Drive the transcribe handler to exhaustion (tiny backoff for the test).
    for _ in 0..6 {
        let out = h.run_env(
            &["work"],
            &[
                ("GILUNE_FAIL_HANDLER", "fake-transcribe"),
                ("GILUNE_BACKOFF_BASE_MS", "1"),
            ],
        );
        assert!(out.status.success());
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    let s = h.status();
    assert_eq!(s["quarantined_jobs"], 1, "job should be quarantined");
    assert_eq!(s["transcripts"], 0);

    // Quarantine must not block new capture or export.
    h.ok(&["capture", "--text", "still capturing"]);
    let dest = tempfile::tempdir().unwrap();
    let dest_str = dest.path().to_string_lossy().to_string();
    h.ok(&["project"]);
    h.ok(&["export", &dest_str]);

    // Operator retries after the fault is fixed: work completes.
    h.ok(&["retry-quarantined"]);
    h.ok(&["work"]);
    let s2 = h.status();
    assert_eq!(s2["quarantined_jobs"], 0);
    assert_eq!(s2["transcripts"], 2);
    h.verify_ok();
}

// ------------------------------------------------------------ vault behaviour

#[test]
fn deleting_markdown_projection_rebuilds_equivalently() {
    let h = Harness::new();
    h.pipeline("TODO: rebuild me");
    let files = h.md_files();
    let mut originals: HashMap<PathBuf, Vec<u8>> = HashMap::new();
    for f in &files {
        originals.insert(f.clone(), std::fs::read(f).unwrap());
        std::fs::remove_file(f).unwrap();
    }
    h.ok(&["project"]);
    for (path, original) in originals {
        let rebuilt = std::fs::read(&path)
            .unwrap_or_else(|_| panic!("{} not rebuilt", path.display()));
        assert_eq!(rebuilt, original, "{} differs after rebuild", path.display());
    }
    h.verify_ok();
}

#[test]
fn external_edit_becomes_revision_and_projection_canonicalises() {
    let h = Harness::new();
    h.pipeline("original text without todos");
    let transcript = h
        .md_files()
        .into_iter()
        .find(|p| p.to_string_lossy().contains("Transcripts/"))
        .expect("transcript file");
    let content = std::fs::read_to_string(&transcript).unwrap();
    let edited = content.replace("original text", "corrected text");
    std::fs::write(&transcript, edited).unwrap();

    let out = h.ok(&["reconcile"]);
    assert!(out.contains("edits=1"), "got: {out}");
    h.ok(&["project"]);
    let final_content = std::fs::read_to_string(&transcript).unwrap();
    assert!(final_content.contains("corrected text"));
    // Converged: nothing further to ingest.
    let out = h.ok(&["reconcile"]);
    assert!(out.contains("edits=0"), "got: {out}");
    h.verify_ok();
}

#[test]
fn external_delete_becomes_proposal_and_file_is_not_resurrected() {
    let h = Harness::new();
    h.pipeline("text destined for deletion");
    let transcript = h
        .md_files()
        .into_iter()
        .find(|p| p.to_string_lossy().contains("Transcripts/"))
        .expect("transcript file");
    std::fs::remove_file(&transcript).unwrap();

    let out = h.ok(&["reconcile"]);
    assert!(out.contains("tombstone_proposals=1"), "got: {out}");
    h.ok(&["project"]);
    assert!(
        !transcript.exists(),
        "deleted file must not be resurrected while the proposal is open"
    );
    let s = h.status();
    assert_eq!(s["tombstone_proposals"], 1);
    assert_eq!(s["transcripts"], 1, "kernel history untouched");
    h.verify_ok();
}

#[test]
fn malformed_front_matter_is_quarantined_untouched() {
    let h = Harness::new();
    h.pipeline("innocent text");
    let transcript = h
        .md_files()
        .into_iter()
        .find(|p| p.to_string_lossy().contains("Transcripts/"))
        .expect("transcript file");
    let broken = "---\ngilune: [unclosed\n---\n\nbody\n";
    std::fs::write(&transcript, broken).unwrap();
    let out = h.ok(&["reconcile"]);
    assert!(out.contains("quarantined=1"), "got: {out}");
    assert_eq!(
        std::fs::read_to_string(&transcript).unwrap(),
        broken,
        "quarantined file must not be modified"
    );
    h.verify_ok();
}

#[test]
fn foreign_markdown_is_an_import_candidate_not_an_error() {
    let h = Harness::new();
    h.pipeline("existing note");
    std::fs::write(
        h.vault_path().join("Captures/obsidian-native-note.md"),
        "just a note the user wrote in Obsidian\n",
    )
    .unwrap();
    let out = h.ok(&["reconcile"]);
    assert!(out.contains("quarantined=1"), "got: {out}");
    let s = h.status();
    assert_eq!(s["quarantine_files"], 1);
    h.verify_ok();
}

#[test]
fn edited_source_is_flagged_never_rewritten_as_source_history() {
    let h = Harness::new();
    h.pipeline("immutable capture text");
    let capture_md = h
        .md_files()
        .into_iter()
        .find(|p| p.to_string_lossy().contains("Captures/"))
        .expect("capture file");
    let content = std::fs::read_to_string(&capture_md).unwrap();
    std::fs::write(&capture_md, content.replace("immutable", "tampered")).unwrap();
    let out = h.ok(&["reconcile"]);
    assert!(out.contains("quarantined=1"), "source edit must quarantine, got: {out}");
    let out = h.ok(&["reconcile"]);
    // Still flagged, still not ingested.
    assert!(out.contains("edits=0"), "got: {out}");
    h.verify_ok();
}

#[test]
fn renamed_file_is_tracked_by_id_not_path() {
    let h = Harness::new();
    h.pipeline("name me differently");
    let transcript = h
        .md_files()
        .into_iter()
        .find(|p| p.to_string_lossy().contains("Transcripts/"))
        .expect("transcript file");
    let new_path = transcript.with_file_name("my own name.md");
    std::fs::rename(&transcript, &new_path).unwrap();
    let out = h.ok(&["reconcile"]);
    assert!(out.contains("renames=1"), "got: {out}");
    assert!(out.contains("tombstone_proposals=0"), "rename is not deletion: {out}");
    // Projection keeps writing to the user's chosen path.
    h.ok(&["project"]);
    assert!(new_path.exists());
    assert!(!transcript.exists());
    h.verify_ok();
}

#[test]
fn export_copy_restores_on_another_machine() {
    let h = Harness::new();
    h.pipeline("TODO: move house");
    let dest = tempfile::tempdir().unwrap();
    let dest_str = dest.path().to_string_lossy().to_string();
    h.ok(&["export", &dest_str]);

    // "Another machine": open the exported tree as a vault. The database is
    // device-local and was not exported; reconcile ingests the files as
    // import candidates — nothing is lost and nothing crashes.
    let out = Command::new(bin())
        .arg("--vault")
        .arg(dest.path())
        .arg("status")
        .output()
        .unwrap();
    assert!(out.status.success(), "fresh open of export failed");
    let out2 = Command::new(bin())
        .arg("--vault")
        .arg(dest.path())
        .arg("reconcile")
        .output()
        .unwrap();
    assert!(out2.status.success());
    let text = stdout(&out2);
    assert!(
        text.contains("quarantined=3"),
        "all three md files become import candidates on a fresh device: {text}"
    );
}
