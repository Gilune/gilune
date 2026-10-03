//! Runtime paths let cross-compiled test executables run on another machine.
use std::path::PathBuf;

pub fn cli() -> PathBuf {
    std::env::var_os("GILUNE_TEST_CLI")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_BIN_EXE_gilune-cli")))
}
