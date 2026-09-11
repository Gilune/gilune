//! The system index as a person and as separate processes meet it
//! (ADR 0020 § 8.3, § 8.9).
//!
//! What only this level can show: the index is maintained across
//! **operating-system processes** and answers the same question in any
//! of them, and a tool that carries no plugin implementation keeps the
//! kernel's own text index current without touching a namespace it
//! cannot derive.

use std::path::Path;
use std::process::{Command, Output};

fn cli(vault: &Path, args: &[&str]) -> Output {
    let out = Command::new(support::cli())
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

fn paths(value: &serde_json::Value) -> Vec<String> {
    value["anchors"]
        .as_array()
        .expect("anchors")
        .iter()
        .map(|a| a["rel_path"].as_str().expect("a path").to_string())
        .collect()
}

#[test]
fn complete_search_pages_documents_across_lines_and_returns_snippets() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["init"]);
    std::fs::write(dir.path().join("a.md"), "alpha\n\nbeta").expect("write");
    std::fs::write(dir.path().join("b.md"), "alpha beta").expect("write");
    cli(dir.path(), &["reconcile"]);
    let page = json(&cli(
        dir.path(),
        &["search", "text:alpha", "text:beta", "--limit", "1"],
    ));
    assert_eq!(page["total_hits"], 2);
    assert_eq!(page["index_complete"], true);
    assert_eq!(page["hits"][0]["rel_path"], "a.md");
    assert_eq!(page["hits"][0]["lines"][1]["number"], 3);
    let next = json(&cli(
        dir.path(),
        &[
            "search",
            "text:alpha",
            "text:beta",
            "--limit",
            "1",
            "--cursor",
            page["next_cursor"].as_str().expect("cursor"),
        ],
    ));
    assert_eq!(next["hits"][0]["rel_path"], "b.md");
    assert!(next["next_cursor"].is_null());
    let lines = json(&cli(
        dir.path(),
        &["search", "text:alpha", "text:beta", "--scope", "line"],
    ));
    assert_eq!(lines["total_hits"], 1);
}

#[test]
fn a_person_can_ask_the_index_what_the_vault_says_and_where() {
    let dir = tempfile::tempdir().expect("tempdir");
    cli(
        dir.path(),
        &["capture", "--text", "the plasterer arrives on tuesday"],
    );
    cli(
        dir.path(),
        &["capture", "--text", "a note about paint and nothing else"],
    );
    cli(dir.path(), &["project"]);

    let status = json(&cli(dir.path(), &["index", "status"]));
    assert_eq!(status["schema_version"], gilune_kernel::index::SCHEMA_VERSION);
    assert_eq!(status["notes"], 2);
    assert!(status["epoch"].as_str().expect("an epoch").len() >= 8);
    let namespaces = status["namespaces"].as_array().expect("namespaces");
    // The two the kernel owns and nobody registers: the note text every
    // full-text query is answered from, and the front matter no indexer
    // is ever shown.
    assert_eq!(namespaces.len(), 2, "{status}");
    assert_eq!(namespaces[0]["namespace"], "frontmatter");
    assert_eq!(namespaces[1]["namespace"], "text");
    for namespace in namespaces {
        assert_eq!(namespace["cursor_seq"], 2, "it caught up with the feed");
    }

    let found = json(&cli(
        dir.path(),
        &["query", "text:plasterer", "--scope", "note"],
    ));
    assert_eq!(paths(&found).len(), 1, "{found}");
    let line = json(&cli(dir.path(), &["query", "text:paint"]));
    assert_eq!(line["anchors"][0]["line"], 1);
    let nothing = json(&cli(dir.path(), &["query", "text:nobody wrote this"]));
    assert!(paths(&nothing).is_empty());

    // What a note says about itself, and nothing a plugin said: no
    // plugin is registered in this process, so every fact here is the
    // kernel's own reading of the front matter it wrote.
    let facts = json(&cli(
        dir.path(),
        &[
            "facts",
            line["anchors"][0]["rel_path"].as_str().expect("a path"),
        ],
    ));
    let rows = facts["facts"].as_array().expect("facts");
    assert!(!rows.is_empty(), "{facts}");
    assert!(
        rows.iter().all(|f| f["namespace"] == "frontmatter"),
        "{facts}"
    );
    assert!(
        rows.iter()
            .any(|f| f["predicate"] == "frontmatter.gilune-type"),
        "the kernel stores its own keys like any other: {facts}"
    );

    // And the front matter is answerable as a query, which is what
    // `[key:value]` in the shipped search language will compile to.
    let typed = json(&cli(
        dir.path(),
        &[
            "query",
            "frontmatter.gilune-type=org.memory.source",
            "--scope",
            "note",
        ],
    ));
    assert_eq!(paths(&typed).len(), 2, "{typed}");
}

#[test]
fn one_process_writes_and_another_finds_it_without_either_rescanning() {
    // The cross-process half. The index lives beside the vault, so a
    // second instance's writes reach the first reader — which is the
    // thing a per-process cache could never do.
    let dir = tempfile::tempdir().expect("tempdir");
    cli(dir.path(), &["capture", "--text", "written by one process"]);
    cli(dir.path(), &["project"]);
    let before = json(&cli(
        dir.path(),
        &["query", "text:one process", "--scope", "note"],
    ));
    assert_eq!(paths(&before).len(), 1);

    cli(
        dir.path(),
        &["capture", "--text", "written by another process"],
    );
    cli(dir.path(), &["project"]);
    let after = json(&cli(
        dir.path(),
        &["query", "text:another process", "--scope", "note"],
    ));
    assert_eq!(paths(&after).len(), 1, "{after}");
    assert_eq!(
        json(&cli(dir.path(), &["index", "status"]))["notes"],
        2,
        "every process that passes keeps the text index current"
    );
}

#[test]
fn a_rebuild_lands_exactly_where_keeping_up_landed() {
    let dir = tempfile::tempdir().expect("tempdir");
    for n in 0..5 {
        cli(
            dir.path(),
            &["capture", "--text", &format!("a thought numbered {n}")],
        );
    }
    cli(dir.path(), &["project"]);
    let kept = json(&cli(
        dir.path(),
        &["query", "text:thought numbered", "--scope", "note"],
    ));

    cli(dir.path(), &["index", "rebuild"]);
    let rebuilt = json(&cli(
        dir.path(),
        &["query", "text:thought numbered", "--scope", "note"],
    ));
    assert_eq!(paths(&kept), paths(&rebuilt));
    assert_eq!(paths(&rebuilt).len(), 5);
}

mod support;
