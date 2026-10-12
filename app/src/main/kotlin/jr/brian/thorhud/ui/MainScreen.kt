package jr.brian.thorhud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import jr.brian.thorhud.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.CardAlign
import jr.brian.thorhud.ui.components.CoinPanel
import jr.brian.thorhud.ui.components.FullWidthStats
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
import jr.brian.thorhud.ui.map.MapViewModel
import jr.brian.thorhud.ui.map.MirrorGame
import jr.brian.thorhud.ui.map.MirrorMapScreen
import jr.brian.thorhud.ui.v.VMainScreen
import jr.brian.thorhud.ui.v.vCharacterOf
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

    // Pause takes over the whole bottom screen — tabs and map included.
    // Only applies to GTA IV; V has its own notice flow inside VMainScreen
    // that covers pause + stalled + online + character-switch.
    if (!isGtaV && state?.paused == true) {
        PausedScreen(
            hello = hello,
            palette = paletteFor(liveState?.episode ?: state?.episode),
        )
        return
    }

    val gameKey = if (isGtaV) "v" else "iv"
    val initialPage = remember(gameKey) { viewModel.pagerPage(gameKey) }

    val mapViewModel: MapViewModel = hiltViewModel()
    val mirrorLocked by mapViewModel.isLocked.collectAsStateWithLifecycle()

    // key(gameKey) rebuilds HudPager (and its PagerState) when the
    // connected game changes, so the restored page actually takes
    // effect instead of staying on the previous game's page.
    key(gameKey) {
    HudPager(
        accent = accent,
        initialPage = initialPage,
        onPageSettled = { viewModel.setPagerPage(gameKey, it) },
        trailingHeader = { currentPage ->
            // Pager page 1 is the map. Show the gesture hint only when
            // the map is on-screen AND the user has unlocked gestures.
            if (currentPage == 1 && !mirrorLocked) {
                Text(
                    text = stringResource(R.string.map_gesture_hint),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        },
        hud = {
            if (isGtaV) {
                VMainScreen(
                    viewModel = viewModel,
                    hello = hello,
                    state = state,
                    isStale = isStale,
                    updateAvailable = updateInfo.isUpdateAvailable,
                    onOpenUpdate = { showUpdateDialog = true },
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
    }
}

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
                    WeaponPanel(liveState, align = CardAlign.End,
                                verticalCenter = true,
                                modifier = Modifier.weight(1f).fillMaxHeight())
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
