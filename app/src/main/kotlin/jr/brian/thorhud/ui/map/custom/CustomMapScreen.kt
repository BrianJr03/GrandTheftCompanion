package jr.brian.thorhud.ui.map.custom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.HeaderIconButton
import kotlin.math.hypot

/**
 * The custom-map ("atlas") page.
 *
 * When an image is on disk, draws it with pan/zoom and overlays blips
 * + the player triangle + the active waypoint on top. The user can
 * long-press to drop a waypoint (GTA V only). When no image exists
 * for the connected game the page shows a "drop a map image at …"
 * hint; the Settings panel's atlas-path row is where the user reads
 * the exact path.
 */
@Composable
internal fun CustomMapScreen(
    game: CustomMapStore.Game,
    accent: Color,
    blips: List<GameState.Blip>,
    pos: GameState.Pos?,
    waypoint: GameState.Waypoint?,
    // Compose-side triangle rotation offset. Default 0 so a world
    // heading of 0 (facing north) draws the triangle pointing up on
    // screen; matches the gold "Player" arrow the game draws on its
    // own pause-map. Overridable per game if V ever needs a different
    // convention.
    headingOffsetDeg: Float = 0f,
    // Episode id from the IV state frame; 0 = GTA IV (Niko),
    // 1 = The Lost and Damned (Johnny), 2 = TBoGT (Luis). Used by the
    // re-anchor hint so the copy says "where Niko / Johnny / Luis is".
    // Null = unknown yet; falls back to "the player".
    episode: Int? = null,
    isGtaV: Boolean,
    atlasPath: String,
    onOpenHud: (() -> Unit)? = null,
    onOpenCheats: (() -> Unit)? = null,
    onOpenBlips: (() -> Unit)? = null,
    onOpenRadio: (() -> Unit)? = null,
    viewModel: AtlasViewModel = hiltViewModel(),
) {
    val data by viewModel.dataFor(game).collectAsStateWithLifecycle()
    val flow by viewModel.calibrationFlow.collectAsStateWithLifecycle()
    val follow by viewModel.followMode.collectAsStateWithLifecycle()
    val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()
    val anchorMode by viewModel.anchorMode.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val bitmap = data.overview
        if (bitmap == null) {
            val progress by viewModel.atlasProgress.collectAsStateWithLifecycle()
            NoImageState(
                path = atlasPath,
                accent = accent,
                progress = progress,
                onBuild = viewModel::requestAtlasExtract,
                onCancel = viewModel::requestAtlasCancel,
                onReload = { viewModel.reload(game) },
            )
            return@Box
        }
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        var viewport by remember(bitmap) {
            val saved = viewModel.savedViewport(game)
            val fit = ViewportModel.fit(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                screenWidth = 1,
                screenHeight = 1,
            )
            mutableStateOf(
                if (saved != null) {
                    fit.copy(
                        scale = saved.scale,
                        offsetX = saved.offsetX,
                        offsetY = saved.offsetY,
                    )
                } else fit
            )
        }
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }

        // Follow: when on, player's image-space position recentres the
        // viewport. The user panning turns it off.
        val calibration = data.calibration
        val playerImagePx = pos?.let { p ->
            calibration?.worldToPixel(p.x, p.y)
        }
        if (follow && playerImagePx != null && canvasSize != IntSize.Zero) {
            viewport = viewport
                .copy(screenWidth = canvasSize.width, screenHeight = canvasSize.height)
                .centerOnImage(playerImagePx.px, playerImagePx.py)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Transform gestures. detectTransformGestures consumes
                // the event, so the pager (which looks for unconsumed
                // single-finger drags) does not swipe tabs during a
                // two-finger pinch or a one-finger pan here. A plain
                // horizontal drag that isn't a transform still reaches
                // the pager.
                //
                // Lock gates ONLY the pan/zoom gesture; tap+long-press
                // below stay live so calibration taps and GTA V waypoint
                // drops still work with the atlas locked.
                .then(
                    if (isLocked) Modifier
                    else Modifier.pointerInput(bitmap, calibration, flow.active) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (pan != Offset.Zero) viewModel.setFollowMode(false)
                            viewport = viewport
                                .copy(
                                    screenWidth = canvasSize.width,
                                    screenHeight = canvasSize.height,
                                )
                                .transform(
                                    deltaOffsetX = pan.x,
                                    deltaOffsetY = pan.y,
                                    zoom = zoom,
                                )
                            viewModel.saveViewport(
                                game, viewport.scale,
                                viewport.offsetX, viewport.offsetY,
                            )
                        }
                    }
                )
                .pointerInput(bitmap, calibration, flow.active, anchorMode) {
                    detectTapGestures(
                        onTap = { offset ->
                            val vp = viewport.copy(
                                screenWidth = canvasSize.width,
                                screenHeight = canvasSize.height,
                            )
                            val img = vp.screenToImage(offset.x, offset.y)
                            if (anchorMode) {
                                // One-tap re-anchor: pair tapped pixel with
                                // Niko's live world pos; nudges translation
                                // only (keeps scale).
                                val p = pos ?: return@detectTapGestures
                                viewModel.reanchor(
                                    game = game,
                                    world = WorldPoint(p.x, p.y),
                                    pixel = PixelPoint(img[0], img[1]),
                                )
                                return@detectTapGestures
                            }
                            if (flow.active) {
                                val world = flow.pendingWorld
                                    ?: nearestBlipWorld(blips, img[0], img[1], calibration)
                                    ?: return@detectTapGestures
                                viewModel.addCalibrationPair(
                                    world = world,
                                    pixel = PixelPoint(img[0], img[1]),
                                )
                            }
                        },
                        onLongPress = { offset ->
                            if (!isGtaV || calibration == null) return@detectTapGestures
                            val vp = viewport.copy(
                                screenWidth = canvasSize.width,
                                screenHeight = canvasSize.height,
                            )
                            val world = vp.screenToWorld(calibration, offset.x, offset.y)
                            // Snap to nearest blip within ~20 m; falls
                            // through to a bare xy waypoint otherwise.
                            val near = blips.minByOrNull { b ->
                                hypot(b.x - world.x, b.y - world.y)
                            }
                            if (near != null && hypot(near.x - world.x, near.y - world.y) < 20f) {
                                viewModel.requestRouteBlip(near.id)
                            } else {
                                viewModel.requestWaypointXy(world.x, world.y)
                            }
                        },
                    )
                }
        ) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            val vp = viewport.copy(
                screenWidth = canvasSize.width,
                screenHeight = canvasSize.height,
            )
            drawMap(imageBitmap, vp)
            drawOverlays(
                viewport = vp,
                calibration = calibration,
                blips = blips,
                playerPos = pos,
                waypoint = waypoint,
                accent = accent,
                // Match the gold "Player" arrow the game draws on its
                // own pause-map tab (sampled on-device, RGB 228/163/57).
                // V keeps the per-episode accent until Brian asks.
                triangleColor = if (game == CustomMapStore.Game.IV)
                    Color(0xFFE4A339) else accent,
                headingOffsetDeg = headingOffsetDeg,
            )
            // Calibration taps drawn as yellow circles so the user can
            // see each pair they committed.
            flow.pixels.forEach { p ->
                val s = vp.imageToScreen(p.px, p.py)
                drawCircle(color = Color(0xFFEDE75F),
                           radius = 10f, center = Offset(s[0], s[1]),
                           style = Stroke(width = 3f))
            }
        }

        // Header: title + nav icons + lock + anchor (IV only).
        AtlasHeader(
            game = game,
            accent = accent,
            isLocked = isLocked,
            anchorMode = anchorMode,
            onToggleLock = { viewModel.toggleLock() },
            onToggleAnchor = { viewModel.toggleAnchorMode() },
            onOpenHud = onOpenHud,
            onOpenCheats = onOpenCheats,
            onOpenBlips = onOpenBlips,
            onOpenRadio = onOpenRadio,
        )

        if (anchorMode && !flow.active) {
            // Dim hint banner. Same bg + font as the three-tap flow bar
            // so the two modes read as a family. Takes priority if the
            // user flips anchor on while the 3-tap flow is also active;
            // tap handler already returns early in that case.
            val name = ivProtagonistFirstName(episode)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp)
                    .background(Color(0xDD000000))
                    .padding(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.atlas_reanchor_hint, name),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    text = if (pos != null)
                        stringResource(
                            R.string.atlas_reanchor_at,
                            "%.0f".format(pos.x),
                            "%.0f".format(pos.y),
                        )
                    else
                        stringResource(R.string.atlas_reanchor_waiting),
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }

        if (flow.active) {
            CalibrationBar(
                flow = flow,
                accent = accent,
                blips = blips,
                playerPos = pos,
                onUseMyPosition = {
                    val p = pos ?: return@CalibrationBar
                    viewModel.armUseMyPosition(WorldPoint(p.x, p.y))
                },
                onAccept = { viewModel.acceptCalibration(game) },
                onRedo   = { viewModel.redoCalibration() },
                onCancel = { viewModel.cancelCalibration() },
            )
        }
    }
}

// Protagonist first name for the IV episode currently reporting.
// Game-specific proper noun, same spelling in every locale (so no
// translatable=false quirks: the string is composed at call time via
// the %1$s slot in `atlas_reanchor_hint`). Any unknown episode falls
// back to "Niko", which is the base-game protagonist and the right
// default on a fresh install.
private fun ivProtagonistFirstName(episode: Int?): String = when (episode) {
    1    -> "Johnny"
    2    -> "Luis"
    else -> "Niko"
}

private fun nearestBlipWorld(
    blips: List<GameState.Blip>,
    imageX: Float,
    imageY: Float,
    calibration: Calibration?,
): WorldPoint? {
    if (calibration == null || blips.isEmpty()) return null
    val tap = calibration.pixelToWorld(imageX, imageY)
    val near = blips.minByOrNull { b -> hypot(b.x - tap.x, b.y - tap.y) }
    return near?.let { WorldPoint(it.x, it.y) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMap(
    bitmap: ImageBitmap,
    vp: ViewportModel,
) {
    // Image centre at viewport centre, scaled around that.
    val cx = bitmap.width / 2f
    val cy = bitmap.height / 2f
    val topLeftX = (-cx + vp.offsetX) * vp.scale + vp.screenWidth / 2f
    val topLeftY = (-cy + vp.offsetY) * vp.scale + vp.screenHeight / 2f
    withTransform({
        translate(left = topLeftX, top = topLeftY)
        scale(scaleX = vp.scale, scaleY = vp.scale, pivot = Offset.Zero)
    }) {
        drawImage(image = bitmap)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOverlays(
    viewport: ViewportModel,
    calibration: Calibration?,
    blips: List<GameState.Blip>,
    playerPos: GameState.Pos?,
    waypoint: GameState.Waypoint?,
    accent: Color,
    triangleColor: Color,
    headingOffsetDeg: Float,
) {
    if (calibration == null) return
    // Blip dots.
    blips.forEach { b ->
        val s = viewport.worldToScreen(calibration, b.x, b.y)
        drawCircle(
            color = Color(0xFFB6CCBC),
            radius = 5f,
            center = Offset(s[0], s[1]),
        )
    }
    // Player dot. No direction — matches the small gold spot the game
    // draws on its own pause-map once Niko stops. Slightly bigger
    // radius than the regular blip dots (5 f) so the player still
    // reads as the primary marker.
    if (playerPos != null) {
        val s = viewport.worldToScreen(calibration, playerPos.x, playerPos.y)
        drawCircle(
            color = triangleColor,
            radius = 7f,
            center = Offset(s[0], s[1]),
        )
    }
    // Active waypoint (xy, no blip id).
    if (waypoint?.active == true && waypoint.ours && waypoint.blip == 0) {
        // We don't carry xy in Waypoint today; the plugin reports it
        // as a blip at the waypoint sprite. Nothing to draw here
        // beyond what the blip list already covers.
    }
}

@Composable
private fun AtlasHeader(
    game: CustomMapStore.Game,
    accent: Color,
    isLocked: Boolean,
    anchorMode: Boolean,
    onToggleLock: () -> Unit,
    onToggleAnchor: () -> Unit,
    onOpenHud: (() -> Unit)?,
    onOpenCheats: (() -> Unit)?,
    onOpenBlips: (() -> Unit)?,
    onOpenRadio: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "atlas",
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f),
        )
        onOpenHud?.let { open ->
            HeaderIconButton(
                icon = Icons.Filled.Home,
                contentDescription = stringResource(R.string.nav_hud_cd),
                tint = accent, onClick = open,
            )
        }
        HeaderIconButton(
            icon = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = stringResource(
                if (isLocked) R.string.map_unlock else R.string.map_lock,
            ),
            tint = accent,
            onClick = onToggleLock,
        )
        if (game == CustomMapStore.Game.IV) {
            // IV only. V's scale will shift with new map DLC, so the
            // scale-preserving one-tap re-anchor would just hide a real
            // calibration gap. On IV the radar.img extents are stable,
            // so a translation nudge is almost always the fix the user
            // actually wants when the triangle drifts.
            HeaderIconButton(
                icon = Icons.Filled.GpsFixed,
                contentDescription = stringResource(
                    if (anchorMode) R.string.atlas_reanchor_cancel_cd
                    else R.string.atlas_reanchor_cd,
                ),
                tint = if (anchorMode) accent else Color(0xFF6A7A6E),
                onClick = onToggleAnchor,
            )
        }
        onOpenCheats?.let { open ->
            HeaderIconButton(
                icon = Icons.Filled.Casino,
                contentDescription = stringResource(R.string.map_cheats_cd),
                tint = accent, onClick = open,
            )
        }
        onOpenBlips?.let { open ->
            HeaderIconButton(
                icon = Icons.Filled.Place,
                contentDescription = stringResource(R.string.map_blips_cd),
                tint = accent, onClick = open,
            )
        }
        onOpenRadio?.let { open ->
            HeaderIconButton(
                icon = Icons.Filled.Radio,
                contentDescription = stringResource(R.string.label_radio),
                tint = accent, onClick = open,
            )
        }
    }
}

@Composable
private fun CalibrationBar(
    flow: AtlasViewModel.CalibrationFlow,
    accent: Color,
    blips: List<GameState.Blip>,
    playerPos: GameState.Pos?,
    onUseMyPosition: () -> Unit,
    onAccept: () -> Unit,
    onRedo: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 44.dp)
    ) {
        val cur = flow.proposedResult
        if (cur is CalibrationResult.Ok) {
            Column(
                modifier = Modifier
                    .background(Color(0xDD000000))
                    .padding(8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "residual: ${"%.1f".format(cur.calibration.maxResidualPx)} px",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowButton(label = "accept", accent = accent,
                        highlighted = true, onClick = onAccept)
                    FlowButton(label = "redo", accent = accent,
                        onClick = onRedo)
                    FlowButton(label = "cancel", accent = accent,
                        onClick = onCancel)
                }
            }
        } else if (cur is CalibrationResult.Rejected) {
            Column(
                modifier = Modifier
                    .background(Color(0xDD000000))
                    .padding(8.dp),
            ) {
                Text(
                    text = "pick three points that are not in a straight line",
                    color = Color(0xFFF2B36A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                FlowButton(label = "redo", accent = accent, onClick = onRedo)
            }
        } else {
            Column(
                modifier = Modifier
                    .background(Color(0xDD000000))
                    .padding(8.dp),
            ) {
                Text(
                    text = "tap on the map where the nearest known point is (${flow.pixels.size + 1}/3)",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    text = if (flow.pendingWorld != null)
                        "primed from your position (${"%.0f".format(flow.pendingWorld.x)}, ${"%.0f".format(flow.pendingWorld.y)})"
                    else
                        "next tap will pair with the nearest blip",
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowButton(label = "use my position", accent = accent,
                        onClick = onUseMyPosition,
                        enabled = playerPos != null)
                    FlowButton(label = "cancel", accent = accent,
                        onClick = onCancel)
                }
            }
        }
    }
}

@Composable
private fun FlowButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(when {
                !enabled -> Color(0xFF1A1A1A)
                highlighted -> accent
                else -> Color(0xFF1F2A24)
            })
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color(0xFF444444)
                highlighted -> Color.Black
                else -> Color(0xFFB6CCBC)
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun NoImageState(
    path: String,
    accent: Color,
    progress: jr.brian.thorhud.model.AtlasProgress?,
    onBuild: () -> Unit,
    onCancel: () -> Unit,
    onReload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "no atlas image",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Spacer(Modifier.width(12.dp))
            // Refresh icon: re-reads the drop-in folder + decodes the
            // atlas overview on-screen. Needed after the plugin writes
            // iv.png into the picked folder (no app process restart).
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "reload",
                tint = accent,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onReload),
            )
        }
        Spacer(Modifier.height(10.dp))

        // Primary call to action — build from the game's own archives.
        // Replaces the pure "drop a file" hint, which stays underneath
        // as the manual fallback.
        val running = progress?.isRunning == true
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accent)
                .clickable(role = Role.Button) {
                    if (running) onCancel() else onBuild()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = if (running) "cancel" else "build from my game files",
                color = Color.Black,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            val label = when {
                progress.isError -> "error: ${progress.err.ifBlank { "unknown" }}"
                progress.isDone -> "done — reload the atlas tab"
                else -> "${progress.state} · ${progress.pct}%"
            }
            Text(
                text = label,
                color = if (progress.isError) Color(0xFFD88080)
                        else Color(0xFFB6CCBC),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "or drop a map image at:",
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = path,
            color = Color(0xFFB6CCBC),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}
