---
description: Trigger the build-apks CI workflow for a branch, wait for it, download the chosen variant, and install it with adb.
argument-hint: <branch> <debug|release>
---

Run the project's CI build-and-install helper.

Expected usage: `/ci-install <branch> <debug|release>`

Arguments passed: `$ARGUMENTS`

Steps:
1. Parse `$ARGUMENTS` into `<branch>` and `<debug|release>`. If either is missing, explain the usage and stop.
2. From the repo root, run `scripts/ci-apk.sh <branch> <variant>`.
3. Stream the script's output to the user as it runs. Do NOT retry on failure — if the script exits non-zero (CI build failed, signature mismatch, no device, etc.), surface the printed diagnostic verbatim and stop.
4. On success, repeat the final `Run URL`, `Commit`, and `Installed versionName/versionCode` summary at the end of your message so it's easy to spot.

Hard rules:
- Never add `--no-trigger` unless the user asked for it explicitly.
- Never run `adb uninstall` on your own, even if the script hits `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The script prints the fix; relay it and stop.
- Never push to the default branch (`main`) as part of this command. The script will push `<branch>` to origin if it is ahead — that is fine. If `<branch>` happens to be `main`, confirm with the user first before invoking the script.
- The Thor is adb serial `114202` — set `ANDROID_SERIAL=114202` if more than one device is attached. Otherwise the script picks the sole connected device automatically.
