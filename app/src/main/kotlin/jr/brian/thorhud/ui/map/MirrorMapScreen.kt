package jr.brian.thorhud.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.R
import jr.brian.thorhud.mirror.ScreenMirror

enum class MirrorGame { IV, V }

internal data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun aspectRatio(screenWidth: Int, screenHeight: Int): Float =
        ((right - left) * screenWidth) / ((bottom - top) * screenHeight)

    /** Apply a user zoom (shrinks around current center) + pan in frac. */
    fun applyTransform(t: MirrorTransform): CropRect {
        val cx = (left + right) / 2f + t.offsetX
        val cy = (top + bottom) / 2f + t.offsetY
        val hx = (right - left) / 2f / t.scale.coerceAtLeast(0.01f)
        val hy = (bottom - top) / 2f / t.scale.coerceAtLeast(0.01f)
        return CropRect(cx - hx, cy - hy, cx + hx, cy + hy)
    }
}

// IV minimap is a circle — the preset is pixel-square so the CircleShape
// clip is a true circle. V minimap is a rectangle, so its preset keeps
// the game's native aspect. User can pinch/drag to tune either.
private val MinimapCropIV = CropRect(0.0540f, 0.7545f, 0.1613f, 0.9452f)
private val MinimapCropV  = CropRect(-0.0013f, 0.7858f, 0.1691f, 0.9810f)

internal fun minimapCropFor(game: MirrorGame): CropRect = when (game) {
    MirrorGame.IV -> MinimapCropIV
    MirrorGame.V  -> MinimapCropV
}

@Composable
internal fun MirrorMapScreen(
    game: MirrorGame,
    accent: Color,
    onRequestConsent: () -> Unit,
    onOpenBlips: (() -> Unit)? = null,
    controlsRight: Boolean = false,
    // In-vehicle gates the speedometer (bottom-right). Routed label
    // paired with inVehicle gates the "driving to <name>" overlay
    // (bottom-left). routeName is null when nothing is routed.
    inVehicle: Boolean = false,
    routeName: String? = null,
    speedMps: Float = 0f,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val captureSize by viewModel.captureSize.collectAsStateWithLifecycle()
    val transforms by viewModel.transforms.collectAsStateWithLifecycle()
    val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val size = captureSize
        // Hint lives at the top-right of the whole map tab, visible
        // only while the user is actively adjusting the mirror.
        if (status == ScreenMirror.Status.Active && size != null && !isLocked) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = stringResource(R.string.map_gesture_hint_zoom),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
                Text(
                    text = stringResource(R.string.map_gesture_hint_pan),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
        // "driving to <name>" rides the bottom-left corner, only while
        // the player is in a vehicle AND the companion knows about a
        // routed destination. Dim label above the accent-tinted name.
        if (status == ScreenMirror.Status.Active && size != null &&
            inVehicle && routeName != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 2.dp, start = 2.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.map_driving_to),
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
                Text(
                    text = routeName,
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
        // Speedometer rides the bottom-right, visible whenever the
        // player is in a vehicle. Converts m/s → integer mph to match
        // the HUD's vehicle panel.
        if (status == ScreenMirror.Status.Active && size != null && inVehicle) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 2.dp, end = 2.dp),
                horizontalAlignment = Alignment.End,
            ) {
                val mph = (speedMps * 2.237f).toInt()
                Text(
                    text = stringResource(R.string.mph_value, mph),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }
        when {
            status == ScreenMirror.Status.Active && size != null -> {
                MirrorMapLayout(
                    game = game,
                    accent = accent,
                    isLocked = isLocked,
                    userTransform = transforms[game] ?: MirrorTransform(),
                    captureSize = size,
                    onOpenBlips = onOpenBlips,
                    controlsRight = controlsRight,
                    viewModel = viewModel,
                )
            }

            status == ScreenMirror.Status.Idle -> {
                Column(
                    modifier = Modifier.widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.map_title),
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.map_explain),
                        color = Color(0xFF6A7A6E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    MapButton(
                        label = stringResource(R.string.map_start),
                        highlighted = true,
                        accent = accent,
                        onClick = onRequestConsent,
                    )
                }
            }

            else -> {
                Text(
                    stringResource(R.string.map_starting),
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun MapButton(
    label: String,
    highlighted: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) accent else Color(0xFF1F2A24))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (highlighted) Color(0xFF000000) else Color(0xFFB6CCBC),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/**
 * Active-mirror layout for both games: an icon column (lock, reset,
 * blips, stop) on one side and the mirror centred horizontally and
 * vertically in the remaining space, with 16dp breathing room on the
 * far side so the map doesn't sit flush against the panel edge.
 * [controlsRight] flips the column from the left to the right side;
 * the mirror padding mirrors with it so the gap stays consistent.
 */
@Composable
private fun MirrorMapLayout(
    game: MirrorGame,
    accent: Color,
    isLocked: Boolean,
    userTransform: MirrorTransform,
    captureSize: android.util.Size,
    onOpenBlips: (() -> Unit)?,
    controlsRight: Boolean,
    viewModel: MapViewModel,
) {
    val baseCrop = minimapCropFor(game)
    val crop = baseCrop.applyTransform(userTransform)
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!controlsRight) {
            MapControlsColumn(
                game = game,
                accent = accent,
                isLocked = isLocked,
                onOpenBlips = onOpenBlips,
                viewModel = viewModel,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(
                    start = if (controlsRight) 16.dp else 0.dp,
                    end = if (controlsRight) 0.dp else 16.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val description = stringResource(R.string.map_view_cd)
            val shape = if (game == MirrorGame.IV) CircleShape
                        else RoundedCornerShape(10.dp)
            val gestureModifier = if (isLocked) {
                Modifier
            } else {
                Modifier.pointerInput(game) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val current = viewModel.transformFor(game)
                        val newScale = (current.scale * zoom).coerceIn(0.3f, 5f)
                        // Convert pan from view px to source frac. Drag
                        // RIGHT (positive pan.x) should move the mirrored
                        // content RIGHT, i.e. shift the crop window LEFT.
                        val viewW = this.size.width.toFloat()
                        val viewH = this.size.height.toFloat()
                        val cropW = (baseCrop.right - baseCrop.left) / newScale
                        val cropH = (baseCrop.bottom - baseCrop.top) / newScale
                        val dOffX = if (viewW > 0f) -pan.x / viewW * cropW else 0f
                        val dOffY = if (viewH > 0f) -pan.y / viewH * cropH else 0f
                        viewModel.updateTransform(
                            game,
                            MirrorTransform(
                                scale = newScale,
                                offsetX = current.offsetX + dOffX,
                                offsetY = current.offsetY + dOffY,
                            ),
                        )
                    }
                }
            }
            AndroidView(
                modifier = Modifier
                    .aspectRatio(crop.aspectRatio(captureSize.width, captureSize.height))
                    .clip(shape)
                    .then(gestureModifier)
                    .semantics { contentDescription = description },
                factory = { context ->
                    MirrorTextureView(context).apply {
                        this.captureSize = captureSize
                        onSurfaceReady = viewModel::attach
                        onSurfaceGone = viewModel::detach
                    }
                },
                update = { view ->
                    view.setCrop(crop.left, crop.top, crop.right, crop.bottom)
                },
            )
        }
        if (controlsRight) {
            MapControlsColumn(
                game = game,
                accent = accent,
                isLocked = isLocked,
                onOpenBlips = onOpenBlips,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun MapControlsColumn(
    game: MirrorGame,
    accent: Color,
    isLocked: Boolean,
    onOpenBlips: (() -> Unit)?,
    viewModel: MapViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        MapIconButton(
            icon = if (isLocked) Icons.Filled.Lock
                   else Icons.Filled.LockOpen,
            contentDescription = stringResource(
                if (isLocked) R.string.map_unlock else R.string.map_lock,
            ),
            accent = accent,
            onClick = { viewModel.toggleLock() },
        )
        MapIconButton(
            icon = Icons.Filled.Refresh,
            contentDescription = stringResource(R.string.map_reset),
            accent = accent,
            onClick = { viewModel.resetTransform(game) },
        )
        onOpenBlips?.let { open ->
            MapIconButton(
                icon = Icons.Filled.Place,
                contentDescription = stringResource(R.string.map_blips_cd),
                accent = accent,
                onClick = open,
            )
        }
        MapIconButton(
            icon = Icons.Filled.Stop,
            contentDescription = stringResource(R.string.map_stop),
            accent = accent,
            onClick = { viewModel.stop() },
        )
    }
}

@Composable
private fun MapIconButton(
    icon: ImageVector,
    contentDescription: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier.size(24.dp),
        )
    }
}
