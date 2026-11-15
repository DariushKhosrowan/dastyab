#!/usr/bin/env python3
"""Foundry-lite tests for the AI Studio bridge helper.

Run with:  python3 -m pytest tests/test_aistudio.py -q

These cover the regressions that made `dastyab aistudio start` unusable:

  * selecting a nekoray wrapper as if it were an xray core
  * binding only pool[0], so one dead node broke the whole command
  * `build-pool` importing from a hardcoded /tmp path
  * `build-pool` writing an empty pool over a working one
  * `verify` printing stale stored egress as if it were live
"""
import importlib.util
import json
import os
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name="dastyab-aistudio.py", alias="ai"):
    path = os.path.join(ROOT, name)
    spec = importlib.util.spec_from_file_location(alias, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="module")
def ai():
    return load()


@pytest.fixture(scope="module")
def tfv():
    return load("test_fresh_vless.py", "tfv")


# --- parsing ---------------------------------------------------------------

def test_parse_vless_keeps_unknown_keys(ai):
    u = ai.parse_vless(
        "vless://uuid-1@1.2.3.4:443?security=reality&type=tcp"
        "&sni=a.example&pbk=KEY&fp=firefox&flow=xtls-rprx-vision"
    )
    assert u["addr"] == "1.2.3.4"
    assert u["port"] == 443
    assert u["q"]["security"] == "reality"
    assert u["q"]["sni"] == "a.example"


def test_parse_vless_strips_fragment_label(ai):
    u = ai.parse_vless("vless://u@h.example:1?security=none#my%20node")
    assert u["addr"] == "h.example"
    assert "#" not in json.dumps(u)


@pytest.mark.parametrize("bad", [
    "garbage",
    "",
    "vless://@1.2.3.4:443?security=none",       # empty uuid
    "vless://uuid@host:notaport?security=none",  # non-numeric port
])
def test_parse_vless_rejects_malformed(ai, bad):
    assert ai.parse_vless(bad) is None


def test_parse_vless_agrees_with_standalone_parser(ai, tfv):
    line = ("vless://uid@host.example:8443?security=tls&type=ws"
            "&path=%2Fws&host=cdn.example")
    assert ai.parse_vless(line) == tfv.parse_vless(line)


# --- core selection --------------------------------------------------------

def test_rejects_nekoray_wrapper(ai):
    """`nekoray_core version` prints 'Xray: ... NekoRay: ...' but cannot run -c."""
    if not os.path.exists("/opt/nekoray/nekoray_core"):
        pytest.skip("nekoray not installed")
    assert ai._is_real_xray("/opt/nekoray/nekoray_core") is False


def test_rejects_nekobox():
    """`nekobox_core` is sing-box: different CLI, must not be selected."""
    ai = load()
    if not os.path.exists("/opt/nekoray/nekobox_core"):
        pytest.skip("nekobox not installed")
    assert ai._is_real_xray("/opt/nekoray/nekobox_core") is False


def test_rejects_missing_and_non_xray(ai):
    assert ai._is_real_xray("/nonexistent/xray") is False
    assert ai._is_real_xray("/bin/true") is False


def test_accepts_real_core_if_present(ai):
    try:
        core = ai.find_xray()
    except SystemExit:
        pytest.skip("no xray core installed")
    assert ai._is_real_xray(core) is True


# --- build-pool must not destroy state -------------------------------------

def test_build_pool_keeps_pool_when_batch_is_dead(ai, tmp_path, monkeypatch):
    pool = tmp_path / "fresh_vless.json"
    pool.write_text('[{"addr": "keep.me", "port": 443, "uuid": "u", "q": {}}]')
    monkeypatch.setattr(ai, "POOL", str(pool))

    uris = tmp_path / "cards.txt"
    uris.write_text("vless://dead-0000@192.0.2.1:443?security=none&type=tcp\n")
    # 192.0.2.1 is TEST-NET-1: guaranteed unroutable, so this probes as dead.
    with pytest.raises(SystemExit) as e:
        ai.pop_pool(str(uris), want=1, probe_port=10985)
    assert "unchanged" in str(e.value)

    survived = json.loads(pool.read_text())
    assert survived[0]["addr"] == "keep.me", "a dead batch must not clobber the pool"


def test_build_pool_rejects_missing_file(ai, tmp_path):
    with pytest.raises(SystemExit):
        ai.pop_pool(str(tmp_path / "nope.txt"))


def test_build_pool_rejects_file_without_uris(ai, tmp_path):
    f = tmp_path / "junk.txt"
    f.write_text("not a uri\nstill not one\n")
    with pytest.raises(SystemExit) as e:
        ai.pop_pool(str(f))
    assert "No vless://" in str(e.value)


def test_build_pool_does_not_import_from_tmp(ai):
    """The old implementation did sys.path.insert(0, '/tmp') + import."""
    src = open(os.path.join(ROOT, "dastyab-aistudio.py")).read()
    assert 'sys.path.insert(0,"/tmp")\n    import test_fresh_vless' not in src
    assert "import test_fresh_vless as tfv" not in src


# --- start() must walk the pool --------------------------------------------

def test_start_walks_pool_and_skips_dead(ai, tmp_path, monkeypatch):
    pool = [{"addr": "dead1", "port": 1, "uuid": "u", "q": {}},
            {"addr": "dead2", "port": 2, "uuid": "u", "q": {}},
            {"addr": "live", "port": 3, "uuid": "u", "q": {}}]
    (tmp_path / "fresh_vless.json").write_text(json.dumps(pool))
    monkeypatch.setattr(ai, "POOL", str(tmp_path / "fresh_vless.json"))
    monkeypatch.setattr(ai, "is_running", lambda: False)
    monkeypatch.setattr(ai, "launch_browser", lambda: None)
    monkeypatch.setattr(ai, "stop", lambda quiet=False: None)
    monkeypatch.setattr(ai, "_bring_up",
                        lambda srv, announce=True: "203.0.113.9" if srv["addr"] == "live" else None)

    ok, idx = ai.start()
    assert ok is True
    assert idx == 2, "start must advance past dead entries to the live one"


def test_start_reports_failure_when_all_dead(ai, tmp_path, monkeypatch, capsys):
    pool = [{"addr": "d1", "port": 1, "uuid": "u", "q": {}}]
    (tmp_path / "fresh_vless.json").write_text(json.dumps(pool))
    monkeypatch.setattr(ai, "POOL", str(tmp_path / "fresh_vless.json"))
    monkeypatch.setattr(ai, "is_running", lambda: False)
    monkeypatch.setattr(ai, "launch_browser", lambda: None)
    monkeypatch.setattr(ai, "stop", lambda quiet=False: None)
    monkeypatch.setattr(ai, "_bring_up", lambda srv, announce=True: None)

    ok, idx = ai.start()
    assert (ok, idx) == (False, None)
    assert "every node in the pool is dead" in capsys.readouterr().err


def test_start_does_not_announce_a_dead_node(ai, tmp_path, monkeypatch, capsys):
    """'Tunnel started' must never be printed for a node that cannot pass traffic."""
    pool = [{"addr": "dead", "port": 1, "uuid": "u", "q": {}}]
    (tmp_path / "fresh_vless.json").write_text(json.dumps(pool))
    monkeypatch.setattr(ai, "POOL", str(tmp_path / "fresh_vless.json"))
    monkeypatch.setattr(ai, "is_running", lambda: False)
    monkeypatch.setattr(ai, "launch_browser", lambda: None)
    monkeypatch.setattr(ai, "stop", lambda quiet=False: None)
    monkeypatch.setattr(ai, "probe_egress", lambda timeout=20: None)
    monkeypatch.setattr(ai, "build_cfg", lambda srv: {"inbounds": [{}], "outbounds": [{}]})
    monkeypatch.setattr(ai, "find_xray", lambda: "/bin/true")
    monkeypatch.setattr(ai.os, "remove", lambda p: None)

    class FakeProc:
        pid = 1
        def poll(self):
            return None  # still alive: the SOCKS port check is what we exercise
        def kill(self):
            pass
        def wait(self, timeout=None):
            pass

    # Socket connect must succeed so _bring_up proceeds to the egress probe.
    class FakeSock:
        def __init__(self, *a, **k):
            pass
        def settimeout(self, t):
            pass
        def connect(self, addr):
            pass
        def close(self):
            pass

    monkeypatch.setattr(ai.subprocess, "Popen", lambda *a, **k: FakeProc())
    monkeypatch.setattr(ai.socket, "socket", FakeSock)
    ai.start()
    out = capsys.readouterr().out
    assert "Tunnel started" not in out


# --- verify() must report reality, not the stored snapshot ------------------

def test_verify_probes_instead_of_printing_stored_egress(ai, tmp_path, monkeypatch, capsys):
    pool = [{"addr": "n1", "port": 1, "uuid": "u", "q": {}, "egress": "10.0.0.1"},
            {"addr": "n2", "port": 2, "uuid": "u", "q": {}, "egress": "10.0.0.2"}]
    p = tmp_path / "fresh_vless.json"
    p.write_text(json.dumps(pool))
    monkeypatch.setattr(ai, "POOL", str(p))
    monkeypatch.setattr(ai, "is_running", lambda: False)
    monkeypatch.setattr(ai, "stop", lambda quiet=False: None)
    monkeypatch.setattr(ai, "_bring_up",
                        lambda srv, announce=True: None if srv["addr"] == "n1" else "203.0.113.7")

    ai.verify()
    out = capsys.readouterr().out
    # Index labels are positional, so the dead entry must be reported at
    # its own index with a DEAD mark, not silently skipped.
    assert "[0]" in out and "DEAD" in out
    assert "DEAD" in out and "203.0.113.7" in out
    assert "1/2 reachable" in out
    # the stale stored value must not be presented as current
    assert "10.0.0.1" not in out


# --- start() on a live tunnel must be a success, not a failure ---------------

def test_start_succeeds_when_tunnel_already_running(ai, monkeypatch):
    """`start` twice in a row must not exit non-zero.

    The CLI turns a non-zero exit into "Aistudio failed". Returning False here
    meant the second `dastyab aistudio start` printed a failure immediately
    after successfully opening the browser on the running tunnel.
    """
    monkeypatch.setattr(ai, "is_running", lambda: True)
    monkeypatch.setattr(ai, "launch_browser", lambda: None)
    ok, idx = ai.start()
    assert ok is True
    assert idx is None


# --- the watchdog must survive a failed rotation ---------------------------

def test_watch_survives_systemexit_during_rotation(ai, tmp_path, monkeypatch):
    """A dead pool during rotation must not kill the watchdog.

    `start` signals an unusable core/pool with SystemExit, which derives from
    BaseException — so `except Exception` let it escape and terminated the
    watchdog on its first rotation.
    """
    pool = [{"addr": "a", "port": 1, "uuid": "u", "q": {}},
            {"addr": "b", "port": 2, "uuid": "u", "q": {}}]
    (tmp_path / "fresh_vless.json").write_text(json.dumps(pool))
    monkeypatch.setattr(ai, "POOL", str(tmp_path / "fresh_vless.json"))
    monkeypatch.setattr(ai, "stop", lambda quiet=False: None)
    # The tunnel never comes back: every probe fails, so rotation keeps going.
    monkeypatch.setattr(ai, "probe_egress", lambda *a, **k: None)

    calls = []

    def fake_start(idx):
        calls.append(idx)
        raise SystemExit("[aistudio] No xray core found")

    monkeypatch.setattr(ai, "start", fake_start)

    class Sentinel(Exception):
        pass

    ticks = {"n": 0}

    def fake_sleep(_seconds):
        ticks["n"] += 1
        if ticks["n"] > 12:
            raise Sentinel()

    monkeypatch.setattr(ai.time, "sleep", fake_sleep)
    with pytest.raises(Sentinel):
        ai.watch(interval=0, max_fail=2)

    # Each rotation tries every other entry: two attempts per round.
    assert len(calls) >= 2, f"watchdog stopped after {len(calls)} attempt(s)"


# --- argv handling ----------------------------------------------------------

def test_build_pool_without_argument_explains_instead_of_indexerror(ai, monkeypatch):
    """`build-pool` with no file is a usage error, not a traceback."""
    import runpy
    monkeypatch.setattr(ai.sys, "argv", ["dastyab-aistudio.py", "build-pool"])
    with pytest.raises(SystemExit) as e:
        runpy.run_path(os.path.join(ROOT, "dastyab-aistudio.py"), run_name="__main__")
    assert "needs a file" in str(e.value)
    assert "IndexError" not in str(e.value)


def test_unknown_action_exits_non_zero(ai, monkeypatch):
    """A typo must not print the docstring and exit 0 (which looks like success)."""
    import runpy
    monkeypatch.setattr(ai.sys, "argv", ["dastyab-aistudio.py", "strat"])
    with pytest.raises(SystemExit) as e:
        runpy.run_path(os.path.join(ROOT, "dastyab-aistudio.py"), run_name="__main__")
    assert "Unknown action" in str(e.value)


def test_dead_pool_advice_uses_the_cli_not_a_loose_file(ai, tmp_path, monkeypatch, capsys):
    """The advice must work on an installed system, where no .py file is on disk."""
    p = tmp_path / "fresh_vless.json"
    p.write_text(json.dumps([{"addr": "n", "port": 1, "uuid": "u", "q": {}}]))
    monkeypatch.setattr(ai, "POOL", str(p))
    monkeypatch.setattr(ai, "is_running", lambda: False)
    monkeypatch.setattr(ai, "stop", lambda quiet=False: None)
    monkeypatch.setattr(ai, "_bring_up", lambda srv, announce=True: None)

    ai.verify()
    out = capsys.readouterr().out
    assert "dastyab aistudio build-pool" in out
    assert "dastyab-aistudio.py build-pool" not in out
