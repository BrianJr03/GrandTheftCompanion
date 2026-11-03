package jr.brian.thorhud.ui.wardrobe

import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Wardrobe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure wardrobe helpers against the plugin's wire format
 * and the captured mock frame shape. The piece packing in particular
 * must byte-match asi-v/src/wardrobe.cpp's unpack_piece or the mock
 * scenario would fail silently on-device.
 */
class WardrobeModelTest {

    @Test
    fun packComponentPieceMatchesPluginUnpack() {
        // slot 3, drawable 2, texture 3 -> 0x300203
        assertEquals(0x300203L, packPiece(false, 3, 2, 3))
        // slot 11, drawable 1, texture 7 -> 0xB00107
        assertEquals(0xB00107L, packPiece(false, 11, 1, 7))
    }

    @Test
    fun packPropPieceSetsBit24() {
        // prop slot 0, drawable 1, texture 1 -> 0x01000101
        assertEquals(0x1000101L, packPiece(true, 0, 1, 1))
        // prop slot 7 (bracelet), drawable 3, texture 0 -> 0x01700300
        assertEquals(0x1700300L, packPiece(true, 7, 3, 0))
    }

    @Test
    fun packPropClearUsesWireSentinel0xFFF() {
        // prop slot 0 cleared -> drawable 0xFFF, texture 0 -> 0x010FFF00
        assertEquals(0x10FFF00L, packPiece(true, 0, PROP_CLEAR, 0))
    }

    @Test
    fun packComponentNegativeDrawableClampsToZero() {
        // Guard: a programmer error (component with drawable=-1) is
        // silently masked so the plugin never sees a bogus 0xFFF
        // sentinel on a component. Texture is preserved.
        assertEquals(0x300004L, packPiece(false, 3, -1, 4))
    }

    @Test
    fun unpackIsInverseOfPackForEveryShape() {
        val cases = listOf(
            UnpackedPiece(false, 3, 2, 3),
            UnpackedPiece(false, 11, 1, 7),
            UnpackedPiece(true, 0, 1, 1),
            UnpackedPiece(true, 7, 3, 0),
            UnpackedPiece(true, 0, PROP_CLEAR, 0),
        )
        for (c in cases) {
            val packed = packPiece(c.isProp, c.slot, c.drawable, c.texture)
            val back = unpackPiece(packed)
            assertEquals(c, back)
        }
    }

    @Test
    fun wardrobeRowsGroupsSlotsAndHidesEmptySections() {
        val w = Wardrobe(
            ver = 1,
            pedModel = 0x9B22DBAFL,
            character = 0,
            pieces = listOf(
                slot(slot = 3, prop = false),            // Tops
                slot(slot = 4, prop = false),            // Legs
                slot(slot = 11, prop = false),           // Tops
                slot(slot = 0, prop = true, cur = -1),   // Props
                slot(slot = 1, prop = false),            // Hair
            ),
        )
        val rows = wardrobeRows(w)
        val sectionKeys = rows.sections.map { it.first }
        assertTrue(WardrobeSection.Tops in sectionKeys)
        assertTrue(WardrobeSection.Legs in sectionKeys)
        assertTrue(WardrobeSection.Props in sectionKeys)
        assertTrue(WardrobeSection.Hair in sectionKeys)
        assertFalse(WardrobeSection.Feet in sectionKeys)
        assertFalse(WardrobeSection.Accessories in sectionKeys)
        val tops = rows.sections.first { it.first == WardrobeSection.Tops }.second
        assertEquals(listOf(3, 11), tops.map { it.slot.slot })
        val props = rows.sections.first { it.first == WardrobeSection.Props }.second
        assertEquals(1, props.size)
        assertTrue(props[0].isProp)
    }

    @Test
    fun snapshotOutfitEmitsAPackedPiecePerSlot() {
        val w = Wardrobe(
            ver = 2,
            pedModel = 0L,
            character = 0,
            pieces = listOf(
                slot(slot = 3, prop = false, cur = 2, tex = 3),
                slot(slot = 4, prop = false, cur = 1, tex = 2),
                slot(slot = 0, prop = true,  cur = 1, tex = 1),
                slot(slot = 1, prop = true,  cur = -1, tex = 0),  // "none"
            ),
        )
        val out = snapshotOutfit(w)
        assertEquals(4, out.size)
        assertEquals(0x300203L, out[0])
        assertEquals(0x400102L, out[1])
        assertEquals(0x1000101L, out[2])
        // Prop clear roundtrips as sentinel 0xFFF so the plugin
        // reads it as a clear request.
        assertEquals(0x11FFF00L, out[3])
    }

    @Test
    fun snapshotOutfitCapsAtTheOutfitWireLimit() {
        val many = List(50) { slot(slot = 3, prop = false, cur = 0, tex = 0) }
        val w = Wardrobe(1, 0L, 0, many)
        assertEquals(MAX_OUTFIT_PARTS, snapshotOutfit(w).size)
    }

    @Test
    fun presetsRoundTripThroughSerialisation() {
        val presets = listOf(
            WardrobePreset("beater outfit", listOf(0x300203L, 0x400102L)),
            WardrobePreset(
                "quote \" and backslash \\",
                listOf(0x1000101L, 0x11FFF00L),
            ),
        )
        val raw = serialisePresets(presets)
        val back = deserialisePresets(raw)
        assertEquals(presets, back)
    }

    @Test
    fun deserialiseEmptyOrGarbageReturnsEmpty() {
        assertEquals(emptyList<WardrobePreset>(), deserialisePresets(null))
        assertEquals(emptyList<WardrobePreset>(), deserialisePresets(""))
        assertEquals(emptyList<WardrobePreset>(), deserialisePresets("not json"))
        assertEquals(emptyList<WardrobePreset>(), deserialisePresets("[{bad}]"))
    }

    @Test
    fun canSavePresetGuardsOnNullAndCap() {
        val empty = Wardrobe(1, 0L, 0, emptyList())
        val one   = Wardrobe(1, 0L, 0, listOf(slot(3, false)))
        val full = List(MAX_PRESETS) { WardrobePreset("p$it", emptyList()) }
        assertFalse(canSavePreset(null, emptyList()))
        assertFalse(canSavePreset(empty, emptyList()))
        assertTrue(canSavePreset(one, emptyList()))
        assertFalse(canSavePreset(one, full))
    }

    private fun slot(
        slot: Int,
        prop: Boolean,
        cur: Int = 0,
        tex: Int = 0,
        pal: Int = 0,
        styles: Int = 1,
        colours: List<Int> = listOf(1),
    ) = GameState.WardrobeSlot(
        slot = slot, prop = prop, cur = cur, tex = tex, pal = pal,
        styles = styles, colours = colours,
    )
}
