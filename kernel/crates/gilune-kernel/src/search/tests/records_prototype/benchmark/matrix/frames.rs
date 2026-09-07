//! Schema-12 fixture normalisation for the frozen delivery comparison.
//! This fixture conversion is test-only; production discards old derived indexes.
use super::*;

pub(super) fn normalise(db: &Connection) {
    let kind: String = db
        .query_row(
            "SELECT type FROM sqlite_master WHERE name='fact_locations'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    if kind == "view" {
        return;
    }
    let storage = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/src/search/absolute_baseline/storage.sql"
    ));
    let start = storage.find("CREATE TABLE fact_frames").unwrap();
    let end = storage.find("CREATE TABLE fact_values").unwrap();
    db.execute_batch(
        "BEGIN; CREATE TEMP TABLE saved_locations AS SELECT * FROM fact_locations;
        DROP TABLE fact_locations;",
    )
    .unwrap();
    db.execute_batch(&storage[start..end]).unwrap();
    db.execute_batch(
        "INSERT INTO fact_locations SELECT * FROM saved_locations;
        DROP TABLE saved_locations; COMMIT; VACUUM;",
    )
    .unwrap();
}
