package jr.brian.thorhud.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [UpdateChecker.isNewerVersion] + [UpdateChecker.parse] run as unit
 * tests. The parse half instantiates [org.json.JSONObject], which is
 * `Stub!`-only on the JDK unit-test classpath, so the whole class is
 * routed through Robolectric to get a real `org.json` on the
 * classpath. No manifest or resources needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UpdateCheckerTest {

    // ---- isNewerVersion --------------------------------------------

    @Test fun newerPatchIsAnUpdate() {
        assertTrue(UpdateChecker.isNewerVersion("0.3.1", "0.3.0"))
    }

    @Test fun newerMinorIsAnUpdate() {
        assertTrue(UpdateChecker.isNewerVersion("0.3.0", "0.2.9"))
    }

    @Test fun newerMajorIsAnUpdate() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "0.9.9"))
    }

    @Test fun sameVersionIsNotAnUpdate() {
        assertFalse(UpdateChecker.isNewerVersion("0.3.0", "0.3.0"))
    }

    @Test fun olderIsNotAnUpdate() {
        assertFalse(UpdateChecker.isNewerVersion("0.2.9", "0.3.0"))
    }

    @Test fun debugSuffixDoesntTripTheCompare() {
        // GTC's debug build has versionNameSuffix "-debug". The user is
        // running 0.3.0-debug; a release of 0.3.0 is not newer.
        assertFalse(UpdateChecker.isNewerVersion("0.3.0", "0.3.0-debug"))
        assertTrue(UpdateChecker.isNewerVersion("0.3.1", "0.3.0-debug"))
    }

    @Test fun leadingVPrefixIsStripped() {
        assertTrue(UpdateChecker.isNewerVersion("v0.3.1", "0.3.0"))
        assertTrue(UpdateChecker.isNewerVersion("0.3.1", "v0.3.0"))
    }

    @Test fun missingComponentsAreTreatedAsZero() {
        // "0.3" compared to "0.3.0" should be equal.
        assertFalse(UpdateChecker.isNewerVersion("0.3", "0.3.0"))
        assertFalse(UpdateChecker.isNewerVersion("0.3.0", "0.3"))
        assertTrue(UpdateChecker.isNewerVersion("0.3.1", "0.3"))
    }

    @Test fun blankVersionsAreNeverAnUpdate() {
        assertFalse(UpdateChecker.isNewerVersion("", "0.3.0"))
        assertFalse(UpdateChecker.isNewerVersion("0.3.0", ""))
        assertFalse(UpdateChecker.isNewerVersion("not-a-version", "0.3.0"))
    }

    // ---- parse ------------------------------------------------------

    @Test fun parsePicksTheApkAsset() {
        val json = JSONObject().apply {
            put("tag_name", "v0.3.0")
            put("body", "## v0.3.0\n\n**Hi**")
            put("html_url", "https://example.test/releases/v0.3.0")
            put("assets", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "ThorBridge.asi")
                    put("browser_download_url", "https://example.test/ThorBridge.asi")
                    put("size", 123L)
                })
                put(JSONObject().apply {
                    put("name", "GTC-release.apk")
                    put("browser_download_url", "https://example.test/GTC-release.apk")
                    put("size", 46_123_456L)
                })
            })
        }

        val info = UpdateChecker.parse(json, currentVersionName = "0.2.0")
        assertTrue(info.isUpdateAvailable)
        assertEquals("0.3.0", info.latestVersion)
        assertEquals("## v0.3.0\n\n**Hi**", info.releaseNotes)
        assertEquals("https://example.test/releases/v0.3.0", info.releaseHtmlUrl)
        assertEquals("https://example.test/GTC-release.apk", info.apkDownloadUrl)
        assertEquals("GTC-release.apk", info.apkFileName)
        assertEquals(46_123_456L, info.apkSize)
        assertTrue(info.hasApkAsset)
    }

    @Test fun parseHandlesManualReleasesWithoutApkAsset() {
        val json = JSONObject().apply {
            put("tag_name", "v0.3.0")
            put("body", "Notes")
            put("html_url", "https://example.test/releases/v0.3.0")
            put("assets", org.json.JSONArray())
        }
        val info = UpdateChecker.parse(json, currentVersionName = "0.2.0")
        assertTrue(info.isUpdateAvailable)
        assertEquals("", info.apkDownloadUrl)
        assertFalse(info.hasApkAsset)
    }

    @Test fun parseReturnsNoUpdateWhenAlreadyOnLatest() {
        val json = JSONObject().apply {
            put("tag_name", "v0.3.0")
            put("body", "")
            put("html_url", "")
            put("assets", org.json.JSONArray())
        }
        val info = UpdateChecker.parse(json, currentVersionName = "0.3.0")
        assertFalse(info.isUpdateAvailable)
        assertEquals("0.3.0", info.latestVersion)
    }
}
