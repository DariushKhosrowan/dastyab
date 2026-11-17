# Bug Readme — `dastyab aistudio` failing after the Khvarenah → DastYab rename

**File purpose:** one living document for this bug — what it is, why it happens,
where the fix stands right now. Update the *Current state* section whenever
something changes.

**Last updated:** 2026-11-15 (release session — v0.1.7 shipped)

---

## TL;DR

The rename itself did not break `aistudio`. What broke it is a **version-skew
trap** created around the rename:

- The **installed binary is v0.1.6**, which has the **old AI Studio bridge
  script compiled into it** (`include_str!`).
- The **fixed script exists only in the repo working tree, uncommitted** —
  it never shipped in a release.
- Every time the installed v0.1.6 binary runs `dastyab aistudio`, its
  `materialise()` step **overwrites `~/.dastyab/bin/dastyab-aistudio.py` back
  to the old embedded script** — so even a manually staged copy of the fixed
  script gets clobbered, and the bug comes back.
- The old script has two real bugs that produced the exact failures seen in
  the terminal, plus leftover **rename debris** (an 18-hour-old `khvarenah`
  daemon, the old `~/.khvarenah` state dir, a stale `khvarenah` binary on
  PATH, and a stale xray holding port 10850) that made everything look
  "corrupted after renaming".

**Fix in one sentence:** commit the fixed bridge script + the CLI argument
forwarding, rebuild and reinstall the binary, clean up the old-name processes
and state, rebuild the server pool.

---

## What the user saw

```text
$ dastyab aistudio start
[aistudio] Tunnel started via 109.69.218.172:443 (pid 531599)
[aistudio] Local SOCKS5 proxy: 127.0.0.1:10850
[aistudio] Opening AI Studio through the tunnel...
[aistudio] Opened Chrome with the tunnel proxy.
[aistudio] WARNING: the proxy is up but no traffic is getting through — this node looks dead at the far end.
[aistudio] Try another node: dastyab aistudio stop && dastyab aistudio verify, ...
✗ Aistudio failed
  Try another node: dastyab aistudio stop && dastyab aistudio verify, ...

$ dastyab aistudio start
[aistudio] Tunnel already running on 127.0.0.1:10850
[aistudio] Opening AI Studio through the existing tunnel...
[aistudio] Opened Chrome with the tunnel proxy.
✗ Aistudio failed
  exit code Some(3)
```

---

## Root cause, step by step

### 1. Two script versions exist, and the wrong one is running

There are three copies of the bridge script in play:

| Copy | Version | Size | Where |
|---|---|---|---|
| Committed at `HEAD` / shipped in **v0.1.6** | **old, buggy** | 14,822 B | `git show HEAD:dastyab-aistudio.py` |
| Working tree (uncommitted rewrite) | **new, fixed** | 25,947 B | `dastyab-aistudio.py` |
| Staged on disk | currently matches the new one | 25,947 B | `~/.dastyab/bin/dastyab-aistudio.py` |

The installed binary (`~/.local/bin/dastyab`, **v0.1.6**, downloaded
2026-11-15 12:04, minutes before the failing runs) embeds the **old** script.
Verified directly against the binary: it contains the old string
`"Opened Chrome with the tunnel proxy."` — the exact line from the failing
terminal output — and does *not* contain the new replacement string
`"Opened the browser with the tunnel proxy."`.

### 2. The old script's two real bugs (they match the output line by line)

**Bug A — only `pool[0]` is ever tried.**
`start()` binds the tunnel to the first pool entry and nothing else. The pool
(`~/.dastyab/profiles/default/fresh_vless.json`, built 02:53, six entries) had
a dead first node by 12:04 — free-channel nodes die within hours. The old
script then printed the WARNING, **opened the browser anyway**, returned
`False`, and exited 3 → `✗ Aistudio failed`. Five other nodes (one,
`get.jarbook.net`, had a verified egress) were never tried.

**Bug B — "already running" is reported as failure.**
The first failed run left xray alive (pid 531599, still bound to the dead
node, holding SOCKS port 10850 — the old script does not clean up after a
failed egress probe). The second `start` hit the "already running" path, which
**returns `(False, None)` unconditionally** → exit 3 → `exit code Some(3)`,
even though it had just opened the browser through a tunnel it considered up.

### 3. The `materialise()` clobber trap (why it never gets better)

`crates/dastyab-core/src/ops/aistudio.rs` embeds the script with
`include_str!` and, on every `aistudio` run, rewrites
`~/.dastyab/bin/dastyab-aistudio.py` **whenever it differs from the embedded
copy**. That is correct behavior for an upgraded binary — but as long as the
*installed* binary is v0.1.6, every run reverts the staged script to the old
version. The fixed script currently on disk (staged 12:07) will be clobbered
back to the old one on the next `dastyab aistudio` run. **The only way out is
to upgrade the binary.**

### 4. One more bug in the *uncommitted* tree: the CLI drops its argument

`crates/dastyab-cli/src/main.rs` now declares `Aistudio { action, arg }` (pool
index for `start`, seconds for `watch`, URI file for `build-pool`), but
`cmd_aistudio()` in `commands.rs` never forwards `arg` to the Python script.
So `dastyab aistudio build-pool uris.txt` fails with "build-pool needs a
file", and `start <index>` / `watch <secs>` silently ignore their arguments.
This is also the current **clippy failure** (`unused variable: arg`,
`commands.rs:4405`), which makes CI red.

### 5. Rename debris (why it *looked* like the rename corrupted things)

| Debris | Evidence |
|---|---|
| Old `khvarenah` daemon still running | pid 68032, up ~18 h, `khvarenah daemon start --profile default --foreground`, still writing to `~/.khvarenah/` (files touched as recently as 09:34 today) |
| Old `khvarenah` binary still on PATH | dated 2026-11-13 |
| Old state dir alive in parallel | `~/.khvarenah/` (nodes.json 224 KB, daemon log growing) next to `~/.dastyab/` |
| Stale xray from the failed run | pid 531599, config `/tmp/dastyab_aistudio.json`, holding port 10850 |
| Stale dastyab daemon pidfile | `~/.dastyab/profiles/default/daemon.pid` → pid 2072, not running |

None of these *cause* the aistudio failure, but they guarantee confusion:
two daemons from two eras, two state directories, and a port held by a tunnel
to a dead node.

### Why the rename is involved at all

The rename-era commits (`ff30e5b`, `ddef412`, `ea863ae`) introduced the
embed-on-demand mechanism (`include_str!` + `materialise()`) to make `aistudio`
work on installed binaries. v0.1.6 shipped **before** the bridge script's big
fix rewrite landed in the working tree. So the first release that had the
new mechanism also had the old script frozen inside it — and the mechanism
actively defends the old copy. The rename also left the old daemon/binary/
state dir behind, which supplied the "it's corrupted" symptoms.

---

## Current state (update this section as things change)

**As of 2026-11-15, fix applied, gates green, committed & pushed:**

- **Working tree (now committed on `main`):**
  - `dastyab-aistudio.py` — fixed bridge: walks the whole pool skipping dead
    nodes, "already running" returns success, guarded pool reads, egress
    confirmed before announcing, live `verify`, safe `build-pool` (no
    empty-pool clobber, no `/tmp` import), nekoray-wrapper detection, real
    argument parsing.
  - `crates/dastyab-cli/src/commands.rs` — `arg` now forwarded to the Python
    bridge; `start <index>` / `watch <secs>` / `build-pool <file>` work.
  - `crates/dastyab-core/src/tunnel/multilayer.rs` — xray resolver matches
    the bridge, `/tmp` path demoted, directories/non-executables rejected;
    formatted.
  - `Cargo.toml` / `Cargo.lock` — version 0.1.7.
- **Verification gates (all green on this machine):** `cargo fmt --check`,
  `cargo clippy --workspace -- -D warnings`, `cargo test --workspace`
  (1145 passed, 1 ignored), `python3 -m pytest tests/ -q` (24 passed).
- **Installed binary:** rebuilt release v0.1.7 → `~/.local/bin/dastyab`,
  embedded script verified to be the fixed one. The materialise clobber trap
  no longer applies: from this version on it defends the fixed script.
- **Staged script on disk:** the fixed one (`~/.dastyab/bin/dastyab-aistudio.py`
  now matches the binary's embedded copy).
- **Processes cleaned:** stale xray (pid 531599) terminated; old `khvarenah`
  daemon (pid 68032) stopped and archived state; stale dastyab daemon pidfile
  removed.
- **Rename debris:** old `khvarenah` binary removed from PATH; `~/.khvarenah`
  archived. (If you want the old state preserved elsewhere, it is in the
  archive noted below.)
- **Server pool:** rebuilt and healthy — 3/3 reachable at last verify
  (`82.39.171.12` → egress `91.239.157.84`, `15.204.97.197`, `139.162.27.96`).
  The old 6-entry batch from 02:53 had expired (0/6). The rebuild used
  `dastyab harvest` (392 fresh nodes) + `build-pool` on the 258 harvested
  vless URIs. Confirmed end to end: tunnel egress `91.239.157.84` vs the
  real IP `163.123.193.6`, and a second `start` now exits 0 instead of
  `exit code Some(3)`.
- **Note on the node store:** only ~1 in 500 entries is a plain remote
  vless endpoint. Most are `ss`/`trojan`/`vmess` (unsupported by the bridge)
  or Cloudflare-worker style with a `127.0.0.1:40443` placeholder and the
  real domain in `host=`. `build-pool` needs real `host:port` addresses, so
  harvest output must be filtered for those (it was, for this rebuild).
- **Xray core:** healthy — `Xray 26.11.9` at `~/.dastyab/bin/xray`.
- **CI:** the pushed `ci.yml` runs fmt + clippy + cargo test + pytest for
  the bridge on every push/PR.

### Blockers

- None. (The earlier write-guard false positive — it read the pre-existing
  optional-string `token` parameter of `cmd_subscribe` as a credential
  literal — was reviewed with the user; it is a plain Rust function
  parameter, not a secret, and the fix was applied via an exact-match shell
  replace under the user's approval.)

---

## Release session — 2026-11-15 (v0.1.7 shipped)

**Status: closed.** The full workspace was tested end to end and v0.1.7 was
released. The fix plan below is complete.

### What the release session changed

- **Profile typo-guard (security).** `profile_dir()` falls back to `default`
  for unknown ids — correct for reads, dangerous for named mutations.
  `profile-rename work w2` rewrote the *default* profile and persisted it
  under the phantom id `work`; `profile-delete typo` deleted the default
  directory (tokens included) while reporting success for the typo'd id.
  `delete_profile`, `rename_profile` and `export_profile` now require a real
  profile id first, and the error names the profiles that do exist.
  `default` is the reserved lazily-materialized profile and always passes.
  Two regression tests cover both directions.
- **TUI no longer panics without a tty.** `ratatui::init()` aborted with a raw
  `Os { code: 6 }` backtrace under cron, CI, or a pipe. It now prints a
  one-line explanation and exits 1.
- **Rustdoc is warning-free workspace-wide.** 12 unresolved intra-doc links,
  1 redundant explicit target and 3 unclosed HTML tags in CLI help were fixed.
- **Two flaky tests fixed.** `test_ensure_daemon_installed_writes_unit_once`
  mutated the process-global `HOME` without the shared env lock, so the
  parallel onboarding tests intermittently read a swapped-out home; and two
  test helpers (`test_mem_dir`, `tmp_home`) leaked one `/tmp` directory per
  run (462 had accumulated) — both now return self-deleting `TempDir` guards.

### Test results (2026-11-15)

| Gate | Result |
|------|--------|
| `cargo fmt --all --check` | pass |
| `cargo clippy --workspace --all-targets -- -D warnings` | pass |
| `cargo doc --no-deps --workspace` | 0 warnings |
| `cargo build --release --workspace` | pass (cli, tui, gui) |
| `cargo test --workspace` | 1149 passed, 0 failed, 1 ignored |
| `python3 -m pytest tests/ -q` | 24 passed |
| `scripts/verify.sh` | PASS=6 FAIL=0 |
| 74 subcommands `--help` | all exit 0 |
| Profile lifecycle + typo guards | pass (default survives every typo) |
| TUI: piped stdin / real pty | clean exit 1 + message / renders |
| GUI under Xvfb | alive 15s, no crash (software GL) |
| Daemon start → status → stop | pass in isolated HOME |
| `aistudio verify` / `start` / `stop` / `watch` | 3/3 servers, live egress |
| `test_fresh_vless.py` no-arg run | pool unchanged (no-clobber guard) |

### Published

- Release: <https://github.com/DastYab/dastyab/releases/tag/v0.1.7>
  (published 2026-11-15 by the tag-triggered workflow).
- 19 assets: `dastyab` / `dastyab-tui` / `dastyab-gui` for linux-amd64,
  linux-arm64, macos-arm64, windows-amd64 (plus the Windows portable zip),
  `.deb` for both Linux arches, the three aistudio helper scripts, and
  `SHA256SUMS.txt`.
- Verified after publication: downloaded `dastyab-linux-amd64` runs and
  reports `dastyab 0.1.7`; its SHA-256 matches `SHA256SUMS.txt`; the `.deb`
  is a valid `dastyab-cli 0.1.7-1 amd64` package; `/releases/latest`
  redirects to `v0.1.7`; and `scripts/install.sh` installs it end to end
  with the checksum check passing.
- CI on `main` is green for the first time (the four prior runs all failed
  on the pytest no-clobber assertion, now fixed): 1093 core tests, 4 path
  regressions, 37 GUI tests, 2 doctests, and `pytest` 21 passed / 3 skipped
  (the skips are the core-dependent tests, which skip cleanly when no xray
  is installed).

**Command coverage limit.** Commands that require a real Cloudflare
deployment, a VPS, or a live zone (`deploy`, `scan`, `zones`, `dns`,
`routes`, `dns-tunnel`, `up`, `doctor` action paths) were exercised at the
argument-validation and error-path level only; they were not run against a
live Cloudflare account during this session.

## Fix plan (complete — kept for history)

1. Apply the `arg` forwarding fix in `cmd_aistudio` (`commands.rs`).
2. Fix formatting: `cargo fmt --all` (multilayer.rs:168).
3. Gate: `scripts/verify.sh` equivalent — fmt, clippy `-D warnings`,
   `cargo test --workspace`, `pytest tests/ -q` — all green.
4. Clean up rename debris: stop the stale xray (pid 531599) and the old
   `khvarenah` daemon (pid 68032); remove the old `khvarenah` binary from
   PATH and archive `~/.khvarenah/`.
5. Rebuild and reinstall: `cargo build --release` → install the new binary
   (v0.1.7) so the embedded script is the fixed one.
6. Rebuild the pool with fresh cards: `dastyab aistudio build-pool <uris.txt>`
   (or `dastyab aistudio verify` to re-probe the existing pool).
7. Commit, tag, release — so `materialise()` defends the *fixed* script from
   then on.

## How to verify the fix

```bash
dastyab --version                                  # must be >= 0.1.7
strings -n 8 "$(command -v dastyab)" | grep -c "Opened the browser with the tunnel proxy"   # must be 1
dastyab aistudio stop                              # clears any stale tunnel
dastyab aistudio verify                            # probes every node live
dastyab aistudio start                             # walks pool, skips dead nodes
python3 -m pytest tests/test_aistudio.py -q        # regression suite green
```

## 2026-11-17 — v0.1.8 Android cross-compile mystery (i686/armv7)

Symptom: release.yml build-android-{i686,armv7} fail with cc-rs
"failed to find tool i686-linux-android-clang" — but aarch64/x86_64 succeed
with the IDENTICAL script (TARGET-derived CC_<target> exported).

Verified locally: `tr '[:lower:]-' '[:upper:]_'` on the target string DOES
produce CC_I686_LINUX_ANDROID / CC_ARMV7_LINUX_ANDROIDEABI. The failing logs
show cc-rs probing CC_i686-linux-android and CC_i686_linux_android = None —
so the export never took effect for those jobs, while the log of the SAME
failing job does show the export line. No code-path difference found.

Next steps to try (in order):
1. Export CC for ALL targets unconditionally plus fallback CC=$LINKER,
   AR_<target>=llvm-ar, and also export TARGET_CC.
2. Use cargo config [target.<triple>] linker/cc in .cargo/config.toml
   (written at CI time) instead of env vars.
3. Split the two failing targets into their own matrix entries with
   hand-written env var names (no shell substitution).
Note: aarch64/x86_64 succeed with the same script — maybe GH runner env
sanitization differs per job, or cache/freshness. Reproduce locally first:
run the exact step with act or the NDK locally before the next tag.
