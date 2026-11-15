# DastYab v0.1.7 — Release Test Report

**Date:** 2026-11-15
**Repository:** <https://github.com/DastYab/dastyab>
**Release:** <https://github.com/DastYab/dastyab/releases/tag/v0.1.7>
**Scope:** full workspace test pass (core, CLI, TUI, GUI, Python bridge),
defects found and fixed, then release with GitHub.

---

## 1. Outcome

| Item | Result |
|------|--------|
| Commit range tested | `dd013a7` → `7cfeed9`, then `b4da01b`, `51c3fd0` |
| Rust tests | **1149 passed, 0 failed, 1 ignored** |
| Python bridge tests | **24 passed** locally; **21 passed, 3 skipped** in CI |
| Workspace gates (fmt / clippy / doc) | **pass, 0 warnings** |
| Platform builds | **4/4 green** (linux-amd64, linux-arm64, macos-arm64, windows-amd64) |
| GitHub Release | **published**, 19 assets, checksums verified |
| CI on `main` | **green** — first time; the four prior runs all failed |

---

## 2. Defects found and fixed

Every item below was found by this session's testing, fixed, covered by a
regression test where the defect was behavioural, and shipped in v0.1.7.

### 2.1 Destructive profile commands acted on a typo (security)

`profile_dir()` falls back to the `default` profile for unknown ids. That is a
sensible read-path convenience, but it silently redirected *named* mutations:

- `profile-delete typoXYZ` deleted the user's **default profile directory —
  tokens included** — while reporting success for the typo'd id.
- `profile-rename work w2` rewrote the **default** profile and persisted it
  under the phantom id `work`.
- `profile-export <typo>` exported the default profile under a wrong name.

Fixed by requiring a real profile id in `delete_profile`, `rename_profile` and
`export_profile` before any disk access. The error names the profiles that do
exist, because the usual cause is passing a *name* (`work`) where an *id*
(`74bea9c2aed6`) is expected. `default` is the reserved, lazily-materialized
profile and always validates — that distinction matters: `backup create`
legitimately exports `default` before anything has been written to it, and an
over-eager guard broke exactly that (caught by
`ops::backup::tests::delete_backup_removes_file_in_dir`).

**Tests:** `rename_rejects_unknown_id_instead_of_rewriting_default`,
`delete_rejects_unknown_id_instead_of_removing_default`.

### 2.2 TUI panicked when stdin was not a terminal

`ratatui::init()` aborted with `failed to initialize terminal: Os { code: 6 }`
and a raw backtrace under cron, CI, or a pipe. It now explains that it is an
interactive dashboard that needs a tty and exits 1.

**Verified:** piped stdin → clean message + exit 1; under a real pty → renders.

### 2.3 Twelve broken rustdoc links, three unclosed HTML tags

`cargo doc --workspace` was not warning-free: 12 unresolved intra-doc links
(renamed items, method links, and rustls items behind the `crypto` module),
1 redundant explicit target, and 3 unclosed HTML tags — the last caused by
angle brackets in clap help text (`http://127.0.0.1:<port>/subscription`),
which rustdoc parses as HTML.

### 2.4 Flaky test: process-global `HOME` mutated without the env lock

`test_ensure_daemon_installed_writes_unit_once` swapped the process-global
`HOME` while the parallel onboarding tests resolved their systemd-unit path
against it, so the second `AlreadyInstalled` assertion intermittently read a
swapped-out home. Reproduced ~1 run in 4 (7 tests failing together); it now
takes the same lock the other env-mutating tests already held. **10/10 stress
runs green.**

### 2.5 Test helpers leaked a `/tmp` directory per run

`test_mem_dir()` (generated configs) and `tmp_home()` (ledger guard) built
`dyb_gencfg_*` / `dyb_ledger_guard_*` paths under `/tmp` and never removed
them — **462 directories** had accumulated on this machine. Both now return a
`TempDir` guard that deletes the tree on drop, including when an assertion
unwinds. Verified: full suite leaves 0 new directories.

### 2.6 CI-only failure: no-clobber test required an xray core

`test_build_pool_keeps_pool_when_batch_is_dead` asserted the word "unchanged"
appears in the abort message. On a machine without an xray core,
`find_xray()` aborts first with "No xray core found", so the assertion failed.
Every CI runner ships no xray, so this test passed on any developer machine
that has one and **failed on all four prior CI runs**. The guarantee under
test — a failed rebuild never clobbers the pool — holds in both cases; the
test now accepts either message and still asserts the pool keeps its entry.

**Verified twice:** with xray on `PATH` (24 passed) and with xray hidden
(24 passed), reproducing the exact CI condition.

---

## 3. Test matrix

### 3.1 Static gates

| Gate | Command | Result |
|------|---------|--------|
| Formatting | `cargo fmt --all --check` | pass |
| Lints | `cargo clippy --workspace --all-targets -- -D warnings` | pass, 0 warnings |
| Docs | `cargo doc --no-deps --workspace` | 0 warnings |
| Project gate | `scripts/verify.sh` | **PASS=6 FAIL=0** |

### 3.2 Automated tests

| Suite | Count | Result |
|-------|-------|--------|
| `dastyab-core` unit | 1093 | pass (1 ignored) |
| `dastyab-core` integration (`path_regression`) | 4 | pass |
| `dastyab-gui` unit | 37 | pass |
| `dastyab-cli` unit | 13 | pass |
| Doc-tests | 2 | pass |
| **Rust total** | **1149** | **0 failed** |
| `tests/test_aistudio.py` (pytest) | 24 | pass (3 skip in CI without xray) |

### 3.3 CLI surface (release binary, isolated `HOME`)

| Check | Result |
|-------|--------|
| `--version` | `dastyab 0.1.7` |
| Top-level `--help` | 74 subcommands listed |
| Every subcommand `--help` | 74/74 exit 0 |
| Profile create / list / rename / export / import | pass |
| Typo-guard: rename/delete/export by bad id | exit 1 with helpful error |
| `profile-delete default` | refused, default survives |
| Token add / list (masked) / remove | pass |
| Export → import round-trip | pass |
| `configs list --json` | `[]` (valid JSON) |
| `DASTYAB_LANG=fa` | localized output |
| Unknown command, missing arg | exit 2 (usage) |
| Runtime error paths | exit 1 |
| `dnstt-server --help` | pass |

### 3.4 Interactive frontends

| Check | Result |
|-------|--------|
| TUI, piped stdin | clean message, exit 1 (no panic) |
| TUI, real pty | renders dashboard |
| GUI under Xvfb (15 s) | stays alive, no crash (software GL warnings only) |

### 3.5 Daemon (isolated `HOME`)

| Step | Result |
|------|--------|
| `daemon start` | writes pid file, state updated |
| `daemon status` | `running: yes`, correct pid + uptime |
| `daemon stop` | exits within seconds on the stop file |

### 3.6 aistudio bridge (live network, real pool)

| Check | Result |
|-------|--------|
| `aistudio verify` | 3/3 servers reachable, live egress IPs |
| `aistudio start` | tunnel up, egress `91.239.157.84`, no stale-node failure |
| `aistudio stop` | clean shutdown |
| `aistudio watch 5` | runs and exits cleanly |
| Embedded script marker in binary | present (1) |
| `test_fresh_vless.py` no-arg run | pool checksum **unchanged** (no-clobber guard) |
| Stale processes from the bug report | gone |

### 3.7 Release artifacts (after publication)

| Check | Result |
|-------|--------|
| Downloads `dastyab-linux-amd64` and runs it | `dastyab 0.1.7` |
| SHA-256 vs `SHA256SUMS.txt` | **OK** |
| `.deb` metadata | valid `dastyab-cli 0.1.7-1 amd64` |
| `/releases/latest` redirect | `v0.1.7` |
| `scripts/install.sh` end to end | installed, checksum verified, runs |
| Asset count / sizes | 19 assets, all non-zero |

---

## 4. Coverage limits (what was *not* exercised)

These paths need real external infrastructure and were tested only at the
argument-validation and error-path level:

- `deploy`, `scan`, `scan-many`, `find` — require a live Cloudflare account
  and a real worker upload/probe cycle.
- `zones`, `dns`, `routes`, `accounts`, `verify`, `token-recover`,
  `token-ensure` — require a valid Cloudflare API token.
- `dns-tunnel`, `up` — require a VPS with SSH access.
- `doctor` was run against the real home (it reported live findings), but its
  auto-heal action paths were not triggered.
- Cloudflare-side behaviour (rate limits, API drift, account states) is
  necessarily out of scope for an offline test pass.

No changes were made to your real `~/.dastyab` data: destructive tests ran in
throwaway `HOME` directories, and the aistudio checks probe the existing pool
read-only.

---

## 5. Commits shipped in this release

| Commit | Subject |
|--------|---------|
| `ce9b370` | fix(core,tui): reject unknown profile ids; no tty panic in TUI |
| `9a84729` | docs: fix every rustdoc warning in the workspace |
| `aff9e8c` | fix(tests): serialize the daemon-install test against the env lock |
| `aa586eb` | fix(tests): stop two test helpers leaking temp dirs |
| `7cfeed9` | docs: record the v0.1.7 release session; fix release badge |
| `b4da01b` | fix(tests): let the no-clobber test pass without an xray core |
| `51c3fd0` | docs(bug-readme): record the published v0.1.7 release and green CI |

Tag `v0.1.7` is annotated and points at `7cfeed9`; later commits are
documentation and test-only, and CI is green on `main` at `51c3fd0`.

---

## 6. Follow-ups (not blocking)

1. The release workflow emits deprecation warnings for `actions/*@v4` running
   on Node 24; upgrading to `@v5` silences them.
2. `install-action` cannot fetch `cargo-deb` for `aarch64` and falls back to
   `cargo-binstall`; pinning a version or building the arm64 `.deb` natively
   would remove the warning.
3. `docs/CHANGELOG.md` contains two `v0.1.4` sections from before the rename;
   worth consolidating.
4. The dev machine's real `~/.dastyab` has a stale daemon state file (a
   `--foreground` daemon started outside systemd, no current pid file). This
   is local environment state, not a code defect — `daemon start`/`stop`
   behave correctly in a clean home.
