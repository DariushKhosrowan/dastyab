//! Process-level TLS provider installation.
//!
//! The CLI/GUI binaries link several rustls consumers: the core crate uses
//! `rustls` with the `ring` provider (default-features off), while
//! `reqwest`'s `rustls-tls` feature pulls rustls with its *default* features,
//! which enable a second provider (`ring`). When two providers are compiled in
//! and no process-level provider is installed, any rustls path that uses the
//! ambient "default" provider panics at runtime:
//!
//! ```text
//! Could not automatically determine the process-level CryptoProvider from
//! Rustls crate features.
//! ```
//!
//! This is exactly what happens on `dastyab drill` (which probes the DoT/TLS
//! rungs) and any TLS probe that doesn't build its own `ClientConfig` from an
//! explicit provider. The fix is to install one provider explicitly at process
//! startup — we standardize on `ring` (the core's provider, a widely-used
//! FIPS-capable C implementation).

/// Install `ring` as the process-level rustls provider. Idempotent: if a
/// provider is already installed this is a no-op. Call once, at the very top
/// of `main()` (before any network/TLS work).
pub fn install_tls_provider() {
    let provider = rustls::crypto::ring::default_provider();
    let _ = rustls::crypto::CryptoProvider::install_default(provider);
}
