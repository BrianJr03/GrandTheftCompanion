package jr.brian.thorhud.ui.cheats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Outcome of the most recent cheat tap. [error] is null while the ack
 * is in flight; set to the plugin's refusal string or a transport
 * reason when the ack fails. The UI reads [cheatId] to disable the
 * button that is currently waiting for its answer so a second tap
 * does not double-send.
 */
internal data class CheatResult(val cheatId: Int, val error: String? = null)

/**
 * One tap on the cheats tab. Copies the pattern of
 * [jr.brian.thorhud.ui.MainViewModel.pickWeapon]: cancel any in-flight
 * send, mark the current id pending, wait for the matching ack by id.
 * Toggle state is NOT inferred from the tap: the UI lights buttons
 * from the state frame's `cheats_mask` only, so a toggle that didn't
 * actually flip (because the plugin refused it) never looks on.
 */
@HiltViewModel
internal class CheatsViewModel @Inject constructor(
    private val repository: GameStateRepository,
) : ViewModel() {

    private val _result = MutableStateFlow<CheatResult?>(null)
    val result: StateFlow<CheatResult?> = _result.asStateFlow()
    private var pending: Job? = null

    fun runCheat(cheatId: Int) {
        pending?.cancel()
        _result.value = CheatResult(cheatId)
        pending = viewModelScope.launch {
            val id = repository.runCheat(cheatId)
            val ack = if (id == null) null else withTimeoutOrNull(ACK_TIMEOUT_MS) {
                repository.ack.first { it?.ackId == id }
            }
            val error = when {
                id == null     -> "not connected"
                ack == null    -> "no reply from the game"
                ack.ok != true -> ack.err?.takeIf { it.isNotBlank() } ?: "refused"
                else           -> null
            }
            // Clear the pending marker when the ack arrives clean;
            // keep the error visible until the next tap replaces it.
            _result.value = if (error == null) null else CheatResult(cheatId, error)
        }
    }

    private companion object {
        // Match MainViewModel.ACK_TIMEOUT_MS: the plugin answers on its
        // next tick (25 Hz) and refuses stale commands at 2 s itself.
        const val ACK_TIMEOUT_MS = 2500L
    }
}
