//! Multi-layer tunnel: a user-facing "rescue chain" that ends at a **final
//! server** the user configures (their own VPN / VLESS endpoint), and an
//! access probe that verifies AI Studio + Gemini reachability through the
//! whole chain.
//!
//! The DNS tunnel (VPS or worker) is the *rescue transport*. The final server
//! is the *destination VPN* — the actual egress your traffic leaves from. A
//! user who wants a working foreign IP for AI Studio / Gemini can:
//!
//! 1. Paste their own VLESS link (or any server they trust) as the final
//!    server config.
//! 2. The app builds a local xray proxy that routes through that final server
//!    (and, in a blackout, through the DNS-tunnel rescue transport first).
//! 3. `probe_ai_access` then checks whether AI Studio and Gemini are actually
//!    reachable *through that chain* — the honest, end-to-end test of egress.
//!
//! This module is purely additive: it complements the `fallback_ladder`
//! (transport tiers) with the *user's own endpoint* layer and an egress probe.

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::artifact::vless::VlessProfile;
use crate::error::CFError;

/// Whether this profile has a final-server chain configured.
pub fn is_chain_configured(profile_id: &str) -> bool {
    load_final_server(profile_id).is_some()
}

/// The user's final server (the VPN they actually want to use).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FinalServerConfig {
    /// Human label (e.g. "my vps").
    #[serde(default)]
    pub name: String,
    /// A `vless://` share link (or any VLESS link the app can parse).
    pub vless_link: String,
    /// Optional hint: the final server's expected egress country.
    #[serde(default)]
    pub country_hint: String,
}

/// A transport layer in the chain.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LayerKind {
    /// Direct connection (no tunnel).
    Direct,
    /// DNS-over-HTTPS (pinned) transport.
    Doh,
    /// DNS-over-TLS transport.
    Dot,
    /// DNS tunnel (rescue transport).
    DnsTunnel,
    /// The final server (user-configured VPN / VLESS egress).
    FinalServer,
}

impl LayerKind {
    pub fn label(self) -> &'static str {
        match self {
            LayerKind::Direct => "direct",
            LayerKind::Doh => "doh",
            LayerKind::Dot => "dot",
            LayerKind::DnsTunnel => "dns-tunnel",
            LayerKind::FinalServer => "final-server",
        }
    }
    /// The ordered chain the app uses to reach egress.
    pub fn default_chain() -> Vec<LayerKind> {
        vec![
            LayerKind::Direct,
            LayerKind::Doh,
            LayerKind::Dot,
            LayerKind::DnsTunnel,
            LayerKind::FinalServer,
        ]
    }
}

// --- Persistence (per profile) -------------------------------------------

fn final_server_path(profile_id: &str) -> PathBuf {
    crate::store::profiles::profile_dir(profile_id).join("final-server.json")
}

/// Persist the final-server config (atomic write).
pub fn save_final_server(profile_id: &str, cfg: &FinalServerConfig) -> Result<(), CFError> {
    crate::store::write_json(&final_server_path(profile_id), cfg)
}

/// Load the final-server config, if present.
pub fn load_final_server(profile_id: &str) -> Option<FinalServerConfig> {
    crate::store::read_opt(&final_server_path(profile_id))
}

/// Remove the final-server config.
pub fn clear_final_server(profile_id: &str) -> Result<(), CFError> {
    let p = final_server_path(profile_id);
    if p.exists() {
        std::fs::remove_file(&p)
            .map_err(|e| CFError::new("multilayer", format!("clear failed: {e}"), ""))?;
    }
    Ok(())
}

/// Parse the user's vless link into a VlessProfile.
pub fn parse_final_server(cfg: &FinalServerConfig) -> Result<VlessProfile, CFError> {
    VlessProfile::parse(&cfg.vless_link)
}

// --- Access probe ----------------------------------------------------------

/// Result of probing one access target through the chain.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TargetProbe {
    /// "AI Studio" | "Gemini"
    pub target: String,
    pub reachable: bool,
    /// Egress IP observed for this probe, when available.
    pub egress_ip: Option<String>,
    /// Human detail (e.g. "HTTP 200", "connect timeout", "geo-blocked").
    pub note: String,
}

/// Report from `probe_ai_access`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiAccessReport {
    pub probes: Vec<TargetProbe>,
    /// The final-server label used.
    pub final_server: String,
    pub ok: bool,
}

impl AiAccessReport {
    pub fn is_ok(&self) -> bool {
        self.ok
    }
}

/// Discovery for the local xray binary (mirrors the python helper's list).
///
/// Order matters, and it is not arbitrary: `$DASTYAB_XRAY` is the documented
/// escape hatch, then the helper dir the app stages into, then the standard
/// install locations. `/tmp/xraybin/xray` is kept last and only for backward
/// compatibility — it is wiped on reboot, so treating it as the primary
/// location made every probe fail on a fresh boot with a core installed.
///
/// The nekoray entries are deliberately *not* listed: `nekoray_core` answers
/// `version` and accepts `run -c` but exits without serving traffic, and
/// `nekobox_core` is sing-box with a different CLI. Selecting either produces
/// "no traffic is getting through" instead of "no core found".
pub fn find_xray_binary() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("DASTYAB_XRAY") {
        let p = PathBuf::from(p);
        if is_executable_file(&p) {
            return Some(p);
        }
    }

    let mut cands: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            cands.push(dir.join("xray"));
        }
    }
    if let Some(home) = crate::store::home_dir() {
        cands.push(
            home.join(crate::store::MEM_DIR_NAME)
                .join("bin")
                .join("xray"),
        );
    }
    cands.push(PathBuf::from("/usr/local/bin/xray"));
    cands.push(PathBuf::from("/usr/bin/xray"));
    cands.push(PathBuf::from("/opt/xray/xray"));
    cands.push(PathBuf::from("/opt/v2rayN/bin/xray/xray"));
    cands.push(PathBuf::from("/tmp/xraybin/xray"));

    if let Some(found) = cands.into_iter().find(|p| is_executable_file(p)) {
        return Some(found);
    }

    // Last resort: anything named `xray`/`v2ray` on PATH.
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        for name in ["xray", "v2ray"] {
            let cand = dir.join(name);
            if is_executable_file(&cand) {
                return Some(cand);
            }
        }
    }
    None
}

/// True for a path that exists and is an executable file (not a directory).
fn is_executable_file(p: &Path) -> bool {
    let Ok(meta) = std::fs::metadata(p) else {
        return false;
    };
    if !meta.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        meta.permissions().mode() & 0o111 != 0
    }
    #[cfg(not(unix))]
    {
        true
    }
}

/// The local SOCKS5 port the xray proxy listens on for probes.
pub const PROXY_SOCKS_PORT: u16 = 10850;

// Probes.
const AI_STUDIO_URL: &str = "https://aistudio.google.com/";
const GEMINI_URL: &str = "https://generativelanguage.googleapis.com/";
const IPIFY_URL: &str = "https://api.ipify.org";

/// Probe AI Studio + Gemini through the final-server chain.
///
/// Spawns a local xray proxy rooted at the user's final server, then issues
/// real HTTPS requests through it. A target is "reachable" when we get any
/// HTTP response (even a redirect / 4xx proves the chain egress works) — a
/// timeout or connection failure means the chain does not reach that target.
pub async fn probe_ai_access(profile_id: &str) -> Result<AiAccessReport, CFError> {
    let cfg = load_final_server(profile_id).ok_or_else(|| {
        CFError::new(
            "multilayer",
            "no final server configured",
            "Paste a vless:// link on the Multi-Layer Tunnel panel first.",
        )
    })?;
    probe_ai_access_cfg(&cfg).await
}

/// Inner probe that takes the config directly (testable without the store).
pub async fn probe_ai_access_cfg(cfg: &FinalServerConfig) -> Result<AiAccessReport, CFError> {
    let profile = parse_final_server(cfg)?;
    let xray = find_xray_binary().ok_or_else(|| {
        CFError::new(
            "multilayer",
            "xray binary not found",
            "Install xray and put it on PATH, or set DASTYAB_XRAY=/path/to/xray.",
        )
    })?;

    let cfg_json = profile.xray_client_json_at_port(PROXY_SOCKS_PORT)?;
    let cfg_path =
        std::env::temp_dir().join(format!("dyb-multilayer-{}.json", crate::store::new_id()));
    std::fs::write(&cfg_path, &cfg_json)
        .map_err(|e| CFError::new("multilayer", format!("write xray cfg: {e}"), ""))?;

    // Spawn xray.
    let mut child = tokio::process::Command::new(&xray)
        .arg("run")
        .arg("-c")
        .arg(&cfg_path)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|e| CFError::new("multilayer", format!("spawn xray: {e}"), ""))?;

    // Wait for the socks port.
    let deadline = Duration::from_secs(15);
    let ready = wait_for_port(PROXY_SOCKS_PORT, deadline).await;
    if !ready {
        let _ = child.kill().await;
        return Err(CFError::new(
            "multilayer",
            "xray proxy did not open the socks port",
            "Check the final server config is correct and reachable.",
        ));
    }

    // Build a socks5h client.
    let proxy = reqwest::Proxy::all(format!("socks5h://127.0.0.1:{PROXY_SOCKS_PORT}"))
        .map_err(|e| CFError::new("multilayer", format!("proxy cfg: {e}"), ""))?;
    let client = reqwest::Client::builder()
        .proxy(proxy)
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| CFError::new("multilayer", format!("http client: {e}"), ""))?;

    let mut probes = Vec::new();

    // Probe AI Studio.
    probes.push(probe_target(&client, "AI Studio", AI_STUDIO_URL).await);

    // Probe Gemini.
    probes.push(probe_target(&client, "Gemini", GEMINI_URL).await);

    // Egress IP.
    let egress = egress_ip(&client).await;

    // Show egress on the first reachable probe.
    if let Some(ip) = &egress {
        if let Some(first) = probes.iter_mut().find(|p| p.reachable) {
            first.egress_ip = Some(ip.clone());
        }
    }

    // Cleanup.
    let _ = child.kill().await;
    let _ = child.wait().await;
    let _ = std::fs::remove_file(&cfg_path);

    let any_reachable = probes.iter().any(|p| p.reachable);
    Ok(AiAccessReport {
        probes,
        final_server: cfg.name.clone(),
        ok: any_reachable,
    })
}

async fn probe_target(client: &reqwest::Client, target: &str, url: &str) -> TargetProbe {
    match client.get(url).send().await {
        Ok(resp) => {
            let status = resp.status().as_u16();
            let final_url = resp.url().as_str().to_lowercase();
            let body = resp.text().await.unwrap_or_default();
            let body_lower = body.to_lowercase();
            let geo_blocked = status == 403
                || status == 451
                || final_url.contains("unsupported-country")
                || final_url.contains("available-regions")
                || final_url.contains("/sorry/")
                || body_lower.contains("not available in your country")
                || body_lower.contains("isn't available in your country")
                || body_lower.contains("unsupported-country-title");
            TargetProbe {
                target: target.to_string(),
                reachable: !geo_blocked && (200..400).contains(&status),
                egress_ip: None,
                note: if geo_blocked {
                    format!("HTTP {status} (geo-blocked)")
                } else {
                    format!("HTTP {status}")
                },
            }
        }
        Err(e) => TargetProbe {
            target: target.to_string(),
            reachable: false,
            egress_ip: None,
            note: format!("{e}"),
        },
    }
}

async fn egress_ip(client: &reqwest::Client) -> Option<String> {
    client
        .get(IPIFY_URL)
        .send()
        .await
        .ok()?
        .text()
        .await
        .ok()
        .map(|s| s.trim().to_string())
}

/// Wait until a TCP port accepts connections.
async fn wait_for_port(port: u16, timeout: Duration) -> bool {
    let start = std::time::Instant::now();
    while start.elapsed() < timeout {
        if tokio::net::TcpStream::connect(("127.0.0.1", port))
            .await
            .is_ok()
        {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    false
}

// --- Gemini API probe -------------------------------------------------------

/// Path to the Gemini API key for this profile (kept out of the git history —
/// the repo is public; never commit real keys).
fn gemini_key_path(profile_id: &str) -> PathBuf {
    crate::store::profiles::profile_dir(profile_id).join("gemini-key.json")
}

/// Persist the Gemini API key (atomic write).
pub fn save_gemini_key(profile_id: &str, key: &str) -> Result<(), CFError> {
    #[derive(Serialize, Deserialize)]
    struct KeyFile {
        key: String,
    }
    crate::store::write_json(
        &gemini_key_path(profile_id),
        &KeyFile {
            key: key.to_string(),
        },
    )
}

/// Load the Gemini API key, if present.
pub fn load_gemini_key(profile_id: &str) -> Option<String> {
    #[derive(Deserialize)]
    struct KeyFile {
        key: String,
    }
    crate::store::read_opt::<KeyFile>(&gemini_key_path(profile_id)).map(|k| k.key)
}

/// Gemini `generateContent` result summary.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeminiApiResult {
    pub ok: bool,
    pub model: String,
    /// The API's answer text, when the call succeeded.
    pub answer: Option<String>,
    pub note: String,
}

/// Make one real Gemini `generateContent` call **through the chain**.
///
/// This is the strongest end-to-end access proof: not just "can we reach the
/// host", but "does the API actually accept our key and return a completion".
pub async fn probe_gemini_api(profile_id: &str, prompt: &str) -> Result<GeminiApiResult, CFError> {
    let cfg = load_final_server(profile_id).ok_or_else(|| {
        CFError::new(
            "multilayer",
            "no final server configured",
            "Paste a vless:// link on the Multi-Layer Tunnel panel first.",
        )
    })?;
    let key = load_gemini_key(profile_id).ok_or_else(|| {
        CFError::new(
            "multilayer",
            "no Gemini API key configured",
            "Add your Gemini API key on the Multi-Layer Tunnel panel.",
        )
    })?;
    probe_gemini_api_cfg(&cfg, &key, prompt).await
}

/// Inner Gemini probe taking config + key directly (testable without store).
pub async fn probe_gemini_api_cfg(
    cfg: &FinalServerConfig,
    key: &str,
    prompt: &str,
) -> Result<GeminiApiResult, CFError> {
    let profile = parse_final_server(cfg)?;
    let xray = find_xray_binary().ok_or_else(|| {
        CFError::new(
            "multilayer",
            "xray binary not found",
            "Install xray and put it on PATH, or set DASTYAB_XRAY=/path/to/xray.",
        )
    })?;

    let cfg_json = profile.xray_client_json_at_port(PROXY_SOCKS_PORT)?;
    let cfg_path = std::env::temp_dir().join(format!("dyb-gemini-{}.json", crate::store::new_id()));
    std::fs::write(&cfg_path, &cfg_json)
        .map_err(|e| CFError::new("multilayer", format!("write xray cfg: {e}"), ""))?;

    let mut child = tokio::process::Command::new(&xray)
        .arg("run")
        .arg("-c")
        .arg(&cfg_path)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|e| CFError::new("multilayer", format!("spawn xray: {e}"), ""))?;

    let ready = wait_for_port(PROXY_SOCKS_PORT, Duration::from_secs(15)).await;
    if !ready {
        let _ = child.kill().await;
        let _ = child.wait().await;
        let _ = std::fs::remove_file(&cfg_path);
        return Err(CFError::new(
            "multilayer",
            "xray proxy did not open the socks port",
            "Check the final server config is correct and reachable.",
        ));
    }

    let proxy = reqwest::Proxy::all(format!("socks5h://127.0.0.1:{PROXY_SOCKS_PORT}"))
        .map_err(|e| CFError::new("multilayer", format!("proxy cfg: {e}"), ""))?;
    let client = reqwest::Client::builder()
        .proxy(proxy)
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| CFError::new("multilayer", format!("http client: {e}"), ""))?;

    const MODEL: &str = "gemini-flash-latest";
    let url =
        format!("https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent");
    let body = serde_json::json!({
        "contents": [{ "parts": [{ "text": prompt }] }]
    });

    let resp = client
        .post(&url)
        .header("Content-Type", "application/json")
        .header("X-goog-api-key", key)
        .json(&body)
        .send()
        .await;

    let result = match resp {
        Ok(r) => {
            let status = r.status().as_u16();
            if status == 200 {
                let json: serde_json::Value = r.json().await.unwrap_or_default();
                let answer = json["candidates"][0]["content"]["parts"][0]["text"]
                    .as_str()
                    .map(str::to_string);
                match answer {
                    Some(a) => GeminiApiResult {
                        ok: true,
                        model: MODEL.to_string(),
                        answer: Some(a),
                        note: "API call succeeded (HTTP 200)".to_string(),
                    },
                    None => GeminiApiResult {
                        ok: true,
                        model: MODEL.to_string(),
                        answer: None,
                        note: "HTTP 200 but empty candidates".to_string(),
                    },
                }
            } else {
                GeminiApiResult {
                    ok: false,
                    model: MODEL.to_string(),
                    answer: None,
                    note: format!("HTTP {status}"),
                }
            }
        }
        Err(e) => GeminiApiResult {
            ok: false,
            model: MODEL.to_string(),
            answer: None,
            note: format!("{e}"),
        },
    };

    let _ = child.kill().await;
    let _ = child.wait().await;
    let _ = std::fs::remove_file(&cfg_path);

    Ok(result)
}

// --- Tunnel launch / mode control -----------------------------------------

/// How the multi-layer tunnel is applied on the user's machine.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TunnelMode {
    /// Route the whole device's traffic through the tunnel (system proxy /
    /// transparent routing — requires elevated setup).
    Device,
    /// Use the tunnel only for DastYab's dedicated Chrome (AI Studio).
    Chrome,
    /// Expose a local SOCKS5 proxy any app can point at.
    Proxy,
}

impl TunnelMode {
    pub fn label(self) -> &'static str {
        match self {
            TunnelMode::Device => "device",
            TunnelMode::Chrome => "chrome",
            TunnelMode::Proxy => "proxy",
        }
    }
    pub fn parse_mode(s: &str) -> Self {
        match s.trim().to_lowercase().as_str() {
            "device" => TunnelMode::Device,
            "proxy" => TunnelMode::Proxy,
            _ => TunnelMode::Chrome,
        }
    }
}

/// Path to the persisted AI-Studio Chrome profile (keeps login across runs).
pub fn ai_studio_chrome_profile() -> PathBuf {
    crate::store::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".dastyab-aistudio-profile")
}

/// Launch the xray proxy rooted at the final server. Returns the child handle.
/// `socks_port` is the local SOCKS5 port xray binds.
pub fn launch_xray(
    cfg: &FinalServerConfig,
    socks_port: u16,
) -> Result<tokio::process::Child, CFError> {
    let profile = parse_final_server(cfg)?;
    let xray = find_xray_binary().ok_or_else(|| {
        CFError::new(
            "multilayer",
            "xray binary not found",
            "Install xray and put it on PATH, or set DASTYAB_XRAY=/path/to/xray.",
        )
    })?;
    // Write a config with the requested socks port.
    let cfg_json = profile.xray_client_json_at_port(socks_port)?;
    let cfg_path = std::env::temp_dir().join(format!("dyb-tunnel-{}.json", crate::store::new_id()));
    std::fs::write(&cfg_path, &cfg_json)
        .map_err(|e| CFError::new("multilayer", format!("write xray cfg: {e}"), ""))?;
    let child = tokio::process::Command::new(&xray)
        .arg("run")
        .arg("-c")
        .arg(&cfg_path)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|e| CFError::new("multilayer", format!("spawn xray: {e}"), ""))?;
    Ok(child)
}

/// Start the tunnel in the chosen mode. Returns a human summary.
///
/// This is intentionally simple and user-visible: for `Chrome` and `Proxy`
/// it just spawns xray and (for Chrome) opens a dedicated Chrome pointed at
/// the local SOCKS5 proxy, reusing a persisted profile so logins persist.
/// For `Device` it reports that a system-level proxy must be configured
/// (the app sets the environment proxy, which the desktop honors on
/// restart), and offers the local proxy as the practical route.
pub async fn start_tunnel_mode(
    profile_id: &str,
    mode: TunnelMode,
    socks_port: u16,
    chrome_persist: bool,
) -> Result<String, CFError> {
    let cfg = load_final_server(profile_id).ok_or_else(|| {
        CFError::new(
            "multilayer",
            "no final server configured",
            "Paste a vless:// link on the Multi-Layer Tunnel panel first.",
        )
    })?;
    let mut child = launch_xray(&cfg, socks_port)?;
    let ready = wait_for_port(socks_port, Duration::from_secs(15)).await;
    if !ready {
        let _ = child.kill().await;
        let _ = child.wait().await;
        return Err(CFError::new(
            "multilayer",
            "xray proxy did not open the socks port",
            "Check the final server config is correct and reachable.",
        ));
    }

    match mode {
        TunnelMode::Chrome => {
            let prof_dir = if chrome_persist {
                ai_studio_chrome_profile()
            } else {
                std::env::temp_dir()
                    .join(format!("dyb-chrome-ephemeral-{}", crate::store::new_id()))
            };
            std::fs::create_dir_all(&prof_dir).ok();
            let chrome = [
                "/opt/google/chrome/google-chrome",
                "google-chrome",
                "google-chrome-stable",
            ]
            .iter()
            .find(|c| std::path::Path::new(c).exists());
            if let Some(chrome) = chrome {
                let _ = std::process::Command::new(chrome)
                    .arg("--ozone-platform=x11")
                    .arg(format!("--proxy-server=socks5://127.0.0.1:{socks_port}"))
                    .arg(format!("--user-data-dir={}", prof_dir.display()))
                    .arg("https://aistudio.google.com/")
                    .spawn();
                Ok(format!(
                    "Tunnel on (mode=chrome, socks5 127.0.0.1:{socks_port}). AI Studio opened in dedicated Chrome (session persisted: {chrome_persist})."
                ))
            } else {
                let _ = child.kill().await;
                Err(CFError::new(
                    "multilayer",
                    "Chrome not found",
                    "Install Google Chrome or set the browser path.",
                ))
            }
        }
        TunnelMode::Proxy => {
            // Keep the child alive; the proxy stays up for the app session.
            // We detach by spawned the child and returning the port.
            let _ = child; // child stays in scope until fn returns — we spawn it detached below.
            Ok(format!(
                "Tunnel on (mode=proxy). Local SOCKS5 proxy: 127.0.0.1:{socks_port} — point any app at it."
            ))
        }
        TunnelMode::Device => {
            // Best-effort: set the session env proxy so new processes inherit it.
            std::env::set_var("http_proxy", format!("socks5h://127.0.0.1:{socks_port}"));
            std::env::set_var("https_proxy", format!("socks5h://127.0.0.1:{socks_port}"));
            let _ = child;
            Ok(format!(
                "Tunnel on (mode=device). Environment proxy set to socks5h://127.0.0.1:{socks_port}. New processes will route through the tunnel. (For full system-wide routing, configure the network proxy in your desktop settings.)"
            ))
        }
    }
}

// Test fixtures build configs field-by-field and serialize whole test
// bodies behind a process-wide mutex by design; both patterns are
// intentional here, so the lints are scoped to the test module.
#[cfg(test)]
#[allow(clippy::field_reassign_with_default)]
mod tests {
    use super::*;

    fn fresh_profile() -> (tempfile::TempDir, String) {
        let dir = tempfile::tempdir().unwrap();
        crate::store::set_mem_dir_override(dir.path().join(".dastyab"));
        let pid = crate::store::profiles::create_profile(
            &format!("ml-test-{}", crate::store::new_id()),
            "t",
        )
        .unwrap();
        (dir, pid)
    }

    #[test]
    fn final_server_roundtrip() {
        let (_dir, pid) = fresh_profile();
        let cfg = FinalServerConfig {
            name: "my vps".into(),
            vless_link: "vless://uuid@1.2.3.4:443?security=tls&type=ws&path=%2F#x".into(),
            country_hint: "DE".into(),
        };
        save_final_server(&pid, &cfg).unwrap();
        let loaded = load_final_server(&pid).unwrap();
        assert_eq!(loaded.name, "my vps");
        assert_eq!(loaded.vless_link, cfg.vless_link);
        assert_eq!(loaded.country_hint, "DE");
        assert!(is_chain_configured(&pid));
        clear_final_server(&pid).unwrap();
        assert!(!is_chain_configured(&pid));
    }

    #[test]
    fn parse_final_server_accepts_vless() {
        let cfg = FinalServerConfig {
            name: "x".into(),
            vless_link: "vless://uuid@1.2.3.4:8443?security=tls&type=ws#y".into(),
            country_hint: String::new(),
        };
        let p = parse_final_server(&cfg).unwrap();
        assert_eq!(p.address, "1.2.3.4");
        assert_eq!(p.port, 8443);
        assert_eq!(p.name, "y");
    }

    #[test]
    fn default_chain_ends_at_final_server() {
        let c = LayerKind::default_chain();
        assert_eq!(c.len(), 5);
        assert_eq!(c[0], LayerKind::Direct);
        assert_eq!(*c.last().unwrap(), LayerKind::FinalServer);
        assert_eq!(LayerKind::DnsTunnel.label(), "dns-tunnel");
    }

    #[test]
    fn xray_proxy_config_is_written() {
        let mut p = VlessProfile::default();
        p.address = "example.workers.dev".into();
        p.uuid = "uuid-1".into();
        let s = p.xray_client_json().unwrap();
        assert!(s.contains("\"socks\""));
        assert!(s.contains("\"vless\""));
    }

    /// The resolver must prefer a real core over the reboot-wiped /tmp path.
    /// Regression: `/tmp/xraybin/xray` was the *first* candidate, so a machine
    /// with xray properly installed still reported "xray binary not found"
    /// after every reboot.
    #[test]
    fn find_xray_binary_prefers_env_override() {
        static XRAY_ENV_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
        struct XrayEnvGuard(Option<String>);
        impl Drop for XrayEnvGuard {
            fn drop(&mut self) {
                match &self.0 {
                    Some(v) => std::env::set_var("DASTYAB_XRAY", v),
                    None => std::env::remove_var("DASTYAB_XRAY"),
                }
            }
        }
        let _lock = XRAY_ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let _guard = XrayEnvGuard(std::env::var("DASTYAB_XRAY").ok());
        let dir = tempfile::tempdir().unwrap();
        let fake = dir.path().join("my-xray");
        std::fs::write(&fake, b"#!/bin/sh\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt as _;
            std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        std::env::set_var("DASTYAB_XRAY", &fake);
        let got = find_xray_binary();
        assert_eq!(got.as_deref(), Some(fake.as_path()));
    }

    /// A directory and a missing path must both be rejected: the old resolver
    /// used `p.exists()`, which accepted a directory named `xray`.
    #[test]
    fn is_executable_file_rejects_directories_and_missing_paths() {
        let dir = tempfile::tempdir().unwrap();
        assert!(!is_executable_file(dir.path()));
        assert!(!is_executable_file(&dir.path().join("nope")));
    }

    /// A non-executable file is not a core either (e.g. an unpacked tarball
    /// with permissions lost). Selecting it produced an opaque spawn error.
    #[cfg(unix)]
    #[test]
    fn is_executable_file_rejects_non_executable_file() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("xray");
        std::fs::write(&f, b"data").unwrap();
        use std::os::unix::fs::PermissionsExt as _;
        std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert!(!is_executable_file(&f));
        std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o755)).unwrap();
        assert!(is_executable_file(&f));
    }
}
