package jr.brian.thorhud.led

import jr.brian.thorhud.GtcSettings
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flashes the thumbsticks red and blue while the player is wanted.
 *
 * [run] does the work for as long as its coroutine lives and hands the
 * sticks back when it is cancelled. It sends one command per beat and
 * nothing in between; with no stars it sends nothing at all.
 */
@Singleton
class PoliceLightsController internal constructor(
    private val state: StateFlow<GameState?>,
    private val lastUpdateMs: StateFlow<Long>,
    private val enabled: StateFlow<Boolean>,
    private val lights: StickLights,
    private val nowMs: () -> Long,
) {
    @Inject constructor(
        repository: GameStateRepository,
        settings: GtcSettings,
        lights: StickLights,
    ) : this(
        state = repository.state,
        lastUpdateMs = repository.lastUpdateMs,
        enabled = settings.policeLightsEnabled,
        lights = lights,
        nowMs = System::currentTimeMillis,
    )

    private enum class Mode { Off, Idle, Flashing }

    suspend fun run() {
        var beat = 0
        var showing = false

        fun release() {
            if (showing) {
                lights.clear()
                showing = false
            }
        }

        try {
            combine(state, enabled) { s, on ->
                when {
                    !on -> Mode.Off
                    PoliceLights.wantedLevel(s) > 0 -> Mode.Flashing
                    else -> Mode.Idle
                }
            }
                // The plugin sends 20 frames a second; only a change of
                // mode restarts anything below.
                .distinctUntilChanged()
                .collectLatest { mode ->
                    when (mode) {
                        Mode.Off -> release()
                        // Switched on with nothing to flash for. One
                        // command either way, and its answer tells the
                        // HUD's light icon whether Bifrost is listening.
                        Mode.Idle -> if (showing) release() else lights.refreshStatus()
                        Mode.Flashing -> {
                            while (true) {
                                // Read the level each beat: a star gained or
                                // lost changes the tempo, not the mode.
                                val current = state.value
                                val level = if (isFresh()) PoliceLights.wantedLevel(current) else 0
                                if (level == 0) {
                                    // Frames stopped (game closed or hung)
                                    // with stars still up. The last state
                                    // sticks, so the mode never changes; let
                                    // go and wait for frames to come back.
                                    release()
                                    delay(STALE_POLL_MS)
                                    continue
                                }
                                val (left, right) = PoliceLights.colorsForBeat(beat++)
                                lights.show(left, right)
                                showing = true
                                delay(PoliceLights.beatIntervalMs(level, PoliceLights.maxStars(current)))
                            }
                        }
                    }
                }
        } finally {
            release()
        }
    }

    private fun isFresh(): Boolean {
        val last = lastUpdateMs.value
        return last > 0L && nowMs() - last <= PoliceLights.STALE_AFTER_MS
    }

    internal companion object {
        const val STALE_POLL_MS = 250L
    }
}
