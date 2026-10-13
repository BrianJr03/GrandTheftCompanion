package jr.brian.thorhud.led

import jr.brian.thorhud.model.GameState

/**
 * The rules of the wanted-level stick lights, kept free of Android and
 * coroutines so they can be unit-tested: when to flash, how fast, and
 * which colour goes on which stick.
 *
 * The look is a police light bar: one stick red and the other blue,
 * trading places on every beat. More stars, quicker beat.
 */
internal object PoliceLights {

    // Half-brightness red/blue. Full brightness read as too harsh on
    // the AMOLED thumbsticks at arm's length. ARGB: alpha stays 0xFF,
    // the colour channel is 0x80 (~50%).
    const val RED = 0xFF800000.toInt()
    const val BLUE = 0xFF000080.toInt()

    /**
     * Beat length at one star. Bifrost takes the sticks back from an
     * app it has not heard from for 1.5 s, and each beat is also our
     * sign of life, so this must stay under 750 ms for one late or
     * dropped beat to go unnoticed.
     */
    const val SLOWEST_BEAT_MS = 600L

    /**
     * Beat length at full stars, and the floor for every beat. Two and
     * a half colour changes a second reads as urgent without turning
     * into a strobe. It also keeps us inside Bifrost's limit of four
     * commands a second with room for the final clear. Do not go below
     * 334 ms (three changes a second).
     */
    const val FASTEST_BEAT_MS = 400L

    /** Frames older than this are not "now". Matches the HUD's own
     *  stale threshold in MainScreen. */
    const val STALE_AFTER_MS = 1500L

    private const val IV_MAX_STARS = 6
    private const val V_MAX_STARS = 5

    /**
     * The wanted level to flash for, or 0 when the sticks should be
     * left alone: not in the world, no stars, or the game is not
     * actually being played right now (loading, GTA Online, a character
     * switch). Pausing is deliberate and keeps the heat on the player's
     * head, so the lights stay lit through the pause menu.
     */
    fun wantedLevel(state: GameState?): Int {
        if (state == null || !state.inGame) return 0
        if (state.stalled || state.online || state.switching) return 0
        return (state.wanted ?: 0).coerceAtLeast(0)
    }

    fun maxStars(state: GameState?): Int =
        if (state?.isGtaV == true) V_MAX_STARS else IV_MAX_STARS

    /**
     * Milliseconds between colour swaps: [SLOWEST_BEAT_MS] at one star,
     * down in even steps to [FASTEST_BEAT_MS] at [maxStars]. Never
     * outside that range, whatever the game reports.
     */
    fun beatIntervalMs(wanted: Int, maxStars: Int): Long {
        val top = maxStars.coerceAtLeast(2)
        val step = (wanted.coerceIn(1, top) - 1).toFloat() / (top - 1)
        val interval = SLOWEST_BEAT_MS - ((SLOWEST_BEAT_MS - FASTEST_BEAT_MS) * step).toLong()
        return interval.coerceIn(FASTEST_BEAT_MS, SLOWEST_BEAT_MS)
    }

    /** Left and right stick colours for beat number [beat]. */
    fun colorsForBeat(beat: Int): Pair<Int, Int> =
        if (beat and 1 == 0) RED to BLUE else BLUE to RED
}
