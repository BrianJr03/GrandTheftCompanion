package jr.brian.thorhud.ui.wardrobe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.model.Wardrobe
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.prefs.HudPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Outcome of the most recent Wardrobe tap. [pendingKey] greys out the
 * one row waiting for its ack; [error] carries the plugin's refusal
 * (or a transport reason) once the ack lands or times out.
 */
internal data class WardrobeResult(
    val pendingKey: String,
    val error: String? = null,
)

/**
 * Hoisted state for the Wardrobe full-screen sheet. Mirrors
 * [jr.brian.thorhud.ui.customs.CustomsViewModel] in shape: one in
 * flight at a time, 2.5 s ack timeout, and the screen reads a
 * [result] flow + the live catalogue off the repository.
 *
 * Presets live on the phone, keyed by `(game, ped-model hash)` so
 * Michael's saved looks never surface for Franklin. [presets] follows
 * whichever wardrobe the plugin last published — a character switch
 * surfaces the new character's saved bag automatically.
 */
@HiltViewModel
internal class WardrobeViewModel @Inject constructor(
    private val repository: GameStateRepository,
    private val prefs: HudPrefs,
) : ViewModel() {

    val wardrobe: StateFlow<Wardrobe?> = repository.wardrobe

    private val _result = MutableStateFlow<WardrobeResult?>(null)
    val result: StateFlow<WardrobeResult?> = _result.asStateFlow()
    private var pending: Job? = null

    /** The saved-looks bag for whichever ped the plugin last reported.
     *  Null wardrobe → empty list; a model change swaps the bag. The
     *  flow is derived from the live catalogue so no race between a
     *  character switch and the next time the user opens the sheet. */
    val presets: StateFlow<List<WardrobePreset>> =
        repository.wardrobe
            .map { w ->
                if (w == null) emptyList()
                else deserialisePresets(
                    prefs.wardrobePresets("v", w.pedModel).value,
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ask the plugin for a fresh catalogue of the current ped. The
     *  sheet calls this on open and on every change-ped signal. */
    fun open() {
        viewModelScope.launch { repository.requestWardrobe() }
    }

    /** Component or prop write for one slot. The screen packs with
     *  [packPiece] so this layer stays mostly plumbing. */
    fun setPiece(packed: Long) = dispatch("set_piece:$packed") {
        repository.setPiece(packed)
    }

    /** Atomic apply. The plugin validates every part first so an
     *  outfit never lands half-applied. */
    fun applyOutfit(parts: List<Long>) = dispatch("apply_outfit") {
        repository.applyOutfit(parts)
    }

    /** SET_PED_DEFAULT_COMPONENT_VARIATION + clear every prop. */
    fun resetOutfit() = dispatch("reset_outfit") {
        repository.resetOutfit()
    }

    /** Save the current look as a named preset. No-op if the current
     *  wardrobe is null, the user is at the preset cap, or the name
     *  is blank. Returns the final list the screen should show. */
    fun savePreset(name: String): List<WardrobePreset> {
        val w = wardrobe.value ?: return presets.value
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return presets.value
        val existing = presets.value
        if (existing.size >= MAX_PRESETS) return existing
        val next = existing + WardrobePreset(trimmed, snapshotOutfit(w))
        prefs.setWardrobePresets("v", w.pedModel, serialisePresets(next))
        return next
    }

    /** Rename a preset by index. Returns the final list; a bad index
     *  or empty name leaves the bag unchanged. */
    fun renamePreset(index: Int, newName: String): List<WardrobePreset> {
        val w = wardrobe.value ?: return presets.value
        val cur = presets.value
        if (index !in cur.indices) return cur
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return cur
        val next = cur.toMutableList().also { it[index] = it[index].copy(name = trimmed) }
        prefs.setWardrobePresets("v", w.pedModel, serialisePresets(next))
        return next
    }

    /** Drop one preset by index. */
    fun deletePreset(index: Int): List<WardrobePreset> {
        val w = wardrobe.value ?: return presets.value
        val cur = presets.value
        if (index !in cur.indices) return cur
        val next = cur.toMutableList().also { it.removeAt(index) }
        prefs.setWardrobePresets("v", w.pedModel, serialisePresets(next))
        return next
    }

    /** Apply a saved preset as a single atomic outfit. */
    fun applyPreset(index: Int) {
        val cur = presets.value
        val preset = cur.getOrNull(index) ?: return
        if (preset.parts.isEmpty()) return
        applyOutfit(preset.parts)
    }

    /** Clear the result after it has been read (e.g. the error line
     *  faded out). The pending guard uses the Job itself so this is
     *  cosmetic only. */
    fun clearResult() { _result.value = null }

    private fun dispatch(key: String, send: suspend () -> Int?) {
        pending?.cancel()
        _result.value = WardrobeResult(key)
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
            _result.value = if (error == null) null else WardrobeResult(key, error)
        }
    }

    private companion object {
        // Same discipline as CustomsViewModel: the plugin answers on its
        // next tick (25 Hz) and refuses stale commands at 2 s itself.
        const val ACK_TIMEOUT_MS = 2500L
    }
}
