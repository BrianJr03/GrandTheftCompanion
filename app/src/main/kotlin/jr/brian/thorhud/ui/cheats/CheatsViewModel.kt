package jr.brian.thorhud.ui.cheats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jr.brian.thorhud.net.GameStateRepository
import jr.brian.thorhud.prefs.HudPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class CheatsViewModel @Inject constructor(
    private val repository: GameStateRepository,
    private val prefs: HudPrefs,
) : ViewModel() {

    private val _result = MutableStateFlow<CheatResult?>(null)
    val result: StateFlow<CheatResult?> = _result.asStateFlow()
    private var pending: Job? = null

    // The connected game, as a HudPrefs key. Follows the hello frame,
    // defaulting to "iv" before anything has connected so the reconciler
    // and the UI have a sensible starting bag to read from.
    private val currentGameKey: StateFlow<String> = repository.hello
        .map { if (it?.isGtaV == true) "v" else "iv" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "iv")

    private fun gameKey(): String = currentGameKey.value

    /** Category names (CheatCategory.name) the user has collapsed on
     *  the cheats tab, for the currently connected game. Empty by
     *  default = every section is expanded, which is what first-time
     *  users see. Persisted across launches, per game. */
    val collapsedSections: StateFlow<Set<String>> = currentGameKey
        .flatMapLatest { prefs.collapsedCheatSections(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Flip a section's collapse state for the connected game.
     *  [category] is CheatCategory.name. */
    fun setSectionCollapsed(category: String, collapsed: Boolean) {
        prefs.setCheatSectionCollapsed(gameKey(), category, collapsed)
    }

    /** User-saved cheat section order for the connected game, as the
     *  list of CheatCategory.name values. Empty list = "use default"
     *  (the Compose layer resolves to alphabetical-by-localized-label
     *  because the data layer has no Context). Any name that is not a
     *  known CheatCategory has already been filtered out by the pref
     *  layer, so this list is always safe to resolve. */
    val savedSectionOrder: StateFlow<List<String>> = currentGameKey
        .flatMapLatest { prefs.cheatSectionOrder(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Replace the saved order with [order] (CheatCategory.name
     *  values). The caller is responsible for passing every category
     *  the current game shows; names outside KNOWN_CHEAT_CATEGORIES
     *  are dropped at the pref layer on reload. */
    fun setSectionOrder(order: List<String>) {
        prefs.setCheatSectionOrder(gameKey(), order)
    }

    /** One-shot cheat ids the user has flagged (via long-press) for
     *  auto-fire on reconnect. Lit on the cheats tab the same way
     *  active toggles are, so the user can tell which one-shots are
     *  armed at a glance. Follows the connected game. */
    val autoApplyCheats: StateFlow<Set<Int>> = currentGameKey
        .flatMapLatest { prefs.autoApplyCheats(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Flip the auto-apply state for a one-shot cheat (used by the
     *  long-press gesture on non-toggle buttons). Returns the new
     *  state so the caller can show a toast with the right wording. */
    fun toggleAutoApply(id: Int): Boolean {
        val key = gameKey()
        val next = !prefs.autoApplyCheats(key).value.contains(id)
        prefs.setCheatAutoApply(key, id, next)
        return next
    }

    /** Onboarding sheet dismissal flag. One shot per installation,
     *  shared across games. */
    fun hasSeenOnboarding(): Boolean = prefs.hasSeenCheatsOnboarding
    fun markOnboardingSeen() { prefs.hasSeenCheatsOnboarding = true }

    /** Wipe the two persisted sets (toggle intent + armed one-shots)
     *  for one game. Fires a one-shot event on [clearedEvents] so
     *  the settings sheet can show a confirmation snackbar. The
     *  other game's bag is untouched. */
    private val _clearedEvents = Channel<Unit>(Channel.BUFFERED)
    val clearedEvents: Flow<Unit> = _clearedEvents.receiveAsFlow()
    fun clearSavedCheats(gameKey: String) {
        prefs.clearAutoCheats(gameKey)
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
        //
        // The persisted sets and the master switch are per game — we
        // flatMapLatest on the current game key so switching from IV
        // to V (or back) picks up that game's bag immediately.
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
            val togglesFlow = currentGameKey.flatMapLatest {
                prefs.enabledCheatToggles(it)
            }
            val oneShotsFlow = currentGameKey.flatMapLatest {
                prefs.autoApplyCheats(it)
            }
            val autoEnabledFlow = currentGameKey.flatMapLatest {
                prefs.autoReapplyCheats(it)
            }
            combine(
                repository.state,
                togglesFlow,
                oneShotsFlow,
                autoEnabledFlow,
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
                // restores it. Stored under the game that is
                // currently connected — IV ids and V ids live in
                // separate bags.
                val willBeOn = !isOn(maskAtTap, cheatId)
                prefs.setCheatToggleEnabled(gameKey(), cheatId, enabled = willBeOn)
            }
            // Clear the pending marker when the ack arrives clean;
            // keep the error visible until the next tap replaces it.
            _result.value = if (error == null) null else CheatResult(cheatId, error)
        }
    }

    /**
     * GTA V only. [packed] is a 0x00RRGGBB value from [PAINT_SWATCHES].
     * Rides the same pending/ack path as [runCheat] so a second tap
     * while the first ack is in flight does nothing (double-send
     * guard), and so the swatch row can grey out from `lastResult`
     * the same way the three paint buttons do. The pending-id slot
     * uses [PAINT_RGB_PENDING_ID] — a value outside the catalog id
     * space so no catalog button ever greys from an in-flight swatch.
     */
    fun paintRgb(packed: Long) {
        pending?.cancel()
        _result.value = CheatResult(PAINT_RGB_PENDING_ID)
        pending = viewModelScope.launch {
            val id = repository.paintRgb(packed)
            val ack = if (id == null) null else withTimeoutOrNull(ACK_TIMEOUT_MS.milliseconds) {
                repository.ack.first { it?.ackId == id }
            }
            val error = when {
                id == null     -> "not connected"
                ack == null    -> "no reply from the game"
                ack.ok != true -> ack.err?.takeIf { it.isNotBlank() } ?: "refused"
                else           -> null
            }
            _result.value = if (error == null) null
                            else CheatResult(PAINT_RGB_PENDING_ID, error)
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
        // Fake pending id for paint_rgb — outside the catalog id space
        // (max catalog id today is 62) so a swatch in flight never
        // greys out a catalog button.
        const val PAINT_RGB_PENDING_ID = -1

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
