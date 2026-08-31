//! A plugin's state is a vault fact, not an app fact (ADR 0011). What one
//! process disables, every other process must honour — the app, this
//! tool and the headless agent read the same registry, so these
//! assertions run across real process boundaries.

use std::path::Path;
use std::process::Command;

fn cli(vault: &Path, args: &[&str]) -> serde_json::Value {
    let out = Command::new(env!("CARGO_BIN_EXE_gilune-cli"))
        .arg("--vault")
        .arg(vault)
        .args(args)
        .env_remove("GILUNE_CRASHPOINT")
        .output()
        .expect("spawn gilune-cli");
    assert!(
        out.status.success(),
        "command {args:?} failed:\n{}",
        String::from_utf8_lossy(&out.stderr)
    );
    serde_json::from_slice(&out.stdout).expect("json stdout")
}

const TRANSCRIBER: &str = "org.memory.fake-transcription";

#[test]
fn a_plugin_disabled_by_one_process_is_disabled_for_every_process() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["work"]); // declares the stand-in transcriber
    cli(dir.path(), &["capture", "--text", "first thought"]);
    cli(dir.path(), &["plugins", "disable", TRANSCRIBER]);

    assert_eq!(
        cli(dir.path(), &["work"])["completed"],
        0,
        "a separate process must honour the disabled plugin"
    );
    assert_eq!(
        cli(dir.path(), &["status"])["jobs_pending"],
        1,
        "waiting work is parked, not discarded"
    );

    cli(dir.path(), &["plugins", "enable", TRANSCRIBER]);
    assert_eq!(
        cli(dir.path(), &["work"])["completed"],
        1,
        "enabling resumes exactly where the work stopped"
    );
}

#[test]
fn nothing_is_queued_for_a_plugin_that_is_switched_off() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["work"]);
    cli(dir.path(), &["plugins", "disable", TRANSCRIBER]);
    cli(dir.path(), &["capture", "--text", "captured while off"]);

    assert_eq!(
        cli(dir.path(), &["status"])["jobs_pending"], 0,
        "a switched-off plugin queues nothing"
    );
    cli(dir.path(), &["plugins", "enable", TRANSCRIBER]);
    assert_eq!(
        cli(dir.path(), &["work"])["completed"], 0,
        "switching a plugin on does not silently re-run the past"
    );
}

#[test]
fn the_vault_projection_is_declared_by_every_host_and_cannot_be_removed() {
    let dir = tempfile::tempdir().expect("tempdir");
    let listed = cli(dir.path(), &["plugins"]);
    let plugins = listed["plugins"].as_array().expect("array");
    let vault = plugins
        .iter()
        .find(|p| p["plugin_id"] == "org.memory.vault")
        .expect("the vault projection is declared on open");
    assert_eq!(vault["required"], true);

    let refused = Command::new(env!("CARGO_BIN_EXE_gilune-cli"))
        .arg("--vault")
        .arg(dir.path())
        .args(["plugins", "remove", "org.memory.vault"])
        .output()
        .expect("spawn");
    assert!(!refused.status.success(), "the vault is the product surface");
}

#[test]
fn a_removed_plugin_stays_removed_when_the_next_process_registers_it() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["work"]);
    cli(dir.path(), &["capture", "--text", "a thought"]);
    cli(dir.path(), &["plugins", "remove", TRANSCRIBER]);

    // `work` declares the stand-in transcriber every run; re-declaring an
    // implementation is not consent to run it again.
    assert_eq!(cli(dir.path(), &["work"])["completed"], 0);
    let listed = cli(dir.path(), &["plugins"]);
    assert!(
        !listed["plugins"]
            .as_array()
            .expect("array")
            .iter()
            .any(|p| p["plugin_id"] == TRANSCRIBER),
        "a removed plugin does not reappear"
    );
}
