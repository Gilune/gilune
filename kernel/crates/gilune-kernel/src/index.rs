//! The system index (ADR 0020 § 2.3–2.4): facts about anchors, stored
//! once, contributed by many, queried by a conjunction nobody wrote.
//!
//! An **anchor** is a place in the vault — a note, or a line in a note.
//! A plugin registers an indexer, a pure function from a note's text to
//! `(anchor, predicate, value)` facts, and the kernel stores every one of
//! them in one table beside a full-text index of the same notes. Two
//! plugins that have never heard of each other join on the anchor, which
//! is the whole reason this is a kernel table and not a plugin's private
//! one. **Predicates are opaque**: the kernel never interprets one and
//! never special-cases one, exactly as it never interprets an event type.
//!
//! Three rules make it safe to lean on and all three live here:
//!
//! - **Derived and disposable.** It is a separate database, outside the
//!   fixture chain, stamped with the change feed's epoch. A database
//!   built against another vault's feed, or by a version this build does
//!   not know, is thrown away and rebuilt rather than served: an index
//!   that answers from somebody else's history is worse than no index.
//! - **Maintained from the feed, never from a scan.** Each namespace
//!   keeps its own cursor, so a process with no indexer for a namespace
//!   leaves that namespace exactly where it was, and a namespace whose
//!   version changed re-derives alone. The only full scan is a rebuild
//!   from cursor 0, which is what a refused cursor asks for.
//! - **Nothing is read that the feed did not name.** A pass reads a
//!   changed note once, whatever the number of registered namespaces,
//!   and reads nothing else.

use crate::changes::{ChangeKind, Cursor, VaultChange};
use crate::store::Store;
use crate::{ids, probe, KernelError, Result};
use rusqlite::OptionalExtension;
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;
use std::sync::Arc;

/// The index database's own schema version. It shares nothing with the
/// vault's: this database is derived, so a version it does not recognise
/// is discarded rather than migrated (constitution rule 12 governs the
/// record, and this is not the record).
pub const SCHEMA_VERSION: i64 = 13;

pub mod lookup;
pub(crate) mod positions;
mod publication;
pub mod scoped;
mod targets;
mod text_writer;
mod writer;
pub mod incremental;

/// The kernel's own namespace, holding the note text every full-text
/// query is answered from. Reserved: a plugin cannot register it, and it
/// is maintained by whichever process passes, registrations or none.
pub const TEXT_NAMESPACE: &str = "text";

/// The other kernel-owned namespace: a note's front matter, one fact per
/// top-level entry. Reserved on the same terms as [`TEXT_NAMESPACE`].
///
/// Generic property queries use these uninterpreted header values regardless
/// of installed domain plugins. Legacy body-only indexers do not receive the
/// header; source/scoped indexers receive metadata through their public input
/// contracts and can contribute their own interpretation. `gilune-id` is stored
/// exactly as `mood` is; which keys mean anything stays the reader's question.
pub const FRONT_MATTER_NAMESPACE: &str = "frontmatter";

/// A front-matter key as a predicate. Namespaced, because
/// `Constraint::Fact` names a predicate and not a namespace: without the
/// prefix a note whose front matter happened to say
/// `org.memory.tasks.due:` would be contributing to the tasks plugin's
/// predicate, which is user data walking into a plugin's vocabulary. The
/// key is lower-cased, which is what makes `[Mood]` and `[mood]` the same
/// question as they have always been.
pub fn front_matter_predicate(key: &str) -> String {
    format!("{FRONT_MATTER_NAMESPACE}.{}", key.to_lowercase())
}

/// Rows one query may answer with, whatever a caller asks for. A query
/// crosses a boundary as owned values (02-kernel-contract), so the bound
/// is here rather than in a caller's arithmetic.
pub const MAX_RESULTS: u32 = 10_000;

/// Change rows one maintenance batch takes. Bounded so that catching up
/// a long feed is many short transactions rather than one enormous one.
const BATCH: u32 = 2_000;

/// The shortest text a trigram index can answer. Below it the query is a
/// scan of the indexed lines — see `text_generator`.
const TRIGRAM_MIN: usize = 3;

/// A plugin's reading of a note. **Pure over the text**, so it can be a
/// Kotlin object today (`NoteIndexer` at the FFI boundary, the `KeyWrap`
/// precedent) and a sandboxed component later, with no vault access
/// either way.
///
/// The text is the note's **body** — front matter split off — and line
/// numbers in the facts it returns are **1-based within that body**,
/// which is what every existing search surface already means by a line
/// number. `Err` is a foreign implementation failing: the pass keeps
/// what it has, the namespace's cursor does not advance past the note,
/// and every other namespace carries on.
pub trait NoteIndexer: Send + Sync {
    fn index(&self, rel_path: &str, text: &str) -> std::result::Result<Vec<Fact>, String>;

    /// Source-aware adapters may inspect the exact supplied source. Legacy
    /// implementations retain their body-only input and body-relative facts.
    fn index_source(
        &self,
        rel_path: &str,
        _source: &str,
        body: &str,
    ) -> std::result::Result<Vec<Fact>, String> {
        self.index(rel_path, body)
    }

    /// Opt-in source locations. Legacy callbacks continue to use physical body
    /// lines; they are never silently promoted to Markdown item scope.
    fn index_scoped(
        &self,
        _rel_path: &str,
        _source: &gilune_document::TextSnapshot,
    ) -> std::result::Result<Vec<scoped::ScopedFact>, String> {
        Err("scoped input is not implemented".into())
    }

    fn uses_scoped_input(&self) -> bool {
        false
    }

    /// Optional local-input callback. Only explicitly registered local producers
    /// receive selections; all others retain the complete-document contract.
    fn index_scoped_locations(
        &self,
        _rel_path: &str,
        _source: &gilune_document::TextSnapshot,
        _locations: &[gilune_document::Location],
    ) -> std::result::Result<Vec<scoped::ScopedFact>, String> {
        Err("local scoped input is not implemented".into())
    }
}

/// One thing a plugin says about an anchor. `line` is `None` for the
/// note itself and 1-based within the note's body otherwise.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fact {
    pub line: Option<u32>,
    pub predicate: String,
    pub value: String,
}

/// What a predicate's values are, declared once when its namespace
/// registers. The kernel still never interprets the predicate — this
/// says only how to **order** its values, which is storage's question.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ValueKind {
    /// Compared as text.
    Text,
    /// Compared as a number; a value that will not parse is stored as
    /// text and excluded from ordered comparisons.
    Number,
    /// Normalised to `YYYY-MM-DD`, where lexicographic order is
    /// chronological order; a value that is not a date is stored as it
    /// came and excluded from ordered comparisons.
    Date,
}

impl ValueKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ValueKind::Text => "text",
            ValueKind::Number => "number",
            ValueKind::Date => "date",
        }
    }

    pub fn parse(text: &str) -> Option<ValueKind> {
        Some(match text {
            "text" => ValueKind::Text,
            "number" => ValueKind::Number,
            "date" => ValueKind::Date,
            _ => return None,
        })
    }
}

/// A predicate and the kind of value it carries, as a registration
/// declares it. A predicate a registration does not name is `Text`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PredicateKind {
    pub predicate: String,
    pub kind: ValueKind,
}

/// One namespace this handle can maintain. **Per handle, per process**,
/// exactly as an external processor's registration is: which code exists
/// here is a fact about this process, never about the vault.
/// The local version must still match the namespace before this handle
/// may derive facts; another process may have installed a newer indexer.
#[derive(Clone)]
pub(crate) struct Registration {
    pub namespace: String,
    version: i64,
    pub kinds: BTreeMap<String, ValueKind>,
    pub indexer: Arc<dyn NoteIndexer>,
}

/// How a fact's value is compared.
///
/// `Ne` and `NotExists` are **filters, not generators**: they narrow the
/// anchors other constraints produced and name none of their own, so a
/// query made only of them answers nothing rather than answering with
/// the vault.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConstraintOp {
    Eq,
    /// No fact with this predicate **and this value** at the anchor.
    Ne,
    /// The value contains this text, using Unicode simple case folding —
    /// what every `keyword:argument` in the shipped DSL already means.
    Contains,
    Prefix,
    Lt,
    Le,
    Gt,
    Ge,
    /// Any fact with this predicate; the value is ignored.
    Exists,
    /// No fact with this predicate at the anchor, whatever its value.
    NotExists,
}

impl ConstraintOp {
    fn generates(self) -> bool {
        !matches!(self, ConstraintOp::Ne | ConstraintOp::NotExists)
    }
}

/// Which indexed text a full-text constraint reads.
///
/// Not `TextField`, which is what it describes: that name is taken by a
/// Compose composable, and a generated Kotlin type of the same name
/// would collide in exactly the files that use both.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TextTarget {
    /// A note's body lines **and** its name — what a bare term in the
    /// shipped search language matches, and the reason the name is
    /// indexed at all.
    Any,
    /// Body lines only — `content:`.
    Body,
    /// The note's name, without its extension — `file:`.
    Name,
    /// The note's path — `path:`.
    Path,
}

/// Where a fact may satisfy a result. The legacy `Fact` variant uses
/// `Inherited`; new cross-domain contributions should choose explicitly.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FactLocality {
    /// Match the result's own location; never broadcast a root fact to lines.
    /// Document search may still ask for contained matches at any location.
    Exact,
    /// Read only document-root facts, explicitly qualifying a contained result.
    DocumentRoot,
    /// Compatibility: a root fact also satisfies every line in its document.
    Inherited,
}

/// Bound the raw input, before deduplication; never silently truncate a set.
pub const MAX_FACT_SET_VALUES: usize = 256;
pub const MAX_FACT_SET_BYTES: usize = 65_536;

/// One term of a query. A query is a conjunction of these and nothing
/// else: there is no `OR` (ADR 0020 § 2.4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Constraint {
    Fact {
        predicate: String,
        op: ConstraintOp,
        value: String,
    },
    FactAt {
        predicate: String,
        op: ConstraintOp,
        value: String,
        locality: FactLocality,
    },
    /// Existential exact membership. Negate the enclosing search clause for
    /// NOT IN, including objects with no value. An empty set matches nothing.
    FactIn {
        predicate: String,
        values: Vec<String>,
        locality: FactLocality,
    },
    Text {
        target: TextTarget,
        query: String,
    },
}

impl Constraint {
    pub(crate) fn validate(&self) -> Result<()> {
        if let Self::FactIn { values, .. } = self {
            if values.len() > MAX_FACT_SET_VALUES
                || values.iter().map(String::len).sum::<usize>() > MAX_FACT_SET_BYTES
            {
                return Err(KernelError::SearchInvalid {
                    detail: "fact membership set exceeds its bounds".into(),
                });
            }
        }
        Ok(())
    }

    pub(crate) fn canonicalize(&mut self) {
        if let Self::FactIn { values, .. } = self {
            values.sort();
            values.dedup();
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QueryScope {
    /// Lines that satisfy every constraint at the same anchor.
    Line,
    /// Notes holding such a line, plus notes whose note-level facts
    /// satisfy every constraint on their own.
    Note,
}

/// A place in the vault. `line` is `None` for the note itself.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Anchor {
    pub rel_path: String,
    pub line: Option<u32>,
}

/// A stored fact, as `facts_of` reports it — the namespace included,
/// because the point of reading them back is finding out who said what.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IndexedFact {
    pub namespace: String,
    pub rel_path: String,
    pub line: Option<u32>,
    pub predicate: String,
    pub value: String,
}

/// What one maintenance pass did.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct IndexPass {
    /// Notes re-derived, counted once per namespace.
    pub indexed: i64,
    pub removed: i64,
    pub moved: i64,
    /// A namespace whose indexer refused a note, as
    /// `<namespace>: <message>`. Its cursor stopped there; every other
    /// namespace carried on.
    pub failures: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NamespaceStatus {
    pub namespace: String,
    pub version: i64,
    pub cursor_seq: i64,
    pub facts: i64,
    pub last_error: Option<String>,
    /// Whether this process carries an indexer for it. A namespace with
    /// no implementation here is left exactly as it is.
    pub registered: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IndexStatus {
    pub schema_version: i64,
    /// The change-feed epoch this index was built against.
    pub epoch: String,
    pub db_bytes: i64,
    pub notes: i64,
    pub lines: i64,
    pub namespaces: Vec<NamespaceStatus>,
}

// ----------------------------------------------------------- the database

const SCHEMA: &str = concat!(
    "
CREATE TABLE index_meta (key TEXT PRIMARY KEY, value NOT NULL);
CREATE TABLE namespaces (
    namespace TEXT PRIMARY KEY,
    version INTEGER NOT NULL,
    scoped INTEGER NOT NULL DEFAULT 0,
    cursor_seq INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    last_error_ms INTEGER
);
CREATE TABLE predicate_kinds (
    namespace TEXT NOT NULL,
    predicate TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('text','number','date')),
    PRIMARY KEY (namespace, predicate)
);
CREATE TABLE source_maps (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rel_path TEXT NOT NULL UNIQUE,
    source_version TEXT NOT NULL,
    layout TEXT NOT NULL,
    map_version INTEGER GENERATED ALWAYS AS (json_extract(layout,'$.version')) STORED
);
CREATE TABLE fact_sources (
    namespace TEXT NOT NULL,
    rel_path TEXT NOT NULL,
    source_version TEXT NOT NULL,
    PRIMARY KEY (namespace, rel_path)
);
CREATE TABLE text_rows (
    id INTEGER PRIMARY KEY,
    rel_path TEXT NOT NULL,
    line INTEGER,
    target_id INTEGER,
    field TEXT NOT NULL CHECK (field IN ('body','name','path')),
    text TEXT NOT NULL,
    folded TEXT NOT NULL
);
CREATE INDEX lines_path ON text_rows(rel_path);
CREATE INDEX lines_path_field_line ON text_rows(rel_path,field,line);
CREATE INDEX text_rows_target ON text_rows(target_id);
CREATE VIEW lines AS SELECT l.id,l.rel_path,
 CASE WHEN l.field='body' THEN COALESCE(t.anchor+b.base_key,l.line) ELSE l.line END AS line,
 l.field,l.text,l.folded FROM text_rows l LEFT JOIN target_slots t ON t.id=l.target_id
 LEFT JOIN position_blocks b ON b.id=t.block_id;
CREATE TRIGGER lines_insert INSTEAD OF INSERT ON lines BEGIN
 INSERT INTO text_rows(id,rel_path,line,field,text,folded) VALUES(NEW.id,NEW.rel_path,NEW.line,NEW.field,NEW.text,NEW.folded);
END;
CREATE TRIGGER lines_delete INSTEAD OF DELETE ON lines BEGIN
 DELETE FROM text_rows WHERE id=OLD.id;
END;
CREATE TRIGGER lines_update INSTEAD OF UPDATE ON lines BEGIN
 UPDATE text_rows SET rel_path=NEW.rel_path,line=NEW.line,target_id=CASE WHEN NEW.line IS OLD.line AND NEW.field=OLD.field THEN target_id END,field=NEW.field,text=NEW.text,folded=NEW.folded WHERE id=OLD.id;
END;
CREATE VIRTUAL TABLE lines_fts USING fts5(
    folded, content='text_rows', content_rowid='id', tokenize='trigram case_sensitive 1'
);
CREATE TRIGGER lines_ai AFTER INSERT ON text_rows BEGIN
    INSERT INTO lines_fts(rowid, folded) VALUES (new.id, new.folded);
END;
CREATE TRIGGER lines_ad AFTER DELETE ON text_rows BEGIN
    INSERT INTO lines_fts(lines_fts, rowid, folded) VALUES ('delete', old.id, old.folded);
END;
CREATE TRIGGER namespaces_ai AFTER INSERT ON namespaces BEGIN
    UPDATE index_meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'generation';
END;
CREATE TRIGGER namespaces_au AFTER UPDATE ON namespaces BEGIN
    UPDATE index_meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'generation';
END;
CREATE TRIGGER namespaces_ad AFTER DELETE ON namespaces BEGIN
    UPDATE index_meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'generation';
END;
",
    include_str!("index/targets.sql"),
    include_str!("index/storage.sql")
);

const DROP_ALL: &str = "
DROP VIEW IF EXISTS source_target_layout;
DROP TRIGGER IF EXISTS source_targets_insert;
DROP TRIGGER IF EXISTS source_targets_before_update;
DROP TRIGGER IF EXISTS source_targets_update;
DROP TRIGGER IF EXISTS source_targets_delete;
DROP TRIGGER IF EXISTS fact_ids_insert;
DROP TRIGGER IF EXISTS fact_ids_update;
DROP VIEW IF EXISTS initial_target_layout;
DROP TABLE IF EXISTS target_slots;
DROP TABLE IF EXISTS position_blocks;
DROP TABLE IF EXISTS prepared_geometry;
DROP TABLE IF EXISTS index_predicates;
DROP TRIGGER IF EXISTS lines_ai;
DROP TRIGGER IF EXISTS lines_ad;
DROP TABLE IF EXISTS lines_fts;
DROP TABLE IF EXISTS text_rows;
DROP TABLE IF EXISTS fact_values;
DROP TABLE IF EXISTS fact_location_slots;
DROP TABLE IF EXISTS fact_frames;
DROP TABLE IF EXISTS source_maps;
DROP TABLE IF EXISTS fact_sources;
DROP TABLE IF EXISTS predicate_kinds;
DROP TABLE IF EXISTS namespaces;
DROP TABLE IF EXISTS index_meta;
";

/// Open the index database for a vault, rebuilding it from nothing when
/// it belongs to another vault's feed, another schema, or nothing at all.
///
/// There is no migration path and there is deliberately not going to be
/// one: the whole database is re-derivable from the feed, so the cost of
/// discarding it is a rebuild and the cost of guessing at it wrong is
/// answers from somebody else's vault.
pub(crate) fn open(local_dir: &Path, epoch: &str) -> Result<rusqlite::Connection> {
    let path = local_dir.join("index.db");
    if let Ok(conn) = connect(&path) {
        match usable(&conn, epoch) {
            Ok(true) => return Ok(conn),
            Ok(false) => {
                create(&conn, epoch)?;
                return Ok(conn);
            }
            Err(_) => drop(conn),
        }
    }
    // Not a database this build can read a single row out of — a file
    // that never was one, or one a later version wrote. It is derived,
    // so the answer is to throw it away rather than to report damage
    // nobody could act on.
    for stale in ["index.db", "index.db-wal", "index.db-shm"] {
        let _ = std::fs::remove_file(local_dir.join(stale));
    }
    let conn = connect(&path)?;
    create(&conn, epoch)?;
    Ok(conn)
}

fn connect(path: &Path) -> Result<rusqlite::Connection> {
    let conn = rusqlite::Connection::open(path)?;
    crate::search_text::register(&conn)?;
    conn.pragma_update(None, "journal_mode", "wal")?;
    // Several processes share one vault by design, and each maintains
    // this index. Longer than the kernel database's five seconds because
    // a maintenance transaction can carry a whole batch of notes.
    conn.busy_timeout(std::time::Duration::from_secs(10))?;
    Ok(conn)
}

fn usable(conn: &rusqlite::Connection, epoch: &str) -> Result<bool> {
    let present: i64 = conn.query_row(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'index_meta'",
        [],
        |row| row.get(0),
    )?;
    if present == 0 {
        return Ok(false);
    }
    Ok(
        meta(conn, "index_schema_version")? == Some(SCHEMA_VERSION.to_string())
            && meta(conn, "feed_epoch")?.as_deref() == Some(epoch),
    )
}

fn meta(conn: &rusqlite::Connection, key: &str) -> Result<Option<String>> {
    Ok(conn
        .query_row(
            "SELECT value FROM index_meta WHERE key = ?1",
            [key],
            |row| row.get(0),
        )
        .map(Some)
        .or_else(no_rows_none)?)
}

fn create(conn: &rusqlite::Connection, epoch: &str) -> Result<()> {
    conn.execute_batch("BEGIN IMMEDIATE;")?;
    let done: Result<()> = (|| {
        let facts_kind: Option<String> = conn
            .query_row(
                "SELECT type FROM sqlite_master WHERE name='facts'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(kind) = facts_kind {
            conn.execute_batch(if kind == "view" {
                "DROP VIEW facts;"
            } else {
                "DROP TABLE facts;"
            })?;
        }
        let locations_kind: Option<String> = conn
            .query_row(
                "SELECT type FROM sqlite_master WHERE name='fact_locations'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(kind) = locations_kind {
            conn.execute_batch(if kind == "view" {
                "DROP VIEW fact_locations;"
            } else {
                "DROP TABLE fact_locations;"
            })?;
        }
        for name in ["index_targets", "lines"] {
            let kind: Option<String> = conn
                .query_row(
                    "SELECT type FROM sqlite_master WHERE name=?1",
                    [name],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(kind) = kind {
                conn.execute_batch(&format!("DROP {kind} {name};"))?;
            }
        }
        conn.execute_batch(DROP_ALL)?;
        conn.execute_batch(SCHEMA)?;
        // Text, and read back as text: `index_meta.value` has no
        // declared type, so a number written here comes back a number
        // and every comparison against it silently fails.
        conn.execute(
            "INSERT INTO index_meta (key, value) VALUES ('index_schema_version', ?1)",
            [SCHEMA_VERSION.to_string()],
        )?;
        conn.execute(
            "INSERT INTO index_meta (key, value) VALUES ('feed_epoch', ?1)",
            [epoch],
        )?;
        conn.execute(
            "INSERT INTO index_meta (key, value) VALUES ('instance', ?1)",
            [ids::new_id()],
        )?;
        conn.execute(
            "INSERT INTO index_meta (key, value) VALUES ('generation', 0)",
            [],
        )?;
        Ok(())
    })();
    match done {
        Ok(()) => conn.execute_batch("COMMIT;")?,
        Err(e) => {
            let _ = conn.execute_batch("ROLLBACK;");
            return Err(e);
        }
    }
    Ok(())
}

fn apply_plan(
    db: &rusqlite::Connection,
    namespace: &str,
    plan: &Plan,
    pass: &mut IndexPass,
    reused: &BTreeSet<String>,
) -> Result<()> {
    for (_, step) in &plan.steps {
        match step {
            Step::Forget { rel_path } => {
                forget_path(db, namespace, rel_path)?;
                pass.removed += 1;
            }
            Step::Put { rel_path, rows } => {
                if let Rows::Lines(lines, source) = rows {
                    text_writer::replace(db, rel_path, lines, source.as_ref())?;
                } else if let Rows::Facts(facts, source_version) = rows {
                    if reused.contains(rel_path) {
                        writer::replace_facts_mode(
                            db,
                            namespace,
                            rel_path,
                            facts,
                            source_version.as_deref(),
                            true,
                        )?;
                    } else {
                        writer::replace_facts(
                            db,
                            namespace,
                            rel_path,
                            facts,
                            source_version.as_deref(),
                        )?;
                    }
                }
                pass.indexed += 1;
            }
            Step::Rekey { from, to } => {
                forget_path(db, namespace, to)?;
                // Nothing moved means another process moved
                // it first, which is the rows already being
                // where this one was about to put them.
                db.execute(
                    "UPDATE facts SET rel_path = ?3
                                  WHERE namespace = ?1 AND rel_path = ?2",
                    rusqlite::params![namespace, from, to],
                )?;
                db.execute(
                    "UPDATE fact_sources SET rel_path=?3 WHERE namespace=?1 AND rel_path=?2",
                    rusqlite::params![namespace, from, to],
                )?;
                pass.moved += 1;
            }
        }
    }
    for (_, step) in &plan.steps {
        let paths: Vec<&str> = match step {
            Step::Forget { rel_path } | Step::Put { rel_path, .. } => vec![rel_path],
            Step::Rekey { from, to } => vec![from, to],
        };
        for path in paths {
            db.execute("DELETE FROM fact_locations WHERE rel_path=?1 AND NOT EXISTS (SELECT 1 FROM fact_values WHERE location_id=fact_locations.id)", [path])?;
            db.execute("DELETE FROM fact_frames WHERE rel_path=?1 AND NOT EXISTS (SELECT 1 FROM fact_location_slots WHERE frame_id=fact_frames.id)", [path])?;
        }
    }
    set_cursor(db, namespace, plan.cursor.max(plan.expected_cursor))?;
    match &plan.failure {
        Some(message) => db.execute(
            "UPDATE namespaces SET last_error = ?2, last_error_ms = ?3
                          WHERE namespace = ?1",
            rusqlite::params![namespace, message, ids::now_ms()],
        )?,
        None => db.execute(
            "UPDATE namespaces SET last_error = NULL, last_error_ms = NULL
                          WHERE namespace = ?1 AND last_error IS NOT NULL",
            [namespace],
        )?,
    };
    Ok(())
}

// -------------------------------------------------------- maintenance plan

/// What the index must be told about one path. Built **before** anything
/// is locked, so a maintenance transaction holds no file handle and runs
/// no foreign code.
enum Step {
    /// Everything this namespace holds about the path goes.
    Forget { rel_path: String },
    /// Replaces everything this namespace holds about the path.
    Put { rel_path: String, rows: Rows },
    /// The same note at a different path, and no re-derivation: a move
    /// changes where a note is, not what it says.
    Rekey { from: String, to: String },
}

enum Rows {
    Lines(Vec<LineRow>, Option<(String, String)>),
    Facts(Vec<FactRow>, Option<String>),
}

struct LineRow {
    line: Option<i64>,
    field: &'static str,
    text: String,
}

struct FactRow {
    line: Option<i64>,
    item_key: Option<i64>,
    source_version: Option<String>,
    predicate: String,
    value: String,
    num: Option<f64>,
    typed: bool,
}

/// One batch, planned: what to write, how far that takes the cursor, and
/// the refusal that stopped it if one did.
struct Plan {
    expected_cursor: i64,
    expected_version: i64,
    steps: Vec<(i64, Step)>,
    cursor: i64,
    failure: Option<String>,
    stopped: bool,
}

/// What one namespace's rows are derived from.
///
/// Two of the three are the kernel's own and ask nobody: the full-text
/// rows, and the front-matter facts no indexer could contribute because
/// no indexer is shown a note's header.
#[derive(Clone, Copy)]
enum Source<'a> {
    Text,
    FrontMatter,
    Plugin(&'a Registration),
}

/// A note as one pass reads it: the prose an indexer sees and the header
/// it does not.
struct NoteText {
    source: String,
    body: String,
    front_matter: Vec<(String, String)>,
    snapshot: std::sync::OnceLock<std::result::Result<gilune_document::TextSnapshot, String>>,
}

impl NoteText {
    fn snapshot(&self) -> std::result::Result<&gilune_document::TextSnapshot, String> {
        self.snapshot
            .get_or_init(|| {
                let _profile = crate::update_profile::span("source_snapshot_parse", "");
                gilune_document::TextSnapshot::new(self.source.clone()).map_err(|e| e.to_string())
            })
            .as_ref()
            .map_err(Clone::clone)
    }
}

/// Notes read during one pass, so a note changed under several
/// namespaces is read once (ADR 0020 § 2.3: the indexers run in the pass
/// that recorded the change, over that note only).
#[derive(Default)]
struct Notes(BTreeMap<String, std::result::Result<Option<NoteText>, String>>);

impl Notes {
    fn of(
        &mut self,
        store: &Store,
        rel_path: &str,
    ) -> std::result::Result<Option<&NoteText>, String> {
        if !self.0.contains_key(rel_path) {
            // Only notes have text to index. An attachment is a changed
            // path in the feed too (ADR 0007, 2026-10-22), and reading an
            // image as prose would stop this namespace's cursor for ever.
            if !rel_path.ends_with(".md") {
                self.0.insert(rel_path.to_string(), Ok(None));
            }
        }
        if !self.0.contains_key(rel_path) {
            let _profile = crate::update_profile::span("read_and_split_source", "");
            probe::file_read();
            let read = match std::fs::read_to_string(store.vault().join(rel_path)) {
                Ok(text) => Ok(Some(NoteText {
                    body: body_of(&text),
                    front_matter: front_matter_of(&text),
                    source: text,
                    snapshot: Default::default(),
                })),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
                Err(error) => Err(format!("cannot index {rel_path}: {error}")),
            };
            self.0.insert(rel_path.to_string(), read);
        }
        match self.0.get(rel_path) {
            Some(Ok(note)) => Ok(note.as_ref()),
            Some(Err(error)) => Err(error.clone()),
            None => Ok(None),
        }
    }
}

/// A note's prose, front matter split off — the text an indexer sees and
/// the text the full-text index holds.
///
/// The same split the shells make (`splitNote`), because a line number
/// has to mean one thing on both sides of the boundary: line 1 is the
/// first line of prose, never the `---` above it.
pub(crate) fn body_of(text: &str) -> String {
    let lines: Vec<&str> = text
        .split('\n')
        .map(|line| line.strip_suffix('\r').unwrap_or(line))
        .collect();
    if lines
        .first()
        .map(|line| line.trim_start_matches('\u{feff}').trim())
        != Some("---")
    {
        return lines.join("\n");
    }
    let Some(close) = lines.iter().skip(1).position(|line| line.trim() == "---") else {
        return lines.join("\n");
    };
    lines[close + 2..].join("\n")
}

/// A note's front matter as `(key, value)` pairs, in the order written.
///
/// The block is the one [`body_of`] splits off, so a note's header and
/// its prose are decided once. What is read out of it is deliberately
/// small, because the kernel is storing somebody else's vocabulary and
/// not interpreting it:
///
/// - a **top-level** `key: value` is one pair;
/// - a top-level key with nothing after the colon followed by indented
///   `- item` lines is one pair **per item**, which is how a YAML list of
///   tags asks to be searched;
/// - anything else indented is a nested mapping and is **ignored** — a
///   fact whose anchor is the note cannot express a path into a tree, and
///   flattening one would invent a key nobody wrote.
///
/// A line with no colon, or whose colon is first, contributes nothing:
/// there is no key to name it by.
pub(crate) fn front_matter_of(text: &str) -> Vec<(String, String)> {
    let lines: Vec<&str> = text
        .split('\n')
        .map(|line| line.strip_suffix('\r').unwrap_or(line))
        .collect();
    if lines.first().map(|line| line.trim()) != Some("---") {
        return Vec::new();
    }
    let Some(close) = lines.iter().skip(1).position(|line| line.trim() == "---") else {
        return Vec::new();
    };
    let mut pairs = Vec::new();
    let mut list_key: Option<String> = None;
    let mut empty_key: Option<String> = None;
    for line in &lines[1..close + 1] {
        let indented = line.starts_with(' ') || line.starts_with('\t');
        if indented {
            empty_key = None;
            let trimmed = line.trim();
            let Some(key) = list_key.as_ref() else {
                continue;
            };
            let Some(item) = trimmed.strip_prefix('-') else {
                continue;
            };
            let item = item.trim();
            if !item.is_empty() {
                pairs.push((key.clone(), item.to_string()));
            }
            continue;
        }
        if let Some(key) = empty_key.take() {
            pairs.push((key, String::new()));
        }
        list_key = None;
        let Some((key, value)) = line.split_once(':') else {
            continue;
        };
        let key = key.trim();
        if key.is_empty() {
            continue;
        }
        let value = value.trim();
        if value.is_empty() {
            list_key = Some(key.to_string());
            empty_key = Some(key.to_string());
        } else {
            pairs.push((key.to_string(), value.to_string()));
        }
    }
    if let Some(key) = empty_key {
        pairs.push((key, String::new()));
    }
    pairs
}

/// The note's name as a person sees it: the file's stem.
fn name_of(rel_path: &str) -> &str {
    let file = rel_path.rsplit('/').next().unwrap_or(rel_path);
    file.strip_suffix(".md").unwrap_or(file)
}

// ------------------------------------------------------------- the store

impl Store {
    /// Run something against the index database, opening it on first
    /// use. A process that never indexes and never queries never creates
    /// the file.
    pub(crate) fn with_index<T>(
        &self,
        act: impl FnOnce(&rusqlite::Connection) -> Result<T>,
    ) -> Result<T> {
        if self.index.borrow().is_none() {
            let conn = open(&self.local_dir(), &self.change_epoch()?)?;
            *self.index.borrow_mut() = Some(conn);
        }
        let held = self.index.borrow();
        match held.as_ref() {
            Some(conn) => act(conn),
            None => Err(KernelError::Corrupt {
                detail: "the index database was closed while it was being used".to_string(),
            }),
        }
    }

    /// Declare that **this handle** carries an indexer for `namespace`.
    ///
    /// Per handle and per session, exactly as an external processor's
    /// registration is (ADR 0018 § 2): a process with no indexer for a
    /// namespace never touches that namespace's facts, so the CLI
    /// running a projection cannot quietly empty the app's index.
    ///
    /// `version` is the indexer's own. Changing it wipes this
    /// namespace's facts and re-derives them from the beginning of the
    /// feed — that namespace alone; nothing else is disturbed. The
    /// predicate kinds are the declaration of how values are ordered and
    /// are rewritten on every registration, so a plugin that changes a
    /// predicate's kind without changing its version leaves facts typed
    /// as they were: the version is what asks for a re-derivation.
    ///
    /// The kernel's own `text` namespace cannot be registered.
    pub fn register_indexer(
        &self,
        namespace: &str,
        version: i64,
        kinds: &[PredicateKind],
        indexer: Arc<dyn NoteIndexer>,
    ) -> Result<()> {
        if namespace.trim().is_empty()
            || namespace == TEXT_NAMESPACE
            || namespace == FRONT_MATTER_NAMESPACE
        {
            return Err(KernelError::VaultUnusable {
                reason: format!("{namespace} is not a namespace a plugin may index into"),
            });
        }
        self.with_index(|db| {
            let held: Option<(i64, bool)> = db
                .query_row(
                    "SELECT version,scoped FROM namespaces WHERE namespace = ?1",
                    [namespace],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .map(Some)
                .or_else(no_rows_none)?;
            let scoped = indexer.uses_scoped_input();
            if held.is_some_and(|(v, s)| v == version && s != scoped) {
                return Err(KernelError::SearchInvalid {
                    detail: "Changing indexer location mode requires a version bump".into(),
                });
            }
            if held != Some((version, scoped)) {
                db.execute_batch("BEGIN IMMEDIATE;")?;
                let done: Result<()> = (|| {
                    forget_namespace(db, namespace)?;
                    db.execute(
                        "INSERT OR REPLACE INTO namespaces (namespace, version, cursor_seq, scoped)
                         VALUES (?1, ?2, 0, ?3)",
                        rusqlite::params![namespace, version, scoped],
                    )?;
                    Ok(())
                })();
                match done {
                    Ok(()) => db.execute_batch("COMMIT;")?,
                    Err(e) => {
                        let _ = db.execute_batch("ROLLBACK;");
                        return Err(e);
                    }
                }
            }
            db.execute(
                "DELETE FROM predicate_kinds WHERE namespace = ?1",
                [namespace],
            )?;
            for kind in kinds {
                db.execute(
                    "INSERT OR REPLACE INTO predicate_kinds (namespace, predicate, kind)
                     VALUES (?1, ?2, ?3)",
                    rusqlite::params![namespace, kind.predicate, kind.kind.as_str()],
                )?;
            }
            Ok(())
        })?;
        let mut registered = self.indexers.borrow_mut();
        registered.retain(|r| r.namespace != namespace);
        registered.push(Registration {
            namespace: namespace.to_string(),
            version,
            kinds: kinds
                .iter()
                .map(|k| (k.predicate.clone(), k.kind))
                .collect(),
            indexer,
        });
        Ok(())
    }

    /// Bring every namespace this process maintains up to the feed.
    ///
    /// Called at the end of the pass that recorded the change —
    /// `project`, `reconcile`, `reconcile_path` — and exposed so a shell
    /// can ask for it directly. Idempotent: a settled vault reads the
    /// cursor, finds nothing waiting, and stops.
    pub fn advance_index(&self) -> Result<IndexPass> {
        let _profile = crate::update_profile::span("advance_index", "");
        let epoch = self.change_epoch()?;
        let mut pass = IndexPass::default();
        let mut notes = Notes::default();
        // One feed boundary per pass keeps cached note bytes from being reused
        // for changes that arrive after those bytes were read.
        let tip: i64 = self.conn().query_row(
            "SELECT COALESCE(MAX(seq),0) FROM vault_changes",
            [],
            |row| row.get(0),
        )?;
        let registered: Vec<Registration> = self.indexers.borrow().clone();
        if self.publish_ready_page(&registered, &epoch, tip, &mut notes, &mut pass)? {
            return Ok(pass);
        }
        self.advance_namespace(
            TEXT_NAMESPACE,
            Source::Text,
            &epoch,
            tip,
            &mut notes,
            &mut pass,
        )?;
        self.advance_namespace(
            FRONT_MATTER_NAMESPACE,
            Source::FrontMatter,
            &epoch,
            tip,
            &mut notes,
            &mut pass,
        )?;
        for registration in &registered {
            self.advance_namespace(
                &registration.namespace,
                Source::Plugin(registration),
                &epoch,
                tip,
                &mut notes,
                &mut pass,
            )?;
        }
        Ok(pass)
    }

    /// Advance the index, and say nothing if it could not be advanced.
    ///
    /// The index is derived: a projection that failed to update it has
    /// still projected, and failing the pass would make a disposable
    /// database able to stop the vault being written. An indexer's
    /// refusal is recorded against its namespace and is readable from
    /// `index_status`; a database that will not open at all leaves
    /// nowhere to record anything, and the next pass tries again.
    pub(crate) fn maintain_index(&self) {
        let _ = self.advance_index();
    }

    /// Throw a namespace away and derive it again from the beginning of
    /// the feed — `None` for all of them, the kernel's text index
    /// included. The sanctioned full scan, and the only one.
    pub fn rebuild_index(&self, namespace: Option<&str>) -> Result<IndexPass> {
        self.with_index(|db| {
            db.execute_batch("BEGIN IMMEDIATE;")?;
            let done: Result<()> = (|| {
                match namespace {
                    Some(namespace) => {
                        forget_namespace(db, namespace)?;
                        db.execute(
                            "UPDATE namespaces SET cursor_seq = 0, last_error = NULL,
                                    last_error_ms = NULL
                              WHERE namespace = ?1",
                            [namespace],
                        )?;
                    }
                    None => {
                        db.execute("DELETE FROM fact_values", [])?;
                        db.execute("DELETE FROM fact_location_slots", [])?;
                        db.execute("DELETE FROM fact_frames", [])?;
                        db.execute("DELETE FROM index_predicates", [])?;
                        db.execute("DELETE FROM fact_sources", [])?;
                        db.execute("DELETE FROM source_maps", [])?;
                        db.execute("DELETE FROM lines", [])?;
                        db.execute(
                            "UPDATE namespaces SET cursor_seq = 0, last_error = NULL,
                                    last_error_ms = NULL",
                            [],
                        )?;
                    }
                }
                Ok(())
            })();
            match done {
                Ok(()) => db.execute_batch("COMMIT;")?,
                Err(e) => {
                    let _ = db.execute_batch("ROLLBACK;");
                    return Err(e);
                }
            }
            Ok(())
        })?;
        self.advance_index()
    }

    /// Drop every namespace a plugin owns (ADR 0011's removal rule): the
    /// namespace named by the plugin's id, and any namespace under it.
    /// The notes are untouched — removing a plugin removes what it said
    /// about the vault, never the vault.
    pub(crate) fn forget_plugin_namespaces(&self, plugin_id: &str) -> Result<()> {
        let under = format!("{plugin_id}.");
        self.with_index(|db| {
            let owned: Vec<String> = {
                let mut stmt = db.prepare(
                    "SELECT namespace FROM namespaces
                      WHERE namespace = ?1 OR (namespace > ?2 AND namespace < ?3)",
                )?;
                // A half-open range on the prefix rather than LIKE: it
                // uses the primary key, and `org.memory.tasksextra` is
                // not a namespace of `org.memory.tasks`.
                let rows = stmt.query_map(
                    rusqlite::params![plugin_id, under.as_str(), prefix_end(&under)],
                    |row| row.get(0),
                )?;
                rows.collect::<std::result::Result<_, _>>()?
            };
            db.execute_batch("BEGIN IMMEDIATE;")?;
            let done: Result<()> = (|| {
                for namespace in &owned {
                    forget_namespace(db, namespace)?;
                    db.execute("DELETE FROM namespaces WHERE namespace = ?1", [namespace])?;
                    db.execute(
                        "DELETE FROM predicate_kinds WHERE namespace = ?1",
                        [namespace],
                    )?;
                }
                Ok(())
            })();
            match done {
                Ok(()) => db.execute_batch("COMMIT;")?,
                Err(e) => {
                    let _ = db.execute_batch("ROLLBACK;");
                    return Err(e);
                }
            }
            Ok(())
        })?;
        self.indexers
            .borrow_mut()
            .retain(|r| r.namespace != plugin_id && !r.namespace.starts_with(&under));
        Ok(())
    }

    /// Everything the index holds about one file, for a person reading
    /// it on the command line.
    pub fn facts_of(&self, rel_path: &str) -> Result<Vec<IndexedFact>> {
        self.with_index(|db| {
            let mut stmt = db.prepare(
                "SELECT namespace, rel_path, line, predicate, value FROM facts
                  WHERE rel_path = ?1 AND item_key IS NULL
                  ORDER BY namespace, line IS NOT NULL, line, predicate, value",
            )?;
            let rows = stmt.query_map([rel_path], |row| {
                Ok(IndexedFact {
                    namespace: row.get(0)?,
                    rel_path: row.get(1)?,
                    line: row.get::<_, Option<i64>>(2)?.map(|n| n.max(0) as u32),
                    predicate: row.get(3)?,
                    value: row.get(4)?,
                })
            })?;
            Ok(rows.collect::<std::result::Result<_, _>>()?)
        })
    }

    pub fn index_status(&self) -> Result<IndexStatus> {
        let registered: BTreeSet<String> = self
            .indexers
            .borrow()
            .iter()
            .map(|r| r.namespace.clone())
            .collect();
        let epoch = self.change_epoch()?;
        let db_bytes = std::fs::metadata(self.local_dir().join("index.db"))
            .map(|m| m.len() as i64)
            .unwrap_or(0);
        self.with_index(|db| {
            let namespaces: Vec<NamespaceStatus> = {
                let mut stmt = db.prepare(
                    "SELECT n.namespace, n.version, n.cursor_seq, n.last_error,
                            (SELECT COUNT(*) FROM facts f WHERE f.namespace = n.namespace)
                       FROM namespaces n ORDER BY n.namespace",
                )?;
                let rows = stmt.query_map([], |row| {
                    let namespace: String = row.get(0)?;
                    Ok(NamespaceStatus {
                        registered: namespace == TEXT_NAMESPACE
                            || namespace == FRONT_MATTER_NAMESPACE
                            || registered.contains(&namespace),
                        namespace,
                        version: row.get(1)?,
                        cursor_seq: row.get(2)?,
                        last_error: row.get(3)?,
                        facts: row.get(4)?,
                    })
                })?;
                rows.collect::<std::result::Result<_, _>>()?
            };
            let lines: i64 = db.query_row(
                "SELECT COUNT(*) FROM lines WHERE field = 'body'",
                [],
                |row| row.get(0),
            )?;
            let notes: i64 = db.query_row(
                "SELECT COUNT(*) FROM lines WHERE field = 'path'",
                [],
                |row| row.get(0),
            )?;
            Ok(IndexStatus {
                schema_version: SCHEMA_VERSION,
                epoch: epoch.clone(),
                db_bytes,
                notes,
                lines,
                namespaces,
            })
        })
    }

    // ------------------------------------------------------ maintenance

    fn advance_namespace(
        &self,
        namespace: &str,
        source: Source<'_>,
        epoch: &str,
        tip: i64,
        notes: &mut Notes,
        pass: &mut IndexPass,
    ) -> Result<()> {
        let mut rebuilt = false;
        loop {
            let (seq, version) = self.with_index(|db| {
                let seq = cursor_of(db, namespace)?;
                let version = db.query_row(
                    "SELECT version FROM namespaces WHERE namespace = ?1",
                    [namespace],
                    |row| row.get(0),
                )?;
                Ok((seq, version))
            })?;
            if let Source::Plugin(registration) = source {
                if registration.version != version {
                    pass.failures.push(format!(
                        "{namespace}: this handle's indexer version is no longer current"
                    ));
                    return Ok(());
                }
            }
            if seq == tip {
                return Ok(());
            }
            let mut page = match self.changes_since(
                &Cursor {
                    epoch: epoch.to_string(),
                    seq,
                },
                BATCH,
            ) {
                Ok(page) => page,
                // A cursor this vault will not answer from is a cursor
                // to abandon: wipe this namespace and derive it again
                // from the beginning. Once — a second refusal is a fault
                // in the feed, not a stale reader.
                Err(KernelError::CursorUnknown { .. }) if !rebuilt => {
                    rebuilt = true;
                    self.with_index(|db| {
                        forget_namespace(db, namespace)?;
                        set_cursor(db, namespace, 0)
                    })?;
                    continue;
                }
                Err(e) => return Err(e),
            };
            page.changes.retain(|change| change.seq <= tip);
            if page.changes.is_empty() {
                return Ok(());
            }
            let _profile = crate::update_profile::span("namespace_batch", namespace);
            let mut plan = self.plan(namespace, source, &page.changes, notes)?;
            plan.expected_cursor = seq;
            plan.expected_version = version;
            let stopped = plan.stopped;
            if let Some(message) = &plan.failure {
                pass.failures.push(format!("{namespace}: {message}"));
            }
            if !self.commit_plan(namespace, plan, pass)? {
                notes.0.clear();
                continue;
            }
            if stopped || page.complete {
                return Ok(());
            }
        }
    }

    /// Work out what the batch means without holding a lock: read the
    /// notes it names, run the indexers over them, and stop at the first
    /// note an indexer refuses.
    fn plan(
        &self,
        namespace: &str,
        source: Source<'_>,
        changes: &[VaultChange],
        notes: &mut Notes,
    ) -> Result<Plan> {
        let _profile = crate::update_profile::span("plan", namespace);
        let mut steps: Vec<(i64, Step)> = Vec::new();
        let mut current: BTreeSet<String> = BTreeSet::new();
        let mut cursor = 0;
        let mut failure = None;
        let mut stopped = false;

        for change in changes {
            match change.kind {
                ChangeKind::Created | ChangeKind::Modified | ChangeKind::Imported => {
                    // The note is re-derived from what is on disk **now**,
                    // so the second and later changes to one path in a
                    // batch would derive the same bytes again. One
                    // indexer call per changed note per pass is the whole
                    // cost model (ADR 0020 § 8.5).
                    if current.contains(&change.rel_path) {
                        cursor = change.seq;
                        continue;
                    }
                    match self.derive(source, &change.rel_path, notes) {
                        Ok(step) => steps.push((change.seq, step)),
                        Err(message) => {
                            failure = Some(message);
                            stopped = true;
                            // Everything before this note is written and
                            // the cursor stops just short of it, so the
                            // next pass meets the same note again.
                            cursor = change.seq - 1;
                            break;
                        }
                    }
                    current.insert(change.rel_path.clone());
                }
                ChangeKind::Removed | ChangeKind::Quarantined => {
                    steps.push((
                        change.seq,
                        Step::Forget {
                            rel_path: change.rel_path.clone(),
                        },
                    ));
                    current.remove(&change.rel_path);
                }
                ChangeKind::Moved => {
                    let from = change.previous_rel_path.clone().unwrap_or_default();
                    match self.plan_move(namespace, source, &from, change, notes)? {
                        Ok(moved) => {
                            // Only a step that actually re-derived makes
                            // the path current. Rows carried across by a
                            // re-key are as old as the pass that wrote
                            // them, so an edit later in the same batch
                            // must still be read.
                            if moved.iter().any(|step| matches!(step, Step::Put { .. })) {
                                current.insert(change.rel_path.clone());
                            }
                            for step in moved {
                                steps.push((change.seq, step));
                            }
                        }
                        Err(message) => {
                            failure = Some(message);
                            stopped = true;
                            cursor = change.seq - 1;
                            break;
                        }
                    }
                }
            }
            if !stopped {
                cursor = change.seq;
            }
        }
        Ok(Plan {
            expected_cursor: 0,
            expected_version: 0,
            steps,
            cursor,
            failure,
            stopped,
        })
    }

    /// A move re-keys rather than re-derives: the note says the same
    /// thing at its new path, so no indexer is asked about it again.
    ///
    /// Two things make that safe. The kernel's own text index holds the
    /// note's name and path **as text**, so for it a move really is a
    /// fresh derivation and no indexer is involved either way. And a
    /// namespace with nothing recorded at the old path has nothing to
    /// re-key — a rebuild replaying `Created(a)` then `Moved(a→b)` finds
    /// no file at `a` — so that case derives at the new path, which is
    /// what makes an incremental index and a rebuilt one agree.
    #[allow(clippy::type_complexity)]
    fn plan_move(
        &self,
        namespace: &str,
        source: Source<'_>,
        from: &str,
        change: &VaultChange,
        notes: &mut Notes,
    ) -> Result<std::result::Result<Vec<Step>, String>> {
        let held: i64 = if namespace == TEXT_NAMESPACE {
            0
        } else {
            self.with_index(|db| {
                Ok(db.query_row(
                    "SELECT COUNT(*) FROM facts WHERE namespace = ?1 AND rel_path = ?2",
                    rusqlite::params![namespace, from],
                    |row| row.get(0),
                )?)
            })?
        };
        if held > 0 {
            return Ok(Ok(vec![Step::Rekey {
                from: from.to_string(),
                to: change.rel_path.clone(),
            }]));
        }
        Ok(self.derive(source, &change.rel_path, notes).map(|step| {
            vec![
                Step::Forget {
                    rel_path: from.to_string(),
                },
                step,
            ]
        }))
    }

    /// One note's rows for one namespace, or the indexer's refusal. The
    /// two kernel-owned sources ask nobody and cannot refuse.
    fn derive(
        &self,
        source: Source<'_>,
        rel_path: &str,
        notes: &mut Notes,
    ) -> std::result::Result<Step, String> {
        let Some(note) = notes.of(self, rel_path)? else {
            // A file the feed named and the vault does not hold: the
            // honest reading of the index is that it is not there.
            return Ok(Step::Forget {
                rel_path: rel_path.to_string(),
            });
        };
        let rows = match source {
            Source::Text => {
                let _profile = crate::update_profile::span("derive_text", TEXT_NAMESPACE);
                Rows::Lines(
                    text_rows(rel_path, &note.body),
                    note.snapshot()
                        .ok()
                        .map(|s| (s.version().into(), scoped::encode_layout(s.layout()))),
                )
            }
            Source::FrontMatter => Rows::Facts(
                note.front_matter
                    .iter()
                    .map(|(key, value)| FactRow {
                        line: None,
                        item_key: None,
                        source_version: note.snapshot().ok().map(|s| s.version().into()),
                        predicate: front_matter_predicate(key),
                        value: value.clone(),
                        num: None,
                        typed: true,
                    })
                    .collect(),
                note.snapshot().ok().map(|s| s.version().into()),
            ),
            Source::Plugin(registration) => {
                if registration.indexer.uses_scoped_input() {
                    let snapshot = note.snapshot()?;
                    let facts = {
                        let _profile = crate::update_profile::span(
                            "plugin_call_including_adapter",
                            &registration.namespace,
                        );
                        registration.indexer.index_scoped(rel_path, snapshot)?
                    };
                    let _profile = crate::update_profile::span(
                        "validate_scoped_facts",
                        &registration.namespace,
                    );
                    return Ok(Step::Put {
                        rel_path: rel_path.into(),
                        rows: Rows::Facts(
                            facts
                                .into_iter()
                                .map(|fact| {
                                    scoped::typed_scoped_row(&registration.kinds, snapshot, fact)
                                })
                                .collect::<std::result::Result<_, _>>()?,
                            Some(snapshot.version().into()),
                        ),
                    });
                }
                let facts =
                    registration
                        .indexer
                        .index_source(rel_path, &note.source, &note.body)?;
                Rows::Facts(
                    facts
                        .into_iter()
                        .map(|fact| typed_row(&registration.kinds, fact))
                        .collect(),
                    None,
                )
            }
        };
        Ok(Step::Put {
            rel_path: rel_path.to_string(),
            rows,
        })
    }

    /// Write a planned batch and move the namespace's cursor, in one
    /// transaction. Nothing here reads a file or calls a plugin.
    fn commit_plan(&self, namespace: &str, plan: Plan, pass: &mut IndexPass) -> Result<bool> {
        let _profile = crate::update_profile::span("commit_plan", namespace);
        self.with_index(|db| {
            db.execute_batch("BEGIN IMMEDIATE;")?;
            let current = db.query_row(
                "SELECT cursor_seq, version FROM namespaces WHERE namespace = ?1",
                [namespace],
                |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?)),
            );
            if current.as_ref().ok() != Some(&(plan.expected_cursor, plan.expected_version)) {
                db.execute_batch("ROLLBACK;")?;
                return match current {
                    Ok(_) => Ok(false),
                    Err(error) => Err(error.into()),
                };
            }
            let done: Result<()> = (|| {
                apply_plan(db, namespace, &plan, pass, &BTreeSet::new())?;
                Ok(())
            })();
            match done {
                Ok(()) => {
                    let _profile = crate::update_profile::span("durable_commit", namespace);
                    db.execute_batch("COMMIT;")?;
                }
                Err(e) => {
                    let _ = db.execute_batch("ROLLBACK;");
                    return Err(e);
                }
            }
            Ok(true)
        })
    }

    // ------------------------------------------------------------ query

    /// Anchors satisfying every constraint (ADR 0020 § 2.4).
    ///
    /// A conjunction and nothing else. A note-level fact — one whose
    /// anchor is the note — satisfies every line of that note, which is
    /// what makes `[status:draft] due:week` a question about one line.
    ///
    /// At `Line` scope the answer is the lines that satisfy everything
    /// **at the same anchor**: `type:task @alice` is a task assigned to
    /// Alice, not a task in a note that mentions her. At `Note` scope it
    /// is the notes of those lines, plus the notes whose note-level
    /// facts satisfy everything on their own.
    ///
    /// Ordered by path and then line, so paging and diffing are stable.
    /// `Ne` and `NotExists` narrow the anchors other constraints found
    /// and produce none, so a query with no positive constraint answers
    /// nothing.
    pub fn query(
        &self,
        constraints: &[Constraint],
        scope: QueryScope,
        limit: u32,
    ) -> Result<Vec<Anchor>> {
        for constraint in constraints {
            constraint.validate()?;
        }
        if !constraints.iter().any(generates) {
            return Ok(Vec::new());
        }
        let (sql, mut params) = build_query(constraints, scope);
        params.push(rusqlite::types::Value::Integer(
            limit.min(MAX_RESULTS) as i64
        ));
        self.with_index(|db| {
            let mut stmt = db.prepare(&sql)?;
            let rows = stmt.query_map(rusqlite::params_from_iter(params.iter()), |row| {
                Ok(Anchor {
                    rel_path: row.get(0)?,
                    line: match scope {
                        QueryScope::Line => row.get::<_, Option<i64>>(1)?.map(|n| n.max(0) as u32),
                        QueryScope::Note => None,
                    },
                })
            })?;
            Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
        })
    }
}

// ------------------------------------------------------------- SQL helpers

fn cursor_of(db: &rusqlite::Connection, namespace: &str) -> Result<i64> {
    let held: Option<i64> = db
        .query_row(
            "SELECT cursor_seq FROM namespaces WHERE namespace = ?1",
            [namespace],
            |row| row.get(0),
        )
        .map(Some)
        .or_else(no_rows_none)?;
    if let Some(seq) = held {
        return Ok(seq);
    }
    // The kernel's own namespace has no registration to create its row,
    // so it creates itself the first time anything asks where it is.
    db.execute(
        "INSERT OR IGNORE INTO namespaces (namespace, version, cursor_seq) VALUES (?1, 0, 0)",
        [namespace],
    )?;
    Ok(0)
}

fn set_cursor(db: &rusqlite::Connection, namespace: &str, seq: i64) -> Result<()> {
    db.execute(
        "INSERT INTO namespaces (namespace, version, cursor_seq) VALUES (?1, 0, ?2)
         ON CONFLICT(namespace) DO UPDATE SET cursor_seq = ?2",
        rusqlite::params![namespace, seq.max(0)],
    )?;
    Ok(())
}

fn forget_namespace(db: &rusqlite::Connection, namespace: &str) -> Result<()> {
    if namespace == TEXT_NAMESPACE {
        db.execute("DELETE FROM lines", [])?;
        db.execute("DELETE FROM source_maps", [])?;
    } else {
        db.execute("DELETE FROM fact_values WHERE predicate_id IN (SELECT id FROM index_predicates WHERE namespace=?1)", [namespace])?;
        db.execute("DELETE FROM fact_sources WHERE namespace = ?1", [namespace])?;
        db.execute(
            "DELETE FROM index_predicates WHERE namespace = ?1",
            [namespace],
        )?;
        db.execute("DELETE FROM fact_location_slots WHERE NOT EXISTS (SELECT 1 FROM fact_values WHERE location_id=fact_location_slots.id)", [])?;
        db.execute("DELETE FROM fact_frames WHERE NOT EXISTS (SELECT 1 FROM fact_location_slots WHERE frame_id=fact_frames.id)", [])?;
    }
    Ok(())
}

fn forget_path(db: &rusqlite::Connection, namespace: &str, rel_path: &str) -> Result<()> {
    let _profile = crate::update_profile::span("forget_path", namespace);
    if namespace == TEXT_NAMESPACE {
        {
            let _profile = crate::update_profile::span("delete_text_rows", namespace);
            db.execute("DELETE FROM lines WHERE rel_path = ?1", [rel_path])?;
        }
        {
            let _profile = crate::update_profile::span("delete_source_map_targets", namespace);
            db.execute("DELETE FROM source_maps WHERE rel_path = ?1", [rel_path])?;
        }
    } else {
        db.execute(
            "DELETE FROM fact_values WHERE location_id IN (SELECT id FROM fact_locations WHERE rel_path=?2) AND predicate_id IN (SELECT id FROM index_predicates WHERE namespace=?1)",
            rusqlite::params![namespace, rel_path],
        )?;
        db.execute(
            "DELETE FROM fact_sources WHERE namespace=?1 AND rel_path=?2",
            rusqlite::params![namespace, rel_path],
        )?;
    }
    Ok(())
}

#[cfg(test)]
fn put(db: &rusqlite::Connection, namespace: &str, rel_path: &str, rows: &Rows) -> Result<()> {
    match rows {
        Rows::Lines(lines, source) => {
            if let Some((version, layout)) = source {
                let _profile = crate::update_profile::span("insert_source_map_targets", namespace);
                db.execute("INSERT INTO source_maps (rel_path, source_version, layout) VALUES (?1, ?2, ?3)", rusqlite::params![rel_path, version, layout])?;
            }
            let _profile = crate::update_profile::span("insert_text_rows_fts", namespace);
            let mut stmt = db.prepare_cached(
                "INSERT INTO lines (rel_path, line, field, text, folded) VALUES (?1, ?2, ?3, ?4, ?5)",
            )?;
            for row in lines {
                stmt.execute(rusqlite::params![
                    rel_path,
                    row.line,
                    row.field,
                    row.text,
                    crate::search_text::fold(&row.text)
                ])?;
            }
        }
        Rows::Facts(facts, source_version) => {
            let _profile = crate::update_profile::span("insert_facts_receipt_indexes", namespace);
            writer::put_facts(db, namespace, rel_path, facts, source_version.as_deref())?;
        }
    }
    Ok(())
}

/// The rows the kernel's own text index holds for one note: its prose a
/// line at a time, its name, and its path. The name and the path are
/// note-level rows because a bare term in the shipped search language
/// matches a note's name as readily as its body, and a conjunction
/// cannot express that `or` — so it lives inside the one constraint.
///
/// Blank lines are not indexed. They carry no text to find and a vault
/// of a thousand notes would otherwise hold thousands of rows saying
/// nothing; line numbers still count them, because a line number has to
/// address the buffer a person will see.
fn text_rows(rel_path: &str, body: &str) -> Vec<LineRow> {
    let mut rows: Vec<LineRow> = body
        .split('\n')
        .enumerate()
        .map(|(index, line)| {
            let text = line.strip_suffix('\r').unwrap_or(line);
            LineRow {
                line: Some(index as i64 + 1),
                field: "body",
                text: text.to_string(),
            }
        })
        .collect();
    rows.push(LineRow {
        line: None,
        field: "name",
        text: name_of(rel_path).to_string(),
    });
    rows.push(LineRow {
        line: None,
        field: "path",
        text: rel_path.to_string(),
    });
    rows
}

/// A fact, typed by what its namespace declared the predicate to carry.
///
/// A value that will not take its declared kind is stored exactly as it
/// arrived and marked untyped: it can still be found by name and by
/// equality — somebody looking for the note where they wrote `due:
/// tomorow` should find it — and it takes no part in an ordered
/// comparison, where it would sort as nonsense.
fn typed_row(kinds: &BTreeMap<String, ValueKind>, fact: Fact) -> FactRow {
    let kind = kinds
        .get(&fact.predicate)
        .copied()
        .unwrap_or(ValueKind::Text);
    let (value, num, typed) = match kind {
        ValueKind::Text => (fact.value, None, true),
        ValueKind::Number => match fact.value.trim().parse::<f64>() {
            Ok(number) if number.is_finite() => (fact.value, Some(number), true),
            _ => (fact.value, None, false),
        },
        ValueKind::Date => match iso_date(&fact.value) {
            Some(date) => (date, None, true),
            None => (fact.value, None, false),
        },
    };
    FactRow {
        line: fact.line.map(i64::from),
        item_key: None,
        source_version: None,
        predicate: fact.predicate,
        value,
        num,
        typed,
    }
}

/// `YYYY-MM-DD` at the head of the value, validated and kept; anything
/// else is not a date. Day granularity, because that is what the vault's
/// dates are — a value that needs a time of day declares itself `Text`.
fn iso_date(value: &str) -> Option<String> {
    let text = value.trim();
    let bytes = text.as_bytes();
    if bytes.len() < 10 || bytes[4] != b'-' || bytes[7] != b'-' {
        return None;
    }
    if !text[..4].bytes().all(|b| b.is_ascii_digit())
        || !text[5..7].bytes().all(|b| b.is_ascii_digit())
        || !text[8..10].bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let month: u32 = text[5..7].parse().ok()?;
    let day: u32 = text[8..10].parse().ok()?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    Some(text[..10].to_string())
}

/// The exclusive upper bound of everything beginning with `prefix`.
fn prefix_end(prefix: &str) -> String {
    let mut end = prefix.to_string();
    end.push('\u{10FFFF}');
    end
}

fn generates(constraint: &Constraint) -> bool {
    match constraint {
        Constraint::Fact { op, .. } | Constraint::FactAt { op, .. } => op.generates(),
        Constraint::Text { .. } | Constraint::FactIn { .. } => true,
    }
}

fn no_rows_none<T>(e: rusqlite::Error) -> std::result::Result<Option<T>, rusqlite::Error> {
    match e {
        rusqlite::Error::QueryReturnedNoRows => Ok(None),
        e => Err(e),
    }
}

// ------------------------------------------------------- the query builder

type Value = rusqlite::types::Value;

/// One constraint as a relation of anchors. `line` NULL is the note
/// itself, which satisfies every line of that note.
pub(crate) struct Piece {
    pub(crate) sql: String,
    pub(crate) params: Vec<Value>,
    pub(crate) generates: bool,
    pub(crate) broadcast_root: bool,
}

/// The whole query as one statement.
///
/// Every constraint becomes a named common table expression, so all the
/// parameters sit in the `WITH` clause in constraint order and the body
/// carries only the limit — the alternative, interleaving parameters
/// between the clauses that reference them, is a positional-argument bug
/// waiting to be written.
///
/// **Counted, not re-asked.** The conjunction was first written as one
/// correlated `EXISTS` per constraint per candidate anchor, which reads
/// as the rule it implements and costs a scan of every constraint's rows
/// for every anchor any of them named: three ordinary words over a
/// thousand notes measured **57 seconds**, against 200 ms for reading
/// every file, which is the wrong curve twice over. So the positive
/// constraints are unioned once, tagged with which constraint each row
/// came from, and an anchor is a hit when it collects **every** tag —
/// one pass and a grouping instead of a nested loop. A fact about a note
/// is broadcast onto that note's candidate lines by the one join that
/// needs it, which is the `line IS NULL` rule stated as a join rather
/// than as a condition inside a subquery.
///
/// Negatives stay a filter and are applied as two membership tests: a
/// negative that holds at the anchor's own line, and one that holds of
/// the whole note. Both are equijoins the planner can index, where the
/// `OR` inside one correlated subquery was neither.
fn build_query(constraints: &[Constraint], scope: QueryScope) -> (String, Vec<Value>) {
    let pieces: Vec<Piece> = constraints.iter().map(piece).collect();
    let mut params: Vec<Value> = Vec::new();
    let mut ctes: Vec<String> = Vec::new();
    for (index, piece) in pieces.iter().enumerate() {
        params.extend(piece.params.iter().cloned());
        // **MATERIALIZED, and it is not a hint.** A constraint's rows are
        // read by four of the clauses below, and SQLite's default is to
        // inline a common table expression into each of them — which for
        // a full-text constraint means running the same FTS query once
        // per row of whatever it was inlined beside. Three ordinary words
        // over a thousand notes took **111 seconds** that way. Computing
        // each constraint once is the whole difference.
        ctes.push(format!("c{index} AS MATERIALIZED ({})", piece.sql));
    }
    let positive: Vec<usize> = pieces
        .iter()
        .enumerate()
        .filter(|(_, piece)| piece.generates)
        .map(|(index, _)| index)
        .collect();
    let negative: Vec<usize> = pieces
        .iter()
        .enumerate()
        .filter(|(_, piece)| !piece.generates)
        .map(|(index, _)| index)
        .collect();

    // Every positive row, carrying the constraint it satisfies.
    let positive_rows = positive
        .iter()
        .map(|index| {
            format!(
                "SELECT {index} AS tag, rel_path, line, {} AS broadcast FROM c{index}",
                u8::from(pieces[*index].broadcast_root)
            )
        })
        .collect::<Vec<_>>()
        .join(" UNION ALL ");
    // Every negative row. Which negative it came from does not matter:
    // not this and not that is not (this or that).
    let negative_rows = if negative.is_empty() {
        "SELECT NULL AS rel_path, NULL AS line, 0 AS broadcast WHERE 0".to_string()
    } else {
        negative
            .iter()
            .map(|index| {
                format!(
                    "SELECT rel_path, line, {} AS broadcast FROM c{index}",
                    u8::from(pieces[*index].broadcast_root)
                )
            })
            .collect::<Vec<_>>()
            .join(" UNION ALL ")
    };
    let wanted = positive.len();

    let shared = format!(
        "pos AS MATERIALIZED ({positive_rows}), \
         neg AS MATERIALIZED ({negative_rows}), \
         anchors AS MATERIALIZED \
             (SELECT DISTINCT rel_path, line FROM pos WHERE line IS NOT NULL), \
         matched AS MATERIALIZED ( \
             SELECT rel_path, line, tag FROM pos WHERE line IS NOT NULL \
             UNION ALL \
             SELECT a.rel_path, a.line, p.tag FROM anchors a \
               JOIN pos p ON p.rel_path = a.rel_path AND p.line IS NULL AND p.broadcast = 1), \
         hits AS MATERIALIZED (SELECT rel_path, line FROM matched \
                   GROUP BY rel_path, line HAVING COUNT(DISTINCT tag) = {wanted}), \
         denied_line AS MATERIALIZED (SELECT rel_path, line FROM neg WHERE line IS NOT NULL), \
         denied_whole AS MATERIALIZED (SELECT DISTINCT rel_path FROM neg WHERE line IS NULL AND broadcast = 1), \
         denied_root AS MATERIALIZED (SELECT DISTINCT rel_path FROM neg WHERE line IS NULL), \
         kept AS MATERIALIZED (SELECT rel_path, line FROM hits \
                   WHERE (rel_path, line) NOT IN (SELECT rel_path, line FROM denied_line) \
                     AND rel_path NOT IN (SELECT rel_path FROM denied_whole))"
    );

    let sql = match scope {
        QueryScope::Line => format!(
            "WITH {}, {shared} SELECT rel_path, line FROM kept ORDER BY rel_path, line LIMIT ?",
            ctes.join(", ")
        ),
        QueryScope::Note => {
            // The second half of the note-scope answer: a note whose
            // note-level facts satisfy everything on their own, with no
            // line involved at all. A negative is judged at the anchor,
            // so only a note-level negative excludes a note-level hit.
            format!(
                "WITH {}, {shared}, \
                 whole AS (SELECT rel_path FROM pos WHERE line IS NULL \
                            GROUP BY rel_path HAVING COUNT(DISTINCT tag) = {wanted}) \
                 SELECT rel_path, NULL AS line FROM \
                 (SELECT rel_path FROM kept \
                   UNION SELECT rel_path FROM whole \
                    WHERE rel_path NOT IN (SELECT rel_path FROM denied_root)) \
                 ORDER BY rel_path LIMIT ?",
                ctes.join(", ")
            )
        }
    };
    (sql, params)
}

pub(crate) fn piece(constraint: &Constraint) -> Piece {
    let mut part = document_piece(constraint);
    if !matches!(constraint, Constraint::Text { .. }) {
        part.sql = format!(
            "SELECT rel_path, line FROM ({}) WHERE item_key IS NULL",
            part.sql
        );
    }
    part
}

pub(crate) fn scoped_piece(constraint: &Constraint) -> Piece {
    scoped_piece_from(constraint, "facts")
}

/// A caller has already selected a source root. Bind each fact probe to its
/// path index: choosing a broad predicate/value index here makes N selected
/// roots scan the same N equal values, producing quadratic work.
#[cfg(test)]
pub(crate) fn scoped_piece_at_root(constraint: &Constraint) -> Piece {
    scoped_piece_from(constraint, "(SELECT l.rel_path,l.line,l.item_key,l.source_version,p.namespace,p.predicate,f.predicate_id,f.value,f.num,f.typed FROM fact_locations l CROSS JOIN fact_values f INDEXED BY fact_values_location ON f.location_id=l.id CROSS JOIN index_predicates p ON p.id=f.predicate_id)")
}

fn scoped_piece_from(constraint: &Constraint, fact_table: &str) -> Piece {
    match constraint {
        Constraint::Fact {
            predicate,
            op,
            value,
        } => fact_piece_from(predicate, *op, value, fact_table),
        Constraint::FactAt {
            predicate,
            op,
            value,
            locality,
        } => localize(
            fact_piece_from(predicate, *op, value, fact_table),
            *locality,
        ),
        Constraint::FactIn {
            predicate,
            values,
            locality,
        } => {
            let mut values = values.clone();
            values.sort();
            values.dedup();
            localize(Piece {
                sql: format!("SELECT rel_path, line, item_key, source_version, namespace FROM {fact_table} WHERE predicate_id IN (SELECT id FROM index_predicates WHERE predicate=?) AND value IN (SELECT value FROM json_each(?))"),
                params: vec![Value::Text(predicate.clone()), Value::Text(serde_json::json!(values).to_string())],
                generates: true,
                broadcast_root: true,
            }, *locality)
        }
        Constraint::Text { target, query } => text_piece(*target, query),
    }
}

pub(crate) fn document_piece(constraint: &Constraint) -> Piece {
    let mut part = scoped_piece(constraint);
    if !matches!(constraint, Constraint::Text { .. }) {
        part.sql=format!("SELECT f.* FROM ({}) f WHERE f.source_version IS NULL OR EXISTS (SELECT 1 FROM source_maps s WHERE s.rel_path=f.rel_path AND s.source_version=f.source_version)",part.sql);
    }
    part
}

fn localize(mut piece: Piece, locality: FactLocality) -> Piece {
    match locality {
        FactLocality::Exact => piece.broadcast_root = false,
        FactLocality::DocumentRoot => piece.sql.push_str(" AND line IS NULL AND item_key IS NULL"),
        FactLocality::Inherited => {}
    }
    piece
}

fn fact_piece_from(predicate: &str, op: ConstraintOp, value: &str, fact_table: &str) -> Piece {
    let (condition, values) = fact_value_condition(op, value);
    let mut params = vec![Value::Text(predicate.to_string())];
    params.extend(values);
    Piece {
        sql: format!("SELECT rel_path, line, item_key, source_version, namespace FROM {fact_table} WHERE predicate_id IN (SELECT id FROM index_predicates WHERE predicate=?){condition}"),
        params,
        generates: op.generates(),
        broadcast_root: true,
    }
}

/// Shared value semantics for wide and target-bound fact execution.
pub(crate) fn fact_value_condition(op: ConstraintOp, value: &str) -> (String, Vec<Value>) {
    let mut params = Vec::new();
    let condition = match op {
        ConstraintOp::Exists | ConstraintOp::NotExists => String::new(),
        ConstraintOp::Eq | ConstraintOp::Ne => {
            params.push(Value::Text(value.to_string()));
            " AND value = ?".to_string()
        }
        ConstraintOp::Contains => {
            params.push(Value::Text(crate::search_text::fold(value)));
            " AND instr(gilune_fold(value), ?) > 0".to_string()
        }
        ConstraintOp::Prefix => {
            let folded = crate::search_text::fold(value);
            let end = crate::search_text::prefix_end(&folded);
            params.push(Value::Text(folded));
            // The expression index stores the fold at write time. Both bounds
            // use BINARY order; punctuation has no LIKE/GLOB wildcard meaning.
            if let Some(end) = end {
                params.push(Value::Text(end));
                " AND gilune_fold(value) >= ? AND gilune_fold(value) < ?".to_string()
            } else {
                " AND gilune_fold(value) >= ?".to_string()
            }
        }
        ConstraintOp::Lt | ConstraintOp::Le | ConstraintOp::Gt | ConstraintOp::Ge => {
            let symbol = match op {
                ConstraintOp::Lt => "<",
                ConstraintOp::Le => "<=",
                ConstraintOp::Gt => ">",
                _ => ">=",
            };
            // A bound that is a number asks a numeric question, and only
            // a predicate declared `Number` can answer one. Everything
            // else is compared as text, which is exactly right for a
            // `Date`: ISO-8601 sorts chronologically. A value that would
            // not take its declared kind is out of both.
            match value.trim().parse::<f64>() {
                Ok(number) if number.is_finite() => {
                    params.push(Value::Real(number));
                    format!(" AND num IS NOT NULL AND num {symbol} ?")
                }
                _ => {
                    params.push(Value::Text(value.to_string()));
                    format!(" AND num IS NULL AND typed = 1 AND value {symbol} ?")
                }
            }
        }
    };
    (condition, params)
}

fn text_piece(target: TextTarget, query: &str) -> Piece {
    let fields = match target {
        TextTarget::Any => "l.field IN ('body','name')",
        TextTarget::Body => "l.field = 'body'",
        TextTarget::Name => "l.field = 'name'",
        TextTarget::Path => "l.field = 'path'",
    };
    // Substring semantics, which is what every text term in the shipped
    // language means. The trigram index answers three characters or
    // more; below that it holds no trigram to look up, so the shorter
    // query is answered by scanning the indexed lines rather than by
    // quietly finding nothing.
    let (condition, param) = if query.chars().count() >= TRIGRAM_MIN {
        (
            "l.id IN (SELECT rowid FROM lines_fts WHERE lines_fts MATCH ?)".to_string(),
            Value::Text(fts_phrase(&crate::search_text::fold(query))),
        )
    } else {
        (
            "instr(l.folded, ?) > 0".to_string(),
            Value::Text(crate::search_text::fold(query)),
        )
    };
    Piece {
        sql: format!("SELECT l.rel_path, l.line FROM lines l WHERE {fields} AND {condition}"),
        params: vec![param],
        generates: true,
        broadcast_root: true,
    }
}

/// A user's text as one FTS5 phrase. Quoting is what makes an apostrophe
/// or a hyphen ordinary text rather than query syntax.
fn fts_phrase(query: &str) -> String {
    format!("\"{}\"", query.replace('"', "\"\""))
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod prefix_tests;

#[cfg(test)]
pub(crate) fn create_position_fixture(db: &rusqlite::Connection) -> Result<()> {
    create(db, "position-fixture")
}
