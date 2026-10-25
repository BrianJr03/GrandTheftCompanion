package jr.brian.thorhud.ui.v

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
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

/** True when V would render a full-screen notice instead of the HUD. */
internal fun vIsNoticeState(hello: GameState?, state: GameState?): Boolean =
    hello?.supported == false ||
        state?.online == true ||
        (state != null && state.inGame && (state.paused || state.stalled)) ||
        state?.switching == true

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
 *   - on foot, tapping the weapon card opens the weapon wheel
 */
@Composable
internal fun VMainScreen(
    viewModel: MainViewModel,
    hello: GameState?,
    state: GameState?,
    isStale: Boolean,
    updateAvailable: Boolean = false,
    onOpenUpdate: (() -> Unit)? = null,
    // Tapping the on-foot weapon card opens the weapon wheel
    // (VWeaponWheel.kt). Null leaves the card inert.
    onOpenWeaponWheel: (() -> Unit)? = null,
    blipsOpen: Boolean = false,
    onOpenBlips: (() -> Unit)? = null,
    inVehicle: Boolean = false,
    onScrollToMap: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenCheats: (() -> Unit)? = null,
    // Long-press on the vehicle card opens Customs. Null leaves the
    // card tap-only (notice screens, IV, when the sheet is already
    // visible, etc.).
    onOpenCustoms: (() -> Unit)? = null,
    // Long-press on the on-foot weapon card opens Ammu-Nation. Null
    // leaves the card wheel-tap only (notice screens, in a vehicle,
    // the plugin refusing commands, etc.).
    onOpenAmmu: (() -> Unit)? = null,
) {
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val lightsOn by viewModel.policeLightsEnabled.collectAsStateWithLifecycle()
    val lightsStatus by viewModel.stickLightsStatus.collectAsStateWithLifecycle()

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
                onOpenSettings = onOpenSettings,
            )
            return
        }
        // Story mode only; the plugin sends nothing but the flag.
        state?.online == true -> {
            VNoticeScreen(
                title = stringResource(R.string.v_notice_online_title),
                accent = palette.accent,
                detail = stringResource(R.string.v_notice_online_detail),
                onOpenSettings = onOpenSettings,
            )
            return
        }
        state != null && state.inGame && (state.paused || state.stalled) -> {
            // The full-screen notice is a brand moment ("Michael"), not a
            // repeated label like the cash card; title-case it here.
            VNoticeScreen(
                title = characterName.replaceFirstChar { it.titlecase() },
                accent = palette.accent,
                detail = versionLine,
                onOpenSettings = onOpenSettings,
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
                onOpenSettings = onOpenSettings,
            )
            return
        }
    }

    // --- HUD -------------------------------------------------------
    val vehicle = live?.vehicle?.takeIf { live.inVehicle }
    val inVehicle = vehicle != null

    // Character is already named by the status-bar title colour and the
    // cash card ("michael · cash"); the chip was redundant.
    val tags = mutableListOf<Pair<String, Boolean>>()
    if (live != null && live.inGame) {
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
            // Mirrors MainScreen: the HUD title drops back to plain
            // "Grand Theft Companion" (no "V") while the cash card is
            // showing "—" (no live money for a protagonist). The
            // moment real data flows the "V" rejoins.
            val hasCash = live?.money != null && character.isProtagonist
            val titleRes = if (hasCash) R.string.brand_v else R.string.brand
            StatusBar(
                isDisconnected = false,
                isStale = isStale,
                inGame = live?.inGame == true,
                wantedLevel = live?.wanted ?: 0,
                palette = palette,
                maxStars = 5,   // GTA V: five stars, not six
                starsRightToLeft = true,   // GTA V HUD fills right-to-left
                title = stringResource(titleRes),
                lightsOn = lightsOn,
                lightsUnavailable = lightsStatus.isUnavailable,
                onToggleLights = viewModel::togglePoliceLights,
                updateAvailable = updateAvailable,
                onOpenUpdate = onOpenUpdate,
                blipsOpen = blipsOpen,
                onOpenBlips = onOpenBlips,
                inVehicle = inVehicle,
                onScrollToMap = onScrollToMap,
                onOpenCheats = onOpenCheats,
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
                    VVehiclePanel(
                        live, palette,
                        modifier = Modifier
                            .weight(1f)
                            .then(longPressCustomsModifier(onOpenCustoms)),
                    )
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
                    // On foot only: the plugin refuses set_weapon in a
                    // vehicle, so the in-vehicle card above stays inert.
                    val weaponCard = if (onOpenWeaponWheel != null &&
                                         live?.inGame == true) {
                        Modifier.weight(1f)
                            .then(weaponCardGestures(onOpenWeaponWheel, onOpenAmmu))
                    } else {
                        Modifier.weight(1f)
                    }
                    VWeaponPanel(live, align = CardAlign.End,
                                 showEditGlyph = onOpenAmmu != null,
                                 modifier = weaponCard)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VVehiclePanel(
                        live, palette, align = CardAlign.Start,
                        modifier = Modifier
                            .weight(1f)
                            .then(longPressCustomsModifier(onOpenCustoms)),
                    )
                    VRadioPanel(live, stations, align = CardAlign.End,
                                modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
            VStatsBars(live, character, palette)
        }
    }
}

/** Chains a long-press-to-Customs gesture on top of the vehicle card.
 *  A null callback leaves the modifier a no-op, so passing null in
 *  notice branches keeps the card inert. combinedClickable is used
 *  rather than pointerInput so a short tap is still swallowed — the
 *  vehicle card has no tap action today and we don't want one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun longPressCustomsModifier(onOpen: (() -> Unit)?): Modifier {
    if (onOpen == null) return Modifier
    val source = remember { MutableInteractionSource() }
    return Modifier.combinedClickable(
        interactionSource = source,
        indication = null,
        onClick = {},
        onLongClick = onOpen,
    )
}

/** Pair of (tap → wheel, long-press → Ammu-Nation) on the on-foot
 *  weapon card. combinedClickable swallows the tap on a long-press,
 *  so a successful hold does not also flash the wheel open. The
 *  tap action is required (opening the wheel) — the on-foot card
 *  is only reached when onOpenWheel is non-null. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun weaponCardGestures(
    onOpenWheel: () -> Unit,
    onOpenAmmu: (() -> Unit)?,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return Modifier.combinedClickable(
        interactionSource = source,
        indication = null,
        onClick = onOpenWheel,
        onLongClick = onOpenAmmu,
    )
}
