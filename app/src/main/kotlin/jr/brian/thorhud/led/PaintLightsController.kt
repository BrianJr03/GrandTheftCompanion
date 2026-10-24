package jr.brian.thorhud.led

import jr.brian.thorhud.GtcSettings
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.net.GameStateRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the car's last custom paint to the thumbstick lights.
 *
 * [run] watches four signals: the last color the user picked (via the
 * color wheel or a swatch), the current game state, the paint-mirror
 * toggle, and the police-lights toggle. When mirror is on, we're in a
 * vehicle, and the police are NOT actively flashing for a wanted
 * level, both sticks hold the picked color. Any precondition drop
 * releases the sticks back to Bifrost.
 *
 * Priority: PoliceLightsController wins while [PoliceLights.wantedLevel]
 * > 0 and police lights are enabled; paint stays off and resumes once
 * the stars drop. Each controller writes to [StickLights.show] /
 * [StickLights.clear] independently; the collectLatest + release-on-
 * exit discipline keeps them from thrashing each other.
 */
@Singleton
class PaintLightsController internal constructor(
    private val state: StateFlow<GameState?>,
    private val lastPaintedRgb: StateFlow<Long?>,
    private val mirrorEnabled: StateFlow<Boolean>,
    private val policeEnabled: StateFlow<Boolean>,
    private val lights: StickLights,
) {
    @Inject constructor(
        repository: GameStateRepository,
        settings: GtcSettings,
        lights: StickLights,
    ) : this(
        state = repository.state,
        lastPaintedRgb = repository.lastPaintedRgb,
        mirrorEnabled = settings.paintToLightsEnabled,
        policeEnabled = settings.policeLightsEnabled,
        lights = lights,
    )

    suspend fun run() {
        var showing = false

        fun release() {
            if (showing) {
                lights.clear()
                showing = false
            }
        }

        try {
            combine(
                state,
                lastPaintedRgb,
                mirrorEnabled,
                policeEnabled,
            ) { s, rgb, mirror, police ->
                val wanted = PoliceLights.wantedLevel(s)
                val policeHasSticks = police && wanted > 0
                when {
                    !mirror                   -> null
                    policeHasSticks           -> null
                    rgb == null               -> null
                    s == null                 -> null
                    !s.inGame                 -> null
                    s.online || s.switching   -> null
                    else                      -> rgb
                }
            }
                .distinctUntilChanged()
                .collectLatest { rgb ->
                    if (rgb == null) {
                        release()
                    } else {
                        // 0x00RRGGBB → ARGB with full alpha. Both sticks
                        // get the same color — matches the plugin's own
                        // "both custom layers at this RGB" behavior.
                        val argb = (0xFF000000.toInt()) or rgb.toInt()
                        lights.show(argb, argb)
                        showing = true
                    }
                }
        } finally {
            release()
        }
    }
}
