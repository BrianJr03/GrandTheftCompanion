#!/usr/bin/env python3
"""One-shot calibration sample capture.

Usage: scripts/calib_capture.py <N>

Steps:
  1. adb forward tcp:47801 → 47801 (idempotent)
  2. Read one `type=state` frame from the plugin socket; it must carry a
     `pos` and be `paused=true` (so Niko is standing still on the pause
     map). Not paused → bail with a clear message.
  3. Screencap the primary display (pause-map, 1920x1080).
  4. Screencap the external display (companion atlas, 1240x1080).
  5. Write all three to claude/calibration-samples/sample_<NN>/.
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

DEVICE = "114202"
PRIMARY_DISPLAY_ID = "4630946441858561667"
EXTERNAL_DISPLAY_ID = "4630946482288158084"
BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 47801
REPO = Path(__file__).resolve().parent.parent
OUT_ROOT = REPO / "claude" / "calibration-samples"


def adb(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", DEVICE, *args], check=False,
                          capture_output=True)


def forward_bridge() -> None:
    r = adb("forward", f"tcp:{BRIDGE_PORT}", f"tcp:{BRIDGE_PORT}")
    if r.returncode != 0:
        sys.exit(f"adb forward failed: {r.stderr.decode('utf-8', 'ignore')}")


def read_one_state(timeout_s: float = 3.0) -> dict:
    """Read the socket for up to timeout_s and return the latest full
    state frame carrying a `pos`. Bail if none seen."""
    s = socket.socket()
    s.settimeout(timeout_s)
    s.connect((BRIDGE_HOST, BRIDGE_PORT))
    buf = b""
    deadline = time.time() + timeout_s
    latest = None
    while time.time() < deadline:
        try:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
            # Try parsing complete lines; keep the latest full `state`.
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                try:
                    f = json.loads(line.decode("utf-8", "ignore"))
                except Exception:
                    continue
                if f.get("type") == "state" and f.get("pos"):
                    latest = f
        except socket.timeout:
            break
    s.close()
    if latest is None:
        sys.exit("no state frame with pos read from plugin socket")
    return latest


def screencap(display_id: str) -> bytes:
    r = adb("exec-out", "screencap", "-d", display_id, "-p")
    if r.returncode != 0 or not r.stdout:
        sys.exit(f"screencap {display_id} failed: "
                 f"{r.stderr.decode('utf-8', 'ignore')}")
    return r.stdout


def main() -> int:
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        print(__doc__, file=sys.stderr)
        return 2
    n = int(sys.argv[1])
    forward_bridge()
    state = read_one_state()
    if not state.get("paused"):
        sys.exit(f"state frame is not paused (paused={state.get('paused')}). "
                 "Pause the game with the map tab visible, then retry.")
    pos = state["pos"]
    print(f"[sample {n:02d}] pos=(x={pos['x']:.2f}, y={pos['y']:.2f}, "
          f"z={pos['z']:.2f}, h={pos.get('h', 0):.2f}) paused=true")

    primary_png = screencap(PRIMARY_DISPLAY_ID)
    external_png = screencap(EXTERNAL_DISPLAY_ID)

    out_dir = OUT_ROOT / f"sample_{n:02d}"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "primary.png").write_bytes(primary_png)
    (out_dir / "external.png").write_bytes(external_png)
    (out_dir / "state.json").write_text(json.dumps(state, indent=2) + "\n")
    print(f"  wrote {out_dir}/{{primary,external}}.png + state.json "
          f"({len(primary_png)} + {len(external_png)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
