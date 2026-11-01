//! The installed `Gilune.exe`: hosts the bundled Java runtime in this
//! process. One process named Gilune owns the window, the sockets and the
//! firewall prompt; nothing is spawned. The runtime's `jvm.dll` is loaded
//! here and asked for a VM with the same options the earlier launcher
//! passed to `javaw.exe`; then `org.gilune.app.MainKt.main` runs on a
//! thread of the runtime's default stack size, as the Java launcher does.
//! Raw JNI declarations keep the crate free of dependencies.
#![windows_subsystem = "windows"]

use std::ffi::{c_char, c_void, OsStr, OsString};
use std::os::windows::ffi::OsStrExt;
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;

/// `Gilune.exe --allow-firewall`: add the Windows Firewall rule that lets
/// this launcher answer other devices on private networks, and nothing
/// else. The app starts this elevated (Windows asks once), so the prompt
/// names Gilune rather than a shell; a per-user install has no other
/// elevated moment. No runtime is loaded.
const ALLOW_FIREWALL: &str = "--allow-firewall";
const FIREWALL_RULE: &str = "Gilune";
/// A console child of a windowed process would open a console of its own.
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

type Jint = i32;
type Jsize = i32;
type Jboolean = u8;
type Jobject = *mut c_void;
type Jclass = Jobject;
type JmethodId = *mut c_void;

const JNI_VERSION_1_8: Jint = 0x0001_0008;
const JNI_OK: Jint = 0;
const JNI_FALSE: Jboolean = 0;
/// Reserve, as the Java launcher's main thread does on Windows x64: the
/// runtime's default (`ThreadStackSize` 0) is the executable's own 1 MiB.
const MAIN_THREAD_STACK: usize = 1 << 20;

#[repr(C)]
struct JavaVmOption {
    option_string: *const u8,
    extra_info: *mut c_void,
}

#[repr(C)]
struct JavaVmInitArgs {
    version: Jint,
    n_options: Jint,
    options: *mut JavaVmOption,
    ignore_unrecognized: Jboolean,
}

#[repr(C)]
union Jvalue {
    l: Jobject,
    _bits: u64,
}

/// `JNIInvokeInterface_` from jni.h: the fields this launcher calls, the
/// rest padded by position.
#[repr(C)]
struct JavaVmTable {
    _reserved: [*mut c_void; 3],
    destroy_java_vm: unsafe extern "system" fn(*mut JavaVm) -> Jint,
    _attach_current_thread: *mut c_void,
    detach_current_thread: unsafe extern "system" fn(*mut JavaVm) -> Jint,
    _get_env: *mut c_void,
    _attach_current_thread_as_daemon: *mut c_void,
}
type JavaVm = *const JavaVmTable;

/// `JNINativeInterface_` from jni.h (JDK 17), indices checked against the
/// header: FindClass 6, ExceptionDescribe 16, ExceptionClear 17,
/// GetStaticMethodID 113, CallStaticVoidMethodA 143, NewString 163,
/// NewObjectArray 172, SetObjectArrayElement 174, ExceptionCheck 228.
#[repr(C)]
struct JniEnvTable {
    _pad0: [*mut c_void; 6],
    find_class: unsafe extern "system" fn(*mut JniEnv, *const c_char) -> Jclass,
    _pad1: [*mut c_void; 9],
    exception_describe: unsafe extern "system" fn(*mut JniEnv),
    exception_clear: unsafe extern "system" fn(*mut JniEnv),
    _pad2: [*mut c_void; 95],
    get_static_method_id:
        unsafe extern "system" fn(*mut JniEnv, Jclass, *const c_char, *const c_char) -> JmethodId,
    _pad3: [*mut c_void; 29],
    call_static_void_method_a:
        unsafe extern "system" fn(*mut JniEnv, Jclass, JmethodId, *const Jvalue),
    _pad4: [*mut c_void; 19],
    new_string: unsafe extern "system" fn(*mut JniEnv, *const u16, Jsize) -> Jobject,
    _pad5: [*mut c_void; 8],
    new_object_array: unsafe extern "system" fn(*mut JniEnv, Jsize, Jclass, Jobject) -> Jobject,
    _pad6: [*mut c_void; 1],
    set_object_array_element: unsafe extern "system" fn(*mut JniEnv, Jobject, Jsize, Jobject),
    _pad7: [*mut c_void; 53],
    exception_check: unsafe extern "system" fn(*mut JniEnv) -> Jboolean,
}
type JniEnv = *const JniEnvTable;
const _: () = assert!(std::mem::size_of::<JniEnvTable>() == 229 * std::mem::size_of::<usize>());
const _: () = assert!(std::mem::size_of::<JavaVmTable>() == 8 * std::mem::size_of::<usize>());

type CreateJavaVm =
    unsafe extern "system" fn(*mut *mut JavaVm, *mut *mut c_void, *mut c_void) -> Jint;

#[link(name = "kernel32")]
extern "system" {
    fn LoadLibraryW(name: *const u16) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, name: *const c_char) -> *mut c_void;
    fn SetDllDirectoryW(path: *const u16) -> i32;
    fn WideCharToMultiByte(
        code_page: u32,
        flags: u32,
        wide: *const u16,
        wide_len: i32,
        multi: *mut u8,
        multi_len: i32,
        default_char: *const u8,
        used_default: *mut i32,
    ) -> i32;
}

fn wide(text: &OsStr) -> Vec<u16> {
    text.encode_wide().chain(Some(0)).collect()
}

fn failure(message: impl Into<String>) -> std::io::Error {
    std::io::Error::other(message.into())
}

/// The runtime takes `char*` options and decodes property values with the
/// ANSI code page (`sun.jnu.encoding`), which is how `javaw.exe` handed its
/// command line over; so paths are converted with that code page, not UTF-8.
fn ansi(text: &OsStr) -> std::io::Result<Vec<u8>> {
    let utf16: Vec<u16> = text.encode_wide().collect();
    if utf16.is_empty() {
        return Ok(vec![0]);
    }
    unsafe {
        let needed = WideCharToMultiByte(
            0, 0, utf16.as_ptr(), utf16.len() as i32,
            std::ptr::null_mut(), 0, std::ptr::null(), std::ptr::null_mut(),
        );
        if needed <= 0 {
            return Err(std::io::Error::last_os_error());
        }
        let mut out = vec![0u8; needed as usize + 1];
        let written = WideCharToMultiByte(
            0, 0, utf16.as_ptr(), utf16.len() as i32,
            out.as_mut_ptr(), needed, std::ptr::null(), std::ptr::null_mut(),
        );
        if written != needed {
            return Err(std::io::Error::last_os_error());
        }
        Ok(out)
    }
}

fn option(prefix: &str, value: &OsStr) -> std::io::Result<Vec<u8>> {
    let mut text = OsString::from(prefix);
    text.push(value);
    ansi(&text)
}

/// `java.class.path` takes no wildcard: the jars are listed, in name order,
/// as `javaw -classpath lib\*` expanded them.
fn class_path(lib: &Path) -> std::io::Result<OsString> {
    let mut jars: Vec<PathBuf> = std::fs::read_dir(lib)?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|e| e.eq_ignore_ascii_case("jar")))
        .collect();
    if jars.is_empty() {
        return Err(failure(format!("No application jars in {}", lib.display())));
    }
    jars.sort();
    let mut joined = OsString::new();
    for (index, jar) in jars.iter().enumerate() {
        if index > 0 {
            joined.push(";");
        }
        joined.push(jar);
    }
    Ok(joined)
}

/// The netsh invocations, in order: delete every rule Windows holds for
/// this launcher and for the runtime's javaw.exe (a Block row from a
/// dismissed prompt beats any Allow, and netsh duplicates by name), then
/// add the one rule — inbound, allow, Private profile only.
fn firewall_commands(root: &Path, exe: &Path) -> Vec<Vec<OsString>> {
    let program = |path: &Path| {
        let mut argument = OsString::from("program=");
        argument.push(path);
        argument
    };
    let words = |text: &str| text.split(' ').map(OsString::from).collect::<Vec<_>>();
    let javaw = root.join("runtime").join("bin").join("javaw.exe");
    let mut commands = Vec::new();
    for path in [&javaw, exe] {
        let mut command = words("advfirewall firewall delete rule name=all");
        command.push(program(path));
        commands.push(command);
    }
    let mut add = words(&format!(
        "advfirewall firewall add rule name={FIREWALL_RULE} dir=in action=allow enable=yes profile=private"
    ));
    add.push(program(exe));
    commands.push(add);
    commands
}

/// Runs the commands; the exit code is the add's, since the deletes may
/// find nothing to delete.
fn allow_firewall(root: &Path, exe: &Path) -> std::io::Result<i32> {
    let system_root = std::env::var_os("SystemRoot").unwrap_or_else(|| OsString::from(r"C:\Windows"));
    let netsh = Path::new(&system_root).join("System32").join("netsh.exe");
    let mut code = 1;
    for command in firewall_commands(root, exe) {
        let status = Command::new(&netsh)
            .args(&command)
            .creation_flags(CREATE_NO_WINDOW)
            .status()?;
        code = status.code().unwrap_or(1);
    }
    Ok(code)
}

fn load_create_java_vm(root: &Path) -> std::io::Result<CreateJavaVm> {
    let bin = root.join("runtime").join("bin");
    let jvm = bin.join("server").join("jvm.dll");
    if !jvm.is_file() {
        return Err(failure(format!("Missing {}", jvm.display())));
    }
    // `javaw.exe` lived in runtime\bin, so every runtime DLL resolved from
    // the application's own directory. Hosted here, that directory has to
    // be named twice: on the DLL search path, for the dependencies of
    // jvm.dll and of the libraries the runtime loads by full path; and on
    // PATH, because the runtime builds `java.library.path` from it, which
    // is where libraries loaded by name (jawt, for one) are found.
    let mut path = OsString::from(&bin);
    if let Some(existing) = std::env::var_os("PATH") {
        path.push(";");
        path.push(existing);
    }
    std::env::set_var("PATH", path);
    unsafe {
        if SetDllDirectoryW(wide(bin.as_os_str()).as_ptr()) == 0 {
            return Err(std::io::Error::last_os_error());
        }
        let module = LoadLibraryW(wide(jvm.as_os_str()).as_ptr());
        if module.is_null() {
            let error = std::io::Error::last_os_error();
            return Err(failure(format!("Could not load {}: {error}", jvm.display())));
        }
        let symbol = GetProcAddress(module, c"JNI_CreateJavaVM".as_ptr());
        if symbol.is_null() {
            return Err(failure(format!("{} has no JNI_CreateJavaVM", jvm.display())));
        }
        Ok(std::mem::transmute::<*mut c_void, CreateJavaVm>(symbol))
    }
}

fn launch(root: &Path) -> std::io::Result<i32> {
    let create = load_create_java_vm(root)?;
    // The same working directory javaw.exe was given.
    std::env::set_current_dir(root)?;
    let mut options: Vec<Vec<u8>> = [
        "-XX:+UseSerialGC",
        "-Xms32m",
        "-Xmx768m",
        "-XX:CICompilerCount=2",
        // Give heap back after a collection instead of keeping it for
        // the next burst (ADR 0032): 59 MB stayed committed for 18 MB.
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=30",
        // Java2D's own Direct3D pipeline: Compose draws through Skia's
        // device, never this one, which cost 35 MB and 16 threads idle.
        "-Dsun.java2d.d3d=false",
        "-Dcompose.application.configure.swing.globals=true",
        concat!("-Djpackage.app-version=", env!("GILUNE_APP_VERSION")),
    ]
    .iter()
    .map(|text| ansi(OsStr::new(text)))
    .collect::<std::io::Result<_>>()?;
    options.push(option("-Dcompose.application.resources.dir=", root.join("resources").as_os_str())?);
    options.push(option("-Djava.class.path=", &class_path(&root.join("lib"))?)?);
    let mut vm_options: Vec<JavaVmOption> = options
        .iter()
        .map(|text| JavaVmOption { option_string: text.as_ptr(), extra_info: std::ptr::null_mut() })
        .collect();
    let mut args = JavaVmInitArgs {
        version: JNI_VERSION_1_8,
        n_options: vm_options.len() as Jint,
        options: vm_options.as_mut_ptr(),
        ignore_unrecognized: JNI_FALSE,
    };
    let program_args: Vec<Vec<u16>> =
        std::env::args_os().skip(1).map(|arg| arg.encode_wide().collect()).collect();
    let mut vm: *mut JavaVm = std::ptr::null_mut();
    let mut env: *mut JniEnv = std::ptr::null_mut();
    unsafe {
        let status = create(
            &mut vm,
            (&mut env as *mut *mut JniEnv).cast(),
            (&mut args as *mut JavaVmInitArgs).cast(),
        );
        if status != JNI_OK || vm.is_null() || env.is_null() {
            return Err(failure(format!("The Java runtime could not be created (JNI status {status})")));
        }
        let table = &**env;
        let failed = |what: &str| -> std::io::Result<i32> {
            if (table.exception_check)(env) != JNI_FALSE {
                (table.exception_describe)(env);
                (table.exception_clear)(env);
            }
            Err(failure(what.to_string()))
        };
        let main_class = (table.find_class)(env, c"org/gilune/app/MainKt".as_ptr());
        if main_class.is_null() {
            return failed("The application's main class is not on the class path");
        }
        let main = (table.get_static_method_id)(
            env, main_class, c"main".as_ptr(), c"([Ljava/lang/String;)V".as_ptr(),
        );
        if main.is_null() {
            return failed("The application's main class has no main(String[])");
        }
        let string_class = (table.find_class)(env, c"java/lang/String".as_ptr());
        if string_class.is_null() {
            return failed("java.lang.String is missing from the runtime");
        }
        let array = (table.new_object_array)(
            env, program_args.len() as Jsize, string_class, std::ptr::null_mut(),
        );
        if array.is_null() {
            return failed("Could not allocate the program arguments");
        }
        for (index, arg) in program_args.iter().enumerate() {
            let text = (table.new_string)(env, arg.as_ptr(), arg.len() as Jsize);
            if text.is_null() {
                return failed("Could not allocate a program argument");
            }
            (table.set_object_array_element)(env, array, index as Jsize, text);
        }
        let argument = Jvalue { l: array };
        (table.call_static_void_method_a)(env, main_class, main, &argument);
        // An uncaught exception from main is described (to a standard error
        // nobody sees from a windowed program) and makes the exit code 1,
        // as the Java launcher reports it.
        let code = if (table.exception_check)(env) != JNI_FALSE {
            (table.exception_describe)(env);
            (table.exception_clear)(env);
            1
        } else {
            0
        };
        // Then, like the Java launcher: let go of this thread and wait for
        // every non-daemon thread the application left running. The
        // application normally ends the process itself before this returns.
        let vm_table = &**vm;
        (vm_table.detach_current_thread)(vm);
        (vm_table.destroy_java_vm)(vm);
        Ok(code)
    }
}

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

fn main() {
    let run = move || -> std::io::Result<i32> {
        let exe = std::env::current_exe()?;
        let root = exe
            .parent()
            .ok_or_else(|| failure("Missing application directory"))?;
        if std::env::args_os().nth(1).is_some_and(|argument| argument == ALLOW_FIREWALL) {
            return allow_firewall(root, &exe);
        }
        launch(root)
    };
    // The runtime's main thread is a thread of known stack size, not the
    // process's first thread, exactly as the Java launcher arranges it.
    let outcome = std::thread::Builder::new()
        .name("main".into())
        .stack_size(MAIN_THREAD_STACK)
        .spawn(run)
        .and_then(|thread| thread.join().map_err(|_| failure("The application thread failed")));
    match outcome {
        Ok(Ok(code)) => std::process::exit(code),
        Ok(Err(error)) | Err(error) => {
            show_error(&format!(
                "Gilune could not start its bundled Java runtime.\n\n{error}"
            ));
            std::process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn firewall_rule_is_private_only_and_replaces_every_rule_for_both_programs() {
        let root = Path::new(r"C:\Users\Pat\AppData\Local\Programs\Gilune");
        let exe = root.join("Gilune.exe");
        let commands: Vec<Vec<String>> = firewall_commands(root, &exe)
            .into_iter()
            .map(|command| command.into_iter().map(|word| word.to_string_lossy().into_owned()).collect())
            .collect();
        assert_eq!(3, commands.len());
        for (command, program) in commands.iter().take(2).zip([
            r"C:\Users\Pat\AppData\Local\Programs\Gilune\runtime\bin\javaw.exe",
            r"C:\Users\Pat\AppData\Local\Programs\Gilune\Gilune.exe",
        ]) {
            assert_eq!(&["advfirewall", "firewall", "delete", "rule", "name=all"], &command[..5]);
            assert_eq!(format!("program={program}"), command[5]);
        }
        let add = commands[2].join(" ");
        assert!(add.starts_with("advfirewall firewall add rule name=Gilune dir=in action=allow enable=yes profile=private program="));
        assert!(add.ends_with(r"\Gilune.exe"));
        assert!(!add.contains("public"));
    }
}
