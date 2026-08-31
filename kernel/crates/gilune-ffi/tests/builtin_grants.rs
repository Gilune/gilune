//! Host policy at the boundary: a bundled plugin's local capabilities
//! are consented by installing the application and granted on first
//! install; network use always waits for an explicit yes (ADR 0011).

#![cfg(feature = "whisper")]

use gilune_ffi::Gilune;

#[test]
fn a_fresh_vault_grants_local_capabilities_but_never_network() {
    let dir = tempfile::tempdir().expect("tempdir");
    let gilune = Gilune::open(dir.path().to_string_lossy().to_string()).expect("open");
    let plugins = gilune.plugins().expect("plugins");
    let transcription = plugins
        .iter()
        .find(|p| p.plugin_id == "org.memory.transcription")
        .expect("transcription is registered on open");
    assert!(
        transcription.granted.contains(&"blobs.read".to_string())
            && transcription.granted.contains(&"objects.write".to_string()),
        "local capabilities are consented by installing the app; granted = {:?}",
        transcription.granted
    );
    assert!(
        !transcription.granted.contains(&"network.request".to_string()),
        "leaving the device always takes an explicit yes"
    );
    assert_eq!(transcription.ungranted, vec!["network.request".to_string()]);
}

#[test]
fn a_revoked_local_capability_is_not_regranted_on_reopen() {
    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path().to_string_lossy().to_string();
    {
        let gilune = Gilune::open(path.clone()).expect("first open");
        gilune
            .revoke_capability("org.memory.transcription".into(), "blobs.read".into())
            .expect("revoke");
    }
    let gilune = Gilune::open(path).expect("reopen");
    let plugins = gilune.plugins().expect("plugins");
    let transcription = plugins
        .iter()
        .find(|p| p.plugin_id == "org.memory.transcription")
        .expect("still registered");
    assert!(
        !transcription.granted.contains(&"blobs.read".to_string()),
        "install-time consent is once; a revocation sticks"
    );
}
