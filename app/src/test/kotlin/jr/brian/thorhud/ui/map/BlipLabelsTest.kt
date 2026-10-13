package jr.brian.thorhud.ui.map

import jr.brian.thorhud.model.GameState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blip list's model: decoding the plugin's `blips` frame and the
 * route fields, and the rows built from them. The GTA V fixtures were
 * captured from ThorBridgeV.asi 0.3.0 against the mock Script Hook V
 * (asi-v/test/run_mock_test.py, `blips` scenario).
 */
class BlipLabelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun decode(line: String) = json.decodeFromString<GameState>(line)

    @Test
    fun blipsFrameDecodesNearestFirst() {
        val f = decode(V_BLIPS)
        assertTrue(f.isBlips)
        assertEquals(1, f.listVersion)
        assertEquals(listOf(302, 301, 303), f.blips.map { it.id })
        val michael = f.blips[1]
        assertEquals(78, michael.sprite)
        assertEquals(4, michael.blipType)
        assertEquals(300f, michael.x)
        assertEquals(500f, michael.distM)
    }

    @Test
    fun waypointFieldsDecode() {
        val none = decode(V_STATE_NO_WAYPOINT)
        assertEquals(false, none.waypoint?.active)
        assertNull(none.routedBlipId)

        val ours = decode(V_STATE_ROUTED)
        assertEquals(true, ours.waypoint?.ours)
        assertEquals(301, ours.routedBlipId)

        // Active but hand-placed: not something the companion may touch.
        val hand = decode(V_STATE_ROUTED.replace("\"ours\":true,\"blip\":301", "\"ours\":false,\"blip\":0"))
        assertEquals(true, hand.waypoint?.active)
        assertNull(hand.routedBlipId)
    }

    @Test
    fun ivRouteFieldsDecode() {
        val f = decode(IV_STATE_ROUTED)
        assertFalse(f.isGtaV)
        assertEquals(true, f.mission)
        assertEquals(12, f.routedBlipId)
        assertNull(decode(IV_STATE_ROUTED.replace("\"route_blip\":12", "\"route_blip\":0")).routedBlipId)
        // An older plugin never sends the field.
        assertNull(decode(IV_STATE_ROUTED.replace(",\"mission\":true,\"route_blip\":12", "")).routedBlipId)
    }

    @Test
    fun labelsAreProvisionalButReadable() {
        assertEquals("Michael", blipLabel(true, 78).name)
        assertTrue(blipLabel(true, 78).missionStart)
        assertEquals("sprite 999", blipLabel(true, 999).name)
        assertFalse(blipLabel(true, 999).missionStart)
        assertEquals("Playboy X", blipLabel(false, 44).name)
        // IV table is separate: a sprite id V knows but IV does not
        // falls through to the unknown-sprite fallback.
        assertEquals("sprite 999", blipLabel(false, 999).name)
    }

    @Test
    fun distanceFormatting() {
        // Imperial to match the games' own HUDs: feet below a mile,
        // miles above. 50 m = 164 ft, 1 mi = 1609.344 m.
        assertEquals("164 ft", blipDistance(50f))
        assertEquals("1607 ft", blipDistance(490.0f))
        // Threshold is 1 mile = 1609.344 m, not a kilometre.
        assertEquals("4048 ft", blipDistance(1234f))
        assertEquals("1.2 mi", blipDistance(2000f))
        assertEquals("3.1 mi", blipDistance(5000f))
    }

    @Test
    fun rowsFlagTheRoutedBlipOnly() {
        val f = decode(V_BLIPS)
        val rows = blipRows(true, f.blips, 301)
        assertEquals(listOf(false, true, false), rows.map { it.routed })
        assertEquals("Michael", rows[1].label.name)
        assertEquals("1640 ft", rows[1].distance)
        assertTrue(blipRows(true, f.blips, null).none { it.routed })
    }

    @Test
    fun groupsPutSinglesFirstThenMultisBothOrderedByDistance() {
        // Two Ammu-Nations, one Franklin, one Lester: singles first in
        // nearest-first order (Franklin then Lester, since Franklin is
        // closer), then multi-item groups by nearest-first (Ammu-Nation).
        val frame = decode(
            "{\"v\":1,\"type\":\"blips\",\"game\":\"v\",\"ver\":1,\"blips\":[" +
            "{\"id\":1,\"sprite\":110,\"colour\":0,\"type\":4,\"display\":2,\"x\":0.0,\"y\":0.0,\"z\":0.0,\"dist_m\":100}," +
            "{\"id\":2,\"sprite\":88,\"colour\":43,\"type\":4,\"display\":2,\"x\":0.0,\"y\":0.0,\"z\":0.0,\"dist_m\":200}," +
            "{\"id\":3,\"sprite\":110,\"colour\":0,\"type\":4,\"display\":2,\"x\":0.0,\"y\":0.0,\"z\":0.0,\"dist_m\":500}," +
            "{\"id\":4,\"sprite\":77,\"colour\":42,\"type\":4,\"display\":2,\"x\":0.0,\"y\":0.0,\"z\":0.0,\"dist_m\":800}]}"
        )
        val groups = blipGroups(true, frame.blips, routedId = 2)
        assertEquals(listOf("Franklin", "Lester", "Ammu-Nation"), groups.map { it.name })
        assertEquals(listOf(1, 1, 2), groups.map { it.count })
        assertEquals(true, groups[0].routed)    // Franklin
        assertEquals(false, groups[1].routed)   // Lester
        assertEquals(false, groups[2].routed)   // Ammu-Nation
        // Within a group, rows stay nearest first.
        assertEquals(listOf(1, 3), groups[2].rows.map { it.blip.id })
    }

    @Test
    fun titleCasePreservesExistingUppercase() {
        assertEquals("Safehouse", "safehouse".titleCase())
        assertEquals("Car Wash", "car wash".titleCase())
        assertEquals("Ammu-Nation", "Ammu-Nation".titleCase())
        assertEquals("LS Customs", "LS Customs".titleCase())
        assertEquals("Sprite 123", "sprite 123".titleCase())
    }

    private companion object {
        const val V_BLIPS = "{\"v\":1,\"type\":\"blips\",\"game\":\"v\",\"ver\":1,\"blips\":[" +
            "{\"id\":302,\"sprite\":1,\"colour\":5,\"type\":4,\"display\":2,\"x\":30.0,\"y\":40.0,\"z\":10.0,\"dist_m\":50}," +
            "{\"id\":301,\"sprite\":78,\"colour\":0,\"type\":4,\"display\":2,\"x\":300.0,\"y\":400.0,\"z\":10.0,\"dist_m\":500}," +
            "{\"id\":303,\"sprite\":52,\"colour\":2,\"type\":4,\"display\":2,\"x\":3000.0,\"y\":4000.0,\"z\":10.0,\"dist_m\":5000}]}"
        const val V_STATE_NO_WAYPOINT = "{\"v\":1,\"type\":\"state\",\"game\":\"v\",\"t\":1628677,\"in_game\":true," +
            "\"online\":false,\"paused\":false,\"stalled\":false,\"switching\":false,\"cutscene\":false,\"mission\":false," +
            "\"animal\":false,\"wheel\":false,\"character\":0,\"ped_model\":225514697,\"money\":1234567,\"health\":175," +
            "\"max_health\":200,\"armor\":40,\"max_armor\":100,\"wanted\":3,\"special\":{\"unlocked\":true,\"enabled\":true," +
            "\"active\":false,\"full\":true},\"weapon_hash\":453432689,\"weapon_name\":\"weapon_pistol\"," +
            "\"weapon_group\":416676503,\"ammo_total\":120,\"ammo_clip\":9,\"clip_size\":12,\"in_vehicle\":false," +
            "\"radio\":-1,\"radio_name\":\"\",\"waypoint\":{\"active\":false,\"ours\":false,\"blip\":0}}"
        val V_STATE_ROUTED = V_STATE_NO_WAYPOINT.replace(
            "\"waypoint\":{\"active\":false,\"ours\":false,\"blip\":0}",
            "\"waypoint\":{\"active\":true,\"ours\":true,\"blip\":301}",
        )
        // asi/src/bridge.cpp format_state at natives level 5.
        const val IV_STATE_ROUTED = "{\"v\":1,\"type\":\"state\",\"t\":123456,\"in_game\":true,\"in_vehicle\":false," +
            "\"paused\":false,\"wheel\":false,\"money\":1000,\"health\":200.0,\"max_health\":200,\"armor\":0," +
            "\"max_armor\":100,\"wanted\":0,\"weapon\":7,\"ammo_total\":120,\"ammo_clip\":17,\"owned\":[]," +
            "\"vehicle\":{\"model\":0,\"name\":\"\",\"speed_mps\":0.00,\"engine_hp\":0.0},\"radio\":-1,\"episode\":0," +
            "\"mission\":true,\"route_blip\":12}"
    }
}
