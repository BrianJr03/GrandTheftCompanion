package jr.brian.thorhud.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the episode → protagonist mapping used by the IV HUD's
 * character-name coin card. 0 Niko, 1 Johnny, 2 Luis, anything else
 * (including null) → plain "coin" fallback (null here).
 */
class IvProtagonistTest {

    @Test
    fun episodeZeroIsNiko() {
        assertEquals(IvProtagonist.Niko, ivProtagonist(0))
    }

    @Test
    fun episodeOneIsJohnny() {
        assertEquals(IvProtagonist.Johnny, ivProtagonist(1))
    }

    @Test
    fun episodeTwoIsLuis() {
        assertEquals(IvProtagonist.Luis, ivProtagonist(2))
    }

    @Test
    fun unknownOrOutOfRangeEpisodeFallsBackToNull() {
        assertNull(ivProtagonist(null))
        assertNull(ivProtagonist(-1))
        assertNull(ivProtagonist(3))
        assertNull(ivProtagonist(99))
    }
}
