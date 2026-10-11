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
 * Phase 1 scaffold screen. Shows the live ThorBridge handshake and tick
 * stream on the primary display. Phase 4 moves the real HUD to the
 * bottom screen.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val hello by viewModel.hello.collectAsStateWithLifecycle()
    val last by viewModel.last.collectAsStateWithLifecycle()
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
            HelloPanel(hello)
            Spacer(Modifier.height(16.dp))
            LastFramePanel(last)
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
        Text("  size         = ${hello.size}", style = body)
        Text("  file_version = ${hello.fileVersion}", style = body)
        Text("  supported    = ${hello.supported}", style = body)
    }
}

@Composable
private fun LastFramePanel(state: GameState) {
    val mono = FontFamily.Monospace
    val body = MaterialTheme.typography.bodyLarge.copy(fontFamily = mono)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("last frame:", style = MaterialTheme.typography.titleSmall)
        Text("  type = ${state.type}", style = body)
        state.tickMs?.let { Text("  t    = $it ms", style = body) }
    }
}
