package jr.brian.thorhud.ui.v

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.MainViewModel
import jr.brian.thorhud.ui.components.CardAlign
import jr.brian.thorhud.ui.components.StatusBar

/**
 * GTA V HUD. Reached from ui/MainScreen.kt when the plugin's hello
 * frame says `"game":"v"`; everything GTA V–specific lives in this
 * package (ui/v/) so the GTA IV screen is untouched.
 *
 * The layout is GTA IV's: status bar, a 2×2 card grid on foot or a
 * three-column row plus radio picker in a vehicle, stat bars along the
 * bottom. What GTA V adds or changes, in the order it shows up below:
 *
 *   - Script Hook V missing / game build not supported by it  -> notice
 *   - GTA Online                                              -> notice
 *   - paused, or the game stopped running scripts ("stalled")  -> notice
 *   - character switch in progress                             -> notice
 *   - three protagonists: accent palette + name follow `character`
 *   - a tag row flagging mission / cutscene / animal / special
 *   - five wanted stars instead of six
 *   - cash per character; hidden for non-protagonists
 *   - radio picker built from the game's own station list
 *   - special-ability bar; engine bar hidden on bicycles
 */
@Composable
internal fun VMainScreen(
    viewModel: MainViewModel,
    hello: GameState?,
    state: GameState?,
    isStale: Boolean,
) {
    val stations by viewModel.stations.collectAsStateWithLifecycle()

    // `state` sticks across stale periods (same as GTA IV) so the
    // palette and notices don't flicker; `live` is null while stale so
    // the cards blank out instead of showing old numbers as current.
    val live = state.takeIf { !isStale }
    val character = vCharacterOf(state?.character)
    // Until a state frame has named a character, cards use GTA IV's
    // neutral titles rather than labelling the player "other".
    val characterKnown = state?.character != null
    val palette = character.palette
    val characterName = stringResource(character.nameRes)
    val versionLine = hello?.fileVersion?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.v_version, it) }

    // --- states with nothing live to show ---------------------------
    when {
        // The plugin loaded but cannot read the game: Script Hook V is
        // not installed, or it does not support this game build.
        hello?.supported == false -> {
            VNoticeScreen(
                title = stringResource(R.string.v_notice_unsupported_title),
                accent = Color(0xFFB04040),
                detail = stringResource(
                    R.string.v_notice_unsupported_detail,
                    hello?.err?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.v_notice_unsupported_unknown),
                ),
            )
            return
        }
        // Story mode only; the plugin sends nothing but the flag.
        state?.online == true -> {
            VNoticeScreen(
                title = stringResource(R.string.v_notice_online_title),
                accent = palette.accent,
                detail = stringResource(R.string.v_notice_online_detail),
            )
            return
        }
        state != null && state.inGame && (state.paused || state.stalled) -> {
            VNoticeScreen(
                title = characterName,
                accent = palette.accent,
                detail = versionLine,
            )
            return
        }
        // Camera is in the sky between characters; every reading is
        // about to change, so show the transition rather than a HUD
        // that is half one character and half the next.
        state?.switching == true -> {
            VNoticeScreen(
                title = stringResource(R.string.v_notice_switching_title),
                accent = palette.accent,
            )
            return
        }
    }

    // --- HUD -------------------------------------------------------
    val vehicle = live?.vehicle?.takeIf { live.inVehicle }
    val inVehicle = vehicle != null

    val tags = mutableListOf<Pair<String, Boolean>>()
    if (live != null && live.inGame) {
        tags.add(characterName to true)
        if (live.mission) tags.add(stringResource(R.string.v_tag_mission) to false)
        if (live.cutscene) tags.add(stringResource(R.string.v_tag_cutscene) to false)
        if (live.animal) tags.add(stringResource(R.string.v_tag_animal) to false)
        if (live.special?.active == true) {
            tags.add(stringResource(R.string.v_tag_special) to true)
        }
    }

    Scaffold(containerColor = Color.Black) { padding ->
        // Scrollable so overflow on narrow aspect ratios or large tag
        // rows reaches the stat bars instead of clipping. Weighted-fill
        // tricks don't survive an unbounded parent, so vertical spacing
        // is fixed and cards use their natural height.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            StatusBar(
                isDisconnected = false,
                isStale = isStale,
                inGame = live?.inGame == true,
                wantedLevel = live?.wanted ?: 0,
                palette = palette,
                maxStars = 5,   // GTA V: five stars, not six
                title = stringResource(R.string.brand_v),
            )
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                VFlagRow(tags, palette.accent)
            }
            Spacer(Modifier.height(8.dp))

            if (inVehicle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VCashRadioPanel(live, character, characterKnown,
                                    stations, Modifier.weight(1f))
                    VWeaponPanel(live, modifier = Modifier.weight(1f))
                    VVehiclePanel(live, palette, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                // No picker in a vehicle without a radio, or before the
                // station list has arrived.
                if (vehicle?.hasRadio != false && stations.isNotEmpty()) {
                    VRadioPicker(
                        stations = stations,
                        currentId = live?.radioName,
                        palette = palette,
                        onStationTap = { index -> viewModel.setRadio(index) },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VCashPanel(live, character, characterKnown,
                               align = CardAlign.Start,
                               modifier = Modifier.weight(1f))
                    VWeaponPanel(live, align = CardAlign.End,
                                 modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VVehiclePanel(live, palette, align = CardAlign.Start,
                                  modifier = Modifier.weight(1f))
                    VRadioPanel(live, stations, align = CardAlign.End,
                                modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
            VStatsBars(live, character, palette)
        }
    }
}
