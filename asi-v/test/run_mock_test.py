#!/usr/bin/env python3
"""Off-device smoke test for ThorBridgeV.asi.

Runs the plugin under Wine against the mock Script Hook V (mock_shv.cpp),
connects to the bridge socket the way the companion app does, and checks
the frames. Needs: wine (64-bit), and a build made with
    ./build.sh -DTHORBRIDGE_BUILD_MOCK=ON

Usage:  python3 test/run_mock_test.py [build_dir]

This validates the plugin's own logic. It says nothing about the real
game or the real Script Hook V — that is what on-device testing is for.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.abspath(sys.argv[1] if len(sys.argv) > 1
                        else os.path.join(HERE, "..", "build"))
PORT = 47801
# $WINE overrides; Debian/Ubuntu's wine64 package installs the binary
# outside PATH.
WINE = (os.environ.get("WINE") or shutil.which("wine64")
        or shutil.which("wine")
        or next((p for p in ("/usr/lib/wine/wine64",) if os.path.exists(p)),
                None))

failures = []


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        failures.append(msg)


class Session:
    """One mock_host.exe run plus a client connection to its bridge."""

    def __init__(self, scenario, seconds, with_shv=True):
        self.dir = tempfile.mkdtemp(prefix="thorv-")
        for f in ("ThorBridgeV.asi", "mock_host.exe"):
            shutil.copy(os.path.join(BUILD, f), self.dir)
        if with_shv:
            shutil.copy(os.path.join(BUILD, "ScriptHookV.dll"), self.dir)
        env = dict(os.environ, MOCK_SCENARIO=scenario, WINEDEBUG="-all")
        env.setdefault("WINEPREFIX", os.path.join(tempfile.gettempdir(),
                                                  "thorv-wineprefix"))
        self.proc = subprocess.Popen(
            [WINE, "mock_host.exe", str(seconds)], cwd=self.dir, env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.sock = None
        self.buf = b""
        deadline = time.time() + 90      # first Wine start builds a prefix
        while time.time() < deadline:
            if self.proc.poll() is not None:
                break
            try:
                self.sock = socket.create_connection(("127.0.0.1", PORT), 1)
                break
            except OSError:
                time.sleep(0.25)
        if self.sock:
            self.sock.settimeout(3)

    def frames(self, seconds):
        """Yield decoded frames for `seconds`."""
        end = time.time() + seconds
        while time.time() < end:
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                return
            self.buf += chunk
            while b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                if line.strip():
                    yield json.loads(line.decode("utf-8"))   # raises if bad

    def send_cmd(self, cid, name, key, value):
        msg = ('{"v":1,"type":"cmd","id":%d,"name":"%s","args":{"%s":%d}}\n'
               % (cid, name, key, value))
        self.sock.sendall(msg.encode())

    def close(self):
        if self.sock:
            self.sock.close()
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        err = self.proc.stderr.read().decode("utf-8", "replace")
        log = ""
        p = os.path.join(self.dir, "ThorBridgeV.log")
        if os.path.exists(p):
            log = open(p, encoding="utf-8", errors="replace").read()
        shutil.rmtree(self.dir, ignore_errors=True)
        return self.proc.returncode, err, log


def joaat(s):
    h = 0
    for c in s.lower().encode():
        h = (h + c) & 0xFFFFFFFF
        h = (h + (h << 10)) & 0xFFFFFFFF
        h ^= h >> 6
    h = (h + (h << 3)) & 0xFFFFFFFF
    h ^= h >> 11
    h = (h + (h << 15)) & 0xFFFFFFFF
    return h


def test_story():
    print("scenario: story")
    s = Session("story", 14)
    check(s.sock is not None, "bridge accepts a connection")
    if not s.sock:
        print(s.close())
        return
    by_type = {}
    states = []
    acks = {}
    sent = False
    t0 = time.time()
    for f in s.frames(11):
        by_type.setdefault(f["type"], []).append(f)
        if f["type"] == "state":
            states.append((time.time() - t0, f))
        if f["type"] == "ack":
            acks[f["id"]] = f
        # Once in the car (mock: after 2 s), exercise the command path.
        if (not sent and f["type"] == "state" and f.get("in_vehicle")
                and by_type.get("stations")):
            sent = True
            s.send_cmd(1, "set_radio", "station", 2)       # ok
            s.send_cmd(2, "set_radio", "station", 99)      # out of range
            s.send_cmd(3, "set_weapon", "weapon", joaat("weapon_knife"))
            s.send_cmd(4, "set_weapon", "weapon", joaat("weapon_rpg"))
            s.send_cmd(5, "bogus", "value", 1)
    rc, err, log = s.close()

    hello = by_type.get("hello", [{}])[0]
    check(hello.get("game") == "v", "hello says game=v")
    check(hello.get("supported") is True and hello.get("shv") is True,
          "hello reports Script Hook V bound")
    check(hello.get("exe") == "mock_host.exe", "hello carries exe name")
    check(all(f.get("game") == "v" for fs in by_type.values() for f in fs),
          "every frame carries game=v")

    check(len(states) > 100, "state frames at ~20 Hz (%d in 11 s)" % len(states))
    first = next((f for _, f in states if f.get("in_game")), {})
    check(first.get("character") == 0, "starts as Michael (character 0)")
    check(first.get("money") == 1234567, "Michael's cash from SP0_TOTAL_CASH")
    check(first.get("health") == 175 and first.get("max_health") == 200,
          "health / max_health")
    check(first.get("armor") == 40 and first.get("max_armor") == 100,
          "armor / max_armor")
    check(first.get("wanted") == 3, "wanted level")
    check(first.get("special") == {"unlocked": True, "enabled": True,
                                   "active": False, "full": True},
          "special-ability flags")
    check(first.get("weapon_hash") == joaat("weapon_pistol")
          and first.get("weapon_name") == "weapon_pistol",
          "weapon hash is unsigned and named")
    check(first.get("ammo_clip") == 9 and first.get("ammo_total") == 120
          and first.get("clip_size") == 12, "ammo via out-pointer native")
    check(first.get("in_vehicle") is False and "vehicle" not in first,
          "on foot at start: no vehicle object")
    check(first.get("radio") == -1 and first.get("radio_name") == "",
          "radio off on foot")

    veh = next((f for _, f in states if f.get("in_vehicle")), {})
    v = veh.get("vehicle", {})
    check(v.get("name") == 'Adder "S" é',
          "vehicle label: escaped quotes + UTF-8 survive (%r)" % v.get("name"))
    check(v.get("class") == 7 and abs(v.get("speed_mps", 0) - 31.5) < 0.01
          and v.get("engine_hp") == 870.0 and v.get("body_hp") == 990.0
          and v.get("has_radio") is True, "vehicle floats / class / radio flag")
    check(veh.get("radio_name") == "RADIO_01_CLASS_ROCK",
          "radio name while driving")

    check(any(f.get("switching") for _, f in states),
          "switching flag seen during the character switch")
    last = states[-1][1] if states else {}
    check(last.get("character") == 1 and last.get("money") == 7654321,
          "ends as Franklin with Franklin's cash")
    check(not any(f.get("stalled") for _, f in states), "never stalled")

    st = by_type.get("stations", [])
    check(len(st) == 1, "stations list sent exactly once (%d)" % len(st))
    names = [x["name"] for x in st[0]["stations"]] if st else []
    check(names == ["RADIO_01_CLASS_ROCK", "RADIO_02_POP",
                    "RADIO_03_HIPHOP_NEW", "RADIO_19_USER",
                    "RADIO_99_MOCK_UNLABELLED"], "station ids in game order")
    labels = {x["name"]: x["label"] for x in st[0]["stations"]} if st else {}
    check(labels.get("RADIO_02_POP") == "Non-Stop-Pop FM"
          and labels.get("RADIO_99_MOCK_UNLABELLED") == "",
          "station labels; unknown label is empty, not \"NULL\"")

    wp = by_type.get("weapons", [])
    check(1 <= len(wp) <= 3, "weapons list sent only when it changes (%d)" % len(wp))
    owned = sorted(x["name"] for x in wp[-1]["weapons"]) if wp else []
    check(owned == ["weapon_assaultrifle", "weapon_grenade", "weapon_knife",
                    "weapon_pistol"], "owned-weapon scan (%s)" % owned)

    check(acks.get(1, {}).get("ok") is True, "set_radio valid index -> ok")
    check(acks.get(2, {}).get("ok") is False
          and "out of range" in acks.get(2, {}).get("err", ""),
          "set_radio bad index -> error")
    check(acks.get(3, {}).get("ok") is False
          and acks.get(3, {}).get("err") == "not on foot",
          "set_weapon refused while driving")
    check(acks.get(4, {}).get("ok") is False, "set_weapon refused (driving / not owned)")
    check(acks.get(5, {}).get("ok") is False
          and "unknown command" in acks.get(5, {}).get("err", ""),
          "unknown command -> error")
    check(any(f.get("radio_name") == "RADIO_03_HIPHOP_NEW" for _, f in states),
          "radio retune visible in later state frames")

    check("UNKNOWN NATIVE" not in err and "ARITY MISMATCH" not in err,
          "mock saw no unknown native / arity mismatch")
    check(rc == 0, "host exited cleanly (rc=%s)" % rc)
    check("[shv] ScriptHookV.dll bound" in log and "resolved via" not in log,
          "all exports resolved by exact decorated name")
    if failures:
        print("---- stderr ----\n" + err[-2000:])
        print("---- ThorBridgeV.log ----\n" + log[-3000:])


def simple(scenario, seconds, predicate, what, with_shv=True):
    print("scenario: %s%s" % (scenario, "" if with_shv else " (no Script Hook V)"))
    s = Session(scenario, seconds + 3, with_shv)
    check(s.sock is not None, "bridge accepts a connection")
    if not s.sock:
        print(s.close())
        return
    frames = list(s.frames(seconds))
    rc, err, log = s.close()
    check(predicate(frames), what)
    check("UNKNOWN NATIVE" not in err and "ARITY MISMATCH" not in err,
          "mock saw no unknown native / arity mismatch")
    check(rc == 0, "host exited cleanly (rc=%s)" % rc)


def main():
    if not WINE:
        sys.exit("wine not found")
    for f in ("ThorBridgeV.asi", "ScriptHookV.dll", "mock_host.exe"):
        if not os.path.exists(os.path.join(BUILD, f)):
            sys.exit("missing %s in %s — build with -DTHORBRIDGE_BUILD_MOCK=ON"
                     % (f, BUILD))

    test_story()

    def states(fr):
        return [f for f in fr if f["type"] == "state"]

    simple("online", 3,
           lambda fr: states(fr) and all(
               f["online"] and not f["in_game"] and "money" not in f
               and f["weapon_hash"] == 0 for f in states(fr)),
           "online: only the online flag, no game data")
    simple("loading", 3,
           lambda fr: states(fr) and all(not f["in_game"] for f in states(fr)),
           "loading screen: in_game stays false")
    simple("other", 3,
           lambda fr: states(fr) and all(
               f["character"] == -1 and f["animal"] and "money" not in f
               and f["special"]["unlocked"] is False
               for f in states(fr) if f["in_game"]),
           "non-protagonist ped: character -1, no cash, no special")
    simple("stall", 7,
           lambda fr: states(fr) and not states(fr)[0]["stalled"]
           and states(fr)[-1]["stalled"] and states(fr)[-1]["in_game"],
           "stall: frames keep flowing and flip to stalled=true")
    simple("story", 3,
           lambda fr: fr and fr[0]["type"] == "hello"
           and fr[0]["supported"] is False and fr[0]["err"]
           and all(f["type"] == "tick" for f in fr[1:]),
           "safe mode: hello explains the missing DLL, then ticks only",
           with_shv=False)

    print()
    if failures:
        print("FAILED (%d):" % len(failures))
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
