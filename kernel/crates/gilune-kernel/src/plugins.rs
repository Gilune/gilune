//! The plugin registry (ADR 0011; lifecycle in `05-plugin-system.md`):
//! which plugins this vault knows, what they declare, what they have been
//! granted, and whether they run. This is vault state, not device state,
//! so the app, the CLI and the headless agent read the same answer.
//!
//! The kernel names no plugin and no domain type. A descriptor is data
//! supplied by whoever bundles or installs an implementation; the kernel
//! records it, gates job delivery on it, and answers questions about it.
//! Which capabilities are auto-granted and which need a person's consent
//! is policy, and policy lives in the host, not here.

use crate::store::Store;
use crate::{ids, KernelError, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PluginClass {
    Domain,
    Provider,
    Adapter,
    Projection,
    Index,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PluginProfile {
    Bundled,
    Sandboxed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PluginState {
    Enabled,
    Disabled,
    /// Removed by the user. The row survives as a tombstone so that
    /// re-registering a bundled plugin on the next launch cannot
    /// resurrect what someone deliberately took away.
    Removed,
}

/// One thing a plugin adds to a host surface. The slot name is a contract
/// between the plugin and the shell (ADR 0006, ADR 0011); the kernel
/// treats it as opaque.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contribution {
    pub slot: String,
    pub id: String,
    pub label: String,
    /// Contribution-private payload, canonical JSON by convention and
    /// opaque here exactly as `settings` is (ADR 0015 § 5). Three strings
    /// cannot express "a decoration matching this regex", and smuggling a
    /// payload into `id` or `label` is not a seam. Empty is the default and
    /// the reading of a manifest written before this field existed.
    pub config: String,
}

/// What an implementation declares about itself — the subset of the
/// manifest the kernel needs in order to schedule it and to let a person
/// see what it does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginDescriptor {
    pub plugin_id: String,
    pub name: String,
    pub class: PluginClass,
    pub profile: PluginProfile,
    /// A required plugin cannot be removed. The Markdown vault projection
    /// is the only one: the vault is the product surface.
    pub required: bool,
    /// The processor this plugin runs, which is also its subscription id.
    pub processor_id: Option<String>,
    /// Event type the processor consumes. The jobs table keys
    /// subscriptions by id, so v0 models exactly one per processor;
    /// several handlers per plugin arrive with the sandboxed profile.
    pub subscribes: Option<String>,
    pub emits: Vec<String>,
    /// Manifest capability names, e.g. `objects.write`, `network.request`.
    pub capabilities: Vec<String>,
    pub contributions: Vec<Contribution>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstalledPlugin {
    pub descriptor: PluginDescriptor,
    pub state: PluginState,
    /// Capabilities actually granted. A declared capability that is not
    /// here has not been consented to and the host must refuse it.
    pub granted: Vec<String>,
    /// Plugin-private settings document (canonical JSON).
    pub settings: String,
    pub installed_ms: i64,
}

impl InstalledPlugin {
    pub fn is_granted(&self, capability: &str) -> bool {
        self.granted.iter().any(|c| c == capability)
    }

    /// Declared but not granted — what a person still has to agree to.
    pub fn ungranted(&self) -> Vec<String> {
        self.descriptor
            .capabilities
            .iter()
            .filter(|c| !self.is_granted(c))
            .cloned()
            .collect()
    }
}

impl Store {
    /// Record an implementation that exists here. Idempotent, and safe to
    /// call on every launch: an existing plugin has its declaration
    /// refreshed but keeps its state, settings and grants, so an update
    /// cannot silently widen what it may do (supply-chain rule), and a
    /// removed plugin stays removed. Returns the state the plugin is in,
    /// so a caller knows whether to initialise it.
    pub fn register_plugin(&self, descriptor: &PluginDescriptor) -> Result<PluginState> {
        let existing = self.plugin_state(&descriptor.plugin_id)?;
        let state = existing.unwrap_or(PluginState::Enabled);
        let manifest = manifest_json(descriptor);
        match existing {
            Some(_) => {
                self.conn().execute(
                    "UPDATE plugins
                     SET name = ?2, class = ?3, profile = ?4, required = ?5,
                         processor_id = ?6, manifest = ?7
                     WHERE plugin_id = ?1",
                    rusqlite::params![
                        descriptor.plugin_id,
                        descriptor.name,
                        descriptor.class.as_str(),
                        descriptor.profile.as_str(),
                        descriptor.required as i64,
                        descriptor.processor_id,
                        manifest,
                    ],
                )?;
            }
            None => {
                self.conn().execute(
                    "INSERT INTO plugins
                     (plugin_id, name, class, profile, required, processor_id,
                      manifest, settings, state, installed_ms)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, '{}', 'enabled', ?8)",
                    rusqlite::params![
                        descriptor.plugin_id,
                        descriptor.name,
                        descriptor.class.as_str(),
                        descriptor.profile.as_str(),
                        descriptor.required as i64,
                        descriptor.processor_id,
                        manifest,
                        ids::now_ms(),
                    ],
                )?;
            }
        }
        self.apply_subscription(descriptor, state)?;
        Ok(state)
    }

    /// Every plugin this vault knows about, tombstones excluded.
    pub fn installed_plugins(&self) -> Result<Vec<InstalledPlugin>> {
        let mut stmt = self.conn().prepare(
            "SELECT plugin_id, name, class, profile, required, processor_id,
                    manifest, settings, state, installed_ms
             FROM plugins WHERE state != 'removed' ORDER BY name",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(PluginRow {
                plugin_id: row.get(0)?,
                name: row.get(1)?,
                class: row.get(2)?,
                profile: row.get(3)?,
                required: row.get::<_, i64>(4)? != 0,
                processor_id: row.get(5)?,
                manifest: row.get(6)?,
                settings: row.get(7)?,
                state: row.get(8)?,
                installed_ms: row.get(9)?,
            })
        })?;
        let mut out = Vec::new();
        for row in rows {
            out.push(self.hydrate(row?)?);
        }
        Ok(out)
    }

    pub fn plugin(&self, plugin_id: &str) -> Result<Option<InstalledPlugin>> {
        Ok(self
            .installed_plugins()?
            .into_iter()
            .find(|p| p.descriptor.plugin_id == plugin_id))
    }

    /// Enable or disable a plugin. Disabling parks its waiting jobs rather
    /// than destroying them: nothing claims them while it is off, and
    /// enabling resumes exactly where the work stopped.
    pub fn set_plugin_enabled(&self, plugin_id: &str, enabled: bool) -> Result<()> {
        let plugin = self.require_plugin(plugin_id)?;
        let state = if enabled { PluginState::Enabled } else { PluginState::Disabled };
        self.conn().execute(
            "UPDATE plugins SET state = ?2 WHERE plugin_id = ?1",
            rusqlite::params![plugin_id, state.as_str()],
        )?;
        self.apply_subscription(&plugin.descriptor, state)?;
        Ok(())
    }

    /// Remove a plugin: its grants and settings go, its waiting jobs are
    /// parked, and everything it ever created stays in the vault as
    /// ordinary user data (lifecycle step 10). Device-local assets a
    /// plugin downloaded are the host's to delete — the kernel does not
    /// know they exist. A required plugin is refused.
    pub fn remove_plugin(&self, plugin_id: &str) -> Result<()> {
        let plugin = self.require_plugin(plugin_id)?;
        if plugin.descriptor.required {
            return Err(KernelError::PluginRequired {
                plugin_id: plugin_id.to_string(),
            });
        }
        let conn = self.conn();
        conn.execute_batch("BEGIN IMMEDIATE;")?;
        let done: Result<()> = (|| {
            conn.execute("DELETE FROM plugin_grants WHERE plugin_id = ?1", [plugin_id])?;
            conn.execute(
                "UPDATE plugins SET state = 'removed', settings = '{}' WHERE plugin_id = ?1",
                [plugin_id],
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
        self.apply_subscription(&plugin.descriptor, PluginState::Removed)
    }

    /// Re-install a plugin the user removed. Grants start empty again:
    /// consent given before a removal is not consent given after it.
    pub fn reinstall_plugin(&self, plugin_id: &str) -> Result<()> {
        let row = self.plugin_row(plugin_id)?.ok_or_else(|| KernelError::NoSuchPlugin {
            plugin_id: plugin_id.to_string(),
        })?;
        let plugin = self.hydrate(row)?;
        self.conn().execute(
            "UPDATE plugins SET state = 'enabled' WHERE plugin_id = ?1",
            [plugin_id],
        )?;
        self.apply_subscription(&plugin.descriptor, PluginState::Enabled)
    }

    /// Grant a declared capability. Granting one the descriptor does not
    /// declare is refused: consent is to what was disclosed.
    pub fn grant_capability(&self, plugin_id: &str, capability: &str) -> Result<()> {
        let plugin = self.require_plugin(plugin_id)?;
        if !plugin.descriptor.capabilities.iter().any(|c| c == capability) {
            return Err(KernelError::UndeclaredCapability {
                plugin_id: plugin_id.to_string(),
                capability: capability.to_string(),
            });
        }
        self.conn().execute(
            "INSERT OR IGNORE INTO plugin_grants (plugin_id, capability, granted_ms)
             VALUES (?1, ?2, ?3)",
            rusqlite::params![plugin_id, capability, ids::now_ms()],
        )?;
        Ok(())
    }

    pub fn revoke_capability(&self, plugin_id: &str, capability: &str) -> Result<()> {
        self.conn().execute(
            "DELETE FROM plugin_grants WHERE plugin_id = ?1 AND capability = ?2",
            [plugin_id, capability],
        )?;
        Ok(())
    }

    /// Whether a plugin may do something right now. False for a disabled
    /// or removed plugin whatever its grants say, so a caller need only
    /// ask this one question.
    pub fn is_granted(&self, plugin_id: &str, capability: &str) -> Result<bool> {
        let Some(plugin) = self.plugin(plugin_id)? else { return Ok(false) };
        Ok(plugin.state == PluginState::Enabled && plugin.is_granted(capability))
    }

    /// Replace a plugin's private settings document.
    pub fn set_plugin_settings(&self, plugin_id: &str, settings: &str) -> Result<()> {
        let _: serde_json::Value = serde_json::from_str(settings)?;
        let changed = self.conn().execute(
            "UPDATE plugins SET settings = ?2 WHERE plugin_id = ?1 AND state != 'removed'",
            [plugin_id, settings],
        )?;
        if changed == 0 {
            return Err(KernelError::NoSuchPlugin {
                plugin_id: plugin_id.to_string(),
            });
        }
        Ok(())
    }

    fn require_plugin(&self, plugin_id: &str) -> Result<InstalledPlugin> {
        self.plugin(plugin_id)?.ok_or_else(|| KernelError::NoSuchPlugin {
            plugin_id: plugin_id.to_string(),
        })
    }

    fn plugin_state(&self, plugin_id: &str) -> Result<Option<PluginState>> {
        let raw: Option<String> = self
            .conn()
            .query_row(
                "SELECT state FROM plugins WHERE plugin_id = ?1",
                [plugin_id],
                |row| row.get(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(e),
            })?;
        raw.map(|s| PluginState::parse(&s)).transpose()
    }

    fn plugin_row(&self, plugin_id: &str) -> Result<Option<PluginRow>> {
        self.conn()
            .query_row(
                "SELECT plugin_id, name, class, profile, required, processor_id,
                        manifest, settings, state, installed_ms
                 FROM plugins WHERE plugin_id = ?1",
                [plugin_id],
                |row| {
                    Ok(PluginRow {
                        plugin_id: row.get(0)?,
                        name: row.get(1)?,
                        class: row.get(2)?,
                        profile: row.get(3)?,
                        required: row.get::<_, i64>(4)? != 0,
                        processor_id: row.get(5)?,
                        manifest: row.get(6)?,
                        settings: row.get(7)?,
                        state: row.get(8)?,
                        installed_ms: row.get(9)?,
                    })
                },
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                e => Err(KernelError::from(e)),
            })
    }

    fn hydrate(&self, row: PluginRow) -> Result<InstalledPlugin> {
        let manifest: serde_json::Value = serde_json::from_str(&row.manifest)?;
        let descriptor = PluginDescriptor {
            plugin_id: row.plugin_id.clone(),
            name: row.name,
            class: PluginClass::parse(&row.class)?,
            profile: PluginProfile::parse(&row.profile)?,
            required: row.required,
            processor_id: row.processor_id,
            subscribes: manifest
                .get("subscribes")
                .and_then(|v| v.as_str())
                .map(str::to_string),
            emits: string_list(&manifest, "emits"),
            capabilities: string_list(&manifest, "capabilities"),
            contributions: contributions_from(&manifest),
        };
        let mut stmt = self
            .conn()
            .prepare("SELECT capability FROM plugin_grants WHERE plugin_id = ?1 ORDER BY capability")?;
        let granted = stmt
            .query_map([&row.plugin_id], |r| r.get::<_, String>(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(InstalledPlugin {
            descriptor,
            state: PluginState::parse(&row.state)?,
            granted,
            settings: row.settings,
            installed_ms: row.installed_ms,
        })
    }

    /// Keep the job subscription in step with the plugin's state. This is
    /// where disabling and removal take effect: while a plugin is off
    /// nothing new is queued for it and jobs already queued are claimable
    /// by nobody, so enabling resumes work that was waiting. Events that
    /// passed while it was off are not backfilled — re-running them is a
    /// deliberate action, not a side effect of switching a plugin on.
    ///
    /// One statement, because two processes may register concurrently and
    /// a disabled plugin must never flicker into life between them.
    fn apply_subscription(&self, descriptor: &PluginDescriptor, state: PluginState) -> Result<()> {
        let (Some(processor_id), Some(event_type)) =
            (&descriptor.processor_id, &descriptor.subscribes)
        else {
            return Ok(());
        };
        self.conn().execute(
            "INSERT INTO subscriptions (subscription_id, event_type, enabled)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(subscription_id)
             DO UPDATE SET event_type = ?2, enabled = ?3",
            rusqlite::params![processor_id, event_type, (state == PluginState::Enabled) as i64],
        )?;
        Ok(())
    }
}

struct PluginRow {
    plugin_id: String,
    name: String,
    class: String,
    profile: String,
    required: bool,
    processor_id: Option<String>,
    manifest: String,
    settings: String,
    state: String,
    installed_ms: i64,
}

fn manifest_json(descriptor: &PluginDescriptor) -> String {
    serde_json::json!({
        "subscribes": descriptor.subscribes,
        "emits": descriptor.emits,
        "capabilities": descriptor.capabilities,
        "contributions": descriptor
            .contributions
            .iter()
            .map(|c| {
                serde_json::json!({
                    "slot": c.slot,
                    "id": c.id,
                    "label": c.label,
                    "config": c.config,
                })
            })
            .collect::<Vec<_>>(),
    })
    .to_string()
}

fn string_list(manifest: &serde_json::Value, key: &str) -> Vec<String> {
    manifest
        .get(key)
        .and_then(|v| v.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|v| v.as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default()
}

fn contributions_from(manifest: &serde_json::Value) -> Vec<Contribution> {
    manifest
        .get("contributions")
        .and_then(|v| v.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| {
                    Some(Contribution {
                        slot: item.get("slot")?.as_str()?.to_string(),
                        id: item.get("id")?.as_str()?.to_string(),
                        label: item.get("label")?.as_str()?.to_string(),
                        // Absent in every manifest written before ADR 0015,
                        // and absent is empty — no migration, because a
                        // contribution has never been a row.
                        config: item
                            .get("config")
                            .and_then(|v| v.as_str())
                            .unwrap_or_default()
                            .to_string(),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

macro_rules! string_enum {
    ($type:ty { $($variant:ident => $text:literal),+ $(,)? }) => {
        impl $type {
            pub fn as_str(self) -> &'static str {
                match self { $(Self::$variant => $text),+ }
            }

            fn parse(raw: &str) -> Result<Self> {
                match raw {
                    $($text => Ok(Self::$variant),)+
                    other => Err(KernelError::Corrupt {
                        detail: format!(
                            "plugin registry holds {other:?}, which is not a {}",
                            stringify!($type)
                        ),
                    }),
                }
            }
        }
    };
}

string_enum!(PluginClass {
    Domain => "domain",
    Provider => "provider",
    Adapter => "adapter",
    Projection => "projection",
    Index => "index",
});

string_enum!(PluginProfile {
    Bundled => "bundled",
    Sandboxed => "sandboxed",
});

string_enum!(PluginState {
    Enabled => "enabled",
    Disabled => "disabled",
    Removed => "removed",
});

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capture::SOURCE_READY_EVENT;
    use crate::processor::FakeTranscriber;

    fn transcription() -> PluginDescriptor {
        PluginDescriptor {
            plugin_id: "org.memory.transcription".to_string(),
            name: "Local transcription".to_string(),
            class: PluginClass::Provider,
            profile: PluginProfile::Bundled,
            required: false,
            processor_id: Some("transcribe".to_string()),
            subscribes: Some(SOURCE_READY_EVENT.to_string()),
            emits: vec!["org.memory.transcript-ready".to_string()],
            capabilities: vec!["blobs.read".to_string(), "network.request".to_string()],
            contributions: vec![Contribution {
                slot: "note.actions".to_string(),
                id: "transcribe-again".to_string(),
                label: "Transcribe again".to_string(),
                config: String::new(),
            }],
        }
    }

    fn vault_projection() -> PluginDescriptor {
        PluginDescriptor {
            plugin_id: "org.memory.vault".to_string(),
            name: "Markdown vault".to_string(),
            class: PluginClass::Projection,
            profile: PluginProfile::Bundled,
            required: true,
            processor_id: None,
            subscribes: None,
            emits: Vec::new(),
            capabilities: Vec::new(),
            contributions: Vec::new(),
        }
    }

    fn store_with_wav() -> (tempfile::TempDir, Store, String) {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.register_plugin(&transcription()).expect("register");
        let receipt = store.capture_bytes("wav", &[7u8; 64]).expect("capture");
        (dir, store, receipt.source_id)
    }

    #[test]
    fn registering_declares_the_plugin_and_subscribes_its_processor() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        assert_eq!(
            store.register_plugin(&transcription()).expect("register"),
            PluginState::Enabled
        );
        let plugins = store.installed_plugins().expect("list");
        assert_eq!(plugins.len(), 1);
        assert_eq!(plugins[0].descriptor, transcription());
        assert_eq!(plugins[0].granted, Vec::<String>::new(), "nothing is granted by default");
        assert_eq!(plugins[0].ungranted().len(), 2);

        store.capture_bytes("wav", &[1u8; 32]).expect("capture");
        let jobs: i64 = store
            .conn()
            .query_row("SELECT COUNT(*) FROM jobs", [], |row| row.get(0))
            .expect("jobs");
        assert_eq!(jobs, 1, "the manifest's subscription is what schedules work");
    }

    #[test]
    fn re_registering_refreshes_the_declaration_but_never_the_grants() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.register_plugin(&transcription()).expect("register");
        store.grant_capability("org.memory.transcription", "blobs.read").expect("grant");

        let mut updated = transcription();
        updated.name = "Local transcription (v2)".to_string();
        updated.capabilities.push("objects.write".to_string());
        store.register_plugin(&updated).expect("re-register");

        let plugin = store.plugin("org.memory.transcription").expect("get").expect("present");
        assert_eq!(plugin.descriptor.name, "Local transcription (v2)");
        assert_eq!(plugin.granted, vec!["blobs.read".to_string()]);
        assert!(
            plugin.ungranted().contains(&"objects.write".to_string()),
            "an update cannot silently widen what a plugin may do"
        );
    }

    #[test]
    fn a_disabled_plugin_parks_its_jobs_and_enabling_resumes_them() {
        let (_dir, store, _source_id) = store_with_wav();
        store.set_plugin_enabled("org.memory.transcription", false).expect("disable");

        let parked = store
            .run_pending_jobs(&[&FakeTranscriber], 1_000, 30_000)
            .expect("run while disabled");
        assert_eq!(parked.completed, 0, "nothing runs for a disabled plugin");
        assert_eq!(store.status().expect("status").jobs_pending, 1, "the work is kept");

        store.set_plugin_enabled("org.memory.transcription", true).expect("enable");
        let resumed = store
            .run_pending_jobs(&[&FakeTranscriber], 2_000, 30_000)
            .expect("run once enabled");
        assert_eq!(resumed.completed, 1, "the parked job resumes where it stopped");
        assert_eq!(resumed.outputs, 1);
    }

    #[test]
    fn removing_a_plugin_keeps_what_it_created_and_drops_what_it_was_given() {
        let (_dir, store, source_id) = store_with_wav();
        store.grant_capability("org.memory.transcription", "blobs.read").expect("grant");
        store.run_pending_jobs(&[&FakeTranscriber], 1_000, 30_000).expect("run");

        store.remove_plugin("org.memory.transcription").expect("remove");

        assert!(store.installed_plugins().expect("list").is_empty());
        assert!(!store.is_granted("org.memory.transcription", "blobs.read").expect("grant check"));
        assert!(
            store.object_text(&source_id).expect("text").is_some(),
            "what a plugin wrote into a note is the user's, not the plugin's"
        );
        assert_eq!(store.revision_count(&source_id).expect("revisions"), 2);
    }

    #[test]
    fn a_removed_plugin_is_not_resurrected_by_the_next_launch() {
        let dir = tempfile::tempdir().expect("tempdir");
        {
            let store = Store::open(dir.path()).expect("open");
            store.register_plugin(&transcription()).expect("register");
            store.remove_plugin("org.memory.transcription").expect("remove");
        }
        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(
            store.register_plugin(&transcription()).expect("re-register"),
            PluginState::Removed,
            "bundling an implementation is not consent to run it again"
        );
        assert!(store.installed_plugins().expect("list").is_empty());

        store.reinstall_plugin("org.memory.transcription").expect("reinstall");
        let plugin = store.plugin("org.memory.transcription").expect("get").expect("present");
        assert_eq!(plugin.state, PluginState::Enabled);
        assert!(plugin.granted.is_empty(), "consent before a removal is not consent after it");
    }

    #[test]
    fn the_vault_projection_cannot_be_removed() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.register_plugin(&vault_projection()).expect("register");
        assert!(matches!(
            store.remove_plugin("org.memory.vault"),
            Err(KernelError::PluginRequired { .. })
        ));
    }

    #[test]
    fn consent_is_to_what_was_disclosed() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.register_plugin(&transcription()).expect("register");
        assert!(matches!(
            store.grant_capability("org.memory.transcription", "objects.write"),
            Err(KernelError::UndeclaredCapability { .. })
        ));
        store.grant_capability("org.memory.transcription", "network.request").expect("grant");
        assert!(store
            .is_granted("org.memory.transcription", "network.request")
            .expect("granted"));
        store.set_plugin_enabled("org.memory.transcription", false).expect("disable");
        assert!(
            !store
                .is_granted("org.memory.transcription", "network.request")
                .expect("check"),
            "a disabled plugin may do nothing, whatever it was granted"
        );
    }

    #[test]
    fn a_contribution_carries_its_config_across_a_reopen_and_the_kernel_never_reads_it() {
        let dir = tempfile::tempdir().expect("tempdir");
        let mut declared = transcription();
        declared.contributions.push(Contribution {
            slot: "editor.decorations".to_string(),
            id: "due-date".to_string(),
            label: "Due date".to_string(),
            config: r#"{"key":"due","pattern":"\\sdue:\\d{4}-\\d{2}-\\d{2}\\b"}"#.to_string(),
        });
        {
            let store = Store::open(dir.path()).expect("open");
            store.register_plugin(&declared).expect("register");
        }
        let store = Store::open(dir.path()).expect("reopen");
        let plugin = store.plugin("org.memory.transcription").expect("get").expect("present");
        assert_eq!(
            plugin.descriptor.contributions, declared.contributions,
            "config round-trips through the manifest byte for byte"
        );
        assert_eq!(
            plugin.descriptor.contributions[0].config, "",
            "a contribution that declares no config reads back as empty"
        );
    }

    #[test]
    fn a_manifest_written_before_config_existed_still_parses() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        store.register_plugin(&transcription()).expect("register");
        // Exactly what `manifest_json` produced before ADR 0015: a
        // contribution is three strings. Contributions are serialised
        // inside the manifest column, never rows, so an older vault needs
        // no migration — the absent key reads as empty.
        store
            .conn()
            .execute(
                "UPDATE plugins SET manifest = ?2 WHERE plugin_id = ?1",
                rusqlite::params![
                    "org.memory.transcription",
                    r#"{"subscribes":"org.memory.source-ready","emits":[],"capabilities":[],
                        "contributions":[{"slot":"note.actions","id":"transcribe-again",
                                          "label":"Transcribe again"}]}"#,
                ],
            )
            .expect("write an old manifest");

        let plugin = store.plugin("org.memory.transcription").expect("get").expect("present");
        assert_eq!(
            plugin.descriptor.contributions,
            vec![Contribution {
                slot: "note.actions".to_string(),
                id: "transcribe-again".to_string(),
                label: "Transcribe again".to_string(),
                config: String::new(),
            }],
            "an old serialisation opens clean and keeps the contribution it held"
        );
    }

    #[test]
    fn settings_survive_a_reopen_and_must_be_a_json_document() {
        let dir = tempfile::tempdir().expect("tempdir");
        {
            let store = Store::open(dir.path()).expect("open");
            store.register_plugin(&transcription()).expect("register");
            assert!(store
                .set_plugin_settings("org.memory.transcription", "not json")
                .is_err());
            store
                .set_plugin_settings("org.memory.transcription", r#"{"model":"tiny"}"#)
                .expect("settings");
        }
        let store = Store::open(dir.path()).expect("reopen");
        let plugin = store.plugin("org.memory.transcription").expect("get").expect("present");
        assert_eq!(plugin.settings, r#"{"model":"tiny"}"#);
    }
}
