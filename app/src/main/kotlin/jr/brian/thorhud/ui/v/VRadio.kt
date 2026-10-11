package jr.brian.thorhud.ui.v

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.HudPalette

// --- GTA V radio --------------------------------------------------------
//
// GTA IV's picker is built on a fixed 20-entry station enum plus a
// per-episode index translation table (see components/RadioPanel.kt).
// None of that carries over:
//
//   * GTA V's station set and ORDER change between game builds as DLC
//     adds stations, so nothing here is keyed by index.
//   * The plugin instead sends the running game's own station list
//     (`stations` frame: stable id + the game's localised label), the
//     picker draws exactly that list, the current station is matched by
//     id (`radio_name`), and a tap sends the tapped entry's POSITION in
//     that list, which the plugin turns back into an id.
//
// The two tables below are therefore only cosmetics / fallbacks: short
// codes for the picker buttons, and names for when the game supplied no
// label. An id missing from both still works; it just gets a derived
// label.

/** Full names, used only when the game sent an empty label. */
private val STATION_NAMES: Map<String, String> = mapOf(
    "RADIO_01_CLASS_ROCK" to "Los Santos Rock Radio",
    "RADIO_02_POP" to "Non-Stop-Pop FM",
    "RADIO_03_HIPHOP_NEW" to "Radio Los Santos",
    "RADIO_04_PUNK" to "Channel X",
    "RADIO_05_TALK_01" to "West Coast Talk Radio",
    "RADIO_06_COUNTRY" to "Rebel Radio",
    "RADIO_07_DANCE_01" to "Soulwax FM",
    "RADIO_08_MEXICAN" to "East Los FM",
    "RADIO_09_HIPHOP_OLD" to "West Coast Classics",
    "RADIO_11_TALK_02" to "Blaine County Radio",
    "RADIO_12_REGGAE" to "Blue Ark",
    "RADIO_13_JAZZ" to "Worldwide FM",
    "RADIO_14_DANCE_02" to "FlyLo FM",
    "RADIO_15_MOTOWN" to "The Lowdown 91.1",
    "RADIO_16_SILVERLAKE" to "Radio Mirror Park",
    "RADIO_17_FUNK" to "Space 103.2",
    "RADIO_18_90S_ROCK" to "Vinewood Boulevard Radio",
    "RADIO_19_USER" to "Self Radio",
    "RADIO_20_THELAB" to "The Lab",
    "RADIO_21_DLC_XM17" to "Blonded Los Santos 97.8 FM",
    "RADIO_22_DLC_BATTLE_MIX1_RADIO" to "Los Santos Underground Radio",
    "RADIO_23_DLC_XM19_RADIO" to "iFruit Radio",
    "RADIO_27_DLC_PRHEI4" to "Still Slipping Los Santos",
    "RADIO_34_DLC_HEI4_KULT" to "Kult FM",
    "RADIO_35_DLC_HEI4_MLR" to "The Music Locker",
    "RADIO_36_AUDIOPLAYER" to "Media Player",
    "RADIO_37_MOTOMAMI" to "MOTOMAMI Los Santos",
)

/** Picker button codes, four characters at most so eight fit a row. */
private val STATION_SHORT: Map<String, String> = mapOf(
    "RADIO_01_CLASS_ROCK" to "LSRR",
    "RADIO_02_POP" to "POP",
    "RADIO_03_HIPHOP_NEW" to "RLS",
    "RADIO_04_PUNK" to "CHX",
    "RADIO_05_TALK_01" to "WCTR",
    "RADIO_06_COUNTRY" to "REBL",
    "RADIO_07_DANCE_01" to "SLWX",
    "RADIO_08_MEXICAN" to "ELS",
    "RADIO_09_HIPHOP_OLD" to "WCC",
    "RADIO_11_TALK_02" to "BCR",
    "RADIO_12_REGGAE" to "BLUE",
    "RADIO_13_JAZZ" to "WWFM",
    "RADIO_14_DANCE_02" to "FLY",
    "RADIO_15_MOTOWN" to "LOW",
    "RADIO_16_SILVERLAKE" to "RMP",
    "RADIO_17_FUNK" to "SPC",
    "RADIO_18_90S_ROCK" to "VBR",
    "RADIO_19_USER" to "SELF",
    "RADIO_20_THELAB" to "LAB",
    "RADIO_21_DLC_XM17" to "BLND",
    "RADIO_22_DLC_BATTLE_MIX1_RADIO" to "LSUR",
    "RADIO_23_DLC_XM19_RADIO" to "IFRT",
    "RADIO_27_DLC_PRHEI4" to "SSLS",
    "RADIO_34_DLC_HEI4_KULT" to "KULT",
    "RADIO_35_DLC_HEI4_MLR" to "MLR",
    "RADIO_36_AUDIOPLAYER" to "MEDI",
    "RADIO_37_MOTOMAMI" to "MOTO",
)

/** "RADIO_12_REGGAE" -> "reggae": last resort when nothing else names it. */
private fun tidyStationId(id: String): String =
    id.removePrefix("RADIO_")
        .dropWhile { it.isDigit() || it == '_' }
        .replace('_', ' ')
        .lowercase()
        .ifBlank { id }

/** Display name: the game's label, else our table, else a tidied id. */
internal fun vStationLabel(id: String, gameLabel: String?): String =
    gameLabel?.takeIf { it.isNotBlank() }
        ?: STATION_NAMES[id]
        ?: tidyStationId(id)

/** Button code: table, else initials of a multi-word label, else the
 *  first four characters. */
internal fun vStationShort(id: String, gameLabel: String?): String {
    STATION_SHORT[id]?.let { return it }
    val label = vStationLabel(id, gameLabel)
    val words = label.split(' ', '-').filter { it.isNotBlank() }
    val code = if (words.size >= 2) {
        words.take(4).joinToString("") { it.take(1) }
    } else {
        label.take(4)
    }
    return code.uppercase()
}

/** Name of the station playing, from the state frame's `radio_name`.
 *  Null when the radio is off. */
internal fun vCurrentStationLabel(
    s: GameState?,
    stations: List<GameState.Station>,
): String? {
    val id = s?.radioName?.takeIf { it.isNotBlank() } ?: return null
    val fromGame = stations.firstOrNull { it.name == id }?.label
    return vStationLabel(id, fromGame)
}

/**
 * Station picker for the in-vehicle layout.
 *
 * @param stations the game's own list, in the game's order
 * @param currentId `radio_name` from the state frame ("" / null = off)
 * @param onStationTap called with the tapped station's position in
 *                     [stations] — that position is the command argument
 */
@Composable
internal fun VRadioPicker(
    stations: List<GameState.Station>,
    currentId: String?,
    palette: HudPalette,
    onStationTap: (Int) -> Unit,
) {
    // GTA IV shows 16 stations in two rows of eight. GTA V has 20+, so
    // allow a third row before the buttons start shrinking.
    val perRow = when {
        stations.size <= 16 -> (stations.size + 1) / 2
        stations.size <= 24 -> 8
        else                -> (stations.size + 2) / 3
    }.coerceAtLeast(1)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.label_radio_stations),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
        )
        stations.indices.chunked(perRow).forEach { rowIndices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (index in rowIndices) {
                    val station = stations[index]
                    val isCurrent = !currentId.isNullOrBlank() &&
                        station.name == currentId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(
                                color = if (isCurrent) palette.accent
                                        else Color(0xFF1F2A24),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { onStationTap(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            vStationShort(station.name, station.label),
                            color = if (isCurrent) Color(0xFF000000)
                                    else Color(0xFFB6CCBC),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
                // Pad a short last row so its buttons keep the same
                // width as the rows above.
                repeat(perRow - rowIndices.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
