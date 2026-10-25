package jr.brian.thorhud.ui.ammunation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.model.Armory
import jr.brian.thorhud.model.GameState
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
 * Outcome of the most recent Ammu-Nation tap. [pendingKey] grey-outs
 * the row waiting on its ack so a second tap in the same row cannot
 * double-send. [error] is null while the ack is in flight; set to
 * the plugin's refusal (or a transport reason) on failure.
 */
internal data class AmmuNationResult(
    val pendingKey: String,
    val error: String? = null,
)

/**
 * Hoisted state for the Ammu-Nation full-screen sheet. Mirrors
 * [jr.brian.thorhud.ui.customs.CustomsViewModel]: one command in
 * flight at a time, 2.5 s ack timeout, pendingKey keyed so each row
 * only greys itself.
 */
@HiltViewModel
internal class AmmuNationViewModel @Inject constructor(
    private val repository: GameStateRepository,
) : ViewModel() {

    val armory: StateFlow<Armory?> = repository.armory
    val weapons: StateFlow<List<GameState.VWeapon>> = repository.weapons
    val state: StateFlow<GameState?> = repository.state

    private val _result = MutableStateFlow<AmmuNationResult?>(null)
    val result: StateFlow<AmmuNationResult?> = _result.asStateFlow()
    private var pending: Job? = null

    /** Ask the plugin for the Ammu-Nation page of [weaponHash].
     *  Called on open and when the user picks a different weapon in
     *  the strip. No-op when the client is not connected. */
    fun open(weaponHash: Long) {
        viewModelScope.launch { repository.requestArmory(weaponHash) }
    }

    fun selectWeapon(weaponHash: Long) = open(weaponHash)

    fun setComponent(widx: Int, cidx: Int, on: Boolean) =
        dispatch("set_component:$cidx") {
            repository.setComponent(widx, cidx, on)
        }

    fun setWeaponTint(widx: Int, tint: Int) =
        dispatch("set_weapon_tint:$tint") {
            repository.setWeaponTint(widx, tint)
        }

    fun fillAmmo(widx: Int) = dispatch("set_ammo:fill") {
        repository.setAmmo(widx, AmmoAction.Fill.wire)
    }

    fun addClip(widx: Int) = dispatch("set_ammo:add_clip") {
        repository.setAmmo(widx, AmmoAction.AddClip.wire)
    }

    fun clearResult() { _result.value = null }

    private fun dispatch(key: String, send: suspend () -> Int?) {
        pending?.cancel()
        _result.value = AmmuNationResult(key)
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
            _result.value = if (error == null) null else AmmuNationResult(key, error)
        }
    }

    private companion object {
        const val ACK_TIMEOUT_MS = 2500L
    }
}
