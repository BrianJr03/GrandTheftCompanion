package jr.brian.thorhud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.model.GameState

/**
 * Phase 2 debug screen. Shows hello + the last state frame in a dense
 * monospace layout. Phase 5 replaces this with a styled HUD on the
 * bottom screen.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val hello by viewModel.hello.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val last by viewModel.last.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "ThorHUD — GTA IV",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "waiting for ThorBridge.asi on 127.0.0.1:47801",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            HelloPanel(hello)
            Spacer(Modifier.height(12.dp))
            StatePanel(state, last)
        }
    }
}

@Composable
private fun HelloPanel(hello: GameState?) {
    val mono = FontFamily.Monospace
    val body = MaterialTheme.typography.bodyLarge.copy(fontFamily = mono)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("hello:", style = MaterialTheme.typography.titleSmall)
        if (hello == null) {
            Text("  (none yet)", style = body)
            return@Column
        }
        Text("  crc32        = ${hello.crc32}", style = body)
        Text("  file_version = ${hello.fileVersion}", style = body)
        Text("  supported    = ${hello.supported}", style = body)
    }
}

@Composable
private fun StatePanel(state: GameState?, last: GameState) {
    val mono = FontFamily.Monospace
    val body = MaterialTheme.typography.bodyLarge.copy(fontFamily = mono)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("state:", style = MaterialTheme.typography.titleSmall)
        if (state == null) {
            Text("  waiting — last frame was: ${last.type}", style = body)
            return@Column
        }
        Text("  in_game     = ${state.inGame}", style = body)
        Text("  in_vehicle  = ${state.inVehicle}", style = body)
        Text("  money       = ${state.money}", style = body)
        Text("  health      = ${state.health}", style = body)
        Text("  armor       = ${state.armor}", style = body)
        Text("  wanted      = ${state.wanted}", style = body)
        Text("  weapon      = ${state.weaponId} (clip=${state.ammoClip}/tot=${state.ammoTotal})", style = body)
        Text("  radio       = ${state.radio}", style = body)
        state.vehicle?.let { v ->
            Text("  vehicle     = ${v.name} (model=${v.model})", style = body)
            Text("  speed_mps   = ${"%.1f".format(v.speedMps)}", style = body)
            Text("  engine_hp   = ${"%.1f".format(v.engineHp)}", style = body)
        }
        Text("  owned=${state.ownedWeapons.size} slots", style = body)
    }
}
