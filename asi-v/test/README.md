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
(game stops resuming the script fiber). The test also runs once with no
`ScriptHookV.dll` present to check safe mode.

**What this proves:** export binding, native argument/return marshalling
(the mock aborts on an unknown native or a wrong argument count), JSON
framing and escaping, list versioning, command round-trips, stall
detection.

**What it cannot prove:** anything about the real game or the real
Script Hook V — whether it loads under Wine on the device, what the
natives actually return, what happens while the game is paused. That
needs a Thor.
