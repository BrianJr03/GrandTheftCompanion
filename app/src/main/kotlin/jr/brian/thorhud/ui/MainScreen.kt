package jr.brian.thorhud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * M4 scaffold screen. Shows the raw game-state stream on the primary
 * display. M5 adds the real HUD on the Thor's bottom screen.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
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
            Spacer(Modifier.height(16.dp))
            StatePanel(state)
        }
    }
}

@Composable
private fun StatePanel(state: GameState) {
    val mono = FontFamily.Monospace
    val body = MaterialTheme.typography.bodyLarge.copy(fontFamily = mono)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("t         = ${state.tickMs} ms", style = body)
        Text("valid     = ${state.valid}", style = body)
        if (state.valid) {
            Text("health    = ${"%.1f".format(state.health)}", style = body)
            Text("maxHealth = ${state.maxHealth}", style = body)
            Text("maxArmor  = ${state.maxArmor}", style = body)
        }
    }
}
