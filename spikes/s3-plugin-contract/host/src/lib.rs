//! Spike S3 host: instantiate the processor component under Wasmtime with a
//! capability-checked import surface, fuel and memory limits, and host-side
//! output policy (size cap, JSON validity). The kernel side of ADR 0003's
//! sandbox claims lives here.

use anyhow::Result;
use std::collections::HashMap;
use std::path::Path;
use std::time::{Duration, Instant};
use wasmtime::component::{Component, Linker};
use wasmtime::{Config, Engine, Store, StoreLimits, StoreLimitsBuilder};

wasmtime::component::bindgen!({
    path: "../wit",
    world: "processor",
});

pub use gilune::processor::types::ProposedOutput;

/// Host policy: the largest payload a processor may return.
pub const MAX_OUTPUT_BYTES: usize = 1024 * 1024;

/// The installed grant: which objects and revisions this plugin may read.
/// Deny-by-default — an empty grant refuses everything.
#[derive(Default, Clone)]
pub struct Grants {
    pub allow_read_source: bool,
    pub allow_read_payload: bool,
    pub sources: HashMap<String, (Vec<u8>, String)>,
    pub payloads: HashMap<String, String>,
}

pub struct HostState {
    pub grants: Grants,
    pub limits: StoreLimits,
    /// Audit trail of capability decisions, as the security-observability
    /// contract requires (redacted: names of operations, not content).
    pub audit: Vec<String>,
    /// A Rust-std guest implicitly imports WASI interfaces (io, clocks,
    /// random, cli); the host grants a deny-by-default context — no
    /// filesystem, no network, no environment, discarded stdio. Anything
    /// more is a capability the manifest would have to declare.
    wasi: wasmtime_wasi::WasiCtx,
    table: wasmtime_wasi::ResourceTable,
}

impl wasmtime_wasi::WasiView for HostState {
    fn ctx(&mut self) -> wasmtime_wasi::WasiCtxView<'_> {
        wasmtime_wasi::WasiCtxView {
            ctx: &mut self.wasi,
            table: &mut self.table,
        }
    }
}

impl gilune::processor::types::Host for HostState {}

impl gilune::processor::host_objects::Host for HostState {
    fn read_source_bytes(&mut self, object_id: String) -> Result<(Vec<u8>, String), String> {
        if !self.grants.allow_read_source {
            self.audit.push("deny read-source-bytes (no grant)".into());
            return Err("capability denied: objects.read (sources)".into());
        }
        match self.grants.sources.get(&object_id) {
            Some((bytes, media)) => {
                self.audit.push("allow read-source-bytes".into());
                Ok((bytes.clone(), media.clone()))
            }
            None => {
                self.audit.push("deny read-source-bytes (out of scope)".into());
                Err("capability denied: object not covered by grant".into())
            }
        }
    }

    fn read_payload(&mut self, revision_id: String) -> Result<String, String> {
        if !self.grants.allow_read_payload {
            self.audit.push("deny read-payload (no grant)".into());
            return Err("capability denied: objects.read (payloads)".into());
        }
        match self.grants.payloads.get(&revision_id) {
            Some(p) => {
                self.audit.push("allow read-payload".into());
                Ok(p.clone())
            }
            None => {
                self.audit.push("deny read-payload (out of scope)".into());
                Err("capability denied: revision not covered by grant".into())
            }
        }
    }
}

/// How a single sandboxed invocation ended.
pub enum RunOutcome {
    /// Handler completed; output already validated against host policy.
    Completed(Option<ProposedOutput>),
    /// Handler returned a domain error (including capability denials it
    /// chose to surface).
    HandlerError(String),
    /// The component trapped (crash, out of fuel, memory limit): contained
    /// by the sandbox, reported with the trap text.
    Trapped(String),
    /// Handler completed but violated host output policy (size, JSON).
    PolicyViolation(String),
}

pub struct RunReport {
    pub outcome: RunOutcome,
    pub elapsed: Duration,
    pub fuel_used: Option<u64>,
    pub audit: Vec<String>,
}

pub struct Sandbox {
    engine: Engine,
    component: Component,
    linker: Linker<HostState>,
}

pub struct RunConfig {
    pub grants: Grants,
    pub fuel: u64,
    pub memory_limit_bytes: usize,
}

impl Default for RunConfig {
    fn default() -> Self {
        RunConfig {
            grants: Grants::default(),
            fuel: 500_000_000,
            memory_limit_bytes: 256 * 1024 * 1024,
        }
    }
}

impl Sandbox {
    pub fn load(component_path: &Path) -> Result<Sandbox> {
        let mut config = Config::new();
        config.consume_fuel(true);
        let engine = Engine::new(&config)?;
        let component = Component::from_file(&engine, component_path).map_err(|e| {
            anyhow::anyhow!("loading component {}: {e}", component_path.display())
        })?;
        let mut linker: Linker<HostState> = Linker::new(&engine);
        Processor::add_to_linker::<_, wasmtime::component::HasSelf<_>>(
            &mut linker,
            |state: &mut HostState| state,
        )?;
        wasmtime_wasi::p2::add_to_linker_sync(&mut linker)?;
        Ok(Sandbox {
            engine,
            component,
            linker,
        })
    }

    /// One isolated invocation: fresh store, fresh instance, enforced limits.
    pub fn run(
        &self,
        cfg: RunConfig,
        handler: &str,
        subject_object: &str,
        subject_revision: &str,
    ) -> Result<RunReport> {
        let state = HostState {
            grants: cfg.grants,
            limits: StoreLimitsBuilder::new()
                .memory_size(cfg.memory_limit_bytes)
                .build(),
            audit: Vec::new(),
            wasi: wasmtime_wasi::WasiCtxBuilder::new().build(),
            table: wasmtime_wasi::ResourceTable::new(),
        };
        let mut store = Store::new(&self.engine, state);
        store.limiter(|s| &mut s.limits);
        store.set_fuel(cfg.fuel)?;

        let start = Instant::now();
        let instance = Processor::instantiate(&mut store, &self.component, &self.linker)?;
        let result = instance.gilune_processor_handler().call_handle(
            &mut store,
            handler,
            subject_object,
            subject_revision,
        );
        let elapsed = start.elapsed();
        let fuel_used = store.get_fuel().ok().map(|left| cfg.fuel.saturating_sub(left));

        let outcome = match result {
            Err(trap) => RunOutcome::Trapped(format!("{trap:#}")),
            Ok(Err(handler_err)) => RunOutcome::HandlerError(handler_err),
            Ok(Ok(output)) => match validate_output(&output) {
                Ok(()) => RunOutcome::Completed(output),
                Err(policy) => RunOutcome::PolicyViolation(policy),
            },
        };
        let audit = store.into_data().audit;
        Ok(RunReport {
            outcome,
            elapsed,
            fuel_used,
            audit,
        })
    }
}

/// Host-side output policy: a completed handler's output must be bounded and
/// well-formed before the kernel would commit anything from it.
fn validate_output(output: &Option<ProposedOutput>) -> std::result::Result<(), String> {
    let Some(out) = output else { return Ok(()) };
    if out.payload_json.len() > MAX_OUTPUT_BYTES {
        return Err(format!(
            "payload too large: {} bytes (max {})",
            out.payload_json.len(),
            MAX_OUTPUT_BYTES
        ));
    }
    if serde_json::from_str::<serde_json::Value>(&out.payload_json).is_err() {
        return Err("payload is not valid JSON".into());
    }
    for (label, v) in [
        ("type-id", &out.type_id),
        ("class", &out.class),
        ("event-type", &out.event_type),
    ] {
        if v.is_empty() || v.len() > 256 {
            return Err(format!("{label} out of bounds"));
        }
    }
    Ok(())
}
