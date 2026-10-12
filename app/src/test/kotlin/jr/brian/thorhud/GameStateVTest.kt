package jr.brian.thorhud

import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.v.VCharacter
import jr.brian.thorhud.ui.v.vCharacterOf
import jr.brian.thorhud.ui.v.vIsUnarmed
import jr.brian.thorhud.ui.v.vStationLabel
import jr.brian.thorhud.ui.v.vStationShort
import jr.brian.thorhud.ui.v.vWeaponLabel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the GTA V protocol additions.
 *
 * The GTA V fixtures are frames captured verbatim from ThorBridgeV.asi
 * running against the mock Script Hook V (asi-v/test/), so this pins
 * the app's model to what the plugin really emits. The GTA IV fixtures
 * follow asi/src/bridge.cpp's format strings and guard against the GTA
 * V fields changing how GTA IV frames decode.
 */
class GameStateVTest {

    // Same configuration as GameStateClient.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun decode(line: String) = json.decodeFromString<GameState>(line)

    @Test
    fun helloIdentifiesGtaV() {
        val f = decode(V_HELLO)
        assertTrue(f.isHello)
        assertTrue(f.isGtaV)
        assertEquals(true, f.supported)
        assertEquals(true, f.shv)
        assertEquals("0.1.0", f.plugin)
    }

    @Test
    fun onFootStateDecodes() {
        val f = decode(V_STATE_ON_FOOT)
        assertTrue(f.isState)
        assertTrue(f.isGtaV)
        assertTrue(f.inGame)
        assertFalse(f.inVehicle)
        assertNull(f.vehicle)
        assertEquals(0, f.character)
        assertEquals(VCharacter.Michael, vCharacterOf(f.character))
        assertEquals(1234567L, f.money)
        assertEquals(175f, f.health)
        assertEquals(200, f.maxHealth)
        assertEquals(3, f.wanted)
        // A joaat hash above Int.MAX_VALUE would fail here if the field
        // were an Int; weapon_pistol happens to fit, the weapons list
        // below does not.
        assertEquals(453432689L, f.weaponHash)
        assertEquals("weapon_pistol", f.weaponName)
        assertEquals(12, f.clipSize)
        assertEquals(9, f.ammoClip)
        assertEquals(120, f.ammoTotal)
        val special = f.special
        assertNotNull(special)
        assertTrue(special!!.unlocked && special.full && !special.active)
        assertEquals(-1, f.radio)
        assertEquals("", f.radioName)
        // GTA IV-only fields stay at their defaults.
        assertNull(f.weaponId)
        assertNull(f.episode)
        assertTrue(f.ownedWeapons.isEmpty())
    }

    @Test
    fun inVehicleStateDecodes() {
        val f = decode(V_STATE_IN_VEHICLE)
        assertTrue(f.inVehicle)
        val v = f.vehicle
        assertNotNull(v)
        // Escaped quotes and non-ASCII from localised game text.
        assertEquals("Adder \"S\" \u00e9", v!!.name)
        assertEquals(7, v.vehicleClass)
        assertEquals(31.5f, v.speedMps)
        assertEquals(870f, v.engineHp)
        assertEquals(990f, v.bodyHp)
        assertEquals(true, v.hasRadio)
        assertEquals("RADIO_01_CLASS_ROCK", f.radioName)
    }

    @Test
    fun listFramesDecode() {
        val stations = decode(V_STATIONS)
        assertTrue(stations.isStations)
        assertEquals(5, stations.stations.size)
        assertEquals("RADIO_02_POP", stations.stations[1].name)
        assertEquals("Non-Stop-Pop FM", stations.stations[1].label)

        val weapons = decode(V_WEAPONS)
        assertTrue(weapons.isWeapons)
        assertEquals(4, weapons.weapons.size)
        // 2578778090 > Int.MAX_VALUE: must survive as a Long.
        assertEquals(2578778090L, weapons.weapons[0].hash)
        assertEquals("weapon_knife", weapons.weapons[0].name)
    }

    @Test
    fun wheelFlagDecodes() {
        val held = decode(V_STATE_WHEEL_HELD)
        assertTrue(held.wheel)
        assertTrue(held.inGame)
        assertFalse(held.inVehicle)
        // Frames from plugin 0.1.0 (and every GTA IV frame) have no
        // such key; the wheel must then read as not held.
        assertFalse(decode(V_STATE_ON_FOOT).wheel)
        assertFalse(decode(IV_STATE).wheel)
    }

    @Test
    fun gtaIvWheelAndOwnedListDecode() {
        val f = decode(IV_STATE_WHEEL_HELD)
        assertFalse(f.isGtaV)
        assertTrue(f.wheel)
        assertFalse(f.inVehicle)
        assertEquals(7, f.weaponId)
        assertEquals(3, f.ownedWeapons.size)
        assertEquals(2, f.ownedWeapons[1].slot)
        assertEquals(7, f.ownedWeapons[1].id)
        assertEquals(120, f.ownedWeapons[1].ammo)
    }

    @Test
    fun tickDecodes() {
        val f = decode(V_TICK)
        assertTrue(f.isTick)
        assertTrue(f.isGtaV)
    }

    @Test
    fun gtaIvFramesAreUnaffected() {
        val hello = decode(IV_HELLO)
        assertTrue(hello.isHello)
        assertFalse(hello.isGtaV)
        assertNull(hello.game)

        val f = decode(IV_STATE)
        assertTrue(f.isState)
        assertFalse(f.isGtaV)
        assertEquals(7, f.weaponId)
        assertEquals(0, f.episode)
        assertEquals(1, f.ownedWeapons.size)
        assertEquals("ORACLE", f.vehicle?.name)
        assertNull(f.vehicle?.vehicleClass)
        assertNull(f.character)
        assertNull(f.special)
        assertFalse(f.online || f.stalled || f.switching)
    }

    @Test
    fun characterMapping() {
        assertEquals(VCharacter.Michael, vCharacterOf(0))
        assertEquals(VCharacter.Franklin, vCharacterOf(1))
        assertEquals(VCharacter.Trevor, vCharacterOf(2))
        assertEquals(VCharacter.Other, vCharacterOf(-1))
        assertEquals(VCharacter.Other, vCharacterOf(null))
        assertFalse(VCharacter.Other.isProtagonist)
    }

    @Test
    fun weaponLabels() {
        assertEquals("pistol", vWeaponLabel("weapon_pistol", 453432689L))
        assertEquals("unarmed", vWeaponLabel("weapon_unarmed", 0xA2719263L))
        assertEquals("unarmed", vWeaponLabel(null, null))
        assertTrue(vIsUnarmed("", 0xA2719263L))
        // Known to the plugin but not to the app's table: tidy the name.
        assertEquals("future gun", vWeaponLabel("weapon_future_gun", 1L))
        // Unknown to both: fall back to the hash.
        assertEquals("weapon 0xDEADBEEF", vWeaponLabel("", 0xDEADBEEFL))
    }

    @Test
    fun stationLabels() {
        // The game's label wins.
        assertEquals("Custom", vStationLabel("RADIO_02_POP", "Custom"))
        // Empty label: our table.
        assertEquals("Non-Stop-Pop FM", vStationLabel("RADIO_02_POP", ""))
        // Unknown id, no label: tidied id.
        assertEquals("mock unlabelled",
                     vStationLabel("RADIO_99_MOCK_UNLABELLED", ""))
        assertEquals("POP", vStationShort("RADIO_02_POP", ""))
        // Unknown id: initials of the label.
        assertEquals("NSF", vStationShort("RADIO_98_NEW", "New Station FM"))
        assertEquals("JAZZ", vStationShort("RADIO_97_X", "Jazzland"))
    }

    private companion object {
        const val V_HELLO = """{"v":1,"type":"hello","game":"v","plugin":"0.1.0","exe":"mock_host.exe","file_version":"","size":250342,"supported":true,"shv":true,"script_started":false,"shv_game_version":9999,"err":""}"""
        const val V_TICK = """{"v":1,"type":"tick","game":"v","t":1269689,"shv":true,"script_started":false}"""
        const val V_STATE_ON_FOOT = """{"v":1,"type":"state","game":"v","t":1269829,"in_game":true,"online":false,"paused":false,"stalled":false,"switching":false,"cutscene":false,"mission":false,"animal":false,"character":0,"ped_model":225514697,"money":1234567,"health":175,"max_health":200,"armor":40,"max_armor":100,"wanted":3,"special":{"unlocked":true,"enabled":true,"active":false,"full":true},"weapon_hash":453432689,"weapon_name":"weapon_pistol","weapon_group":416676503,"ammo_total":120,"ammo_clip":9,"clip_size":12,"in_vehicle":false,"radio":-1,"radio_name":""}"""
        // Plugin 0.2.0, weapon-wheel control held on foot.
        const val V_STATE_WHEEL_HELD = """{"v":1,"type":"state","game":"v","t":941074,"in_game":true,"online":false,"paused":false,"stalled":false,"switching":false,"cutscene":false,"mission":false,"animal":false,"wheel":true,"character":0,"ped_model":225514697,"money":1234567,"health":175,"max_health":200,"armor":40,"max_armor":100,"wanted":3,"special":{"unlocked":true,"enabled":true,"active":false,"full":true},"weapon_hash":453432689,"weapon_name":"weapon_pistol","weapon_group":416676503,"ammo_total":120,"ammo_clip":9,"clip_size":12,"in_vehicle":false,"radio":-1,"radio_name":""}"""
        const val V_STATE_IN_VEHICLE = """{"v":1,"type":"state","game":"v","t":1271539,"in_game":true,"online":false,"paused":false,"stalled":false,"switching":false,"cutscene":false,"mission":false,"animal":false,"character":0,"ped_model":225514697,"money":1234567,"health":175,"max_health":200,"armor":40,"max_armor":100,"wanted":3,"special":{"unlocked":true,"enabled":true,"active":false,"full":true},"weapon_hash":453432689,"weapon_name":"weapon_pistol","weapon_group":416676503,"ammo_total":120,"ammo_clip":9,"clip_size":12,"in_vehicle":true,"vehicle":{"model":3078201489,"name":"Adder \"S\" é","class":7,"speed_mps":31.5,"engine_hp":870.0,"body_hp":990.0,"altitude_m":0.0,"has_radio":true},"radio":0,"radio_name":"RADIO_01_CLASS_ROCK"}"""
        const val V_STATIONS = """{"v":1,"type":"stations","game":"v","stations":[{"name":"RADIO_01_CLASS_ROCK","label":"Los Santos Rock Radio"},{"name":"RADIO_02_POP","label":"Non-Stop-Pop FM"},{"name":"RADIO_03_HIPHOP_NEW","label":"Radio Los Santos"},{"name":"RADIO_19_USER","label":"Self Radio"},{"name":"RADIO_99_MOCK_UNLABELLED","label":""}]}"""
        const val V_WEAPONS = """{"v":1,"type":"weapons","game":"v","weapons":[{"hash":2578778090,"group":416676503,"ammo":120,"name":"weapon_knife"},{"hash":453432689,"group":416676503,"ammo":120,"name":"weapon_pistol"},{"hash":3220176749,"group":416676503,"ammo":120,"name":"weapon_assaultrifle"},{"hash":2481070269,"group":416676503,"ammo":120,"name":"weapon_grenade"}]}"""
        const val IV_HELLO = """{"v":1,"type":"hello","crc32":"0x1A2B3C4D","size":15628696,"file_version":"1.2.0.59","supported":true}"""
        // Natives level 4, LB held on foot. Follows format_state in
        // asi/src/bridge.cpp; not captured from a running game.
        const val IV_STATE_WHEEL_HELD = """{"v":1,"type":"state","t":223344,"in_game":true,"in_vehicle":false,"paused":false,"wheel":true,"money":2500,"health":180.0,"max_health":200,"armor":50,"max_armor":100,"wanted":0,"weapon":7,"ammo_total":120,"ammo_clip":12,"owned":[{"slot":1,"id":1,"ammo":1},{"slot":2,"id":7,"ammo":120},{"slot":8,"id":4,"ammo":5}],"vehicle":{"model":0,"name":"","speed_mps":0.00,"engine_hp":0.0},"radio":-1,"episode":0}"""
        const val IV_STATE = """{"v":1,"type":"state","t":123456,"in_game":true,"in_vehicle":true,"paused":false,"money":2500,"health":180.0,"max_health":200,"armor":50,"max_armor":100,"wanted":2,"weapon":7,"ammo_total":60,"ammo_clip":12,"owned":[{"slot":2,"id":7,"ammo":60}],"vehicle":{"model":1348744438,"name":"ORACLE","speed_mps":12.50,"engine_hp":950.0},"radio":3,"episode":0}"""
    }
}
