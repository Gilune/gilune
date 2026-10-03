//! The schema v5 upgrade, against a vault a real schema-v4 binary wrote
//! (ADR 0019 § Consequences; the migration idiom the v1 fixture
//! established in spike S2).
//!
//! `tests/fixtures/v4-vault` was produced by the pre-M5a `gilune-cli` and
//! its library: two captures — one carrying audio — a processor output, an
//! edit made outside the app, and an accepted tombstone. Five operations,
//! covering every kind the v4 kernel ever recorded, none of which has an
//! envelope because v4 never had one.
//!
//! The upgrade has to do two separate things and both are tested here:
//! carry the data (every migration owes that), and make history that was
//! only ever *written* into history that can be *read* — reconciled kind
//! names, canonical envelopes, a per-device sequence that continues where
//! the global counter stopped. The last assertion is the real one: the
//! adopted operations are applied into a fresh replica, which converges.

use gilune_kernel::changes::ChangeKind;
use gilune_kernel::index::{Constraint, QueryScope, TextTarget};
use gilune_kernel::limits::Limits;
use gilune_kernel::operations;
use gilune_kernel::store::{Store, SCHEMA_VERSION};
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

fn fixture_copy() -> tempfile::TempDir {
    let source = std::env::var_os("GILUNE_TEST_REPOSITORY")
        .map(|root| PathBuf::from(root).join("kernel/crates/gilune-cli/tests/fixtures/v4-vault"))
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/v4-vault"));
    let dir = tempfile::tempdir().expect("tempdir");
    copy_tree(&source, dir.path());
    dir
}

fn copy_tree(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).expect("create");
    for entry in std::fs::read_dir(from).expect("read fixture").flatten() {
        let target = to.join(entry.file_name());
        if entry.path().is_dir() {
            copy_tree(&entry.path(), &target);
        } else {
            std::fs::copy(entry.path(), &target).expect("copy");
        }
    }
}

fn cli(vault: &Path, crashpoint: Option<&str>, args: &[&str]) -> Output {
    let mut cmd = Command::new(support::cli());
    cmd.arg("--vault").arg(vault).args(args);
    match crashpoint {
        Some(point) => cmd.env("GILUNE_CRASHPOINT", point),
        None => cmd.env_remove("GILUNE_CRASHPOINT"),
    };
    cmd.output().expect("spawn gilune-cli")
}

/// What the fixture holds, asserted so a regenerated fixture that lost
/// its coverage fails loudly instead of silently testing less.
fn assert_fixture_shape(store: &Store) {
    let status = store.status().expect("status");
    assert_eq!(status.sources, 2, "two captures");
    assert_eq!(status.operations, 5, "five operations of three legacy kinds");
    assert_eq!(status.blobs_ready, 2);
    assert!(store.verify().expect("verify").is_empty());
}

#[test]
fn a_v4_vault_upgrades_in_place_without_losing_what_it_held() {
    let dir = fixture_copy();
    let store = Store::open(dir.path()).expect("open a vault a v4 binary wrote");
    // Whatever this build's schema is, not the version this test was
    // written against: the fixture is evidence about the *v4 shape*
    // surviving, and pinning the target version would make every later
    // migration edit this line rather than run the chain.
    assert_eq!(store.schema_version().expect("version"), SCHEMA_VERSION);
    assert_fixture_shape(&store);

    // The projection is unchanged by the upgrade: same committed state,
    // same bytes, so nobody's vault churns on first launch after M5a.
    let before: Vec<(String, Vec<u8>)> = markdown_files(dir.path());
    let summary = store.project().expect("project");
    assert_eq!(summary.written, 0, "an upgrade rewrites nothing a person can see");
    assert_eq!(markdown_files(dir.path()), before);

    // And the change feed says what the vault already holds.
    //
    // **This overturns what the v7 step decided**, and the reason is the
    // index. v7 left the feed empty on an upgrade, on the honest ground
    // that an upgrade changes no file. But the feed is not only a change
    // log: it is the enumeration a derived index rebuilds from
    // (ADR 0020 § 2.3), and `project` announces a file only when the
    // vault did not have it — so a feed that begins empty over a vault of
    // a thousand notes is an index that begins empty over a vault of a
    // thousand notes, and a search that finds nothing until each note
    // happens to be edited. The v8 step therefore announces every
    // projection and every import the feed has never named, once.
    let origin = store.change_origin().expect("origin");
    assert_eq!(origin.seq, 0);
    let page = store.changes_since(&origin, 100).expect("changes");
    let projected: Vec<String> = before
        .iter()
        .map(|(name, _)| name.clone())
        .filter(|name| name.ends_with(".md"))
        .map(|name| format!("Captures/{name}"))
        .collect();
    let announced: Vec<String> = page
        .changes
        .iter()
        .map(|change| change.rel_path.clone())
        .collect();
    assert_eq!(announced, projected, "everything already there, in path order");
    assert!(page.changes.iter().all(|c| c.kind == ChangeKind::Created));
    assert!(page.complete);

    // Which is to say: what the vault held is findable, without anybody
    // editing it to make it so.
    store.advance_index().expect("index");
    let found = store
        .query(
            &[Constraint::Text { target: TextTarget::Path, query: String::new() }],
            QueryScope::Note,
            100,
        )
        .expect("query");
    assert_eq!(found.len(), projected.len(), "every note the upgrade found is indexed");
}

#[test]
fn every_operation_v4_wrote_becomes_a_decodable_envelope_of_a_reconciled_kind() {
    let dir = fixture_copy();
    let store = Store::open(dir.path()).expect("open");
    let device = store.device_id().expect("device");

    let envelopes = store.operations_after(&device, 0, 100).expect("operations");
    assert_eq!(envelopes.len(), 5, "every legacy row now has bytes to send");

    let mut kinds = Vec::new();
    for (index, bytes) in envelopes.iter().enumerate() {
        let envelope = operations::decode(bytes, &Limits::DEFAULT)
            .unwrap_or_else(|e| panic!("operation {} must decode: {e}", index + 1));
        assert_eq!(envelope.device_id, device);
        assert_eq!(envelope.op_seq, index as i64 + 1, "the sequence stays dense");
        assert_eq!(
            operations::encode(&envelope),
            *bytes,
            "an adopted envelope is canonical, like any other"
        );
        kinds.push(envelope.kind);
    }
    assert_eq!(
        kinds,
        vec![
            operations::OBJECT_CREATED,
            operations::OBJECT_CREATED,
            operations::OBJECT_REVISED,
            operations::OBJECT_REVISED,
            operations::TOMBSTONE,
        ],
        "create-source and create-object become one kind; create-revision becomes another"
    );

    // The global counter is gone and the per-device one continues from
    // where it left off rather than restarting into a collision.
    store.capture_bytes("txt", b"the first thought after the upgrade").expect("capture");
    let next = store.operations_after(&device, 5, 10).expect("operations");
    assert_eq!(next.len(), 1);
    assert_eq!(
        operations::decode(&next[0], &Limits::DEFAULT).expect("decode").op_seq,
        6,
        "the local sequence space continued, it did not begin again"
    );
}

#[test]
fn history_adopted_from_v4_applies_cleanly_into_a_replica_that_never_saw_v4() {
    let source = fixture_copy();
    let upgraded = Store::open(source.path()).expect("open");
    let device = upgraded.device_id().expect("device");
    let envelopes = upgraded.operations_after(&device, 0, 100).expect("operations");

    let fresh = tempfile::tempdir().expect("tempdir");
    let replica = Store::open(fresh.path()).expect("open a vault with no history at all");
    for bytes in &envelopes {
        replica.apply_operation(&device, bytes, Limits::DEFAULT).expect("apply");
    }

    assert_eq!(
        replica.convergence_digest().expect("digest"),
        upgraded.convergence_digest().expect("digest"),
        "adopted history is real history: a replica reaches the same state from it"
    );
    assert_eq!(replica.sync_frontier(&device).expect("frontier"), 5);
    assert!(
        replica.integrity_alerts().expect("alerts").is_empty(),
        "nothing about a reconciled kind is unrecognised"
    );
    // Both creations named their blob, so the replica knows to ask for
    // both — including the one whose object was later tombstoned, whose
    // original bytes are preserved rather than chased away by a deletion.
    assert_eq!(replica.blobs_awaiting_bytes().expect("awaiting").len(), 2);
}

#[test]
fn an_upgrade_interrupted_at_any_boundary_converges_on_the_next_open() {
    for point in ["migration.during", "adoption.during"] {
        let dir = fixture_copy();
        let crashed = cli(dir.path(), Some(point), &["status"]);
        assert!(!crashed.status.success(), "{point}: the process must have died");

        let store = Store::open(dir.path()).expect("reopen after the crash");
        assert_eq!(store.schema_version().expect("version"), SCHEMA_VERSION, "{point}");
        assert_fixture_shape(&store);
        let device = store.device_id().expect("device");
        assert_eq!(
            store.operations_after(&device, 0, 100).expect("operations").len(),
            5,
            "{point}: adoption either happened or is finished now, never half done"
        );
    }
}

fn markdown_files(root: &Path) -> Vec<(String, Vec<u8>)> {
    let mut found = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir).expect("read").flatten() {
            if entry.file_name() == ".gilune" {
                continue;
            }
            if entry.path().is_dir() {
                stack.push(entry.path());
            } else {
                found.push((
                    entry.file_name().to_string_lossy().to_string(),
                    std::fs::read(entry.path()).expect("read file"),
                ));
            }
        }
    }
    found.sort();
    found
}

mod support;
