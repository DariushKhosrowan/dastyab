# Bug Readme — `dastyab aistudio` failing after the Khvarenah → DastYab rename

**File purpose:** one living document for this bug — what it is, why it happens,
where the fix stands right now. Update the *Current state* section whenever
something changes.

**Last updated:** 2026-11-15 (investigation session)

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
- **Server pool:** 6 entries, built 02:53; live `verify` after the fix
  shows **0/6 reachable** — the batch expired during the day (normal for
  free-channel cards). Rebuild with fresh cards:
  `dastyab aistudio build-pool <uris.txt>`. Before the fix, `start` bound
  only `pool[0]`; now it walks the pool, so a fresh pool keeps working as
  entries die.
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

## Remaining fix plan (in order)

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
