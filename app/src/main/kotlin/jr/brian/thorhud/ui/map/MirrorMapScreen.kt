package jr.brian.thorhud.ui.map

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
    viewModel: MapViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val captureSize by viewModel.captureSize.collectAsStateWithLifecycle()
    val transforms by viewModel.transforms.collectAsStateWithLifecycle()
    val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.start(result.resultCode, data)
        }
    }

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
        when {
            status == ScreenMirror.Status.Active && size != null -> {
                val baseCrop = minimapCropFor(game)
                val userT = transforms[game] ?: MirrorTransform()
                val crop = baseCrop.applyTransform(userT)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
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
                                    // Convert pan from view px to source frac.
                                    // Drag RIGHT (positive pan.x) should move the
                                    // mirrored content RIGHT — i.e. shift the crop
                                    // window LEFT.
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
                        // GTA V's rectangular mirror sits in a wide 16:9-ish
                        // box; cap it smaller than the available space.
                        // IV's circular view uses the full box.
                        val sizeModifier = if (game == MirrorGame.IV) {
                            Modifier
                        } else {
                            Modifier.fillMaxSize(0.70f)
                        }
                        AndroidView(
                            modifier = sizeModifier
                                .aspectRatio(crop.aspectRatio(size.width, size.height))
                                .clip(shape)
                                .then(gestureModifier)
                                .semantics { contentDescription = description },
                            factory = { context ->
                                MirrorTextureView(context).apply {
                                    this.captureSize = size
                                    onSurfaceReady = viewModel::attach
                                    onSurfaceGone = viewModel::detach
                                }
                            },
                            update = { view ->
                                view.setCrop(crop.left, crop.top, crop.right, crop.bottom)
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MapButton(
                            label = stringResource(
                                if (isLocked) R.string.map_unlock else R.string.map_lock,
                            ),
                            highlighted = !isLocked,
                            accent = accent,
                            onClick = { viewModel.toggleLock() },
                        )
                        MapButton(
                            label = stringResource(R.string.map_reset),
                            highlighted = false,
                            accent = accent,
                            onClick = { viewModel.resetTransform(game) },
                        )
                        Spacer(Modifier.weight(1f))
                        MapButton(
                            label = stringResource(R.string.map_stop),
                            highlighted = false,
                            accent = accent,
                            onClick = { viewModel.stop() },
                        )
                    }
                }
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
                        onClick = { consentLauncher.launch(viewModel.consentIntent()) },
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
