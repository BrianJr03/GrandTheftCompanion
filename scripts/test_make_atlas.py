"""Unit tests for make-atlas.py.

All fixtures are generated at test time — nothing derived from a
game file enters the repo (LEGAL.md).

Run with:
    python3 -m unittest discover scripts
"""

from __future__ import annotations

import importlib.util
import io
import os
import shutil
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image


# Load the dash-named script as a module.
_HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location(
    "make_atlas", _HERE / "make-atlas.py"
)
assert _SPEC and _SPEC.loader
make_atlas = importlib.util.module_from_spec(_SPEC)
# Register before exec so dataclasses can look up the module.
sys.modules["make_atlas"] = make_atlas
_SPEC.loader.exec_module(make_atlas)


def _mk_tile(path: Path, size: int, color: tuple[int, int, int]) -> None:
    Image.new("RGB", (size, size), color).save(path, format="PNG")


class GridInferenceTests(unittest.TestCase):

    def test_v_minimap_sea_grid(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            # 2 cols × 3 rows minimap_sea_<col>_<row>
            for c in range(2):
                for r in range(3):
                    _mk_tile(d / f"minimap_sea_{c}_{r}.png", 4, (0, 0, 0))
            grid = make_atlas.infer_grid(d)
            self.assertEqual((grid.cols, grid.rows), (2, 3))
            self.assertEqual(len(grid.placements), 6)
            self.assertIn("minimap_sea", grid.scheme)

    def test_v_minimap_land_grid(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            for c in range(3):
                for r in range(2):
                    _mk_tile(d / f"minimap_{c}_{r}.png", 4, (0, 0, 0))
            grid = make_atlas.infer_grid(d)
            self.assertEqual((grid.cols, grid.rows), (3, 2))

    def test_iv_radar_col_row_grid(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            for c in range(4):
                for r in range(4):
                    _mk_tile(d / f"radar_{c}_{r}.png", 4, (0, 0, 0))
            grid = make_atlas.infer_grid(d)
            self.assertEqual((grid.cols, grid.rows), (4, 4))

    def test_iv_flat_index_square_guess(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            # IV CE ships 64 radar tiles — perfect square.
            for i in range(64):
                _mk_tile(d / f"radar{i:02d}.png", 4, (0, 0, 0))
            grid = make_atlas.infer_grid(d)
            self.assertEqual((grid.cols, grid.rows), (8, 8))

    def test_name_sort_fallback_requires_explicit_grid(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            # Non-matching names
            for i in range(6):
                _mk_tile(d / f"blob_{i}.png", 4, (0, 0, 0))
            with self.assertRaises(SystemExit):
                make_atlas.infer_grid(d)
            # With override, works.
            grid = make_atlas.infer_grid(d, cols_override=3, rows_override=2)
            self.assertEqual((grid.cols, grid.rows), (3, 2))
            self.assertEqual(len(grid.placements), 6)


class StitchTests(unittest.TestCase):

    def _write_3x2(
        self, d: Path, *, size: int = 16
    ) -> dict[tuple[int, int], tuple[int, int, int]]:
        """3 cols × 2 rows of flat-colour tiles. Returns {(col,row): color}."""
        colors: dict[tuple[int, int, int], tuple[int, int, int]] = {}
        palette = {
            (0, 0): (255, 0, 0),
            (1, 0): (0, 255, 0),
            (2, 0): (0, 0, 255),
            (0, 1): (255, 255, 0),
            (1, 1): (0, 255, 255),
            (2, 1): (255, 0, 255),
        }
        for (c, r), col in palette.items():
            _mk_tile(d / f"minimap_sea_{c}_{r}.png", size, col)
            colors[(c, r)] = col
        return palette

    def test_3x2_corners(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            palette = self._write_3x2(d, size=16)
            grid = make_atlas.infer_grid(d)
            img = make_atlas.stitch_grid(grid)
            self.assertEqual(img.size, (48, 32))
            # Top-left tile is (0,0), bottom-right is (2,1) by default.
            self.assertEqual(img.getpixel((0, 0))[:3], palette[(0, 0)])
            self.assertEqual(img.getpixel((47, 0))[:3], palette[(2, 0)])
            self.assertEqual(img.getpixel((0, 31))[:3], palette[(0, 1)])
            self.assertEqual(img.getpixel((47, 31))[:3], palette[(2, 1)])

    def test_flip_rows_swaps_vertical(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            palette = self._write_3x2(d, size=16)
            grid = make_atlas.infer_grid(d)
            img = make_atlas.stitch_grid(grid, flip_rows=True)
            # After flip, (0,0) tile paints at the bottom.
            self.assertEqual(img.getpixel((0, 31))[:3], palette[(0, 0)])
            self.assertEqual(img.getpixel((0, 0))[:3], palette[(0, 1)])

    def test_flip_cols_swaps_horizontal(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            palette = self._write_3x2(d, size=16)
            grid = make_atlas.infer_grid(d)
            img = make_atlas.stitch_grid(grid, flip_cols=True)
            self.assertEqual(img.getpixel((0, 0))[:3], palette[(2, 0)])
            self.assertEqual(img.getpixel((47, 0))[:3], palette[(0, 0)])

    def test_missing_tile_filled(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            # 2×2 grid missing (1,1)
            _mk_tile(d / "minimap_sea_0_0.png", 8, (255, 0, 0))
            _mk_tile(d / "minimap_sea_1_0.png", 8, (0, 255, 0))
            _mk_tile(d / "minimap_sea_0_1.png", 8, (0, 0, 255))
            grid = make_atlas.infer_grid(d)
            self.assertEqual((grid.cols, grid.rows), (2, 2))
            img = make_atlas.stitch_grid(
                grid, missing_fill=(1, 2, 3, 255)
            )
            # (1,1) tile region defaults to the fill colour.
            self.assertEqual(img.getpixel((15, 15))[:3], (1, 2, 3))

    def test_mismatched_tile_sizes_refused(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            _mk_tile(d / "minimap_sea_0_0.png", 8, (0, 0, 0))
            _mk_tile(d / "minimap_sea_1_0.png", 16, (0, 0, 0))
            _mk_tile(d / "minimap_sea_0_1.png", 8, (0, 0, 0))
            _mk_tile(d / "minimap_sea_1_1.png", 8, (0, 0, 0))
            grid = make_atlas.infer_grid(d)
            with self.assertRaises(SystemExit):
                make_atlas.stitch_grid(grid)

    def test_downscale_when_over_max_side(self):
        img = Image.new("RGB", (1000, 500), (0, 0, 0))
        out = make_atlas._downscale_if_needed(img, 400)
        self.assertEqual(out.size, (400, 200))

    def test_no_downscale_when_under_max_side(self):
        img = Image.new("RGB", (300, 200), (0, 0, 0))
        out = make_atlas._downscale_if_needed(img, 400)
        self.assertEqual(out.size, (300, 200))


class DdsRoundTripTests(unittest.TestCase):

    def test_synthetic_dxt1_dds_opens(self):
        # Minimal DXT1 DDS of 4×4 solid red.
        c565 = (31 << 11)  # red
        header = bytearray(128)
        header[0:4] = b"DDS "
        struct.pack_into("<I", header, 4, 124)
        struct.pack_into("<I", header, 8, 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000)
        struct.pack_into("<I", header, 12, 4)
        struct.pack_into("<I", header, 16, 4)
        struct.pack_into("<I", header, 20, 8)
        struct.pack_into("<I", header, 28, 1)
        struct.pack_into("<I", header, 76, 32)
        struct.pack_into("<I", header, 80, 0x4)
        header[84:88] = b"DXT1"
        struct.pack_into("<I", header, 108, 0x1000)
        block = struct.pack("<HHI", c565, c565, 0)
        with tempfile.TemporaryDirectory() as td:
            dds = Path(td) / "minimap_sea_0_0.dds"
            dds.write_bytes(bytes(header) + block)
            # Only one tile → infer_grid parses it as a 1×1 grid.
            grid = make_atlas.infer_grid(Path(td))
            self.assertEqual((grid.cols, grid.rows), (1, 1))
            img = make_atlas.stitch_grid(grid)
            self.assertEqual(img.size, (4, 4))
            self.assertEqual(img.getpixel((0, 0))[:3], (255, 0, 0))


class InstallPathTests(unittest.TestCase):

    def test_app_map_dir_formatting(self):
        self.assertEqual(
            make_atlas.app_map_dir("jr.brian.thorhud"),
            "/storage/emulated/0/Android/data/jr.brian.thorhud/files/map",
        )
        self.assertEqual(
            make_atlas.app_map_dir("jr.brian.thorhud.debug"),
            "/storage/emulated/0/Android/data/jr.brian.thorhud.debug/files/map",
        )

    def test_default_app_package_parses_gradle(self):
        pkg = make_atlas.default_app_package("v")
        # The repo's build.gradle.kts has applicationId "jr.brian.thorhud".
        self.assertEqual(pkg, "jr.brian.thorhud")

    def test_is_termux_detection(self):
        with mock.patch.dict(os.environ, {"PREFIX": "/data/data/com.termux/files/usr"}):
            self.assertTrue(make_atlas.is_termux())
        with mock.patch.dict(os.environ, {"PREFIX": ""}, clear=False):
            os.environ.pop("PREFIX", None)
            self.assertFalse(make_atlas.is_termux())


class LocalInstallFallbackTests(unittest.TestCase):

    def test_local_write_to_app_dir(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "v.png"
            Image.new("RGB", (4, 4), (0, 0, 0)).save(src)
            app_dir = Path(td) / "app_dir"
            # Force the "app dir" to a writable tmp location so we
            # exercise the success path without touching /storage.
            rc = make_atlas._install_local(
                src,
                game="v",
                package="jr.brian.thorhud",
                keep_calibration=False,
                app_dir_override=str(app_dir),
            )
            self.assertEqual(rc, 0)
            self.assertTrue((app_dir / "v.png").is_file())

    def test_local_strips_calibration_when_present(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "v.png"
            Image.new("RGB", (4, 4), (0, 0, 0)).save(src)
            app_dir = Path(td) / "app_dir"
            app_dir.mkdir()
            (app_dir / "v.json").write_text("{}", encoding="utf-8")
            rc = make_atlas._install_local(
                src,
                game="v",
                package="jr.brian.thorhud",
                keep_calibration=False,
                app_dir_override=str(app_dir),
            )
            self.assertEqual(rc, 0)
            self.assertFalse((app_dir / "v.json").exists())

    def test_local_keeps_calibration_when_requested(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "v.png"
            Image.new("RGB", (4, 4), (0, 0, 0)).save(src)
            app_dir = Path(td) / "app_dir"
            app_dir.mkdir()
            (app_dir / "v.json").write_text("{}", encoding="utf-8")
            rc = make_atlas._install_local(
                src,
                game="v",
                package="jr.brian.thorhud",
                keep_calibration=True,
                app_dir_override=str(app_dir),
            )
            self.assertEqual(rc, 0)
            self.assertTrue((app_dir / "v.json").is_file())


class AdbInstallTests(unittest.TestCase):

    def test_adb_mode_resolves_single_device_and_pushes(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "v.png"
            Image.new("RGB", (4, 4), (0, 0, 0)).save(src)

            calls: list[list[str]] = []

            def fake_which(bin: str):
                return "/fake/adb" if bin == "adb" else None

            def fake_devices(adb_bin: str) -> list[str]:
                return ["114202"]

            def fake_run(cmd, check=True, capture_output=False, text=False):
                calls.append(cmd)
                r = mock.Mock()
                r.returncode = 0
                r.stdout = "-rw-rw---- 1 u0_a0 ext_data 1234 Jan 1 v.png"
                return r

            args = {
                "which": mock.patch.object(
                    make_atlas.shutil, "which", side_effect=fake_which
                ),
                "devs": mock.patch.object(
                    make_atlas, "_adb_devices", side_effect=fake_devices
                ),
                "run": mock.patch.object(
                    make_atlas.subprocess, "run", side_effect=fake_run
                ),
                "env": mock.patch.dict(os.environ, {}, clear=False),
            }
            os.environ.pop("ANDROID_SERIAL", None)
            with args["which"], args["devs"], args["run"], args["env"]:
                rc = make_atlas._install_via_adb(
                    src,
                    game="v",
                    package="jr.brian.thorhud",
                    keep_calibration=False,
                )
            self.assertEqual(rc, 0)
            joined = [" ".join(c) for c in calls]
            # mkdir, push, rm (calibration), ls
            self.assertTrue(any("push" in c for c in joined), joined)
            self.assertTrue(any("mkdir" in c for c in joined), joined)
            self.assertTrue(any(" rm " in c for c in joined), joined)
            self.assertTrue(any(" ls " in c for c in joined), joined)
            self.assertTrue(all("-s 114202" in c for c in joined), joined)

    def test_adb_mode_errors_with_multiple_devices_and_no_env(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "v.png"
            Image.new("RGB", (4, 4), (0, 0, 0)).save(src)

            def fake_which(bin: str):
                return "/fake/adb" if bin == "adb" else None

            os.environ.pop("ANDROID_SERIAL", None)
            with mock.patch.object(
                make_atlas.shutil, "which", side_effect=fake_which
            ), mock.patch.object(
                make_atlas, "_adb_devices", return_value=["a", "b"]
            ):
                with self.assertRaises(SystemExit):
                    make_atlas._install_via_adb(
                        src,
                        game="v",
                        package="jr.brian.thorhud",
                        keep_calibration=False,
                    )


class ParserWiringTests(unittest.TestCase):

    def test_stitch_dry_run_prints_grid_and_exits_zero(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            _mk_tile(d / "minimap_sea_0_0.png", 8, (0, 0, 0))
            _mk_tile(d / "minimap_sea_1_0.png", 8, (0, 0, 0))
            rc = make_atlas.main(
                [
                    "stitch",
                    "--game",
                    "v",
                    "--tiles",
                    str(d),
                    "--dry-run",
                ]
            )
            self.assertEqual(rc, 0)

    def test_stitch_writes_png(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td) / "tiles"
            d.mkdir()
            _mk_tile(d / "minimap_sea_0_0.png", 8, (10, 20, 30))
            _mk_tile(d / "minimap_sea_1_0.png", 8, (10, 20, 30))
            _mk_tile(d / "minimap_sea_0_1.png", 8, (10, 20, 30))
            _mk_tile(d / "minimap_sea_1_1.png", 8, (10, 20, 30))
            out = Path(td) / "v.png"
            rc = make_atlas.main(
                [
                    "stitch",
                    "--game",
                    "v",
                    "--tiles",
                    str(d),
                    "--out",
                    str(out),
                ]
            )
            self.assertEqual(rc, 0)
            self.assertTrue(out.is_file())
            got = Image.open(out)
            self.assertEqual(got.size, (16, 16))


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
