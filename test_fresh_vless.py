#!/usr/bin/env python3
"""Parse fresh v2rayN share URIs into xray client configs and verify egress."""
import json, re, subprocess, sys, time, os, urllib.parse, socket, signal, random

# Resolved at runtime, never a fixed path: the old hardcoded
# "/tmp/xraybin/xray" is wiped on reboot, so this tool failed on every fresh
# boot even with a core installed. $DASTYAB_XRAY and the standard locations
# are honoured, matching `dastyab aistudio`.
def _find_xray():
    cands = [os.environ.get("DASTYAB_XRAY", ""),
             os.path.expanduser("~/.dastyab/bin/xray"),
             os.path.join(os.path.dirname(os.path.abspath(__file__)), "xray"),
             "/usr/local/bin/xray", "/usr/bin/xray", "/opt/xray/xray"]
    for c in cands:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    for d in os.environ.get("PATH", "").split(os.pathsep):
        p = os.path.join(d, "xray") if d else ""
        if p and os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    raise SystemExit("no xray core found; set DASTYAB_XRAY=/path/to/xray")

XRAY = None  # resolved lazily by find_xray()
SOCK = 10850
SERVERS_FILE = os.path.expanduser("~/.dastyab/profiles/default/fresh_vless.json")

def find_xray():
    global XRAY
    if XRAY is None:
        XRAY = _find_xray()
    return XRAY

def parse_vless(line):
    line = line.split('#')[0]
    m = re.match(r"vless://([^@]*)@([^:]+):(\d+)\?(.*)", line)
    if not m: return None
    uuid, host, port, qs = m.group(1), m.group(2), int(m.group(3)), m.group(4)
    q = dict(urllib.parse.parse_qsl(qs))
    return {"uuid":uuid,"addr":host,"port":port,"q":q}

def build_xray_cfg(p):
    q = p['q']; addr=p['addr']; port=p['port']
    sec = q.get('security','none'); net = q.get('type','tcp')
    out = {"protocol":"vless","settings":{"vnext":[{"address":addr,"port":port,
            "users":[{"id":p['uuid'],"encryption":"none"}]}]}}
    if q.get('flow'):
        out['settings']['vnext'][0]['users'][0]['flow'] = q['flow']
    ss = {}
    # reality
    if sec == 'reality':
        ss['security']='reality'
        ss['realitySettings']={'serverName':q.get('sni',addr),
            'fingerprint':q.get('fp','chrome'),'publicKey':q.get('pbk',''),
            'shortId':q.get('sid',''),'spiderX':q.get('spx','/')}
    elif sec == 'tls':
        ss['security']='tls'
        ss['tlsSettings']={'serverName':q.get('sni',addr),'fingerprint':q.get('fp','chrome')}
        if q.get('alpn'):
            ss['tlsSettings']['alpn'] = [x for x in q['alpn'].split(',') if x]
        if q.get('pinSHA256'):
            ss['tlsSettings']['pinnedPeerCertSha256'] = [q['pinSHA256']]
    else:
        ss['security']='none'
    if net == 'ws':
        ss['network']='ws'
        ss['wsSettings']={'path':q.get('path','/'),'headers':{'Host':q.get('host') or q.get('sni') or addr}}
    elif net == 'xhttp':
        ss['network']='xhttp'
        ss['xhttpSettings']={'path':q.get('path','/'),'host':q.get('host') or q.get('sni') or addr,
                             'mode':q.get('mode','auto')}
    elif net == 'grpc':
        ss['network']='grpc'
        ss['grpcSettings']={'serviceName':q.get('serviceName','')}
    else:
        ss['network']='tcp'
    out['streamSettings']=ss
    inb = {"listen":"127.0.0.1","port":SOCK,"protocol":"socks","settings":{"auth":"noauth","udp":True},"tag":"socks-in"}
    return {"log":{"loglevel":"warning"},"inbounds":[inb],"outbounds":[out,{"protocol":"freedom","tag":"direct"}]}

def probe(port=SOCK, timeout=15):
    try:
        r = subprocess.run(["curl","-s","--max-time",str(timeout),
            "--socks5-hostname",f"127.0.0.1:{port}","https://api.ipify.org"],
            capture_output=True, text=True, timeout=timeout+3)
        out = r.stdout.strip()
        return out if out and not out.startswith("curl") else None
    except Exception:
        return None

def test(server, wait=3, probe_timeout=15):
    cfg = build_xray_cfg(server)
    cfgpath = "/tmp/check_%s.json" % (abs(hash(server['addr']+str(server['port']))) % 100000)
    open(cfgpath,"w").write(json.dumps(cfg))
    proc=None
    try:
        proc = subprocess.Popen([find_xray(),"run","-c",cfgpath],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(wait)
        ip = probe(SOCK, probe_timeout)
        return ip
    finally:
        if proc:
            try: proc.terminate()
            except: pass
            time.sleep(0.2)
            try: proc.kill()
            except: pass

def load_fresh():
    if os.path.exists(SERVERS_FILE):
        return json.load(open(SERVERS_FILE))
    # else parse from the embedded raw list - read from file passed or regenerate
    return None

def main():
    # read URIs from a file if given, else fall back
    src = sys.argv[1] if len(sys.argv)>1 else None
    uris=[]
    if src and os.path.exists(src):
        for line in open(src):
            line=line.strip()
            if line.startswith("vless://"):
                uris.append(parse_vless(line))
    uris=[u for u in uris if u]
    print(f"parsed {len(uris)} vless configs")
    # randomize and test up to 30
    random.seed(1); random.shuffle(uris)
    working=[]
    tested=set()
    for u in uris[:30]:
        key=(u['addr'],u['port'])
        if key in tested: continue
        tested.add(key)
        ip = test(u)
        status = "WORKING" if ip else "fail"
        print(f"  {status} {u['addr']}:{u['port']} type={u['q'].get('type','tcp')} sec={u['q'].get('security','none')} -> {ip or '-'}")
        if ip:
            working.append({**u,"egress":ip})
        if len(working)>=6: break
    if working:
        os.makedirs(os.path.dirname(SERVERS_FILE), exist_ok=True)
        tmp = SERVERS_FILE + ".tmp"
        with open(tmp, "w") as fh:
            json.dump(working, fh, indent=1)
        os.replace(tmp, SERVERS_FILE)
        print(f"\nSaved {len(working)} working servers to {SERVERS_FILE}")
        for w in working:
            print(f"  {w['addr']}:{w['port']} -> {w['egress']}")
    else:
        # Never clobber a usable pool because one channel's cards expired.
        print(f"\nNo working server found; leaving {SERVERS_FILE} unchanged.")

if __name__=="__main__":
    main()
