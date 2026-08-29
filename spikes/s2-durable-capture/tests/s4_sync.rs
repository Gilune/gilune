//! S4 convergence harness: multiple replicas exchanging operations through a
//! shared-folder relay (ADR 0005 semantics over the ADR 0008 shared-folder
//! transport shape). Replicas run in-process; every open replays startup
//! recovery, so each step models a fresh process on that device.

use spike_s2::{capture, jobs, processors, store, sync, vault, Kernel};
use std::path::Path;

struct Replica {
    dir: tempfile::TempDir,
}

impl Replica {
    fn new() -> Replica {
        let r = Replica {
            dir: tempfile::tempdir().unwrap(),
        };
        let k = r.open();
        processors::seed_subscriptions(&k).unwrap();
        r
    }
    fn open(&self) -> Kernel {
        Kernel::open(self.dir.path()).unwrap()
    }
    fn capture(&self, text: &str) -> String {
        let mut k = self.open();
        capture::capture(&mut k, text.as_bytes(), "text/plain")
            .unwrap()
            .object_id
    }
    fn capture_bytes(&self, bytes: &[u8], media: &str) -> String {
        let mut k = self.open();
        capture::capture(&mut k, bytes, media).unwrap().object_id
    }
    fn work(&self) -> usize {
        jobs::work(&mut self.open()).unwrap()
    }
    fn project(&self) {
        vault::project(&mut self.open()).unwrap();
    }
    fn push(&self, relay: &Path) -> sync::PushReport {
        sync::push(&mut self.open(), relay).unwrap()
    }
    fn pull(&self, relay: &Path) -> sync::PullReport {
        sync::pull(&mut self.open(), relay).unwrap()
    }
    fn count(&self, sql: &str) -> i64 {
        self.open().conn.query_row(sql, [], |r| r.get(0)).unwrap()
    }
    fn verify_ok(&self) {
        let problems = store::verify_invariants(&self.open()).unwrap();
        assert!(problems.is_empty(), "verify failed: {problems:?}");
    }
    /// Digest of user-visible converged state: objects, their sorted heads,
    /// revision payloads, and tombstones. Equal digests = convergence.
    fn digest(&self) -> String {
        let k = self.open();
        let mut rows: Vec<String> = Vec::new();
        let mut stmt = k
            .conn
            .prepare(
                "SELECT o.object_id, o.type_id, o.class,
                        COALESCE(o.tombstone_json, ''),
                        COALESCE((SELECT GROUP_CONCAT(h.revision_id)
                                  FROM (SELECT revision_id FROM heads
                                        WHERE object_id = o.object_id
                                        ORDER BY revision_id) h), '')
                 FROM objects o ORDER BY o.object_id",
            )
            .unwrap();
        let mapped = stmt
            .query_map([], |r| {
                Ok(format!(
                    "{}|{}|{}|{}|{}",
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, String>(4)?
                ))
            })
            .unwrap();
        for row in mapped {
            rows.push(row.unwrap());
        }
        spike_s2::ids::sha256_hex(rows.join("\n").as_bytes())
    }
    fn transcript_md(&self) -> std::path::PathBuf {
        let mut found = None;
        let dir = self.dir.path().join("Transcripts");
        for entry in std::fs::read_dir(dir).unwrap() {
            found = Some(entry.unwrap().path());
        }
        found.expect("transcript md")
    }
}

fn relay() -> tempfile::TempDir {
    tempfile::tempdir().unwrap()
}

#[test]
fn capture_flows_to_second_replica_without_reprocessing() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    a.capture("Met Priya about the launch.\nTODO: send the budget");
    a.work();
    a.project();
    let pushed = a.push(r.path());
    assert_eq!(pushed.ops_written, 3, "source + transcript + task ops");
    assert_eq!(pushed.blobs_written, 1);

    let pulled = b.pull(r.path());
    assert_eq!(pulled.ops_applied, 3);
    assert_eq!(pulled.pending_blobs, 0);

    assert_eq!(b.count("SELECT COUNT(*) FROM objects WHERE class='source'"), 1);
    assert_eq!(
        b.count("SELECT COUNT(*) FROM objects WHERE type_id='org.memory.transcription/transcript'"),
        1
    );
    assert_eq!(
        b.count("SELECT COUNT(*) FROM objects WHERE type_id='org.memory.tasks/task'"),
        1
    );
    // origin-device policy: B holds the results but was given no work.
    assert_eq!(b.count("SELECT COUNT(*) FROM jobs"), 0);
    assert_eq!(b.count("SELECT COUNT(*) FROM processing_runs"), 0);
    assert_eq!(b.work(), 0, "nothing for B to reprocess");

    // Identical projection on both sides, file for file.
    b.project();
    let a_md = std::fs::read(a.transcript_md()).unwrap();
    let b_md = std::fs::read(b.transcript_md()).unwrap();
    assert_eq!(a_md, b_md, "projected transcript must be byte-identical");

    assert_eq!(a.digest(), b.digest());
    a.verify_ok();
    b.verify_ok();
}

#[test]
fn pull_before_processing_respects_origin_device() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    a.capture("TODO: transcribe on A only");
    a.push(r.path());
    b.pull(r.path());
    // B has the source and correctly waits for A to do A's work.
    assert_eq!(b.count("SELECT COUNT(*) FROM objects"), 1);
    assert_eq!(b.count("SELECT COUNT(*) FROM jobs"), 0);

    a.work();
    a.push(r.path());
    let pulled = b.pull(r.path());
    assert_eq!(pulled.ops_applied, 2, "transcript and task arrive");
    assert_eq!(a.digest(), b.digest());
}

#[test]
fn concurrent_external_edits_preserve_both_heads() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    a.capture("shared text, no todos here");
    a.work();
    a.push(r.path());
    b.pull(r.path());
    a.project();
    b.project();

    // Both sides edit the same transcript while "offline".
    for (replica, tag) in [(&a, "edited on A"), (&b, "edited on B")] {
        let path = replica.transcript_md();
        let content = std::fs::read_to_string(&path).unwrap();
        std::fs::write(&path, content.replace("shared text", tag)).unwrap();
        vault::reconcile(&mut replica.open()).unwrap();
    }

    a.push(r.path());
    b.push(r.path());
    a.pull(r.path());
    b.pull(r.path());

    let heads = |x: &Replica| {
        x.count(
            "SELECT COUNT(*) FROM heads h JOIN objects o ON o.object_id = h.object_id
             WHERE o.type_id = 'org.memory.transcription/transcript'",
        )
    };
    assert_eq!(heads(&a), 2, "A must hold both concurrent heads");
    assert_eq!(heads(&b), 2, "B must hold both concurrent heads");
    assert_eq!(a.digest(), b.digest(), "same heads on both sides");

    // Projection surfaces the conflict rather than hiding it.
    a.project();
    let md = std::fs::read_to_string(a.transcript_md()).unwrap();
    assert!(md.contains("conflicted: true"), "got:\n{md}");
    a.verify_ok();
    b.verify_ok();
}

#[test]
fn unknown_type_round_trips_between_replicas() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    let payload = serde_json::json!({ "colour": "blue", "size": 3 });
    let object_id = store::dev_add_object(
        &mut a.open(),
        "com.example.widgets/widget",
        &payload,
    )
    .unwrap();
    a.push(r.path());
    b.pull(r.path());

    let stored: String = b
        .open()
        .conn
        .query_row(
            "SELECT r.payload_json FROM revisions r WHERE r.object_id = ?1",
            [&object_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(stored, payload.to_string(), "unknown payload preserved verbatim");

    b.project();
    let objects_dir = b.dir.path().join("Objects");
    assert!(objects_dir.exists(), "unknown type projected generically");
    assert_eq!(a.digest(), b.digest());
}

#[test]
fn duplicate_pull_and_late_joiner_converge() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    a.capture("TODO: one");
    a.capture("two, nothing to do");
    a.work();
    a.push(r.path());

    let first = b.pull(r.path());
    assert!(first.ops_applied > 0);
    let second = b.pull(r.path());
    assert_eq!(second.ops_applied, 0, "second pull is a no-op");

    // A third replica joining later reaches the same state from scratch.
    let c = Replica::new();
    c.pull(r.path());
    assert_eq!(a.digest(), b.digest());
    assert_eq!(a.digest(), c.digest());
    c.verify_ok();
}

#[test]
fn sequence_gap_halts_that_device_until_repaired() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    a.capture("first");
    a.capture("second");
    a.capture("third");
    a.push(r.path());

    // Hide the middle operation: a gap, not a loss.
    let a_device = a.open().device_id.clone();
    let ops_dir = r.path().join("ops").join(&a_device);
    let hidden = ops_dir.join("000000000002.json");
    let aside = ops_dir.join("aside");
    std::fs::rename(&hidden, &aside).unwrap();

    let pulled = b.pull(r.path());
    assert_eq!(pulled.ops_applied, 1, "only seq 1 may apply before the gap");
    assert_eq!(pulled.gaps, 1);
    assert!(b.count("SELECT COUNT(*) FROM integrity_alerts WHERE kind='sync-gap'") >= 1);

    std::fs::rename(&aside, &hidden).unwrap();
    let pulled = b.pull(r.path());
    assert_eq!(pulled.ops_applied, 2, "gap repaired, the rest applies");
    assert_eq!(a.digest(), b.digest());
}

#[test]
fn corrupt_operation_halts_without_damage() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    a.capture("precious");
    a.push(r.path());

    let a_device = a.open().device_id.clone();
    let op_file = r.path().join("ops").join(&a_device).join("000000000001.json");
    let original = std::fs::read(&op_file).unwrap();
    std::fs::write(&op_file, b"{definitely not json").unwrap();

    let pulled = b.pull(r.path());
    assert_eq!(pulled.ops_applied, 0);
    assert_eq!(pulled.corrupt, 1);
    assert_eq!(b.count("SELECT COUNT(*) FROM objects"), 0, "nothing half-applied");

    std::fs::write(&op_file, original).unwrap();
    let pulled = b.pull(r.path());
    assert_eq!(pulled.ops_applied, 1);
    assert_eq!(a.digest(), b.digest());
}

#[test]
fn interrupted_blob_transfer_resumes_without_corruption() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    // ~200 KB "audio": four chunks at the spike's 64 KB chunk size.
    let bytes: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
    a.capture_bytes(&bytes, "audio/wav");
    let pushed = a.push(r.path());
    assert_eq!(pushed.blobs_written, 1);

    // Lose a middle chunk in transfer.
    let blob_id: String = a
        .open()
        .conn
        .query_row("SELECT blob_id FROM blobs", [], |row| row.get(0))
        .unwrap();
    let chunk = r.path().join("blobs").join(&blob_id).join("2.chunk");
    let aside = chunk.with_extension("aside");
    std::fs::rename(&chunk, &aside).unwrap();

    let pulled = b.pull(r.path());
    assert_eq!(pulled.ops_applied, 1, "envelope applies even without bytes");
    assert_eq!(pulled.pending_blobs, 1);
    b.project(); // must not fail while bytes are pending
    b.verify_ok();

    std::fs::rename(&aside, &chunk).unwrap();
    let pulled = b.pull(r.path());
    assert_eq!(pulled.blobs_completed, 1);
    assert_eq!(pulled.pending_blobs, 0);

    // The assembled original is byte-identical.
    let rel: String = b
        .open()
        .conn
        .query_row("SELECT rel_path FROM blobs", [], |row| row.get(0))
        .unwrap();
    let assembled = std::fs::read(b.dir.path().join(rel)).unwrap();
    assert_eq!(assembled, bytes);
    b.verify_ok();
}

#[test]
fn tombstone_propagates_and_clears_the_remote_projection() {
    let (a, b, r) = (Replica::new(), Replica::new(), relay());
    a.capture("temporary thought");
    a.work();
    a.push(r.path());
    b.pull(r.path());
    b.project();
    let b_transcript = b.transcript_md();
    assert!(b_transcript.exists());

    let transcript_id: String = a
        .open()
        .conn
        .query_row(
            "SELECT object_id FROM objects WHERE type_id='org.memory.transcription/transcript'",
            [],
            |row| row.get(0),
        )
        .unwrap();
    store::tombstone_object(&mut a.open(), &transcript_id).unwrap();
    a.project();
    a.push(r.path());
    b.pull(r.path());
    b.project();

    assert!(!b_transcript.exists(), "tombstoned projection must clear on B");
    assert_eq!(
        b.count("SELECT COUNT(*) FROM objects WHERE tombstone_json IS NOT NULL"),
        1,
        "kernel history keeps the tombstone"
    );
    assert_eq!(a.digest(), b.digest());
}

#[test]
fn three_replicas_converge_with_mixed_writers() {
    let (a, b, c, r) = (Replica::new(), Replica::new(), Replica::new(), relay());
    a.capture("TODO: from A");
    a.work();
    store::dev_add_object(
        &mut b.open(),
        "com.example.notes/sticky",
        &serde_json::json!({ "text": "from B" }),
    )
    .unwrap();
    c.capture("from C, unprocessed");

    for x in [&a, &b, &c] {
        x.push(r.path());
    }
    for x in [&a, &b, &c] {
        x.pull(r.path());
    }

    let d = a.digest();
    assert_eq!(d, b.digest());
    assert_eq!(d, c.digest());
    for x in [&a, &b, &c] {
        x.verify_ok();
        // Origin-device work is not stolen by other replicas: only A ran
        // processing; C's unprocessed capture stays unprocessed everywhere.
        assert_eq!(
            x.count("SELECT COUNT(*) FROM processing_runs") > 0,
            std::ptr::eq(x, &a),
            "only A ran processing"
        );
    }
}
