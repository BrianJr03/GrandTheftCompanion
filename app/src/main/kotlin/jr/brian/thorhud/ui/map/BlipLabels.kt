package jr.brian.thorhud.ui.map

import jr.brian.thorhud.model.GameState

/**
 * What to call a map blip, by game and sprite id. No Android imports:
 * this is the part of the blip list that can be unit-tested.
 *
 * PROVISIONAL. The GTA V names come from the public `radar_*` sprite
 * list (FiveM's blip reference) and have not been checked against the
 * story mode's actual mission icons; GTA IV has no public table at all.
 * The first on-device runs of the blip census are what settle both.
 * Until then an unknown sprite shows as "sprite N", and the numbers
 * stay visible next to every name so a wrong guess is easy to report.
 */
internal data class BlipLabel(
    val name: String,
    /** Believed to be somewhere a mission or job starts. Provisional. */
    val missionStart: Boolean,
)

private val V_LABELS: Map<Int, BlipLabel> = mapOf(
    1 to BlipLabel("standard", false),          // radar_level
    8 to BlipLabel("waypoint", false),          // radar_waypoint (filtered out by the plugin)
    59 to BlipLabel("heist", true),
    66 to BlipLabel("stranger", true),          // radar_random_character
    77 to BlipLabel("Lester", true),
    78 to BlipLabel("Michael", true),
    79 to BlipLabel("Trevor", true),
    86 to BlipLabel("Lamar", true),
    88 to BlipLabel("Franklin", true),
    112 to BlipLabel("Michael (exile)", true),
    114 to BlipLabel("stranger (f)", true),
    115 to BlipLabel("stranger (m)", true),
    123 to BlipLabel("Trevor (exile)", true),
    124 to BlipLabel("Michael / Trevor", true),
    143 to BlipLabel("objective (blue)", false),
    144 to BlipLabel("objective (green)", false),
    145 to BlipLabel("objective (red)", false),
    146 to BlipLabel("objective (yellow)", false),
    208 to BlipLabel("Trevor (grey)", true),
    209 to BlipLabel("Trevor (red)", true),
    210 to BlipLabel("Franklin (grey)", true),
    211 to BlipLabel("Franklin (blue)", true),
    362 to BlipLabel("heist prep", true),
)

// The EFLC multiplayer wiki's one documented example is sprite 44 =
// Playboy X; nothing else is public. Fill in from the census.
private val IV_LABELS: Map<Int, BlipLabel> = mapOf(
    44 to BlipLabel("Playboy X", true),
)

internal fun blipLabel(isGtaV: Boolean, sprite: Int): BlipLabel =
    (if (isGtaV) V_LABELS else IV_LABELS)[sprite]
        ?: BlipLabel("sprite $sprite", false)

/** "50 m" below a kilometre, "1.2 km" above. */
internal fun blipDistance(distM: Float): String =
    if (distM < 1000f) "${distM.toInt()} m"
    else String.format(java.util.Locale.US, "%.1f km", distM / 1000f)

/** One row of the companion's blip list. */
internal data class BlipRow(
    val blip: GameState.Blip,
    val label: BlipLabel,
    val distance: String,
    val routed: Boolean,
)

/**
 * Rows for the list, in the plugin's order (nearest first), with the
 * routed one flagged. [routedId] is [GameState.routedBlipId].
 */
internal fun blipRows(isGtaV: Boolean, blips: List<GameState.Blip>, routedId: Int?): List<BlipRow> =
    blips.map { b ->
        BlipRow(
            blip = b,
            label = blipLabel(isGtaV, b.sprite),
            distance = blipDistance(b.distM),
            routed = routedId != null && routedId == b.id,
        )
    }
