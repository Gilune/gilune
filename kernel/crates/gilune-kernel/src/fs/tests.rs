use super::*;

#[test]
fn publishing_never_replaces_a_file_that_appeared_after_preparation() {
    let dir = tempfile::tempdir().unwrap();
    let source = dir.path().join("prepared");
    let target = dir.path().join("note.md");
    std::fs::write(&source, b"prepared content").unwrap();
    std::fs::write(&target, b"external content").unwrap();
    assert!(!publish_new_file(&source, &target).unwrap());
    assert_eq!(std::fs::read(target).unwrap(), b"external content");
    assert_eq!(std::fs::read(source).unwrap(), b"prepared content");
}

#[test]
fn competing_publishers_expose_exactly_one_complete_file() {
    let dir = tempfile::tempdir().unwrap();
    let target = dir.path().join("note.md");
    let barrier = std::sync::Barrier::new(8);
    let winners = std::thread::scope(|scope| {
        let workers: Vec<_> = (0u8..8)
            .map(|n| {
                let source = dir.path().join(format!("prepared-{n}"));
                let bytes = vec![n; 16 * 1024];
                std::fs::write(&source, &bytes).unwrap();
                let target = &target;
                let barrier = &barrier;
                scope.spawn(move || {
                    barrier.wait();
                    publish_new_file(&source, target).unwrap().then_some(bytes)
                })
            })
            .collect();
        workers
            .into_iter()
            .filter_map(|w| w.join().unwrap())
            .collect::<Vec<_>>()
    });
    assert_eq!(winners.len(), 1);
    assert_eq!(std::fs::read(target).unwrap(), winners[0]);
}
