//! FFI facade for the shared Nuvio core.
//!
//! Phase 0 only proves the boundary: one sync call and one async call, with the
//! Android/KMP binding generation wired up around them. The logic crates
//! (`nuvio-net`, `nuvio-addon`, `nuvio-plugin`, `nuvio-track`,
//! `nuvio-progress`) join this workspace as their migration phase starts — they
//! are deliberately absent until they have real code to hold.

uniffi::setup_scaffolding!();

/// Version of the linked core. Surfaced in the app's diagnostics screen so a
/// mismatch between Kotlin bindings and the packaged `.so` is visible at runtime.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Async smoke test: proves `async fn` → Kotlin `suspend` round-trips on every
/// target before any real async work is built on top of it.
#[uniffi::export]
pub async fn core_self_check() -> String {
    tokio::task::yield_now().await;
    format!("nuvio-core {} ok", env!("CARGO_PKG_VERSION"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_version_has_three_segments() {
        assert_eq!(core_version().split('.').count(), 3);
    }

    #[tokio::test]
    async fn self_check_reports_the_linked_version() {
        assert_eq!(
            core_self_check().await,
            format!("nuvio-core {} ok", core_version())
        );
    }
}
