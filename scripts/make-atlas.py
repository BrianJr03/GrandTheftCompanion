#!/usr/bin/env python3
# make-atlas.py — build and install the GTC atlas map image from
# the user's own GTA IV / GTA V game files.
#
# Subcommands:
#   stitch   assemble a directory of exported tiles into one PNG
#   install  push a PNG to the GTC app's files/map/<game>.png
#   extract  (experimental) pull tiles out of game archives
#   all      extract → stitch → install
#   doctor   report what the script found (adb, termux, game dirs, app dir)
#
# Standard library + Pillow only. No numpy, nothing with a native
# build, so Termux (pkg install python python-pillow) works too.
#
# The repo ships ONLY the tool. No tile, stitched image, decryption
# key, or test fixture derived from a game file ever enters the
# repo (LEGAL.md).

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

# Pillow is imported lazily: install / doctor / extract do not touch
# any image, so a user who already has a PNG and just wants to push it
# can run the script in Termux without python-pillow.
def _require_pillow():
    try:
        from PIL import Image  # noqa: F401
        return Image
    except ImportError:  # pragma: no cover
        sys.stderr.write(
            "error: Pillow not installed.\n"
            "  Desktop: pip install Pillow\n"
            "  Termux:  pkg install python python-pillow\n"
        )
        sys.exit(2)


# ---------- shared utilities ----------

def log(msg: str) -> None:
    print(f"==> {msg}", file=sys.stderr)


def warn(msg: str) -> None:
    print(f"warning: {msg}", file=sys.stderr)


def die(msg: str, code: int = 1):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(code)


def is_termux() -> bool:
    prefix = os.environ.get("PREFIX", "")
    return "com.termux" in prefix


def default_app_package(game: str) -> str:
    # Resolve applicationId from app/build.gradle.kts if we can find
    # it next to the script; otherwise return the production id.
    # Debug variant adds `.debug`; `--package` lets the user override.
    here = Path(__file__).resolve().parent
    gradle = here.parent / "app" / "build.gradle.kts"
    pkg = "jr.brian.thorhud"
    if gradle.is_file():
        text = gradle.read_text(encoding="utf-8", errors="replace")
        m = re.search(r'applicationId\s*=\s*"([^"]+)"', text)
        if m:
            pkg = m.group(1)
    return pkg


# ---------- stitch ----------

# IV naming schemes we understand:
#   radar00..radar63     — single flat index, grid inferred by count
#   radar_<col>_<row>    — explicit grid
# V naming schemes we understand:
#   minimap_sea_<col>_<row>   — the water tile set
#   minimap_<col>_<row>       — land only
#   minimap_[0-9]+            — flat index (rare)
#
# Everything else: user passes --cols/--rows and we sort by name.

TILE_EXTS = (".png", ".dds", ".tif", ".tiff", ".bmp")


@dataclass
class TilePlacement:
    """One tile's position in the final grid."""
    path: Path
    col: int
    row: int


@dataclass
class Grid:
    cols: int
    rows: int
    placements: list[TilePlacement]
    scheme: str  # debug label for logs


def _tile_files(tiles_dir: Path) -> list[Path]:
    out: list[Path] = []
    for p in sorted(tiles_dir.iterdir()):
        if p.is_file() and p.suffix.lower() in TILE_EXTS:
            out.append(p)
    return out


def infer_grid(
    tiles_dir: Path,
    cols_override: Optional[int] = None,
    rows_override: Optional[int] = None,
) -> Grid:
    files = _tile_files(tiles_dir)
    if not files:
        die(f"no tile files in {tiles_dir}")

    pat_cr_sea = re.compile(r"^minimap_sea_(\d+)_(\d+)", re.IGNORECASE)
    pat_cr = re.compile(r"^minimap_(\d+)_(\d+)", re.IGNORECASE)
    pat_iv_cr = re.compile(r"^radar_(\d+)_(\d+)", re.IGNORECASE)
    pat_iv_flat = re.compile(r"^radar(\d+)$", re.IGNORECASE)
    pat_v_flat = re.compile(r"^minimap_?(\d+)$", re.IGNORECASE)

    # Prefer explicit col/row names.
    cr_hits: list[tuple[Path, int, int]] = []
    scheme = ""
    for p in files:
        stem = p.stem
        for pat, label in (
            (pat_cr_sea, "minimap_sea_c_r"),
            (pat_cr, "minimap_c_r"),
            (pat_iv_cr, "radar_c_r"),
        ):
            m = pat.match(stem)
            if m:
                cr_hits.append((p, int(m.group(1)), int(m.group(2))))
                scheme = label
                break

    if cr_hits:
        cols = max(c for _, c, _ in cr_hits) + 1
        rows = max(r for _, _, r in cr_hits) + 1
        placements = [TilePlacement(p, c, r) for p, c, r in cr_hits]
        return Grid(cols=cols, rows=rows, placements=placements, scheme=scheme)

    # Flat-index schemes: need either an override or a perfect square.
    flat_hits: list[tuple[Path, int]] = []
    for p in files:
        stem = p.stem
        m = pat_iv_flat.match(stem) or pat_v_flat.match(stem)
        if m:
            flat_hits.append((p, int(m.group(1))))

    if flat_hits:
        flat_hits.sort(key=lambda t: t[1])
        n = len(flat_hits)
        if cols_override and rows_override:
            cols, rows = cols_override, rows_override
            if cols * rows < n:
                die(
                    f"--cols {cols} × --rows {rows} = {cols*rows} "
                    f"cannot hold {n} tiles"
                )
            scheme = "flat index + explicit grid"
        else:
            # Guess a square; warn if it is not.
            root = int(round(n ** 0.5))
            if root * root == n:
                cols = rows = root
                scheme = f"flat index, square-guess {cols}×{rows}"
            else:
                die(
                    f"{n} flat-indexed tiles is not a square grid; "
                    "pass --cols and --rows explicitly"
                )
        # IV flat radar indices are column-major in practice (radar00
        # is NW, radar01 is one tile south, ...). Row-major and
        # column-major both produce a square; the user picks the
        # right one with --flip-rows / --flip-cols after a
        # --dry-run.  Here, default to row-major, left-to-right,
        # top-to-bottom.
        placements = [
            TilePlacement(p, i % cols, i // cols) for p, i in flat_hits
        ]
        return Grid(cols=cols, rows=rows, placements=placements, scheme=scheme)

    # Last resort: sort by name, require explicit --cols/--rows.
    if not (cols_override and rows_override):
        die(
            "could not parse tile names and no --cols/--rows given.\n"
            f"first file: {files[0].name}"
        )
    cols, rows = cols_override, rows_override
    if cols * rows < len(files):
        die(
            f"--cols {cols} × --rows {rows} = {cols*rows} cannot hold "
            f"{len(files)} tiles"
        )
    placements = [
        TilePlacement(p, i % cols, i // cols) for i, p in enumerate(files)
    ]
    return Grid(cols=cols, rows=rows, placements=placements, scheme="name sort")


def _open_tile(path: Path):
    Image = _require_pillow()
    im = Image.open(path)
    im.load()
    if im.mode != "RGBA":
        im = im.convert("RGBA")
    return im


def stitch_grid(
    grid: Grid,
    *,
    flip_rows: bool = False,
    flip_cols: bool = False,
    missing_fill: tuple[int, int, int, int] = (0, 0, 0, 255),
):
    if not grid.placements:
        die("grid has no placements")

    Image = _require_pillow()
    first = _open_tile(grid.placements[0].path)
    tile_w, tile_h = first.size

    canvas = Image.new(
        "RGBA",
        (tile_w * grid.cols, tile_h * grid.rows),
        missing_fill,
    )

    seen: set[tuple[int, int]] = set()
    for place in grid.placements:
        tile = _open_tile(place.path)
        if tile.size != (tile_w, tile_h):
            die(
                f"tile {place.path.name} is {tile.size}, "
                f"expected {(tile_w, tile_h)}"
            )
        c = (grid.cols - 1 - place.col) if flip_cols else place.col
        r = (grid.rows - 1 - place.row) if flip_rows else place.row
        canvas.paste(tile, (c * tile_w, r * tile_h))
        seen.add((c, r))

    missing = []
    for r in range(grid.rows):
        for c in range(grid.cols):
            if (c, r) not in seen:
                missing.append((c, r))
    if missing:
        warn(
            f"{len(missing)} missing tile(s), filled with "
            f"RGBA{missing_fill}: {missing[:4]}"
            + (f" (+{len(missing)-4} more)" if len(missing) > 4 else "")
        )
    return canvas


def _downscale_if_needed(im, max_side: int):
    Image = _require_pillow()
    long_side = max(im.size)
    if long_side <= max_side:
        return im
    scale = max_side / long_side
    new = (int(im.size[0] * scale), int(im.size[1] * scale))
    warn(
        f"image long side {long_side} > --max-side {max_side}; "
        f"downscaling to {new[0]}×{new[1]}"
    )
    return im.resize(new, Image.LANCZOS)


def cmd_stitch(args: argparse.Namespace) -> int:
    tiles_dir = Path(args.tiles).expanduser().resolve()
    if not tiles_dir.is_dir():
        die(f"tiles dir not found: {tiles_dir}")

    grid = infer_grid(tiles_dir, args.cols, args.rows)
    log(f"grid scheme: {grid.scheme}")
    log(
        f"grid: {grid.cols}×{grid.rows} tiles, "
        f"{len(grid.placements)} tile files"
    )
    if args.dry_run:
        first = grid.placements[0]
        log(
            f"first tile: {first.path.name} → (col={first.col}, row={first.row})"
        )
        if len(grid.placements) > 1:
            last = grid.placements[-1]
            log(
                f"last tile:  {last.path.name} → (col={last.col}, row={last.row})"
            )
        return 0

    img = stitch_grid(grid, flip_rows=args.flip_rows, flip_cols=args.flip_cols)
    img = _downscale_if_needed(img, args.max_side)
    out_path = Path(args.out).expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    # Flatten to RGB — the app does not need an alpha channel and PNG
    # without alpha is noticeably smaller.
    Image = _require_pillow()
    flat = Image.new("RGB", img.size, (0, 0, 0))
    flat.paste(img, mask=img.split()[-1] if img.mode == "RGBA" else None)
    flat.save(out_path, format="PNG", optimize=True)
    log(f"wrote {out_path} ({flat.size[0]}×{flat.size[1]})")
    return 0


# ---------- install ----------

def app_map_dir(package: str) -> str:
    # The GTC app writes to its own filesDir, which under
    # Android/data/<pkg>/files/map/ is also shell-readable on Android
    # 10- devices with Legacy external storage. On 11+ this location
    # is the app's "externalFilesDir", shared with any process that
    # has the right scoped-storage permission; adb shell writes
    # fine, Termux typically cannot (hence the Download/ fallback).
    return f"/storage/emulated/0/Android/data/{package}/files/map"


def _adb_devices(adb_bin: str) -> list[str]:
    out = subprocess.run(
        [adb_bin, "devices"], check=True, capture_output=True, text=True
    ).stdout
    serials: list[str] = []
    for line in out.splitlines()[1:]:
        s = line.strip()
        if not s:
            continue
        parts = s.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def _install_via_adb(
    image: Path, game: str, package: str, keep_calibration: bool
) -> int:
    adb = shutil.which("adb")
    if not adb:
        die("adb not on PATH")
    serials = _adb_devices(adb)
    env_serial = os.environ.get("ANDROID_SERIAL")
    if env_serial:
        if env_serial not in serials:
            die(f"ANDROID_SERIAL={env_serial} not in `adb devices`")
        serial = env_serial
    elif len(serials) == 0:
        die("no adb devices. Connect one or set ANDROID_SERIAL.")
    elif len(serials) > 1:
        die(f"multiple adb devices ({serials}); set ANDROID_SERIAL")
    else:
        serial = serials[0]
    log(f"adb device: {serial}")

    remote_dir = app_map_dir(package)
    remote_png = f"{remote_dir}/{game}.png"
    remote_json = f"{remote_dir}/{game}.json"

    subprocess.run(
        [adb, "-s", serial, "shell", "mkdir", "-p", remote_dir],
        check=True,
    )
    subprocess.run(
        [adb, "-s", serial, "push", str(image), remote_png], check=True
    )

    if not keep_calibration:
        # Deleting a non-existent file returns non-zero on some
        # shells; swallow the error.
        subprocess.run(
            [adb, "-s", serial, "shell", "rm", "-f", remote_json],
            check=False,
        )

    ls = subprocess.run(
        [adb, "-s", serial, "shell", "ls", "-l", remote_png],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    log(f"on device: {ls}")
    log(f"app will read: {remote_png}")
    log("remember to recalibrate the atlas in GTC.")
    return 0


def _install_local(
    image: Path, game: str, package: str, keep_calibration: bool,
    app_dir_override: Optional[str],
) -> int:
    # Running on the device itself (Termux). First try the app dir;
    # fall back to Download/gtc-atlas/ if we cannot write there.
    remote_dir = app_dir_override or app_map_dir(package)
    remote_png = Path(remote_dir) / f"{game}.png"
    remote_json = Path(remote_dir) / f"{game}.json"

    try:
        remote_png.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(image, remote_png)
        if not keep_calibration and remote_json.exists():
            try:
                remote_json.unlink()
            except OSError:
                pass
        log(f"copied → {remote_png}")
        log(f"app will read: {remote_png}")
        log("remember to recalibrate the atlas in GTC.")
        return 0
    except (PermissionError, OSError) as e:
        warn(f"could not write app dir ({e.__class__.__name__}: {e})")

    # Fallback: Download/gtc-atlas/
    fallback_dir = Path("/storage/emulated/0/Download/gtc-atlas")
    fallback_png = fallback_dir / f"{game}.png"
    try:
        fallback_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(image, fallback_png)
    except (PermissionError, OSError) as e:
        die(
            f"could not write fallback either: {e}\n"
            "Run `termux-setup-storage` first and grant storage access, "
            "then re-run."
        )
    print(file=sys.stderr)
    log(f"wrote fallback: {fallback_png}")
    log("Termux cannot write to the app's data dir on Android 11+.")
    log("Open GTC and use its atlas 'import image' button if available,")
    log("or copy with a file manager:")
    log(f"  {fallback_png}  →  {remote_png}")
    return 0


def cmd_install(args: argparse.Namespace) -> int:
    image = Path(args.image).expanduser().resolve()
    if not image.is_file():
        die(f"image not found: {image}")
    game = args.game
    package = args.package or default_app_package(game)
    log(f"package: {package}")

    mode = args.mode
    if mode == "auto":
        mode = "local" if is_termux() else "adb"
        log(f"auto-selected install mode: {mode}")

    if mode == "adb":
        return _install_via_adb(image, game, package, args.keep_calibration)
    return _install_local(
        image, game, package, args.keep_calibration, args.app_dir
    )


# ---------- extract (experimental) ----------

def _search_game_dir(game_dir: Path, game: str) -> dict:
    """Doctor-style peek at a game directory. No archive parsing."""
    out: dict = {"game": game, "root": str(game_dir), "found": {}}
    if not game_dir.is_dir():
        out["error"] = "not a directory"
        return out
    found = out["found"]
    if game == "iv":
        # Look for the exe and the radar texture archive.
        for name in ("GTAIV.exe", "LaunchGTAIV.exe", "EFLC.exe"):
            p = game_dir / name
            if p.is_file():
                found[name] = p.stat().st_size
        for sub in ("pc/textures", "pc/data", "pc/anim"):
            p = game_dir / sub
            if p.is_dir():
                found[sub + "/"] = sorted(x.name for x in p.iterdir())[:6]
        # Prefer radar.img if present.
        candidates = list(game_dir.rglob("radar.img"))
        if candidates:
            found["radar.img"] = [str(c) for c in candidates[:4]]
    elif game == "v":
        for name in ("GTA5.exe", "GTAVLauncher.exe"):
            p = game_dir / name
            if p.is_file():
                found[name] = p.stat().st_size
        # Minimap tiles live inside x64*.rpf; we cannot read them
        # here, just note presence.
        rpfs = sorted(game_dir.glob("x64*.rpf"))
        if rpfs:
            found["x64*.rpf"] = [p.name for p in rpfs[:8]]
        updates = sorted(game_dir.glob("update/*.rpf"))
        if updates:
            found["update/*.rpf"] = [p.name for p in updates[:8]]
    return out


def cmd_extract(args: argparse.Namespace) -> int:
    game_dir = Path(args.game_dir).expanduser().resolve()
    out_dir = Path(args.out).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    # Both archive formats need machinery we do not currently ship:
    # IV wants IMG v3 + .wtd decoding, V wants encrypted RPF7 and
    # keys derived from the user's own GTA5.exe. Rather than
    # shipping a half-finished parser that produces a wrong map,
    # extract prints precise guidance and defers to a PC tool.
    survey = _search_game_dir(game_dir, args.game)
    log(f"extract is experimental; archive parsing is not implemented.")
    log(f"game dir survey: {survey['found'] or '(nothing recognised)'}")

    if args.game == "iv":
        print(
            "\n"
            "To get IV radar tiles, on a PC:\n"
            "  1. Install OpenIV (openiv.com) and open your IV install.\n"
            "  2. Navigate to pc/textures/radar.img (or wherever your\n"
            "     install stores it).\n"
            "  3. Export every radar<nn>.wtd into a folder, then from\n"
            "     each .wtd export its texture as .dds or .png.\n"
            "  4. Point `make-atlas.py stitch --game iv --tiles <dir>` at it.\n"
            "\n"
            "On Android alone this is not currently possible with\n"
            "stock tooling; the extract subcommand will refuse to\n"
            "guess rather than produce a wrong map.\n",
            file=sys.stderr,
        )
    else:  # v
        print(
            "\n"
            "GTA V's archives (RPF7) are encrypted. The AES/NG keys\n"
            "are derived from your own GTA5.exe and are NOT shipped\n"
            "in this repo (LEGAL.md). To get V minimap tiles:\n"
            "  1. On a PC, use OpenIV or CodeWalker to open your\n"
            "     GTA V install (point it at GTA5.exe so it derives\n"
            "     the keys from your own binary).\n"
            "  2. Browse to update/update.rpf → x64/textures/pause_menu*\n"
            "     and export the `minimap_sea_<col>_<row>` tile set\n"
            "     (and `minimap_<col>_<row>` for land-only overrides).\n"
            "  3. Point `make-atlas.py stitch --game v --tiles <dir>` at it.\n"
            "\n"
            "On Android alone this is not currently possible without\n"
            "the derived keys, which must come from your own exe at\n"
            "run time. Not implemented.\n",
            file=sys.stderr,
        )
    return 2  # experimental: non-zero so `all` short-circuits cleanly


# ---------- all ----------

def cmd_all(args: argparse.Namespace) -> int:
    tmp_tiles = Path(args.tiles) if args.tiles else Path(
        f"/tmp/gtc-atlas-{args.game}-tiles"
    )
    tmp_tiles.mkdir(parents=True, exist_ok=True)

    extract_ns = argparse.Namespace(
        game=args.game, game_dir=args.game_dir, out=str(tmp_tiles)
    )
    rc = cmd_extract(extract_ns)
    if rc != 0:
        warn("extract did not produce tiles; aborting `all` run.")
        return rc

    stitch_ns = argparse.Namespace(
        game=args.game,
        tiles=str(tmp_tiles),
        out=args.out or f"{args.game}.png",
        dry_run=False,
        cols=None,
        rows=None,
        flip_rows=False,
        flip_cols=False,
        max_side=args.max_side,
    )
    rc = cmd_stitch(stitch_ns)
    if rc != 0:
        return rc

    install_ns = argparse.Namespace(
        game=args.game,
        image=stitch_ns.out,
        mode=args.mode,
        package=args.package,
        keep_calibration=args.keep_calibration,
        app_dir=args.app_dir,
    )
    return cmd_install(install_ns)


# ---------- doctor ----------

def cmd_doctor(args: argparse.Namespace) -> int:
    print("make-atlas doctor\n")
    print(f"  python     : {sys.version.split()[0]}")
    try:
        from PIL import Image as _I
        print(f"  Pillow     : {_I.__version__}")
    except Exception as e:
        print(f"  Pillow     : MISSING ({e}) — only stitch needs it")

    termux = is_termux()
    print(f"  termux     : {'yes' if termux else 'no'} "
          f"(PREFIX={os.environ.get('PREFIX','')!r})")

    adb = shutil.which("adb")
    print(f"  adb        : {adb or 'not on PATH'}")
    if adb:
        try:
            serials = _adb_devices(adb)
            print(f"  adb devs   : {serials or '(none)'}")
        except Exception as e:
            print(f"  adb devs   : error ({e})")

    pkg = default_app_package("v")
    print(f"  app pkg    : {pkg}")
    print(f"  app map dir: {app_map_dir(pkg)}")
    if termux:
        d = Path(app_map_dir(pkg))
        try:
            d.mkdir(parents=True, exist_ok=True)
            probe = d / ".make_atlas_probe"
            probe.write_text("ok", encoding="utf-8")
            probe.unlink()
            print(f"  app dir rw : yes")
        except Exception as e:
            print(f"  app dir rw : NO ({e.__class__.__name__})")
            print(f"               fallback would be /storage/emulated/0/Download/gtc-atlas/")

    # Game dir guesses.
    print("\n  likely game-dir roots to try with --game-dir:")
    guesses = [
        "/storage/emulated/0/Download/PC Rom/GTA IV/Game/Grand Theft Auto IV",
        "/storage/emulated/0/Download/PC Rom/GTA V/Game",
        "/storage/emulated/0/Android/data/gamehub.lite/files",
        "/storage/emulated/0/Android/data/app.gamenative/files",
    ]
    for g in guesses:
        p = Path(g)
        exists = p.is_dir()
        print(f"    [{'x' if exists else ' '}] {g}")
    return 0


# ---------- argparse ----------

def _add_stitch_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--game", choices=("v", "iv"), required=True)
    p.add_argument("--tiles", required=True, help="directory of exported tiles")
    p.add_argument(
        "--out",
        default=None,
        help="output PNG path (default: <game>.png in cwd)",
    )
    p.add_argument("--cols", type=int, default=None)
    p.add_argument("--rows", type=int, default=None)
    p.add_argument(
        "--flip-rows",
        action="store_true",
        help="reverse row order (first row becomes last)",
    )
    p.add_argument(
        "--flip-cols",
        action="store_true",
        help="reverse column order",
    )
    p.add_argument(
        "--max-side",
        type=int,
        default=8192,
        help="downscale if the longest side exceeds this (default 8192)",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="print the inferred grid and exit without stitching",
    )


def _add_install_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--game", choices=("v", "iv"), required=True)
    p.add_argument("--image", required=True)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument(
        "--adb",
        dest="mode",
        action="store_const",
        const="adb",
        help="install via adb to the connected device",
    )
    mode.add_argument(
        "--local",
        dest="mode",
        action="store_const",
        const="local",
        help="install on this device (Termux)",
    )
    p.set_defaults(mode="auto")
    p.add_argument(
        "--package",
        default=None,
        help="override applicationId (default: parsed from app/build.gradle.kts)",
    )
    p.add_argument(
        "--app-dir",
        default=None,
        help="override the target map directory (local only)",
    )
    p.add_argument(
        "--keep-calibration",
        action="store_true",
        help="do NOT delete the old <game>.json calibration beside the image",
    )


def _add_extract_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--game", choices=("v", "iv"), required=True)
    p.add_argument("--game-dir", required=True)
    p.add_argument("--out", required=True, help="output tiles directory")


def _add_all_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--game", choices=("v", "iv"), required=True)
    p.add_argument("--game-dir", required=True)
    p.add_argument(
        "--tiles",
        default=None,
        help="where to drop extracted tiles (default: /tmp/gtc-atlas-<game>-tiles)",
    )
    p.add_argument("--out", default=None, help="stitched PNG path")
    p.add_argument("--max-side", type=int, default=8192)
    mode = p.add_mutually_exclusive_group()
    mode.add_argument(
        "--adb", dest="mode", action="store_const", const="adb"
    )
    mode.add_argument(
        "--local", dest="mode", action="store_const", const="local"
    )
    p.set_defaults(mode="auto")
    p.add_argument("--package", default=None)
    p.add_argument("--app-dir", default=None)
    p.add_argument("--keep-calibration", action="store_true")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="make-atlas.py",
        description=(
            "Build and install the GTC atlas map image from your own "
            "game files. Works on a desktop with adb, or on the device "
            "itself inside Termux."
        ),
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("stitch", help="assemble tiles into one PNG")
    _add_stitch_args(p)
    p.set_defaults(func=lambda a: cmd_stitch(_fill_stitch_out(a)))

    p = sub.add_parser("install", help="push a PNG into GTC's files/map/")
    _add_install_args(p)
    p.set_defaults(func=cmd_install)

    p = sub.add_parser(
        "extract",
        help="(experimental) pull tiles out of game archives",
    )
    _add_extract_args(p)
    p.set_defaults(func=cmd_extract)

    p = sub.add_parser("all", help="extract → stitch → install")
    _add_all_args(p)
    p.set_defaults(func=cmd_all)

    p = sub.add_parser(
        "doctor",
        help="report what the script found (adb, termux, game dirs)",
    )
    p.set_defaults(func=cmd_doctor)

    return parser


def _fill_stitch_out(a: argparse.Namespace) -> argparse.Namespace:
    if not a.out:
        a.out = f"{a.game}.png"
    return a


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)
    return args.func(args)


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
