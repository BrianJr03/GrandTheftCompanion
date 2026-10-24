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
    check(all(f.get("wheel") is False for _, f in states),
          "wheel flag present and false while the control is never held")

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


def test_wheel():
    """On foot throughout. The mock holds the weapon-wheel control from
    3.0 s to 5.5 s and, 100 ms after release, re-selects whatever was in
    hand when the wheel opened."""
    print("scenario: wheel")
    knife = joaat("weapon_knife")            # above 2^31: must stay unsigned
    grenade = joaat("weapon_grenade")
    rifle = joaat("weapon_assaultrifle")
    s = Session("wheel", 12)
    check(s.sock is not None, "bridge accepts a connection")
    if not s.sock:
        print(s.close())
        return
    states = []
    acks = {}
    have_weapons = False
    sent_foot = sent_wheel = False
    for f in s.frames(9):
        if f["type"] == "weapons":
            have_weapons = True
        if f["type"] == "ack":
            acks[f["id"]] = f
        if f["type"] != "state":
            continue
        states.append(f)
        if not f.get("in_game"):
            continue
        if not sent_foot and have_weapons and not f.get("wheel"):
            sent_foot = True
            s.send_cmd(1, "set_weapon", "weapon", knife)
        if not sent_wheel and f.get("wheel"):
            sent_wheel = True
            s.send_cmd(2, "set_weapon", "weapon", grenade)
            s.send_cmd(3, "set_weapon", "weapon", joaat("weapon_rpg"))
            s.send_cmd(4, "set_weapon", "weapon", rifle)   # replaces 2
    rc, err, log = s.close()

    live = [f for f in states if f.get("in_game")]
    check(knife > 0x7FFFFFFF, "test weapon hash really is above 2^31")
    check(bool(live) and live[0].get("wheel") is False
          and live[0].get("weapon_hash") == joaat("weapon_pistol"),
          "starts with the pistol, wheel flag false")
    check(sent_foot and acks.get(1, {}).get("ok") is True,
          "set_weapon on foot -> ok")
    held = [f for f in live if f.get("wheel")]
    before = live[:live.index(held[0])] if held else live
    check(any(f.get("weapon_hash") == knife
              and f.get("weapon_name") == "weapon_knife" for f in before),
          "on-foot pick equips at once, before the wheel opens")
    span = (held[-1]["t"] - held[0]["t"]) if held else 0
    check(1800 <= span <= 2600,
          "wheel flag true for the hold, minus the tap filter (%d ms)" % span)
    check(sent_wheel and acks.get(2, {}).get("ok") is True
          and acks.get(4, {}).get("ok") is True,
          "set_weapon while the wheel is held -> ok")
    check(acks.get(3, {}).get("ok") is False
          and acks.get(3, {}).get("err") == "weapon not owned",
          "set_weapon for an unowned weapon is still refused on the wheel")
    check(bool(held) and all(f.get("weapon_hash") == knife for f in held),
          "pick made on the wheel is deferred while the control is held")
    last = live[-1] if live else {}
    check(last.get("wheel") is False and last.get("weapon_hash") == rifle
          and last.get("weapon_name") == "weapon_assaultrifle",
          "after release the LAST wheel pick is equipped (0x%08X)"
          % last.get("weapon_hash", 0))
    check("[mock] wheel commit" in err,
          "mock game re-selected its own highlight after the release")
    check("re-applied" in log,
          "plugin put the companion's pick back after the game's commit")
    check(log.count("weapon-wheel control held") == 1,
          "one log line for the one hold")
    tail = [f for f in live if not f.get("wheel")
            and f["t"] > (held[-1]["t"] + 1000 if held else 0)]
    check(bool(tail) and all(f.get("weapon_hash") == rifle for f in tail),
          "pick is stable once the settle window has passed")

    check("UNKNOWN NATIVE" not in err and "ARITY MISMATCH" not in err
          and "UNEXPECTED CONTROL" not in err,
          "mock saw no unknown native / arity mismatch / wrong control")
    check(rc == 0, "host exited cleanly (rc=%s)" % rc)
    if failures:
        print("---- stderr ----\n" + err[-2000:])
        print("---- ThorBridgeV.log ----\n" + log[-3000:])


def test_blips():
    """On foot throughout, three blips on the map. The plugin's census
    must list them nearest first; route_blip must set the waypoint and
    report it as ours; at 4 s the mock player drags the waypoint by
    hand, after which it is no longer ours; route_clear removes it."""
    print("scenario: blips")
    s = Session("blips", 13)
    check(s.sock is not None, "bridge accepts a connection")
    if not s.sock:
        print(s.close())
        return
    blips_frames = []
    acks = {}
    phase = 0            # 0 wait census, 1 routed, 2 dragged, 3 cleared, 4 rerouted
    seen = {}            # phase -> first state frame seen in it
    for f in s.frames(10):
        if f["type"] == "blips":
            blips_frames.append(f)
        if f["type"] == "ack":
            acks[f["id"]] = f
        if f["type"] != "state" or not f.get("in_game"):
            continue
        wp = f.get("waypoint", {})
        if phase == 0 and blips_frames:
            s.send_cmd(1, "route_blip", "blip", 301)
            s.send_cmd(2, "route_blip", "blip", 999)
            phase = 1
        elif phase == 1 and wp.get("active") and wp.get("ours"):
            seen.setdefault(1, f)
        if phase == 1 and 1 in seen and wp.get("active") and not wp.get("ours"):
            seen[2] = f
            phase = 2
            s.send_cmd(3, "route_clear", "value", 0)
        elif phase == 2 and not wp.get("active"):
            seen[3] = f
            phase = 3
            s.send_cmd(4, "route_blip", "blip", 302)
        elif phase == 3 and wp.get("active") and wp.get("ours"):
            seen[4] = f
            phase = 4
            s.send_cmd(5, "blip_census", "value", 0)
    rc, err, log = s.close()

    check(bool(blips_frames), "a blips frame arrived")
    first = blips_frames[0] if blips_frames else {"blips": []}
    ids = [b["id"] for b in first["blips"]]
    check(ids == [302, 301, 303], "census lists the three blips nearest first (%s)" % ids)
    if ids == [302, 301, 303]:
        b = {x["id"]: x for x in first["blips"]}
        check(b[301]["sprite"] == 78 and b[302]["sprite"] == 1 and b[303]["sprite"] == 52,
              "sprites are the game's raw ids")
        check(abs(b[301]["dist_m"] - 500) < 2 and abs(b[302]["dist_m"] - 50) < 2
              and abs(b[303]["dist_m"] - 5000) < 2,
              "distances measured from the player ped")
        check(b[301]["x"] == 300.0 and b[301]["y"] == 400.0 and b[301]["z"] == 10.0,
              "Vector3 returns read all three components")
    check(len(blips_frames) == 1,
          "an unchanged census is not re-sent (%d frames)" % len(blips_frames))
    check(acks.get(1, {}).get("ok") is True, "route_blip on a listed blip -> ok")
    check(acks.get(2, {}).get("ok") is False
          and acks.get(2, {}).get("err") == "blip not in the last scan",
          "route_blip on an unlisted id is refused")
    check(1 in seen and seen[1]["waypoint"].get("blip") == 301,
          "state reports the waypoint as active, ours, on blip 301")
    check("[mock] waypoint set at (300, 400)" in err,
          "SET_NEW_WAYPOINT was called with the blip's x/y")
    check(2 in seen and seen[2]["waypoint"].get("blip") == 0,
          "after the player drags it, the waypoint is active but not ours")
    check("moved by the player" in log, "plugin logged the hand move")
    check(acks.get(3, {}).get("ok") is True and 3 in seen,
          "route_clear -> ok and the waypoint goes inactive")
    check(acks.get(4, {}).get("ok") is True and 4 in seen
          and seen[4]["waypoint"].get("blip") == 302,
          "a second route_blip works after the clear")
    check(acks.get(5, {}).get("ok") is True and "census ver=" in log
          and "id=301 sprite=78" in log,
          "blip_census writes the table to the log")
    check("first census: 3 blip(s)" in log, "first pass logged once")

    check("UNKNOWN NATIVE" not in err and "ARITY MISMATCH" not in err
          and "without FIRST" not in err and "unknown blip" not in err,
          "mock saw no unknown native / arity mismatch / bad blip walk")
    check(rc == 0, "host exited cleanly (rc=%s)" % rc)
    if failures:
        print("---- stderr ----\n" + err[-2000:])
        print("---- ThorBridgeV.log ----\n" + log[-3000:])


def test_cheats():
    """Michael on foot, in-game throughout. Fire each cheat path and
    confirm the plugin: posts the right ack, flips the right mask bit
    on toggles, refuses IV-only / out-of-state ids with the right
    reason, defers the spawn-car ack across the model-load wait."""
    print("scenario: cheats")
    s = Session("cheats", 15)
    check(s.sock is not None, "bridge accepts a connection")
    if not s.sock:
        print(s.close())
        return
    states = []
    acks = {}
    sent = False
    hello = None
    for f in s.frames(13):
        if f["type"] == "hello":
            hello = f
        if f["type"] == "ack":
            acks[f["id"]] = f
        if f["type"] != "state":
            continue
        states.append(f)
        if not f.get("in_game"):
            continue
        if not sent:
            sent = True
            # One-shots.
            s.send_cmd(1, "cheat", "cheat", 3)   # wanted clear
            s.send_cmd(2, "cheat", "cheat", 1)   # heal + armor
            s.send_cmd(3, "cheat", "cheat", 2)   # wanted +1
            s.send_cmd(4, "cheat", "cheat", 7)   # next weather (EXTRASUNNY)
            s.send_cmd(5, "cheat", "cheat", 8)   # next time of day
            s.send_cmd(6, "cheat", "cheat", 6)   # fix vehicle (on foot -> refused)
            s.send_cmd(7, "cheat", "cheat", 22)  # IV-only toggle: refused
            s.send_cmd(8, "cheat", "cheat", 99)  # unknown
            # Toggles: on, off, on. The mask bits land on later frames.
            s.send_cmd(10, "cheat", "cheat", 20)  # invincible on
            s.send_cmd(11, "cheat", "cheat", 20)  # invincible off
            s.send_cmd(12, "cheat", "cheat", 26)  # slow motion on
            # Spawn-car: ack is deferred until the mock reports loaded.
            s.send_cmd(20, "cheat", "cheat", 10)  # spawn adder
            s.send_cmd(21, "cheat", "cheat", 10)  # second request: refused
    rc, err, log = s.close()

    check(hello is not None and hello.get("cheats") is True,
          "hello advertises cheats support")

    # The on-foot cheat scenario never enters a vehicle; sanity-check it.
    check(all(f.get("in_vehicle") is False for f in states if f.get("in_game")),
          "cheats scenario stays on foot")

    # One-shots.
    check(acks.get(1, {}).get("ok") is True, "wanted clear -> ok")
    check(acks.get(2, {}).get("ok") is True, "heal -> ok")
    check(acks.get(3, {}).get("ok") is True, "wanted +1 -> ok")
    check(acks.get(4, {}).get("ok") is True, "next weather -> ok")
    check(acks.get(5, {}).get("ok") is True, "next time of day -> ok")
    check(acks.get(6, {}).get("ok") is False
          and acks.get(6, {}).get("err") == "not in a vehicle",
          "fix vehicle on foot -> 'not in a vehicle'")
    check(acks.get(7, {}).get("ok") is False
          and acks.get(7, {}).get("err") == "cheat not in this build",
          "IV-only toggle (fast reload) -> 'cheat not in this build'")
    check(acks.get(8, {}).get("ok") is False
          and acks.get(8, {}).get("err") == "unknown cheat",
          "id out of range -> 'unknown cheat'")

    # The mock reports health through GET_ENTITY_HEALTH after a heal
    # writes it; wanted follows the same pattern.
    post_heal = [f for f in states if f.get("in_game")
                 and acks.get(2, {}).get("ok") is True]
    check(any(f.get("health") == 200 for f in post_heal),
          "health reaches 200 in a later state frame after heal")
    # Wanted went 3 -> clear (0) -> +1 (1). Last live frame has 1.
    live = [f for f in states if f.get("in_game")]
    check(bool(live) and live[-1].get("wanted") == 1,
          "wanted ends at 1 (clear then +1)")

    # Toggle mask tracks the two taps on 20 then the final tap on 26.
    def bit(f, id): return (f.get("cheats_mask", 0) >> (id - 20)) & 1
    after_tog_on  = [f for f in live if acks.get(10, {}).get("ok") is True]
    after_tog_off = [f for f in live if acks.get(11, {}).get("ok") is True]
    after_slow    = [f for f in live if acks.get(12, {}).get("ok") is True]
    check(any(bit(f, 20) == 1 for f in after_tog_on),
          "invincible on -> mask bit 0 set")
    check(any(bit(f, 20) == 0 for f in after_tog_off),
          "invincible off -> mask bit 0 cleared")
    check(any(bit(f, 26) == 1 for f in after_slow),
          "slow motion on -> mask bit 6 set")
    # Final state: only bit 6 set.
    check(live[-1].get("cheats_mask") == (1 << 6),
          "final mask = 0x40 (slow motion only) got 0x%X"
          % live[-1].get("cheats_mask", 0))

    # Spawn car: first ack ok (eventually), second refused as in progress.
    check(acks.get(20, {}).get("ok") is True,
          "spawn car -> ok (ack after model load)")
    check(acks.get(21, {}).get("ok") is False
          and acks.get(21, {}).get("err") == "spawn in progress",
          "second spawn while one is pending -> refused")

    check("[cheats] session marked cheat-active" in log,
          "STAT_SET_CHEAT_IS_ACTIVE fired once per session")
    check("UNKNOWN NATIVE" not in err and "ARITY MISMATCH" not in err,
          "mock saw no unknown native / arity mismatch")
    check(rc == 0, "host exited cleanly (rc=%s)" % rc)
    if failures:
        print("---- stderr ----\n" + err[-2000:])
        print("---- ThorBridgeV.log ----\n" + log[-3000:])


def test_paint():
    """In a vehicle throughout; the mock reports the current palette
    pair + the two custom-colour flags. Exercises every paint command:
    respray writes a fresh pair and fires the fix path, next colour
    bumps c1 (wrapping at kPaletteMax), swap reverses the pair, and
    paint_rgb sets both custom-layer flags true (then next colour
    clears the primary one via CLEAR_VEHICLE_CUSTOM_PRIMARY_COLOUR).
    """
    print("scenario: paint")
    s = Session("paint", 15)
    check(s.sock is not None, "bridge accepts a connection")
    if not s.sock:
        print(s.close())
        return
    states = []
    acks = {}
    hello = None
    phase = 0
    for f in s.frames(13):
        if f["type"] == "hello":
            hello = f
        if f["type"] == "ack":
            acks[f["id"]] = f
        if f["type"] != "state":
            continue
        states.append(f)
        if not f.get("in_game") or not f.get("in_vehicle"):
            continue
        if phase == 0:
            phase = 1
            # One-shots. All send under the same phase so acks pile up.
            s.send_cmd(1,  "cheat",     "cheat", 60)  # respray
            s.send_cmd(2,  "cheat",     "cheat", 61)  # next colour
            s.send_cmd(3,  "cheat",     "cheat", 62)  # swap
            s.send_cmd(4,  "paint_rgb", "rgb",   0x00B21E1E)
            s.send_cmd(5,  "cheat",     "cheat", 61)  # next colour clears custom
            # Wrap check: pound next colour a palette-worth of taps so
            # c1 wraps back around below kPaletteMax (plugin uses
            # (c1+1) % (kPaletteMax+1)).
            for i in range(10):
                s.send_cmd(10 + i, "cheat", "cheat", 61)
    rc, err, log = s.close()

    check(hello is not None and hello.get("cheats") is True,
          "hello advertises cheats support")

    live_in_veh = [f for f in states
                   if f.get("in_game") and f.get("in_vehicle")]
    check(bool(live_in_veh), "the paint scenario stays in a vehicle")

    # The vehicle block carries the pair + the custom flag.
    first = live_in_veh[0]
    v = first.get("vehicle", {})
    check(v.get("colour1") is not None and v.get("colour2") is not None,
          "vehicle block carries colour1 / colour2 before any tap")
    check(v.get("custom") is False, "custom flag starts false")

    # All four taps are ack'd ok.
    check(acks.get(1, {}).get("ok") is True, "respray -> ok")
    check(acks.get(2, {}).get("ok") is True, "next colour -> ok")
    check(acks.get(3, {}).get("ok") is True, "swap -> ok")
    check(acks.get(4, {}).get("ok") is True, "paint_rgb -> ok")
    check(acks.get(5, {}).get("ok") is True, "next colour (after rgb) -> ok")

    # The custom flag flipped true on the paint_rgb tap. Scan for it in
    # the stream so the race between the ack and the next state frame
    # does not cause a flake. Then it must return to false after any
    # id-shaped paint (which clears both layers).
    custom_true  = [f for f in live_in_veh if f.get("vehicle", {}).get("custom")]
    check(bool(custom_true),
          "a state frame reports custom=true while an RGB override is active")

    last_v = live_in_veh[-1].get("vehicle", {})
    check(last_v.get("custom") is False,
          "custom flag back to false after the trailing id-shaped paints")
    # Final palette index must be in-range — the wrap at kPaletteMax
    # kept it there.
    check(0 <= (last_v.get("colour1") or -1) <= 160,
          "colour1 within the palette after the wrap sweep (c1=%s)"
          % last_v.get("colour1"))

    # Plugin log lines: four distinct effect lines (one per effect
    # kind), not one per tap — "next colour" fires many times.
    check("[cheat] respray (60) -> ok" in log,
          "respray log line present")
    check("[cheat] next colour (61) -> ok" in log,
          "next colour log line present")
    check("[cheat] swap colours (62) -> ok" in log,
          "swap log line present")
    check("[cheat] custom colour (63) rgb=#B21E1E -> ok" in log,
          "custom colour log carries the hex RGB")

    check("UNKNOWN NATIVE" not in err and "ARITY MISMATCH" not in err,
          "mock saw no unknown native / arity mismatch")
    check(rc == 0, "host exited cleanly (rc=%s)" % rc)
    if failures:
        print("---- stderr ----\n" + err[-2000:])
        print("---- ThorBridgeV.log ----\n" + log[-3000:])


def test_paint_on_foot_refusal():
    """Reuse the story scenario (on foot for the first 2 s) to confirm
    every paint command is refused with "not in a vehicle" before the
    car entry. Keeps the paint scenario itself in-vehicle-only."""
    print("scenario: paint on-foot refusal (story)")
    s = Session("story", 10)
    check(s.sock is not None, "bridge accepts a connection")
    if not s.sock:
        print(s.close())
        return
    acks = {}
    sent = False
    for f in s.frames(5):
        if f["type"] == "ack":
            acks[f["id"]] = f
        if (not sent and f["type"] == "state" and f.get("in_game")
                and not f.get("in_vehicle")):
            sent = True
            s.send_cmd(1, "cheat", "cheat", 60)       # respray
            s.send_cmd(2, "cheat", "cheat", 61)       # next
            s.send_cmd(3, "cheat", "cheat", 62)       # swap
            s.send_cmd(4, "paint_rgb", "rgb", 0x00FFFFFF)
    rc, err, log = s.close()
    for cid in (1, 2, 3, 4):
        a = acks.get(cid, {})
        check(a.get("ok") is False and a.get("err") == "not in a vehicle",
              "paint cmd id=%d on foot -> 'not in a vehicle'" % cid)
    check("UNKNOWN NATIVE" not in err and "ARITY MISMATCH" not in err,
          "mock saw no unknown native / arity mismatch")
    check(rc == 0, "host exited cleanly (rc=%s)" % rc)


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
    test_wheel()
    test_blips()
    test_cheats()
    test_paint()
    test_paint_on_foot_refusal()

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
