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
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Place
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
    // GTA V renders stars right-to-left in the game HUD; mirror that
    // here so the companion matches the player's expectations.
    starsRightToLeft: Boolean = false,
    // Routed screens pass brand_iv / brand_v so the title names the
    // game. Default keeps backward compat if a new caller forgets.
    title: String = stringResource(R.string.brand),
    // Thumbstick-lights switch at the far right; hidden when
    // [onToggleLights] is null. [lightsUnavailable] = switched on, but
    // Bifrost is missing or not letting us in.
    lightsOn: Boolean = false,
    lightsUnavailable: Boolean = false,
    onToggleLights: (() -> Unit)? = null,
    // In-app update chip, shown between the stars and the lights
    // toggle when the GitHub Releases API reports a newer version.
    updateAvailable: Boolean = false,
    onOpenUpdate: (() -> Unit)? = null,
    // Blip-list chip. Opens the map blips panel from any screen that
    // shows the status bar. [blipsOpen] colours the icon with the
    // accent while the panel is up.
    blipsOpen: Boolean = false,
    onOpenBlips: (() -> Unit)? = null,
    // Jump-to-map chip, between the pin and the stars. Only shows
    // when the player is in a vehicle AND the caller supplies
    // [onScrollToMap]; notice-state callers (where no pager is
    // visible) leave the handler null so the chip stays hidden.
    inVehicle: Boolean = false,
    onScrollToMap: (() -> Unit)? = null,
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
        // Chip cluster sits between two weighted spacers. The trailing
        // spacer only appears while the wanted stars are visible — that
        // way chips centre themselves between the title and the stars,
        // but drift to the right edge when the stars fold away so the
        // bar never looks half-empty.
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Blips chip. Dimmed and non-interactive while the connection
            // is dead, stale, or the game is not loaded in — nothing to
            // route to anyway.
            onOpenBlips?.let { onOpen ->
                BlipsChip(
                    palette = palette,
                    open = blipsOpen,
                    enabled = !isDisconnected && !isStale && inGame,
                    onTap = onOpen,
                )
            }
            // Update chip is only there when a newer release is out.
            if (updateAvailable && onOpenUpdate != null) {
                UpdateChip(palette = palette, onTap = onOpenUpdate)
            }
            // Lights toggle rides with the wanted stars: wanted-level 0
            // is "nothing is happening", so the whole flash feature
            // folds away. The service's enabled/disabled preference
            // lives in settings, not here — turning off because the
            // stars dropped never flips that bit. When the next star
            // lights up the icon slides back in with whatever colour
            // the preference says.
            onToggleLights?.let { onToggle ->
                AnimatedVisibility(
                    visible = wantedLevel >= 1,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                ) {
                    LightsToggle(
                        on = lightsOn,
                        unavailable = lightsUnavailable,
                        palette = palette,
                        onToggle = onToggle,
                    )
                }
            }
            // Car chip: appears only while the player is in a vehicle
            // on the HUD tab. Tap scrolls the pager to the mirror. The
            // slide/fade keeps it from popping in the moment a car is
            // entered.
            onScrollToMap?.let { onScroll ->
                AnimatedVisibility(
                    visible = inVehicle,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut(),
                ) {
                    CarChip(palette = palette, onTap = onScroll)
                }
            }
        }
        if (wantedLevel >= 1) {
            Spacer(Modifier.weight(1f))
        }
        // Wanted stars always sit at the far right of the status bar so
        // the player's eye always lands on them in the same spot. Below
        // wanted=1 the widget folds away; above it, slides in from the
        // right edge when the first star lights up.
        AnimatedVisibility(
            visible = wantedLevel >= 1,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit  = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            WantedStars(level = wantedLevel, palette = palette, maxStars = maxStars, rightToLeft = starsRightToLeft)
        }
    }
}

/**
 * Map-blips chip. Accent while the panel is open, dim grey otherwise.
 * When [enabled] is false the icon fades further and the tap is a
 * no-op — nothing to list / route to without a live connection.
 * Matches [UpdateChip] / [LightsToggle] size so the three sit evenly.
 */
@Composable
private fun BlipsChip(
    palette: HudPalette,
    open: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    // Live connection: accent, same as the title. Dead/stale/pre-game:
    // the dim grey the unlit wanted stars use. ([open] is kept in the
    // signature in case a future variant wants to tint-differentiate.)
    val tint = if (enabled) palette.accent else Color(0xFF2A332D)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = stringResource(R.string.map_blips_cd),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Jump-to-map chip. Only drawn (via AnimatedVisibility above) while
 * the player is in a vehicle, so tapping does the natural thing of
 * taking the player to the mirror. Accent tint, same size as the
 * other chips.
 */
@Composable
private fun CarChip(palette: HudPalette, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.DirectionsCar,
            contentDescription = stringResource(R.string.car_chip_cd),
            tint = palette.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The "download arrow" that lights up in the status bar when
 * UpdateChecker finds a newer release. Tap opens the update dialog.
 */
@Composable
private fun UpdateChip(palette: HudPalette, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowCircleDown,
            contentDescription = stringResource(R.string.update_chip_cd),
            tint = palette.accent,
            modifier = Modifier.size(20.dp),
        )
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
private fun WantedStars(level: Int, palette: HudPalette, maxStars: Int, rightToLeft: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(maxStars) { i ->
            val lit = if (rightToLeft) i >= (maxStars - level) else i < level
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
