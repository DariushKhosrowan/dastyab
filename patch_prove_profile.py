#!/usr/bin/env python3
"""Make `prove` able to test a REAL signed-in session, and stop reporting
an unauthenticated marketing page as proof.

Why: cookies in a Chrome profile are encrypted with a key held by the OS
keyring. Copying the profile (or opening it headless alongside the live
browser) yields a page that says "Sign in" — i.e. the marketing page,
which shows no gate markers and therefore looked like success. The
account gate (`/unavailable`) only appears for an authenticated session.

So:
  * `browser_gate()` detects the unauthenticated landing page and reports
    it as UNKNOWN ("not signed in"), never as a pass;
  * `prove --profile DIR` tests a real profile directory (requires Chrome
    to be closed for that profile, which the caller validates);
  * `probe_gates(auth=True)` is used by that path so the account gate is
    classified instead of being silently satisfied.
"""
path = "dastyab-flow.py"
s = open(path).read()

# --- 1. detect the signed-out landing page -------------------------------
old = '''    if "aisandboxangularfrontend" not in low:
        return False, "browser did not render the Flow app"
    return True, "browser loaded the Flow app (no region or account gate)"'''
new = '''    if "aisandboxangularfrontend" not in low:
        return False, "browser did not render the Flow app"
    # Signed out, Flow serves its marketing landing page: no gate markers,
    # but also no evidence about the account. Treating that as a pass made
    # `prove` claim success while never testing the gate that actually
    # blocks a signed-in user.
    if "sign in" in low and "credits" not in low:
        return None, ("browser is NOT signed in (landing page) — region "
                      "gate looks clear, account gate untested; pass "
                      "--profile <dir> with a signed-in profile to test it")
    return True, "browser loaded the Flow app (no region or account gate)"'''
assert s.count(old) == 1, "anchor 1"
s = s.replace(old, new, 1)

# --- 2. browser_gate accepts a profile dir -------------------------------
old2 = '''def browser_gate(timeout=150):'''
new2 = '''def browser_gate(timeout=150, profile_dir=None):'''
assert s.count(old2) == 1, "anchor 2"
s = s.replace(old2, new2, 1)

old3 = '''    prof = os.path.join("/tmp", "dastyab-flow-gate-%d" % os.getpid())'''
new3 = '''    # When a signed-in profile is supplied, drive THAT directory (Chrome
    # refuses to share a profile with a running instance, so the caller
    # must have closed it). Otherwise use a throwaway dir: the result is
    # an unauthenticated check, useful for the region gate only.
    prof = profile_dir or os.path.join(
        "/tmp", "dastyab-flow-gate-%d" % os.getpid())'''
assert s.count(old3) == 1, "anchor 3"
s = s.replace(old3, new3, 1)

# Don't delete a profile we were handed.
old4 = '''    finally:
        shutil.rmtree(prof, ignore_errors=True)'''
new4 = '''    finally:
        if profile_dir is None:
            shutil.rmtree(prof, ignore_errors=True)'''
assert s.count(old4) == 1, "anchor 4"
s = s.replace(old4, new4, 1)

# A locked profile must be reported, not silently reinterpreted.
old5 = '''    except (OSError, subprocess.SubprocessError) as e:
        return None, f"browser gate failed to run ({e})"'''
new5 = '''    except (OSError, subprocess.SubprocessError) as e:
        return None, f"browser gate failed to run ({e})"
    if "SingletonLock" in dom or "ProcessSingleton" in dom:
        return None, ("profile is in use by a running Chrome — close that "
                      "browser first, then re-run")'''
assert s.count(old5) == 1, "anchor 5"
s = s.replace(old5, new5, 1)

# --- 3. prove --profile ---------------------------------------------------
old6 = '''    p = sub.add_parser("prove",
                       help="prove Flow access in a real browser (decisive)")
    p.add_argument("--try", dest="try_n", type=int, default=6)
    p.add_argument("--country", default=None)'''
new6 = '''    p = sub.add_parser("prove",
                       help="prove Flow access in a real browser (decisive)")
    p.add_argument("--try", dest="try_n", type=int, default=6)
    p.add_argument("--country", default=None)
    p.add_argument("--profile", default=None,
                   help="a signed-in Chrome profile dir to test the "
                        "account gate (close that Chrome first)")'''
assert s.count(old6) == 1, "anchor 6"
s = s.replace(old6, new6, 1)

old7 = '''def cmd_prove(tries=6, want_country=None):'''
new7 = '''def cmd_prove(tries=6, want_country=None, profile=None):'''
assert s.count(old7) == 1, "anchor 7"
s = s.replace(old7, new7, 1)

# Thread the profile through to the gate, and classify the account gate.
old8 = '''            b_ok = g.get("browser_ok")
            if b_ok:'''
new8 = '''            b_ok = g.get("browser_ok")
            if b_ok is False and str(g.get("reason", "")).startswith("ACCOUNT-GATE:"):
                print("[flow]     IP GATE PASSED (region is fine).", flush=True)
                print("[flow]     ACCOUNT GATE FAILED — a proxy cannot fix "
                      "this.", flush=True)
                print("[flow] " + str(g["reason"])[len("ACCOUNT-GATE:"):].strip(),
                      flush=True)
                return 4
            if b_ok:'''
assert s.count(old8) == 1, "anchor 8"
s = s.replace(old8, new8, 1)

# probe_gates must honour the profile for the decisive check.
old9 = '''    b_ok, b_detail = browser_gate()'''
new9 = '''    b_ok, b_detail = browser_gate(profile_dir=browser_profile)'''
assert s.count(old9) == 1, "anchor 9"
s = s.replace(old9, new9, 1)

old10 = '''def probe_gates(want_country=None):'''
new10 = '''def probe_gates(want_country=None, browser_profile=None):'''
assert s.count(old10) == 1, "anchor 10"
s = s.replace(old10, new10, 1)

# Pass the profile from cmd_prove's probe call.
old11 = '''            g = probe_gates(want_country)
            if not g["verdict"]:'''
new11 = '''            g = probe_gates(want_country, browser_profile=profile)
            if not g["verdict"]:'''
if s.count(old11) == 0:
    # the earlier account-gate insert may have changed the shape; retry
    old11 = '''            g = probe_gates(want_country)'''
    new11 = '''            g = probe_gates(want_country, browser_profile=profile)'''
assert s.count(old11) == 1, "anchor 11"
s = s.replace(old11, new11, 1)

# Main dispatch.
old12 = '''    if cmd == "prove":
        return cmd_prove(a.try_n, a.country)'''
new12 = '''    if cmd == "prove":
        return cmd_prove(a.try_n, a.country, a.profile)'''
assert s.count(old12) == 1, "anchor 12"
s = s.replace(old12, new12, 1)

open(path, "w").write(s)
print("prove --profile wired; signed-out landing detected")
