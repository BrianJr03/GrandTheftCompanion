# Host-side tests for ThorBridge.asi's weapon-wheel and blip logic

```
cd asi
./test/run_tests.sh        # needs only a C++17 compiler for this machine
```

`src/weapons.cpp` (owned-weapon scan, LB hold filter, `set_weapon` checks)
is written without `<windows.h>` and reaches the game only through the
three functions in `src/weapon_natives.h`. `weapons_test.cpp` supplies
fakes for those and runs the logic as an ordinary program. `src/blips.cpp`
(map blip census, GPS route on/off, natives level 5) is built the same
way on top of `src/blip_natives.h`, and `blips_test.cpp` fakes a map.

**What this proves:** the scan never reads a slot outside the ped's
12-entry weapon array (the suspected cause of the crash the first time
the scan was tried) and always supplies all three out-pointers; the ammo
native is never asked about weapon 0; caching and rescans; the hold
filter that keeps a tap of LB from flashing the wheel; which `set_weapon`
requests are refused.

For the blips: the FIRST/NEXT walk covers every blip type and stops,
per-blip reads only touch live handles, the list is nearest first and
capped by distance, the 2 s rescan clock (across a tick wrap), route
on/off bookkeeping (old route off before the new one on, no SET_ROUTE on
a dead blip), and which `route_blip` requests are refused.

**What it cannot prove:** that the real natives behave the way the fakes
assume, that the argument marshalling in `src/weapon_natives.cpp` and
`src/blip_natives.cpp` is right, or anything about the game. Natives
level 4 had to be tried on a device before it became the default; level
5 is waiting for the same.
