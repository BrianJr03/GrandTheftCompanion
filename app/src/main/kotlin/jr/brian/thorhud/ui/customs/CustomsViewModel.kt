package jr.brian.thorhud.ui.customs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.model.Mods
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Outcome of the most recent Customs tap. [error] is null while the
 * ack is in flight; set to the plugin's refusal (or a transport reason)
 * on failure. The screen reads [pendingKey] to grey out the row that
 * is waiting for its answer so a second tap in the same row cannot
 * double-send.
 */
internal data class CustomsResult(
    val pendingKey: String,
    val error: String? = null,
)

/**
 * Hoisted state for the Customs full-screen sheet. Mirrors the shape
 * of [jr.brian.thorhud.ui.cheats.CheatsViewModel] so the UI reads a
 * `result` flow, calls one of the sender methods, and sees the error
 * (if any) land on `result` after the ack timeout. One command in
 * flight at a time: a second tap replaces the first while the ack is
 * outstanding, matching the single-ack discipline the cheats tab uses.
 */
@HiltViewModel
internal class CustomsViewModel @Inject constructor(
    private val repository: GameStateRepository,
) : ViewModel() {

    val mods: StateFlow<Mods?> = repository.mods
    val vehicle: StateFlow<GameState?> = repository.state

    private val _result = MutableStateFlow<CustomsResult?>(null)
    val result: StateFlow<CustomsResult?> = _result.asStateFlow()
    private var pending: Job? = null

    /** Ask the plugin for a fresh catalogue of the current vehicle.
     *  No-op when the client is not connected. */
    fun open() {
        viewModelScope.launch { repository.requestMods() }
    }

    fun setMod(slot: Int, index: Int) = dispatch("set_mod:$slot") {
        repository.setMod(slot, index)
    }

    fun toggleMod(slot: Int) = dispatch("toggle_mod:$slot") {
        repository.toggleMod(slot)
    }

    fun setWheelType(type: Int) = dispatch("set_wheel_type") {
        repository.setWheelType(type)
    }

    fun setTint(tint: Int) = dispatch("set_tint") { repository.setTint(tint) }

    fun setPlateStyle(style: Int) = dispatch("set_plate_style") {
        repository.setPlateStyle(style)
    }

    fun setPlate(text: String) = dispatch("set_plate") {
        repository.setPlate(text)
    }

    fun setNeon(packedRgb: Long) = dispatch("set_neon") {
        repository.setNeon(packedRgb)
    }

    fun setSmoke(packedRgb: Long) = dispatch("set_smoke") {
        repository.setSmoke(packedRgb)
    }

    fun setXenonColour(index: Int) = dispatch("set_xenon_colour") {
        repository.setXenonColour(index)
    }

    fun setBulletproofTyres(on: Boolean) = dispatch("set_bulletproof_tyres") {
        repository.setBulletproofTyres(on)
    }

    /** Paint cheat ids (respray=60, next=61, swap=62). The Customs
     *  sheet surfaces them in its Paint section so the player gets
     *  the same options the Pay 'n' Spray / LSC shops offer. */
    fun runPaintCheat(cheatId: Int) = dispatch("paint_cheat:$cheatId") {
        repository.runCheat(cheatId)
    }

    /** Custom-colour RGB (cheat id 63 under the hood). The screen's
     *  swatch row and color wheel both route here. */
    fun paintRgb(packed: Long) = dispatch("paint_rgb") {
        repository.paintRgb(packed)
    }

    /** Clear the result after it has been read (e.g. the error line
     *  faded out). The pending guard uses the Job itself so this is
     *  cosmetic only. */
    fun clearResult() { _result.value = null }

    private fun dispatch(key: String, send: suspend () -> Int?) {
        pending?.cancel()
        _result.value = CustomsResult(key)
        pending = viewModelScope.launch {
            val id = send()
            val ack = if (id == null) null else withTimeoutOrNull(ACK_TIMEOUT_MS.milliseconds) {
                repository.ack.first { it?.ackId == id }
            }
            val error = when {
                id == null     -> "not connected"
                ack == null    -> "no reply from the game"
                ack.ok != true -> ack.err?.takeIf { it.isNotBlank() } ?: "refused"
                else           -> null
            }
            _result.value = if (error == null) null else CustomsResult(key, error)
        }
    }

    private companion object {
        // Same discipline as MainViewModel.pickWeapon and
        // CheatsViewModel.runCheat: the plugin answers on its next tick
        // (25 Hz) and refuses stale commands at 2 s itself.
        const val ACK_TIMEOUT_MS = 2500L
    }
}
