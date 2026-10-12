package jr.brian.thorhud.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jr.brian.thorhud.R

@Composable
internal fun StatusBar(
    isDisconnected: Boolean,
    isStale: Boolean,
    inGame: Boolean,
    wantedLevel: Int,
    palette: HudPalette,
    // GTA IV has six wanted stars; GTA V passes 5.
    maxStars: Int = 6,
    // Routed screens pass brand_iv / brand_v so the title names the
    // game. Default keeps backward compat if a new caller forgets.
    title: String = stringResource(R.string.brand),
    // Thumbstick-lights switch at the far right; hidden when
    // [onToggleLights] is null. [lightsUnavailable] = switched on, but
    // Bifrost is missing or not letting us in.
    lightsOn: Boolean = false,
    lightsUnavailable: Boolean = false,
    onToggleLights: (() -> Unit)? = null,
) {
    val mono = FontFamily.Monospace
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title,
             style = MaterialTheme.typography.titleMedium,
             color = palette.accent)
        val badge: Pair<Int, Color>? = when {
            isDisconnected -> R.string.status_disconnected to Color(0xFFB04040)
            isStale        -> R.string.status_stale        to Color(0xFFC08A3C)
            !inGame        -> R.string.status_waiting      to Color(0xFF8EA096)
            else           -> null
        }
        badge?.let { (labelRes, color) ->
            Text(stringResource(labelRes), color = color,
                 fontFamily = mono, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        // Stars only make sense once the player has heat; hide the whole
        // widget below wanted=1 and slide it in from the right edge
        // when the first star lights up.
        AnimatedVisibility(
            visible = wantedLevel >= 1,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit  = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            WantedStars(level = wantedLevel, palette = palette, maxStars = maxStars)
        }
        // Last in the row so it stays put while the stars slide in and
        // out beside it.
        onToggleLights?.let { onToggle ->
            LightsToggle(
                on = lightsOn,
                unavailable = lightsUnavailable,
                palette = palette,
                onToggle = onToggle,
            )
        }
    }
}

/**
 * Tap to switch the red/blue thumbstick lights on or off. Accent when
 * on, grey when off, amber when on but Bifrost is not taking commands.
 */
@Composable
private fun LightsToggle(
    on: Boolean,
    unavailable: Boolean,
    palette: HudPalette,
    onToggle: () -> Unit,
) {
    val tint = when {
        !on         -> Color(0xFF4A574F)
        unavailable -> Color(0xFFC08A3C)
        else        -> palette.accent
    }
    val description = stringResource(
        when {
            !on         -> R.string.lights_off
            unavailable -> R.string.lights_unavailable
            else        -> R.string.lights_on
        },
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lightbulb,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun WantedStars(level: Int, palette: HudPalette, maxStars: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(maxStars) { i ->
            val lit = i < level
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(
                    if (lit) R.string.wanted_star_lit else R.string.wanted_star_dim, i,
                ),
                tint = if (lit) palette.stars else Color(0xFF2A332D),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
