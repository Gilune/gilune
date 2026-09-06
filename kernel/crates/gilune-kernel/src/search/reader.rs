//! Independent, cancellable index reads. A count never holds the Store mutex or
//! a page's connection. Generation checks prevent combining different snapshots.
use super::*;
use std::path::PathBuf;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};

pub struct SearchQuery {
    index: PathBuf,
    feed: PathBuf,
    request: SearchRequest,
    item_mode: bool,
    cancelled: Arc<AtomicBool>,
}

#[derive(Debug, Clone)]
pub struct SearchSlice {
    pub hits: Vec<SearchHit>,
    /// None means not counted yet, never zero matches.
    pub total_hits: Option<u64>,
    pub next_cursor: Option<String>,
    pub generation: String,
    pub index_complete: bool,
    pub issues: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct SearchCount {
    pub total_hits: u64,
    pub generation: String,
    pub index_complete: bool,
    pub issues: Vec<String>,
}

impl Store {
    pub fn prepare_search(&self, request: SearchRequest, item_mode: bool) -> Result<SearchQuery> {
        validate(&request)?;
        if item_mode && (!request.line_mode || request.extension_candidates) {
            return Err(invalid(
                "item search requires indexed item membership, without a line extractor",
            ));
        }
        self.with_index(|_| Ok(()))?;
        Ok(SearchQuery {
            index: self.local_dir().join("index.db"),
            feed: self
                .conn()
                .path()
                .ok_or_else(|| invalid("search requires a persistent index"))?
                .into(),
            request,
            item_mode,
            cancelled: Arc::new(AtomicBool::new(false)),
        })
    }
}

impl SearchQuery {
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }

    pub fn page(
        &self,
        cursor: Option<String>,
        extension: Option<&dyn SearchExtension>,
    ) -> Result<SearchSlice> {
        let mut request = self.request.clone();
        request.cursor = cursor;
        let page = self.run(&request, extension, Execution::Page, None)?;
        let total_hits = (!(if self.item_mode {
            target_query::supports(&request)
        } else {
            anchor_page::supports(&request)
        }))
        .then_some(page.total_hits);
        Ok(SearchSlice {
            hits: page.hits,
            total_hits,
            next_cursor: page.next_cursor,
            generation: page.generation,
            index_complete: page.index_complete,
            issues: page.issues,
        })
    }

    pub fn count(
        &self,
        generation: &str,
        extension: Option<&dyn SearchExtension>,
    ) -> Result<SearchCount> {
        let mut request = self.request.clone();
        request.cursor = None;
        let page = self.run(&request, extension, Execution::Count, Some(generation))?;
        Ok(SearchCount {
            total_hits: page.total_hits,
            generation: page.generation,
            index_complete: page.index_complete,
            issues: page.issues,
        })
    }

    fn run(
        &self,
        request: &SearchRequest,
        extension: Option<&dyn SearchExtension>,
        execution: Execution,
        expected: Option<&str>,
    ) -> Result<SearchPage> {
        if self.cancelled.load(Ordering::Acquire) {
            return Err(invalid("search cancelled"));
        }
        let db =
            Connection::open_with_flags(&self.index, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        crate::search_text::register(&db)?;
        let cancelled = self.cancelled.clone();
        db.progress_handler(1000, Some(move || cancelled.load(Ordering::Acquire)));
        db.execute_batch("BEGIN")?;
        let generation: String = db.query_row("SELECT (SELECT value FROM index_meta WHERE key='instance') || ':' || (SELECT value FROM index_meta WHERE key='generation')", [], |r| r.get(0))?;
        if expected.is_some_and(|e| e != generation) {
            return Err(KernelError::SearchCursorExpired);
        }
        let result = search_snapshot(&db, request, extension, self.item_mode, execution, || {
            let feed = Connection::open_with_flags(
                &self.feed,
                rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
            )?;
            Ok(
                feed.query_row("SELECT COALESCE(MAX(seq),0) FROM vault_changes", [], |r| {
                    r.get(0)
                })?,
            )
        });
        if self.cancelled.load(Ordering::Acquire) {
            return Err(invalid("search cancelled"));
        }
        result
        // Dropping the private connection closes its read transaction on every
        // success, cancellation, plugin error and cursor-expiry path.
    }
}
