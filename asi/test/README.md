# Host-side test for ThorBridge.asi's weapon-wheel logic

```
cd asi
./test/run_tests.sh        # needs only a C++17 compiler for this machine
```

`src/weapons.cpp` (owned-weapon scan, LB hold filter, `set_weapon` checks)
is written without `<windows.h>` and reaches the game only through the
three functions in `src/weapon_natives.h`. `weapons_test.cpp` supplies
fakes for those and runs the logic as an ordinary program.

**What this proves:** the scan never reads a slot outside the ped's
12-entry weapon array (the suspected cause of the crash the first time
the scan was tried) and always supplies all three out-pointers; the ammo
native is never asked about weapon 0; caching and rescans; the hold
filter that keeps a tap of LB from flashing the wheel; which `set_weapon`
requests are refused.

**What it cannot prove:** that the real natives behave the way the fake
assumes, that the argument marshalling in `src/weapon_natives.cpp` is
right, or anything about the game. Natives level 4 has to be tried on a
device before it becomes the default.
