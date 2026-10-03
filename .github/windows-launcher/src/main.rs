#![windows_subsystem = "windows"]

use std::ffi::OsString;
use std::path::Path;
use std::process::{Command, ExitStatus};

fn launch(root: &Path) -> std::io::Result<ExitStatus> {
    let mut resources = OsString::from("-Dcompose.application.resources.dir=");
    resources.push(root.join("resources"));
    // OsString arguments preserve Unicode paths and let Rust quote Windows
    // command lines correctly. Java expands the classpath wildcard itself.
    Command::new(root.join("runtime/bin/javaw.exe"))
        .current_dir(root)
        .args([
            "-XX:+UseSerialGC",
            "-Xms32m",
            "-Xmx768m",
            "-XX:CICompilerCount=2",
            "-Dcompose.application.configure.swing.globals=true",
            concat!("-Djpackage.app-version=", env!("GILUNE_APP_VERSION")),
        ])
        .arg(resources)
        .arg("-classpath")
        .arg(root.join("lib/*"))
        .arg("org.gilune.app.MainKt")
        .args(std::env::args_os().skip(1))
        .status()
}

#[cfg(windows)]
fn show_error(message: &str) {
    #[link(name = "user32")]
    extern "system" {
        fn MessageBoxW(
            window: *mut std::ffi::c_void,
            text: *const u16,
            caption: *const u16,
            flags: u32,
        ) -> i32;
    }
    let text: Vec<u16> = message.encode_utf16().chain(Some(0)).collect();
    let title: Vec<u16> = "Gilune".encode_utf16().chain(Some(0)).collect();
    // NUL-terminated buffers remain alive for this synchronous Windows call.
    unsafe {
        MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), 0x10);
    }
}

#[cfg(not(windows))]
fn show_error(message: &str) {
    eprintln!("{message}");
}

fn main() {
    let result = std::env::current_exe().and_then(|exe| {
        let root = exe
            .parent()
            .ok_or_else(|| std::io::Error::other("Missing application directory"))?;
        launch(root)
    });
    match result {
        Ok(status) => std::process::exit(status.code().unwrap_or(1)),
        Err(error) => {
            show_error(&format!(
                "Gilune could not start its bundled Java runtime.\n\n{error}"
            ));
            std::process::exit(1);
        }
    }
}
