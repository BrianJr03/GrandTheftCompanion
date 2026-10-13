# Off-device test for ThorBridgeV.asi

`mock_shv.cpp` builds a stand-in `ScriptHookV.dll` (same exported symbol
names, canned story-mode answers), `mock_host.cpp` loads the plugin the
way an ASI loader would, and `run_mock_test.py` connects to the bridge
socket like the companion app and checks every frame.

```
cd asi-v
./build.sh -DTHORBRIDGE_BUILD_MOCK=ON
python3 test/run_mock_test.py          # needs 64-bit Wine; set $WINE to override
```

Scenarios (`MOCK_SCENARIO`): `story` (Michael on foot → in a car → switch
to Franklin), `online`, `loading`, `other` (non-protagonist ped), `stall`
(game stops resuming the script fiber), `wheel` (on foot; the weapon-wheel
control is held for 2.5 s, and after release the mock re-selects the weapon
that was in hand when the wheel opened), `blips` (on foot; three blips on
the map, and at 4 s the mock player drags the waypoint by hand). Every
scenario has the same three map blips. The test also runs once with no
`ScriptHookV.dll` present to check safe mode.

**What this proves:** export binding, native argument/return marshalling
(the mock aborts on an unknown native or a wrong argument count; Vector3
returns are three padded slots), JSON framing and escaping, list
versioning, command round-trips, stall detection, that a weapon picked
while the wheel control is held is deferred to release and survives the
mock's own re-selection, and that the blip census lists the map nearest
first, `route_blip` sets the waypoint on a blip's coordinates, a waypoint
the player moves stops being "ours", and `route_clear` removes it.

**What it cannot prove:** anything about the real game or the real
Script Hook V — whether it loads under Wine on the device, what the
natives actually return, what happens while the game is paused, or what
the real weapon wheel does to the selected weapon when it closes (the
`wheel` scenario encodes a guess). That needs a Thor.
