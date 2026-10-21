package jr.brian.thorhud.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import jr.brian.thorhud.ui.components.InfoSheet
import jr.brian.thorhud.ui.components.IvWeaponWheelOverlay
import jr.brian.thorhud.ui.components.MoneyRadioPanel
import jr.brian.thorhud.ui.components.PausedScreen
import jr.brian.thorhud.ui.components.RadioPanel
import jr.brian.thorhud.ui.components.SettingsSheet
import jr.brian.thorhud.ui.components.RadioPicker
import jr.brian.thorhud.ui.components.SetupScreen
import jr.brian.thorhud.ui.components.StatusBar
import jr.brian.thorhud.ui.components.VehiclePanel
import jr.brian.thorhud.ui.components.WeaponPanel
import jr.brian.thorhud.ui.components.paletteFor
import jr.brian.thorhud.ui.components.stationListFor
import jr.brian.thorhud.ui.map.BlipListPanel
import jr.brian.thorhud.ui.map.MapViewModel
import jr.brian.thorhud.ui.map.MirrorGame
import jr.brian.thorhud.ui.map.MirrorMapScreen
import jr.brian.thorhud.ui.map.blipGroups
import jr.brian.thorhud.ui.map.blipLabel
import jr.brian.thorhud.ui.map.titleCase
import jr.brian.thorhud.ui.cheats.CheatsScreen
import jr.brian.thorhud.ui.cheats.CheatsViewModel
import jr.brian.thorhud.ui.components.HudPalette
import jr.brian.thorhud.ui.v.VMainScreen
import jr.brian.thorhud.ui.v.VRadioPicker
import jr.brian.thorhud.ui.v.VWeaponWheelOverlay
import jr.brian.thorhud.ui.v.rememberVWheelMemory
import jr.brian.thorhud.ui.v.vCharacterOf
import jr.brian.thorhud.ui.v.vIsNoticeState
import jr.brian.thorhud.ui.v.wheelReorder
import jr.brian.thorhud.update.UpdateSheet
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
    // Auto-open once per process start when a newer release lands and
    // the user has not already dismissed this exact version. The
    // status-bar chip stays independent — tapping it still opens the
    // dialog regardless of the dismissed-version pref.
    var autoPromptHandled by remember { mutableStateOf(false) }
    LaunchedEffect(updateInfo) {
        if (autoPromptHandled || updateInfo.latestVersion.isBlank()) return@LaunchedEffect
        autoPromptHandled = true
        if (updateInfo.isUpdateAvailable &&
            updateInfo.latestVersion != viewModel.dismissedUpdateVersion
        ) {
            showUpdateDialog = true
        }
    }
    if (showUpdateDialog && updateInfo.isUpdateAvailable) {
        UpdateSheet(
            updateInfo = updateInfo,
            currentVersion = viewModel.currentVersionName,
            palette = paletteFor(state?.episode),
            onDismiss = {
                viewModel.markUpdateDismissed(updateInfo.latestVersion)
                showUpdateDialog = false
            },
        )
    }

    // GTA V has its own HUD layout (ui/v/); hello frame names the game.
    // Also computed when disconnected (hello is null) so the whole tree
    // can see a stable accent; defaults to GTA IV neutral.
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
    val ivWheelOrder by viewModel.ivWheelOrder.collectAsStateWithLifecycle()
    val vWheelOrder by viewModel.vWheelOrder.collectAsStateWithLifecycle()
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
    // The blips panel rides over everything so it is reachable from
    // either pager page and either game's HUD. State lives on the VM
    // so a hardware key (dpad-down on the Thor; see HudActivity's
    // dispatchKeyEvent) can toggle it even when Compose does not have
    // focus.
    val showBlips by viewModel.showBlips.collectAsStateWithLifecycle()
    val toggleBlips: () -> Unit = { viewModel.toggleBlips() }

    // The companion runs on the Thor's bottom panel while the player
    // is playing on the top screen. An accidental back swipe should
    // never kill the HUD mid-mission: if the blips panel is open, back
    // dismisses it, otherwise we just swallow the event.
    BackHandler { if (showBlips) viewModel.setBlips(false) }

    // Settings sheet (open from the gear icon on paused / setup /
    // notice screens). State lives here so one sheet instance covers
    // every surface that can open it.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val openSettings: () -> Unit = { showSettings = true }
    // Radio bottom sheet opened from the map screen's icon column.
    // Shows the same picker that lives on the HUD tab, so changing
    // stations without swiping back is a tap away.
    var showRadioSheet by rememberSaveable { mutableStateOf(false) }
    val autoSwipeOnDrive by viewModel.autoSwipeOnDrive.collectAsStateWithLifecycle()
    val autoStartMirror by viewModel.autoStartMirror.collectAsStateWithLifecycle()
    val alwaysReturnToHud by viewModel.alwaysReturnToHud.collectAsStateWithLifecycle()
    val mapControlsRight by viewModel.mapControlsRight.collectAsStateWithLifecycle()
    val reconnecting by viewModel.reconnecting.collectAsStateWithLifecycle()

    // Hoisted mirror-consent launcher so both MirrorMapScreen's
    // "start mirroring" button and the auto-start-when-connected
    // effect use the same path.
    val mapViewModel: MapViewModel = hiltViewModel()
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            mapViewModel.start(result.resultCode, data)
        }
    }
    val requestMirrorConsent: () -> Unit = {
        consentLauncher.launch(mapViewModel.consentIntent())
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

    val gameKey = if (isGtaV) "v" else "iv"

    // One-time swipe hint on the first connected run. Dismissing
    // writes a prefs flag so it never shows again.
    var showSwipeHint by remember { mutableStateOf(!viewModel.hasSeenSwipeHint) }
    if (showSwipeHint && !isDisconnected) {
        val dismissHint = {
            showSwipeHint = false
            viewModel.hasSeenSwipeHint = true
        }
        InfoSheet(
            title = stringResource(R.string.swipe_hint_title),
            body = stringResource(R.string.swipe_hint_body),
            accent = accent,
            onDismiss = dismissHint,
        ) {
            TextButton(onClick = dismissHint) {
                Text(stringResource(R.string.swipe_hint_dismiss), color = accent)
            }
        }
    }

    // Single composition root for the whole screen so BlipsOverlay
    // keeps a stable identity across every state transition
    // (disconnect, pause, V notice, HUD). Earlier the overlay was
    // instantiated inside each branch separately; a pause → unpause
    // dropped and re-created it, which reset its internal state and
    // in some cases dropped the live subscriptions it depends on.
    Box(Modifier.fillMaxSize()) {
        when {
            isDisconnected -> SetupScreen(lastError, onOpenSettings = openSettings)
            !isGtaV && state?.paused == true -> PausedScreen(
                hello = hello,
                palette = paletteFor(liveState?.episode ?: state?.episode),
                onOpenSettings = openSettings,
            )
            isGtaV && vIsNoticeState(hello, state) -> VMainScreen(
                viewModel = viewModel,
                hello = hello,
                state = state,
                isStale = isStale,
                updateAvailable = updateInfo.isUpdateAvailable,
                onOpenUpdate = { showUpdateDialog = true },
                blipsOpen = showBlips,
                onOpenBlips = toggleBlips,
                onOpenSettings = openSettings,
            )
            else -> {
                // key(gameKey) rebuilds HudPager (and its PagerState)
                // when the connected game changes, so a V→IV switch
                // (or vice versa) always lands on the HUD tab of the
                // new game.
                key(gameKey) {
                    val pagerState = rememberHudPagerState()
                    val scope = rememberCoroutineScope()
                    val scrollToMap: () -> Unit = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    }
                    // Auto-swipe behaviour (settings toggle, both
                    // games):
                    //  Enter vehicle: remember the tab the user is on
                    //    right now, then jump to the map tab.
                    //  Exit vehicle, user is still on the map tab: go
                    //    back to the remembered pre-drive tab.
                    //  Exit vehicle, user has manually swiped away
                    //    from the map during the drive: leave them
                    //    where they are, don't yank them around.
                    val inVehicleNow = liveState?.inVehicle == true
                    var preDrivePage by rememberSaveable(gameKey) {
                        mutableStateOf(0)
                    }
                    LaunchedEffect(inVehicleNow, autoSwipeOnDrive,
                                   alwaysReturnToHud, gameKey) {
                        if (!autoSwipeOnDrive) return@LaunchedEffect
                        if (inVehicleNow) {
                            preDrivePage = pagerState.currentPage
                            if (pagerState.currentPage != 1) {
                                pagerState.animateScrollToPage(1)
                            }
                        } else {
                            // Exit vehicle. With the override on, go
                            // to the HUD tab regardless of where the
                            // user was. Without it, only pull back if
                            // the user is still sitting on the map;
                            // a manual swipe during the drive wins.
                            val target = if (alwaysReturnToHud) 0 else preDrivePage
                            val onMap = pagerState.currentPage == 1
                            if (target != pagerState.currentPage &&
                                (alwaysReturnToHud || onMap)
                            ) {
                                pagerState.animateScrollToPage(target)
                            }
                        }
                    }
                    // Auto-launch the mirror consent prompt when the
                    // setting is on, the connection is live, and the
                    // mirror hasn't been started yet. One shot per
                    // Idle-to-live transition so the user is not
                    // nagged over and over.
                    val mirrorStatus by mapViewModel.status.collectAsStateWithLifecycle()
                    LaunchedEffect(autoStartMirror, liveState?.inGame == true, mirrorStatus) {
                        if (autoStartMirror &&
                            liveState?.inGame == true &&
                            mirrorStatus == jr.brian.thorhud.mirror.ScreenMirror.Status.Idle
                        ) {
                            requestMirrorConsent()
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        HudPager(
                            pagerState = pagerState,
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
                                        blipsOpen = showBlips,
                                        onOpenBlips = toggleBlips,
                                        inVehicle = inVehicleNow,
                                        onScrollToMap = scrollToMap,
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
                                        blipsOpen = showBlips,
                                        onOpenBlips = toggleBlips,
                                        inVehicle = inVehicleNow,
                                        onScrollToMap = scrollToMap,
                                    )
                                }
                            },
                            map = {
                                // Drive "driving to <name>" off the user's
                                // own tap in the blips panel, not the
                                // plugin's routeBlip echo. Blips come in
                                // their own frame (mapViewModel.blips);
                                // the per-frame liveState.blips field is
                                // empty outside the "blips" frame type.
                                val selectedRouteId by mapViewModel.selectedRouteId
                                    .collectAsStateWithLifecycle()
                                val knownBlips by mapViewModel.blips
                                    .collectAsStateWithLifecycle()
                                // titleCase matches how the blips panel
                                // itself renders the name (first letter
                                // of each word up, existing uppercase
                                // preserved), so "APC" and "Ammu-Nation"
                                // stay as-is.
                                val routedBlip = selectedRouteId?.let { id ->
                                    knownBlips.firstOrNull { it.id == id }
                                }
                                val routeName = routedBlip?.let {
                                    blipLabel(isGtaV, it.sprite, it.colour).name.titleCase()
                                }
                                MirrorMapScreen(
                                    game = if (isGtaV) MirrorGame.V else MirrorGame.IV,
                                    accent = accent,
                                    onRequestConsent = requestMirrorConsent,
                                    onOpenBlips = toggleBlips,
                                    onOpenRadio = { showRadioSheet = true },
                                    controlsRight = mapControlsRight,
                                    inVehicle = inVehicleNow,
                                    routeName = routeName,
                                    speedMps = liveState?.vehicle?.speedMps ?: 0f,
                                    waypointDistM = if (isGtaV) routedBlip?.distM else null,
                                )
                            },
                            cheats = {
                                val cheatsViewModel: CheatsViewModel = hiltViewModel()
                                val cheatResult by cheatsViewModel.result
                                    .collectAsStateWithLifecycle()
                                val collapsed by cheatsViewModel.collapsedSections
                                    .collectAsStateWithLifecycle()
                                CheatsScreen(
                                    isGtaV = isGtaV,
                                    cheatsSupported = hello?.cheatsSupported,
                                    cheatsMask = liveState?.cheatsMask
                                        ?: state?.cheatsMask,
                                    accent = accent,
                                    lastResult = cheatResult,
                                    collapsedSections = collapsed,
                                    onToggleSection = { name, wasExpanded ->
                                        cheatsViewModel.setSectionCollapsed(
                                            name, collapsed = wasExpanded,
                                        )
                                    },
                                    onRunCheat = cheatsViewModel::runCheat,
                                )
                            },
                        )
                        // Drawn over the pager so it covers both pages
                        // and swallows the swipe between them.
                        if (showWheel && isGtaV) {
                            VWeaponWheelOverlay(
                                weapons = weapons,
                                equippedHash = equippedWeapon,
                                pick = weaponPick,
                                memory = wheelMemory,
                                held = wheelHeld,
                                palette = vCharacterOf(state?.character).palette,
                                order = vWheelOrder,
                                onPick = viewModel::pickWeapon,
                                onDismiss = { wheelOpenedByTap = false },
                                onReorder = { from, to ->
                                    viewModel.setWheelOrder(
                                        "v",
                                        wheelReorder(vWheelOrder, from, to),
                                    )
                                },
                                onResetOrder = { viewModel.resetWheelOrder("v") },
                            )
                        } else if (showWheel) {
                            IvWeaponWheelOverlay(
                                owned = liveState?.ownedWeapons.orEmpty(),
                                equippedId = liveState?.weaponId,
                                pick = weaponPick,
                                held = wheelHeld,
                                palette = paletteFor(liveState?.episode ?: state?.episode),
                                order = ivWheelOrder,
                                onPick = viewModel::pickWeapon,
                                onDismiss = { wheelOpenedByTap = false },
                                onReorder = { from, to ->
                                    viewModel.setWheelOrder(
                                        "iv",
                                        wheelReorder(ivWheelOrder, from, to),
                                    )
                                },
                                onResetOrder = { viewModel.resetWheelOrder("iv") },
                            )
                        }
                    }
                }
            }
        }
        // Stable, always-composed: reachable from any state, keeps its
        // scroll / expand / dialog state across state transitions.
        BlipsOverlay(
            isGtaV = isGtaV,
            accent = accent,
            show = showBlips,
            onClose = { viewModel.setBlips(false) },
        )
        if (showSettings) {
            SettingsSheet(
                palette = if (isGtaV) vCharacterOf(state?.character).palette
                          else paletteFor(liveState?.episode ?: state?.episode),
                autoSwipeOnDrive = autoSwipeOnDrive,
                onAutoSwipeChange = viewModel::setAutoSwipeOnDrive,
                autoStartMirror = autoStartMirror,
                onAutoStartMirrorChange = viewModel::setAutoStartMirror,
                alwaysReturnToHud = alwaysReturnToHud,
                onAlwaysReturnToHudChange = viewModel::setAlwaysReturnToHud,
                mapControlsRight = mapControlsRight,
                onMapControlsRightChange = viewModel::setMapControlsRight,
                onReconnectPlugin = viewModel::reconnectPlugin,
                isReconnecting = reconnecting,
                reconnectEvents = viewModel.reconnectEvents,
                onDismiss = { showSettings = false },
            )
        }
        if (showRadioSheet) {
            val vStations by viewModel.stations.collectAsStateWithLifecycle()
            RadioSheet(
                palette = if (isGtaV) vCharacterOf(state?.character).palette
                          else paletteFor(liveState?.episode ?: state?.episode),
                isGtaV = isGtaV,
                ivStations = stationListFor(liveState?.episode),
                ivCurrentLocal = liveState?.radio ?: -1,
                vStations = vStations,
                vCurrentId = liveState?.radioName,
                onStationTap = viewModel::setRadio,
                onDismiss = { showRadioSheet = false },
            )
        }
    }
}

/**
 * The blips panel, drawn above everything else when [show] is true.
 * Reads from the same MapViewModel the mirror page uses, so the list
 * and the routed-blip highlight stay in sync whichever surface opened
 * the panel.
 */
@Composable
private fun BlipsOverlay(
    isGtaV: Boolean,
    accent: Color,
    show: Boolean,
    onClose: () -> Unit,
) {
    if (!show) return
    val mapViewModel: MapViewModel = hiltViewModel()
    val blips by mapViewModel.blips.collectAsStateWithLifecycle()
    val gameState by mapViewModel.state.collectAsStateWithLifecycle()
    val groups = blipGroups(isGtaV, blips, gameState?.routedBlipId)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
    ) {
        BlipListPanel(
            groups = groups,
            accent = accent,
            // GTA IV: GET_BLIP_COORDS only returns real coords for
            // type-4 (coord) and type-5 (contact) blips; the type-1/2/
            // 3/6 crowd (car/char/object/pickup) all read (0,0,0) so
            // their distances are meaningless. Hide the distance for
            // the whole IV list — groups still sort by it internally.
            showDistance = isGtaV,
            onRoute = mapViewModel::routeBlip,
            onClear = mapViewModel::routeClear,
            onLogCensus = mapViewModel::blipCensus,
            onClose = onClose,
        )
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
    blipsOpen: Boolean = false,
    onOpenBlips: (() -> Unit)? = null,
    inVehicle: Boolean = false,
    onScrollToMap: (() -> Unit)? = null,
) {
    val palette = paletteFor(liveState?.episode ?: state?.episode)

    Scaffold(containerColor = Color.Black) { padding ->
        // Scrollable so overflow (longer translations, future tag rows)
        // reaches the stat bars instead of clipping. Mirrors VMainScreen:
        // vertical weight-fill tricks don't survive an unbounded parent,
        // so spacing is fixed and cards use their natural height.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
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
                blipsOpen = blipsOpen,
                onOpenBlips = onOpenBlips,
                inVehicle = inVehicle,
                onScrollToMap = onScrollToMap,
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
                Spacer(Modifier.height(16.dp))
                RadioPicker(
                    stations = stationListFor(liveState?.episode),
                    currentLocal = liveState?.radio ?: -1,
                    palette = palette,
                    onStationTap = { enumIdx -> viewModel.setRadio(enumIdx) },
                )
                Spacer(Modifier.height(16.dp))
            } else {
                // On foot: 2×2. Half-width columns give long weapon
                // names ("pump shotgun", "sawed-off shotgun", "combat
                // sniper") room to render without an ellipsis. Cards
                // use natural height so the whole page scrolls as one.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoinPanel(liveState, align = CardAlign.Start,
                              verticalCenter = true,
                              modifier = Modifier.weight(1f))
                    val weaponCard = Modifier.weight(1f).let {
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VehiclePanel(liveState, palette, align = CardAlign.Start,
                                 modifier = Modifier.weight(1f))
                    RadioPanel(liveState, align = CardAlign.End,
                               modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
            FullWidthStats(liveState, palette)
        }
    }
}

/**
 * ModalBottomSheet wrapping the same radio picker shown on the HUD
 * tab. Dispatches to [RadioPicker] (IV) or [VRadioPicker] (V) so a
 * tap on either flavour routes through the same [onStationTap] into
 * `MainViewModel.setRadio`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioSheet(
    palette: HudPalette,
    isGtaV: Boolean,
    ivStations: IntArray,
    ivCurrentLocal: Int,
    vStations: List<GameState.Station>,
    vCurrentId: String?,
    onStationTap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color.Black,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            if (isGtaV) {
                VRadioPicker(
                    stations = vStations,
                    currentId = vCurrentId,
                    palette = palette,
                    onStationTap = onStationTap,
                )
            } else {
                RadioPicker(
                    stations = ivStations,
                    currentLocal = ivCurrentLocal,
                    palette = palette,
                    onStationTap = onStationTap,
                )
            }
        }
    }
}
