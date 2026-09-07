//! Frozen schema-12 query control for the position-storage benchmark only.
//! Copied before the schema-13 migration; never compiled into a shipped kernel.
#![allow(dead_code)]
use super::*;
mod target_projection;
mod target_query;
mod target_scan;
mod text_window;
#[allow(clippy::too_many_arguments)]
pub(super) fn execute(
    db: &Connection,
    q: &SearchRequest,
    after: Option<Rank>,
    mode: Execution,
) -> Result<SearchPage> {
    let required = items::requirements(db, q, &mut vec![])?;
    if target_query::supports(q) {
        target_query::execute(
            db,
            q,
            &required,
            "positions".into(),
            "positions".into(),
            after,
            vec![],
            mode,
        )
    } else {
        target_scan::search(
            db,
            q,
            None,
            &required,
            "positions".into(),
            "positions".into(),
            after,
            vec![],
        )
    }
}
pub(super) fn timings() -> [f64; 4] {
    target_query::TIMINGS.with(|t| t.get())
}
