package jr.brian.thorhud.ui.cheats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.prefs.HudPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

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
 *
 * Persistence: a successful toggle tap also writes the user's new
 * intent to [HudPrefs.enabledCheatToggles]. On reconnect / game
 * relaunch the plugin's own toggle mask resets to zero, and the
 * reconciler installed in [init] re-applies the persisted intent as
 * soon as the game reports a safe state (see [GameState.isCheatSafe]).
 * The user can disable the auto-re-apply via a Settings toggle.
 */
@HiltViewModel
internal class CheatsViewModel @Inject constructor(
    private val repository: GameStateRepository,
    private val prefs: HudPrefs,
) : ViewModel() {

    private val _result = MutableStateFlow<CheatResult?>(null)
    val result: StateFlow<CheatResult?> = _result.asStateFlow()
    private var pending: Job? = null

    /** Category names (CheatCategory.name) the user has collapsed on
     *  the cheats tab. Empty by default = every section is expanded,
     *  which is what first-time users see. Persisted across launches. */
    val collapsedSections: StateFlow<Set<String>> = prefs.collapsedCheatSections

    /** Flip a section's collapse state. [category] is CheatCategory.name. */
    fun setSectionCollapsed(category: String, collapsed: Boolean) {
        prefs.setCheatSectionCollapsed(category, collapsed)
    }

    /** One-shot cheat ids the user has flagged (via long-press) for
     *  auto-fire on reconnect. Lit on the cheats tab the same way
     *  active toggles are, so the user can tell which one-shots are
     *  armed at a glance. */
    val autoApplyCheats: StateFlow<Set<Int>> = prefs.autoApplyCheats

    /** True if a one-shot id is armed for auto-fire. UI uses this to
     *  render the button as filled rather than outlined. */
    fun isAutoApply(id: Int): Boolean = prefs.autoApplyCheats.value.contains(id)

    /** Flip the auto-apply state for a one-shot cheat (used by the
     *  long-press gesture on non-toggle buttons). Returns the new
     *  state so the caller can show a toast with the right wording. */
    fun toggleAutoApply(id: Int): Boolean {
        val next = !prefs.autoApplyCheats.value.contains(id)
        prefs.setCheatAutoApply(id, next)
        return next
    }

    /** Onboarding sheet dismissal flag. One shot per installation. */
    fun hasSeenOnboarding(): Boolean = prefs.hasSeenCheatsOnboarding
    fun markOnboardingSeen() { prefs.hasSeenCheatsOnboarding = true }

    /** Wipe the two persisted sets (toggle intent + armed one-shots).
     *  Fires a one-shot event on [clearedEvents] so the settings
     *  sheet can show a confirmation snackbar. */
    private val _clearedEvents = Channel<Unit>(Channel.BUFFERED)
    val clearedEvents: Flow<Unit> = _clearedEvents.receiveAsFlow()
    fun clearSavedCheats() {
        prefs.clearAutoCheats()
        _clearedEvents.trySend(Unit)
    }

    init {
        // Auto-re-apply reconciler. One shot per connection session:
        // when a state frame first arrives safe-to-fire, send `cheat`
        // commands for any persisted toggles whose bits are off in
        // the live mask. If the user disabled auto-re-apply in
        // Settings, do nothing. If the plugin re-boots (cheats_mask
        // becomes null again), the "already reconciled" latch
        // resets so the next connection re-arms the reconciler.
        viewModelScope.launch {
            // Guard rails against spamming the plugin:
            //   * `alreadyReconciled` fires us exactly once per
            //     connection session (reset only when cheatsMask goes
            //     null, i.e. the plugin dropped off).
            //   * `safeStreak` must reach kSafeStreakFrames before we
            //     start sending — avoids firing on a momentary safe
            //     window during a cutscene fade or save-load.
            //   * Each send is ack-gated: we do not queue the next
            //     cheat until the previous one has resolved (or
            //     ACK_TIMEOUT_MS has elapsed). The plugin's command
            //     queue (V: 16 slots, IV: 32 slots) therefore holds
            //     at most one of our cheats at a time.
            //   * A small delay between sends keeps the per-tick
            //     native-write count comfortably low.
            var alreadyReconciled = false
            var safeStreak = 0
            combine(
                repository.state,
                prefs.enabledCheatToggles,
                prefs.autoApplyCheats,
                prefs.autoReapplyCheats,
            ) { state, toggles, oneShots, autoEnabled ->
                ReconcileSnapshot(state, toggles, oneShots, autoEnabled)
            }.collect { snap ->
                val mask = snap.state?.cheatsMask
                if (mask == null) {
                    alreadyReconciled = false
                    safeStreak = 0
                    return@collect
                }
                if (snap.state.isCheatSafe != true) {
                    safeStreak = 0
                    return@collect
                }
                safeStreak++
                if (alreadyReconciled || !snap.autoEnabled) return@collect
                if (safeStreak < kSafeStreakFrames) return@collect
                val missingToggles = snap.toggles.filter { !isOn(mask, it) }
                val armedOneShots = snap.oneShots.toList()
                alreadyReconciled = true
                val missing = missingToggles + armedOneShots
                if (missing.isEmpty()) return@collect
                for (id in missing) {
                    val cmdId = repository.runCheat(id)
                    if (cmdId != null) {
                        // Ack-gate: wait for the plugin to confirm (or
                        // for the stale timeout to fire its own err)
                        // before issuing the next cheat.
                        withTimeoutOrNull(ACK_TIMEOUT_MS.milliseconds) {
                            repository.ack.first { it?.ackId == cmdId }
                        }
                    }
                    delay(RECONCILE_INTERVAL_MS)
                }
            }
        }
    }

    fun runCheat(cheatId: Int) {
        // Record the mask the user tapped against BEFORE sending so
        // we know what state the toggle will flip into. The state
        // flow may have moved on by the time the ack lands.
        val maskAtTap = repository.state.value?.cheatsMask ?: 0
        pending?.cancel()
        _result.value = CheatResult(cheatId)
        pending = viewModelScope.launch {
            val id = repository.runCheat(cheatId)
            val ack = if (id == null) null else withTimeoutOrNull(ACK_TIMEOUT_MS.milliseconds) {
                repository.ack.first { it?.ackId == id }
            }
            val error = when {
                id == null     -> "not connected"
                ack == null    -> "no reply from the game"
                ack.ok != true -> ack.err?.takeIf { it.isNotBlank() } ?: "refused"
                else           -> null
            }
            if (error == null && cheatId >= FIRST_TOGGLE_ID &&
                cheatId <= MAX_TOGGLE_ID) {
                // Successful toggle tap. Flip the persisted intent
                // based on what state the toggle was in just before
                // the tap. The plugin flipped the bit on its end;
                // we mirror the new bit in prefs so re-connect
                // restores it.
                val willBeOn = !isOn(maskAtTap, cheatId)
                prefs.setCheatToggleEnabled(cheatId, enabled = willBeOn)
            }
            // Clear the pending marker when the ack arrives clean;
            // keep the error visible until the next tap replaces it.
            _result.value = if (error == null) null else CheatResult(cheatId, error)
        }
    }

    private data class ReconcileSnapshot(
        val state: jr.brian.thorhud.model.GameState?,
        val toggles: Set<Int>,
        val oneShots: Set<Int>,
        val autoEnabled: Boolean,
    )

    private companion object {
        // Match MainViewModel.ACK_TIMEOUT_MS: the plugin answers on its
        // next tick (25 Hz) and refuses stale commands at 2 s itself.
        const val ACK_TIMEOUT_MS = 2500L

        // Gap between reconciliation commands AFTER the previous one
        // has been ack'd. Three plugin ticks is enough breathing room
        // for the game thread to finish each native write before we
        // queue the next.
        const val RECONCILE_INTERVAL_MS = 150L

        // How many consecutive safe-state frames to see before firing.
        // State frames arrive at ~20 Hz so this is ~500 ms of "really
        // in game and settled" — long enough to skip a cutscene fade
        // but short enough to feel immediate on a clean save-load.
        const val kSafeStreakFrames = 10

        // Top of the toggle id range per asi/src/cheats.h.
        const val MAX_TOGGLE_ID = 34
    }
}
