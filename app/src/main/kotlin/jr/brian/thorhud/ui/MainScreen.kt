package jr.brian.thorhud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import jr.brian.thorhud.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.CardAlign
import jr.brian.thorhud.ui.components.CoinPanel
import jr.brian.thorhud.ui.components.FullWidthStats
import jr.brian.thorhud.ui.components.IvWeaponWheelOverlay
import jr.brian.thorhud.ui.components.MoneyRadioPanel
import jr.brian.thorhud.ui.components.PausedScreen
import jr.brian.thorhud.ui.components.RadioPanel
import jr.brian.thorhud.ui.components.RadioPicker
import jr.brian.thorhud.ui.components.SetupScreen
import jr.brian.thorhud.ui.components.StatusBar
import jr.brian.thorhud.ui.components.VehiclePanel
import jr.brian.thorhud.ui.components.WeaponPanel
import jr.brian.thorhud.ui.components.paletteFor
import jr.brian.thorhud.ui.components.stationListFor
import jr.brian.thorhud.ui.map.MirrorGame
import jr.brian.thorhud.ui.map.MirrorMapScreen
import jr.brian.thorhud.ui.v.VMainScreen
import jr.brian.thorhud.ui.v.VWeaponWheelOverlay
import jr.brian.thorhud.ui.v.rememberVWheelMemory
import jr.brian.thorhud.ui.v.vCharacterOf
import jr.brian.thorhud.ui.v.vIsNoticeState
import jr.brian.thorhud.update.UpdateDialog
import kotlinx.coroutines.delay

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val hello by viewModel.hello.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastUpdateMs by viewModel.lastUpdateMs.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val lightsOn by viewModel.policeLightsEnabled.collectAsStateWithLifecycle()
    val lightsStatus by viewModel.stickLightsStatus.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.checkForUpdate() }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val isStale = lastUpdateMs > 0 && (now - lastUpdateMs) > 1500
    val isDisconnected = lastUpdateMs == 0L
    val liveState = if (!isDisconnected && !isStale) state else null

    // Hoisted so SetupScreen, the IV HUD and the V HUD share one
    // instance; palette uses whichever episode we know about so the
    // dialog's accent matches the HUD below it.
    var showUpdateDialog by remember { mutableStateOf(false) }
    if (showUpdateDialog && updateInfo.isUpdateAvailable) {
        UpdateDialog(
            updateInfo = updateInfo,
            currentVersion = viewModel.currentVersionName,
            palette = paletteFor(state?.episode),
            onDismiss = { showUpdateDialog = false },
        )
    }

    if (isDisconnected) {
        SetupScreen(lastError)
        return
    }

    // GTA V has its own HUD layout (ui/v/); hello frame names the game.
    val isGtaV = hello?.isGtaV == true
    val accent = if (isGtaV) {
        vCharacterOf(state?.character).palette.accent
    } else {
        paletteFor(liveState?.episode ?: state?.episode).accent
    }

    // Weapon wheel, both games (ui/v/VWeaponWheel.kt draws it;
    // components/IvWeaponWheel.kt is the GTA IV side). It is on screen
    // while the player holds the wheel button (`wheel` in the state
    // frame: GTA V plugin 0.2.0+, GTA IV plugin natives level 4) or
    // after a tap on the weapon card.
    // Collected up here, ahead of the early returns below, so GTA V's
    // per-slot memory keeps following the equipped weapon across pause
    // and notice screens.
    val weapons by viewModel.weapons.collectAsStateWithLifecycle()
    val weaponPick by viewModel.weaponPick.collectAsStateWithLifecycle()
    // What is in hand, in the game's own terms: a weapon hash in GTA V,
    // a small weapon id in GTA IV. Same terms as WeaponPick.hash.
    val equippedWeapon = if (isGtaV) liveState?.weaponHash
                         else liveState?.weaponId?.toLong()
    val wheelMemory = rememberVWheelMemory(weapons, equippedWeapon.takeIf { isGtaV })
    var wheelOpenedByTap by remember { mutableStateOf(false) }
    val wheelHeld = liveState?.wheel == true
    // On foot only: both plugins refuse set_weapon in a vehicle.
    val onFoot = liveState?.inGame == true && liveState?.inVehicle == false
    val wheelUsable = onFoot && if (isGtaV) {
        !vIsNoticeState(hello, state)
    } else {
        // A GTA IV plugin that does not scan owned weapons (natives
        // level below 4, which includes every release so far) sends an
        // empty list. Keep the wheel off then, rather than opening one
        // with nothing on it but fists.
        liveState?.ownedWeapons?.isNotEmpty() == true
    }
    val showWheel = wheelUsable && (wheelHeld || wheelOpenedByTap)
    // What a tap on the weapon card does; null leaves the card inert.
    val openWheel: (() -> Unit)? =
        if (wheelUsable) { { wheelOpenedByTap = true } } else null

    // Releasing the button closes the wheel however it was opened, and
    // a tap-opened wheel does not outlive the situation it was opened
    // in (got in a car, paused, lost the connection).
    LaunchedEffect(wheelHeld, wheelUsable) {
        if (!wheelHeld || !wheelUsable) wheelOpenedByTap = false
    }
    // A tap-opened wheel left alone closes by itself; every pick
    // restarts the clock.
    LaunchedEffect(wheelOpenedByTap, wheelHeld, weaponPick) {
        if (wheelOpenedByTap && !wheelHeld) {
            delay(WHEEL_IDLE_CLOSE_MS)
            wheelOpenedByTap = false
        }
    }
    // The pick only means something while the wheel is up, and only
    // until the game reports that weapon in hand.
    LaunchedEffect(showWheel, weaponPick, equippedWeapon) {
        val pick = weaponPick ?: return@LaunchedEffect
        if (!showWheel || (pick.error == null && pick.hash == equippedWeapon)) {
            viewModel.clearWeaponPick()
        }
    }

    // Pause (and the V-specific notices: unsupported, online, stalled,
    // character-switch) take over the whole bottom screen. Rendering
    // these above HudPager means both tabs are replaced and swipe is
    // disabled while the notice is up.
    if (!isGtaV && state?.paused == true) {
        PausedScreen(
            hello = hello,
            palette = paletteFor(liveState?.episode ?: state?.episode),
        )
        return
    }
    if (isGtaV && vIsNoticeState(hello, state)) {
        VMainScreen(
            viewModel = viewModel,
            hello = hello,
            state = state,
            isStale = isStale,
            updateAvailable = updateInfo.isUpdateAvailable,
            onOpenUpdate = { showUpdateDialog = true },
        )
        return
    }

    val gameKey = if (isGtaV) "v" else "iv"
    val initialPage = remember(gameKey) { viewModel.pagerPage(gameKey) }

    // One-time swipe hint on the first connected run. Dismissing
    // writes a prefs flag so it never shows again.
    var showSwipeHint by remember { mutableStateOf(!viewModel.hasSeenSwipeHint) }
    if (showSwipeHint) {
        AlertDialog(
            onDismissRequest = {
                showSwipeHint = false
                viewModel.hasSeenSwipeHint = true
            },
            title = { Text(stringResource(R.string.swipe_hint_title)) },
            text = { Text(stringResource(R.string.swipe_hint_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSwipeHint = false
                    viewModel.hasSeenSwipeHint = true
                }) { Text(stringResource(R.string.swipe_hint_dismiss)) }
            },
        )
    }

    // key(gameKey) rebuilds HudPager (and its PagerState) when the
    // connected game changes, so the restored page actually takes
    // effect instead of staying on the previous game's page.
    key(gameKey) {
    Box(Modifier.fillMaxSize()) {
    HudPager(
        initialPage = initialPage,
        onPageSettled = { viewModel.setPagerPage(gameKey, it) },
        hud = {
            if (isGtaV) {
                VMainScreen(
                    viewModel = viewModel,
                    hello = hello,
                    state = state,
                    isStale = isStale,
                    updateAvailable = updateInfo.isUpdateAvailable,
                    onOpenUpdate = { showUpdateDialog = true },
                    onOpenWeaponWheel = openWheel,
                )
            } else {
                IvHudScreen(
                    viewModel = viewModel,
                    state = state,
                    liveState = liveState,
                    isStale = isStale,
                    lightsOn = lightsOn,
                    lightsUnavailable = lightsStatus.isUnavailable,
                    updateAvailable = updateInfo.isUpdateAvailable,
                    onOpenUpdate = { showUpdateDialog = true },
                    onOpenWeaponWheel = openWheel,
                )
            }
        },
        map = {
            MirrorMapScreen(
                game = if (isGtaV) MirrorGame.V else MirrorGame.IV,
                accent = accent,
            )
        },
    )
    // Drawn over the pager so it covers both pages and swallows the
    // swipe between them while it is up.
    if (showWheel && isGtaV) {
        VWeaponWheelOverlay(
            weapons = weapons,
            equippedHash = equippedWeapon,
            pick = weaponPick,
            memory = wheelMemory,
            held = wheelHeld,
            palette = vCharacterOf(state?.character).palette,
            onPick = viewModel::pickWeapon,
            onDismiss = { wheelOpenedByTap = false },
        )
    } else if (showWheel) {
        IvWeaponWheelOverlay(
            owned = liveState?.ownedWeapons.orEmpty(),
            equippedId = liveState?.weaponId,
            pick = weaponPick,
            held = wheelHeld,
            palette = paletteFor(liveState?.episode ?: state?.episode),
            onPick = viewModel::pickWeapon,
            onDismiss = { wheelOpenedByTap = false },
        )
    }
    }
    }
}

/** How long a tap-opened weapon wheel stays up with no further taps. */
private const val WHEEL_IDLE_CLOSE_MS = 6000L

@Composable
private fun IvHudScreen(
    viewModel: MainViewModel,
    state: GameState?,
    liveState: GameState?,
    isStale: Boolean,
    lightsOn: Boolean,
    lightsUnavailable: Boolean,
    updateAvailable: Boolean,
    onOpenUpdate: () -> Unit,
    // Tapping the on-foot weapon card opens the weapon wheel. Null
    // leaves the card inert (in a vehicle, or nothing to pick from).
    onOpenWeaponWheel: (() -> Unit)?,
) {
    val palette = paletteFor(liveState?.episode ?: state?.episode)

    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            // Drop the "IV" suffix when there's no live cash to show
            // (the HUD is a shell with "—" everywhere) — the title
            // naming a game it can't read from feels like a lie.
            val titleRes = if (liveState?.money != null) R.string.brand_iv
                           else R.string.brand
            StatusBar(
                isDisconnected = false,
                isStale = isStale,
                inGame = liveState?.inGame == true,
                wantedLevel = liveState?.wanted ?: 0,
                palette = palette,
                starsRightToLeft = true,
                title = stringResource(titleRes),
                lightsOn = lightsOn,
                lightsUnavailable = lightsUnavailable,
                onToggleLights = viewModel::togglePoliceLights,
                updateAvailable = updateAvailable,
                onOpenUpdate = onOpenUpdate,
            )
            Spacer(Modifier.height(8.dp))
            val inVehicle = liveState?.inVehicle == true
            if (inVehicle) {
                // In-vehicle: three narrow columns — coin+radio stacked
                // on the left, weapon in the middle, vehicle on the
                // right. The radio PICKER below gets a full row to
                // itself since the player is actively driving.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MoneyRadioPanel(liveState, Modifier.weight(1f))
                    WeaponPanel(liveState, modifier = Modifier.weight(1f))
                    VehiclePanel(liveState, palette, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.weight(1f))
                RadioPicker(
                    stations = stationListFor(liveState?.episode),
                    currentLocal = liveState?.radio ?: -1,
                    palette = palette,
                    onStationTap = { enumIdx -> viewModel.setRadio(enumIdx) },
                )
                Spacer(Modifier.weight(1f))
            } else {
                // On foot: 2×2. Half-width columns give long weapon
                // names ("pump shotgun", "sawed-off shotgun", "combat
                // sniper") room to render without an ellipsis. Each row
                // takes equal vertical space (weight 1f) so WeaponPanel
                // can vertically-centre its contents in its cell —
                // "clip 15/30" sits in the vertical middle of the right
                // column, visually between the weapon name and the
                // radio panel below.
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoinPanel(liveState, align = CardAlign.Start,
                              verticalCenter = true,
                              modifier = Modifier.weight(1f).fillMaxHeight())
                    val weaponCard = Modifier.weight(1f).fillMaxHeight().let {
                        if (onOpenWeaponWheel != null) {
                            it.clickable(onClick = onOpenWeaponWheel)
                        } else {
                            it
                        }
                    }
                    WeaponPanel(liveState, align = CardAlign.End,
                                verticalCenter = true,
                                modifier = weaponCard)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VehiclePanel(liveState, palette, align = CardAlign.Start,
                                 modifier = Modifier.weight(1f).fillMaxHeight())
                    RadioPanel(liveState, align = CardAlign.End,
                               modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            FullWidthStats(liveState, palette)
        }
    }
}
