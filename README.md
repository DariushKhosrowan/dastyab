# DastYab · دستیاب

**A self-hosted Cloudflare Workers VLESS VPN console written in Rust — deploy stealth Workers, discover verified clean IPs, export client configs, and chain resilient tunnels from Linux, macOS, Windows, or Android.**

[English](#english) · [فارسی (Persian)](#persian) · [Website (dastyab.dev)](https://dastyab.dev/) · [Releases](https://github.com/DastYab/dastyab/releases/latest) · [Architecture](docs/ARCHITECTURE.md) · [Security](docs/SECURITY.md)

> **دستیاب (DastYab)** — *Persian for “within reach”*: finding the reachable path through network interference and keeping it reachable.

| Specification | Details |
|---|---|
| **Core Engine** | Rust (edition 2021) — `dastyab-core` |
| **Frontends** | CLI (`dastyab`) · Terminal UI (`dastyab-tui`) · Desktop GUI (`dastyab-gui`) · Android APK (`com.dastyab.app`) |
| **Client Exports** | `v2rayN` / `v2rayNG` · `sing-box` · `Clash` / `Clash.Meta` |
| **Storage & Integrity** | Atomic `tempfile + fsync + rename`, `0600` token isolation, SHA-256 profile manifests |
| **License** | MIT © [Dariush Khosrowan](https://khosrowan.ir) |

---

<a id="english"></a>

## English Overview

### What DastYab Does

DastYab turns a single **Cloudflare API token** into an end-to-end **VLESS edge-tunnel control plane** without Python or Node runtime dependencies on desktop/server:

1. **Stealth Worker Deployment** — Deploys an edgetunnel VLESS Cloudflare Worker with anti-bot pacing and per-deploy byte-fingerprint humanization so no two deployments share identical signatures.
2. **Clean-IP Discovery & Autopilot** — Runs a step-0 Worker health gate (`dastyab autopilot health`) before scanning, discovers clean Cloudflare edge IPs via two-phase streaming probes (`find` / `slipscan`), verifies end-to-end tunnels with `sing-box`, and rotates degraded endpoints automatically.
3. **Instant Client Exports** — Generates ready-to-import configs for **v2rayN / v2rayNG**, **sing-box**, and **Clash / Clash.Meta** in one command, tracked across profiles in a SHA-256-verified artifact inventory.
4. **Multi-Layer Tunnel & Region-Locked Google Access** — Built-in isolated bridges for **Google AI Studio / Gemini** (`dastyab aistudio`, SOCKS port `10850`) and **Google Flow** (`dastyab flow`, SOCKS port `10860`), supporting 2-hop proxy chains (`--hops 2`), 151-country gate verification, and dedicated Chrome profile isolation.
5. **Cloudflare Operations & Hardening** — Manage DNS records, zones, worker routes, per-worker usage analytics, portable JSON backups, workspace snapshots (`dastyab workspace snapshot`), and run local security audits (`dastyab security`).
6. **Native Android Companion** — Packages the Rust CLI natively as `libdastyab.so` across `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`, paired with a pure-Kotlin local HTTP/SOCKS5 multi-hop proxy bridge (`LocalProxyServer`).

### 60-Second Quick Start

Install the single static binary (Linux / macOS):

```bash
curl -fsSL https://raw.githubusercontent.com/DastYab/dastyab/main/scripts/install.sh | sh
dastyab --version
```

> **Windows:** Download `dastyab-windows-amd64-portable.zip` (CLI + TUI + GUI) from [Releases](https://github.com/DastYab/dastyab/releases/latest).  
> **Debian / Ubuntu:** Install `dastyab-linux-amd64.deb` with `sudo apt install ./dastyab-linux-amd64.deb`.  
> **Android:** Install `DastYab-*-android.apk` from [Releases](https://github.com/DastYab/dastyab/releases/latest).  
> **From Source:** `cargo build --release --workspace` (requires Rust 1.99+ stable).

Configure and deploy in five commands:

```bash
# 1. Store your Cloudflare API token (mode 0600 in ~/.dastyab/)
dastyab token add cf-main <YOUR_CF_API_TOKEN>

# 2. Verify token permissions and list accounts
dastyab verify && dastyab accounts

# 3. Deploy a VLESS Worker with curated presets
dastyab deploy --preset standard

# 4. Find verified clean Cloudflare IPs (stops once target count passes E2E check)
dastyab find 5

# 5. Export configs for your preferred client
dastyab export clash   --output clash.yaml
dastyab export singbox --output config.json
dastyab export v2rayn  --output xray.json
```

### Four Frontends, One Core

| Frontend | Binary / Package | Best For |
|---|---|---|
| **CLI** | `dastyab` | Scriptable automation, headless servers, cron autopilot, `aistudio` / `flow` bridges |
| **TUI** | `dastyab-tui` | Keyboard-driven terminal dashboard (`ratatui`) over SSH or local shells |
| **Desktop GUI** | `dastyab-gui` | Royal-gold-on-obsidian `eframe/egui` app with Deploy, Profiles, Clean IPs, Configs, and Multi-Layer tabs |
| **Android App** | `com.dastyab.app` | Mobile management, blackout rescue, local HTTP/SOCKS5 proxy chaining, QR sharing |

### Storage & Security Model

All persistent state lives under `~/.dastyab/` with atomic writes (`tempfile` + `fsync` + `rename`):

```text
~/.dastyab/
├── profiles/
│   └── default/
│       ├── profile.json    # profile metadata
│       ├── token.json      # Cloudflare API tokens (mode 0600)
│       ├── accounts.json   # cached Cloudflare account IDs
│       ├── history.json    # generated VLESS links
│       ├── cleanips.json   # verified clean Cloudflare IPs
│       ├── flow_pool.json  # ranked egress routes for Flow / AI Studio
│       └── config.json     # per-profile settings
└── backups/                # portable hash-verified profile bundles
```

Run `dastyab security` to audit file permissions and check for accidental token exposure, and `dastyab manifest-verify` to verify SHA-256 integrity across generated artifacts.

---

<a id="persian"></a>

<div dir="rtl">

## معرفی فارسی (دستیاب — DastYab)

**دستیاب (DastYab)** یک کنسول کامل و متن‌باز برای راه‌اندازی، مدیریت و نگهداری **تانل VLESS روی Cloudflare Workers** است که با زبان **Rust** نوشته شده و بدون نیاز به Python یا Node.js به‌صورت یک فایل اجرایی استاتیک روی **لینوکس، مک، ویندوز** و همچنین به شکل **اپلیکیشن اندروید** عرضه می‌شود.

### چرا دستیاب؟

بسیاری از ابزارهای فیلترشکن یا اسکنرهای آی‌پی تمیز، پراکنده هستند و بعد از مدتی به دلیل محدودیت‌های شبکه یا بلاک شدن دامنه‌ها از کار می‌افتند. دستیاب کل چرخه را در یک هسته یکپارچه جمع کرده است:

- **دیپلوی هوشمند ورکر کلودفلر:** راه‌اندازی ورکر VLESS با الگوی ضدشناسایی (Stealth Pacing) و تغییر اثرانگشت بایتی در هر دیپلوی تا دو نسخه مشابه هم نباشند.
- **اسکن واقعی آی‌پی تمیز + پایش خودکار (Autopilot):** پیش از اسکن، سلامت ورکر بررسی می‌شود تا در صورت مسدودی اکانت کلودفلر وقت شما هدر نرود؛ سپس آی‌پی‌های تمیز با تست واقعی `sing-box` تأیید شده و در صورت افت کیفیت به‌صورت خودکار جایگزین می‌شوند.
- **خروجی آماده برای همه کلاینت‌ها:** تولید مستقیم کانفیگ برای **v2rayN / v2rayNG**، **sing-box** و **Clash / Clash.Meta** همراه با بررسی سلامت فایل‌ها (SHA-256).
- **دسترسی به سرویس‌های تحریم‌شده گوگل:** پل‌های ایزوله داخلی برای **Google AI Studio / Gemini** (پورت `10850`) و **Google Flow** (پورت `10860`) با پشتیبانی از زنجیره دو-پرشی (`--hops 2`) و بررسی کشور خروجی مجاز.
- **امنیت و پشتیبان‌گیری اتمیک:** ذخیره توکن‌ها با سطح دسترسی `0600`، نوشتن اتمیک فایل‌ها بدون خطر خرابی هنگام قطع برق/برنامه، و ممیزی امنیتی داخلی با دستور `dastyab security`.

### شروع سریع در چند دستور

نصب تک‌خطی در لینوکس و macOS:

</div>

```bash
curl -fsSL https://raw.githubusercontent.com/DastYab/dastyab/main/scripts/install.sh | sh
dastyab --version
```

<div dir="rtl">

برای **ویندوز** فایل `dastyab-windows-amd64-portable.zip` (شامل CLI، TUI و محیط گرافیکی GUI) و برای **اندروید** فایل `DastYab-*-android.apk` را از [صفحه Releases](https://github.com/DastYab/dastyab/releases/latest) دریافت کنید.

پس از نصب، با پنج دستور زیر اولین تانل خود را بسازید:

</div>

```bash
# ۱. افزودن توکن API کلودفلر (ذخیره با دسترسی امن 0600)
dastyab token add cf-main <YOUR_CF_API_TOKEN>

# ۲. بررسی اعتبار توکن و لیست اکانت‌ها
dastyab verify && dastyab accounts

# ۳. دیپلوی ورکر VLESS روی کلودفلر
dastyab deploy --preset standard

# ۴. یافتن ۵ آی‌پی تمیزِ تأییدشده با تست واقعی تانل
dastyab find 5

# ۵. دریافت خروجی برای کلاینت دلخواه
dastyab export clash   --output clash.yaml
dastyab export singbox --output config.json
dastyab export v2rayn  --output xray.json
```

<div dir="rtl">

### رابط‌های کاربری دستیاب

- **خط فرمان (`dastyab`):** مناسب برای اسکریپت‌نویسی، سرورها و اجرای خودکار Autopilot و پل‌های `aistudio` / `flow`.
- **رابط ترمینال (`dastyab-tui`):** داشبورد صفحه‌کلیدی سریع در ترمینال (مناسب اتصال SSH).
- **اپلیکیشن دسکتاپ (`dastyab-gui`):** رابط گرافیکی سبک با تم طلایی روی مشکی برای مدیریت پروفایل‌ها، توکن‌ها، اسکن آی‌پی و تانل چندلایه.
- **اپلیکیشن اندروید (`com.dastyab.app`):** همراه با باینری نیتیو `libdastyab.so` برای تمام معماری‌ها (`arm64-v8a`، `armeabi-v7a`، `x86_64`، `x86`) و پل پروکسی محلی HTTP/SOCKS5.

</div>

---

## Documentation

| Document | Coverage |
|---|---|
| [docs/PRINCIPLES.md](docs/PRINCIPLES.md) | Mission, threat model, resilience ladder, engineering standards |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Workspace structure, module boundaries, data flow |
| [docs/MODULES.md](docs/MODULES.md) | Module-by-module guide (`cf`, `probe`, `tunnel`, `artifact`, `mesh`) |
| [docs/API.md](docs/API.md) | Cloudflare v4 API client coverage |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, token isolation, audit checks |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Building, cross-compiling, Android APK & release CI |
| [docs/USER-GUIDE-DNS-TUNNEL.md](docs/USER-GUIDE-DNS-TUNNEL.md) | Blackout DNS tunnel setup guide |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Formatting, clippy, and workspace test requirements |

---

## Contributing & License

Before opening a pull request:

```bash
cargo fmt --all -- --check
cargo clippy --workspace -- -D warnings
cargo test --workspace
```

Released under the **MIT License** © [Dariush Khosrowan](https://khosrowan.ir) · [dastyab.dev](https://dastyab.dev/)
