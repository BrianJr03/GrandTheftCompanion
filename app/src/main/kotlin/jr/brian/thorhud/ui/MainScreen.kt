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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.delay

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val hello by viewModel.hello.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastUpdateMs by viewModel.lastUpdateMs.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val isStale = lastUpdateMs > 0 && (now - lastUpdateMs) > 1500
    val isDisconnected = lastUpdateMs == 0L
    val liveState = if (!isDisconnected && !isStale) state else null

    if (isDisconnected) {
        SetupScreen(lastError)
        return
    }

    val palette = paletteFor(liveState?.episode ?: state?.episode)

    if (state?.paused == true) {
        PausedScreen(hello = hello, palette = palette)
        return
    }

    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            StatusBar(
                isDisconnected = isDisconnected,
                isStale = isStale,
                inGame = liveState?.inGame == true,
                wantedLevel = liveState?.wanted ?: 0,
                palette = palette,
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
