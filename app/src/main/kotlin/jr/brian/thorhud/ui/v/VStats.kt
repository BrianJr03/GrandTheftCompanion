package jr.brian.thorhud.ui.v

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.Bar
import jr.brian.thorhud.ui.components.HudPalette

/**
 * Bottom stat bars — GTA V counterpart of components/FullWidthStats.kt.
 *
 * Health sits full-width on top exactly as in GTA IV. The second row
 * holds armor plus whichever of these apply:
 *   - special: GTA V only; shown for Michael / Franklin / Trevor once
 *     the ability is unlocked.
 *   - engine: in a vehicle that has one (not on a bicycle).
 */
@Composable
internal fun VStatsBars(
    s: GameState?,
    character: VCharacter,
    palette: HudPalette,
) {
    val v = s?.vehicle?.takeIf { s.inVehicle }
    val special = s?.special?.takeIf {
        character.isProtagonist && it.unlocked
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
    ) {
        // GTA V peds are alive from 100 up to their max (200 for the
        // protagonists) and dead below 100, so the bar spans 100..max
        // and reads 0 at the point the game's own bar is empty.
        val maxHealth = ((s?.maxHealth ?: 200) - 100).coerceAtLeast(1)
        val health = ((s?.health?.toInt() ?: 100) - 100).coerceIn(0, maxHealth)
        Bar(label = stringResource(R.string.label_health),
            current = health.toFloat(), max = maxHealth.toFloat(),
            fill = palette.health)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Bar(label = stringResource(R.string.label_armor),
                current = s?.armor?.toFloat() ?: 0f,
                max = (s?.maxArmor ?: 100).coerceAtLeast(100).toFloat(),
                fill = palette.armor,
                modifier = Modifier.weight(1f))

            if (special != null) {
                // The game gives scripts no meter level, only states,
                // so this bar is deliberately two-position: full when
                // the ability is ready or running, empty while it
                // recharges. The text says which.
                val ready = special.active || special.full
                Bar(label = stringResource(R.string.v_label_special),
                    current = if (ready) 1f else 0f, max = 1f,
                    fill = VSpecialColor,
                    modifier = Modifier.weight(1f),
                    valueText = stringResource(when {
                        special.active -> R.string.v_special_active
                        special.full   -> R.string.v_special_ready
                        else           -> R.string.v_special_charging
                    }))
            }

            if (v != null && vHasEngine(v)) {
                // GTA V engine health runs -4000..1000; below 0 the
                // engine is on fire. Clamp like GTA IV does.
                Bar(label = stringResource(R.string.label_engine),
                    current = v.engineHp.coerceAtLeast(0f),
                    max = 1000f,
                    fill = palette.engine,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}
