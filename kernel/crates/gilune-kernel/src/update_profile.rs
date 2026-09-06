//! Build-time and runtime opt-in diagnostics for the complete update path.
//! Never logs source paths, content or values. Spans overlap; do not sum parents
//! with their children. No timers, environment reads or output in normal builds.
#[cfg(feature = "index-profile")]
pub struct Span {
    active: Option<(std::time::Instant, &'static str, String)>,
}
#[cfg(not(feature = "index-profile"))]
pub struct Span;

#[cfg(not(feature = "index-profile"))]
impl Drop for Span {
    #[inline]
    fn drop(&mut self) {}
}

#[inline]
pub fn span(phase: &'static str, namespace: &str) -> Span {
    #[cfg(feature = "index-profile")]
    {
        static ENABLED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
        let enabled = *ENABLED.get_or_init(|| std::env::var_os("GILUNE_PROFILE_INDEX").is_some());
        Span {
            active: enabled.then(|| (std::time::Instant::now(), phase, namespace.to_string())),
        }
    }
    #[cfg(not(feature = "index-profile"))]
    {
        let _ = (phase, namespace);
        Span
    }
}

#[cfg(feature = "index-profile")]
impl Drop for Span {
    fn drop(&mut self) {
        if let Some((start, phase, namespace)) = &self.active {
            let elapsed = start.elapsed().as_secs_f64() * 1000.0;
            eprintln!(
                "UPDATE_PROFILE {}",
                serde_json::json!({"phase":phase,"namespace":namespace,"ms":elapsed})
            );
        }
    }
}
