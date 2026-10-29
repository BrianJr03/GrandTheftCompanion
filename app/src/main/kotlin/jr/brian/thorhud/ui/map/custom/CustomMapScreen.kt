package jr.brian.thorhud.ui.map.custom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Atlas page. See [AtlasCamera] / [PositionTrack] / [ZoomPresets] /
 * [RegionPolicy] for the pure-Kotlin motion math; this file is only
 * the Compose glue: one frame loop writes [viewport], gestures feed
 * the camera, and the Canvas reads whatever was last written. There
 * is exactly one writer of the viewport state.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun CustomMapScreen(
    game: CustomMapStore.Game,
    accent: Color,
    blips: List<GameState.Blip>,
    pos: GameState.Pos?,
    waypoint: GameState.Waypoint?,
    headingOffsetDeg: Float = 0f,
    episode: Int? = null,
    isGtaV: Boolean,
    atlasPath: String,
    onOpenHud: (() -> Unit)? = null,
    onOpenCheats: (() -> Unit)? = null,
    onOpenBlips: (() -> Unit)? = null,
    onOpenRadio: (() -> Unit)? = null,
    // Paused only: a gear sits at the far right of the atlas header so
    // settings is reachable without swiping back to the paused HUD.
    // Null when the game is unpaused.
    onOpenSettings: (() -> Unit)? = null,
    viewModel: AtlasViewModel = hiltViewModel(),
) {
    val data by viewModel.dataFor(game).collectAsStateWithLifecycle()
    val flow by viewModel.calibrationFlow.collectAsStateWithLifecycle()
    val follow by viewModel.followMode.collectAsStateWithLifecycle()
    val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()
    val anchorMode by viewModel.anchorMode.collectAsStateWithLifecycle()
    val smoothing by viewModel.followSmoothing.collectAsStateWithLifecycle()
    val snapZoomToPresets by viewModel.snapZoomToPresets.collectAsStateWithLifecycle()
    val orientation by viewModel.orientationFor(game).collectAsStateWithLifecycle()

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

        // Sample a corner pixel on load so rotation can fill the empty
        // triangles beyond the image bounds with the atlas's own ocean
        // tone instead of flat black. The four corners usually agree;
        // top-left is enough for the shipping atlases.
        val edgeFillColor = remember(bitmap) {
            runCatching { Color(bitmap.getPixel(0, 0)) }.getOrDefault(Color.Black)
        }

        // Player triangle, centred at origin, apex at (0, -10). Built
        // once per bitmap so no path allocation per frame.
        val trianglePath = remember(bitmap) {
            Path().apply {
                moveTo(0f, -10f)
                lineTo(7f, 8f)
                lineTo(0f, 4f)
                lineTo(-7f, 8f)
                close()
            }
        }

        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        val positionTrack = remember(bitmap) { PositionTrack() }
        val camera = remember(bitmap) {
            val saved = viewModel.savedViewport(game)
            val initial = AtlasCamera(
                centerImageX = bitmap.width / 2f,
                centerImageY = bitmap.height / 2f,
                scale = saved?.scale ?: 1f,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                screenWidth = 1, screenHeight = 1,
            )
            // The saved offset is in image-pixel space (center - target),
            // same convention [ViewportModel.centerOnImage] uses. Invert
            // it to recover the camera's center.
            val cx = bitmap.width / 2f - (saved?.offsetX ?: 0f)
            val cy = bitmap.height / 2f - (saved?.offsetY ?: 0f)
            mutableStateOf(initial.copy(centerImageX = cx, centerImageY = cy))
        }
        val viewport = remember(bitmap) { mutableStateOf(camera.value.toViewport()) }
        val lastPlayerImg = remember(bitmap) { mutableStateOf<Pair<Float, Float>?>(null) }

        // On open, restore the saved follow default. If follow was on
        // and we have a known player position, fly to it (so the user
        // sees the arc from their saved view, not a snap).
        LaunchedEffect(bitmap, game) {
            val saved = viewModel.savedViewport(game) ?: return@LaunchedEffect
            if (saved.follow) {
                viewModel.setFollowMode(true)
                viewModel.requestFollowReEngage()
            }
        }

        // 20 Hz position samples -> PositionTrack. We submit on every
        // change of [pos]; the extrapolation inside the frame loop
        // handles the inter-sample interpolation.
        //
        // The first time we see the player while follow is on, kick
        // off a flyTo (handled by the followReEngage listener below)
        // so the atlas zooms to the saved preset over the player
        // instead of smoothing from the saved view at the saved scale.
        val calibration = data.calibration
        val playerFirstSeen = remember(bitmap) { mutableStateOf(false) }
        LaunchedEffect(pos, calibration) {
            val p = pos ?: return@LaunchedEffect
            val cal = calibration ?: return@LaunchedEffect
            val img = cal.worldToPixel(p.x, p.y)
            positionTrack.submit(img.px, img.py, p.h, System.nanoTime())
            lastPlayerImg.value = img.px to img.py
            if (!playerFirstSeen.value) {
                playerFirstSeen.value = true
                if (viewModel.followMode.value) {
                    viewModel.requestFollowReEngage()
                }
            }
        }

        // Fly-to on follow re-engage. The ViewModel exposes a one-shot
        // "re-engage" event the Compose side consumes.
        LaunchedEffect(bitmap, calibration) {
            viewModel.followReEngage.collectLatest {
                val cal = calibration ?: return@collectLatest
                val img = lastPlayerImg.value
                    ?: pos?.let { cal.worldToPixel(it.x, it.y) }?.let { it.px to it.py }
                    ?: return@collectLatest
                val fitScale = ViewportModel.fit(
                    imageWidth = bitmap.width, imageHeight = bitmap.height,
                    screenWidth = canvasSize.width.coerceAtLeast(1),
                    screenHeight = canvasSize.height.coerceAtLeast(1),
                ).scale
                val preset = viewModel.savedPresetIndex(game)
                val scales = ZoomPresets.scalesFor(fitScale, cal)
                val targetScale = scales.getOrElse(preset) { camera.value.scale }
                camera.value = camera.value.startFlyTo(
                    toX = img.first, toY = img.second,
                    toScale = targetScale,
                    nowNanos = System.nanoTime(),
                )
            }
        }

        // Debounced write of the viewport to prefs. snapshotFlow turns
        // the mutableState into a flow we can debounce; one write 500 ms
        // after the last change, plus a flush on disposal.
        LaunchedEffect(bitmap) {
            snapshotFlow { camera.value }
                .debounce(500)
                .collect { c ->
                    val vp = c.toViewport()
                    viewModel.saveViewport(game, vp.scale, vp.offsetX, vp.offsetY)
                }
        }
        DisposableEffect(bitmap) {
            onDispose {
                val vp = camera.value.toViewport()
                viewModel.saveViewport(game, vp.scale, vp.offsetX, vp.offsetY)
            }
        }

        // The one frame loop. Owns the single write to [viewport]; no
        // other code path writes it.
        LaunchedEffect(bitmap) {
            while (true) {
                val nowNanos = withFrameNanos { it }
                val cal2 = calibration
                var c = camera.value
                if (canvasSize.width > 0 && canvasSize.height > 0) {
                    c = c.copy(screenWidth = canvasSize.width,
                               screenHeight = canvasSize.height)
                }
                if (follow && cal2 != null) {
                    val ext = positionTrack.extrapolate(nowNanos)
                    if (ext != null) {
                        c = c.withTarget(AtlasCamera.Target(ext.ix, ext.iy, c.scale))
                        // Guard against a long teleport: if the player
                        // target jumped more than kFlyToJumpFraction of
                        // the viewport, fly instead of smoothing.
                        val dx = (ext.ix - c.centerImageX) * c.scale
                        val dy = (ext.iy - c.centerImageY) * c.scale
                        val diag = hypot(c.screenWidth.toFloat(), c.screenHeight.toFloat())
                        if (hypot(dx, dy) > diag * AtlasCamera.kFlyToJumpFraction &&
                            c.flyTo == null) {
                            c = c.startFlyTo(ext.ix, ext.iy, c.scale, nowNanos)
                        }
                    }
                } else if (c.target != null) {
                    c = c.copy(target = null)
                }

                // Rotation target. Priorities:
                //  * orientation == NorthUp -> target 0 (ease).
                //  * follow off              -> target 0 (ease back out
                //    of heading-up for the duration of the pan).
                //  * low-speed on foot       -> null (freeze, so the
                //    stick-look flicker doesn't make the map twitch).
                //  * else                    -> rotate image to put
                //    heading at the top of the screen.
                // Tau is slower (400 ms) for the "ease to north" paths
                // so pan-off and setting toggles feel like a settle,
                // not a snap.
                val rotTarget: Float?
                val rotTau: Int
                when {
                    orientation == MapOrientation.NorthUp || !follow -> {
                        rotTarget = 0f
                        rotTau = AtlasCamera.kRotationEaseOffMs
                    }
                    cal2 == null -> {
                        rotTarget = 0f
                        rotTau = AtlasCamera.kRotationEaseOffMs
                    }
                    else -> {
                        val speedPxPerSec = positionTrack.speedPxPerSec() ?: 0f
                        val mPerImagePx = metersPerImagePx(cal2)
                        val speedMps = speedPxPerSec * mPerImagePx
                        val headingDeg = positionTrack.extrapolate(nowNanos)?.headingDeg
                        if (speedMps < AtlasCamera.kLowSpeedHoldMps || headingDeg == null) {
                            rotTarget = null
                            rotTau = AtlasCamera.kRotationTimeConstantMs
                        } else {
                            // Through the calibration so a Y-flipped atlas
                            // doesn't make the camera turn the wrong way.
                            val screenHead = screenHeadingDeg(cal2, headingDeg)
                            rotTarget = (360f - screenHead) % 360f
                            rotTau = AtlasCamera.kRotationTimeConstantMs
                        }
                    }
                }
                c = c.withRotationTarget(rotTarget)

                camera.value = c.step(
                    nowNanos,
                    timeConstantMs = smoothing.tauMs,
                    deadZonePx = AtlasCamera.kDeadZonePx,
                    rotationTimeConstantMs = rotTau,
                    rotationDeadZoneDeg = AtlasCamera.kRotationDeadZoneDeg,
                )
                viewport.value = camera.value.toViewport()
            }
        }

        val scope = rememberCoroutineScope()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isLocked) Modifier
                    else Modifier.pointerInput(bitmap, calibration, flow.active) {
                        awaitEachGesture {
                            val vt = VelocityTracker()
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            vt.addPointerInputChange(firstDown)
                            var pinching = false
                            var panned = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val activeCount = event.changes.count { it.pressed }
                                if (activeCount == 0) break
                                if (activeCount >= 2 && !pinching) pinching = true
                                if (pinching) {
                                    val zoom = event.calculateZoom()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (zoom != 1f && centroid != Offset.Unspecified) {
                                        camera.value = camera.value.zoomAbout(
                                            zoom, centroid.x, centroid.y,
                                        )
                                        viewport.value = camera.value.toViewport()
                                        event.changes.forEach { it.consume() }
                                    }
                                } else {
                                    val pan = event.calculatePan()
                                    if (pan != Offset.Zero) {
                                        panned = true
                                        camera.value = camera.value.panBy(pan.x, pan.y)
                                        viewport.value = camera.value.toViewport()
                                        viewModel.setFollowMode(false)
                                        event.changes.forEach {
                                            vt.addPointerInputChange(it)
                                            it.consume()
                                        }
                                    } else {
                                        event.changes.forEach {
                                            vt.addPointerInputChange(it)
                                        }
                                    }
                                }
                            }
                            if (!pinching && panned) {
                                val v = vt.calculateVelocity()
                                camera.value = camera.value.startFling(v.x, v.y)
                            } else if (pinching && snapZoomToPresets) {
                                val cal3 = calibration
                                if (cal3 != null) {
                                    val fitScale = ViewportModel.fit(
                                        imageWidth = bitmap.width, imageHeight = bitmap.height,
                                        screenWidth = canvasSize.width.coerceAtLeast(1),
                                        screenHeight = canvasSize.height.coerceAtLeast(1),
                                    ).scale
                                    val idx = ZoomPresets.nearestIndex(
                                        camera.value.scale, fitScale, cal3,
                                    )
                                    val target = ZoomPresets.scalesFor(fitScale, cal3)[idx]
                                    viewModel.setPresetIndex(game, idx)
                                    val cx = canvasSize.width  / 2f
                                    val cy = canvasSize.height / 2f
                                    // Fly scale toward the preset. Centre
                                    // stays where the gesture left it, so
                                    // the user doesn't jump sideways.
                                    camera.value = camera.value.startFlyTo(
                                        toX = camera.value.centerImageX,
                                        toY = camera.value.centerImageY,
                                        toScale = target,
                                        nowNanos = System.nanoTime(),
                                        durationMs = 250,
                                    )
                                }
                            }
                        }
                    }
                )
                .pointerInput(bitmap, calibration, flow.active, anchorMode) {
                    detectTapGestures(
                        onTap = { offset ->
                            val vp = viewport.value
                            val img = vp.screenToImage(offset.x, offset.y)
                            if (anchorMode) {
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
                        onDoubleTap = { offset ->
                            // Double tap on the player triangle = re-
                            // engage follow with a fly-to.
                            val p = pos ?: return@detectTapGestures
                            val cal = calibration ?: return@detectTapGestures
                            val playerScreen = viewport.value
                                .worldToScreen(cal, p.x, p.y)
                            val dx = offset.x - playerScreen[0]
                            val dy = offset.y - playerScreen[1]
                            if (hypot(dx, dy) < 24f) {
                                viewModel.setFollowMode(true)
                                viewModel.requestFollowReEngage()
                            }
                        },
                        onLongPress = { offset ->
                            if (!isGtaV || calibration == null) return@detectTapGestures
                            val world = viewport.value.screenToWorld(calibration, offset.x, offset.y)
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
            val vp = viewport.value
            drawMap(imageBitmap, vp, edgeFillColor)
            drawOverlays(
                viewport = vp,
                calibration = calibration,
                blips = blips,
                playerPos = pos,
                waypoint = waypoint,
                accent = accent,
                triangleColor = if (game == CustomMapStore.Game.IV)
                    Color(0xFFE4A339) else accent,
                headingOffsetDeg = headingOffsetDeg,
                trianglePath = trianglePath,
            )
            flow.pixels.forEach { p ->
                val s = vp.imageToScreen(p.px, p.py)
                drawCircle(color = Color(0xFFEDE75F),
                           radius = 10f, center = Offset(s[0], s[1]),
                           style = Stroke(width = 3f))
            }
        }

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
            onOpenSettings = onOpenSettings,
            onCenterOnPlayer = {
                viewModel.setFollowMode(true)
                viewModel.requestFollowReEngage()
            },
        )

        if (!flow.active && !anchorMode) {
            ZoomOverlay(
                scale = viewport.value.scale,
                fitScale = ViewportModel.fit(
                    imageWidth = bitmap.width, imageHeight = bitmap.height,
                    screenWidth = canvasSize.width.coerceAtLeast(1),
                    screenHeight = canvasSize.height.coerceAtLeast(1),
                ).scale,
                calibration = calibration,
                accent = accent,
                onZoomIn = {
                    val fit = ViewportModel.fit(
                        imageWidth = bitmap.width, imageHeight = bitmap.height,
                        screenWidth = canvasSize.width.coerceAtLeast(1),
                        screenHeight = canvasSize.height.coerceAtLeast(1),
                    ).scale
                    val cal = calibration ?: return@ZoomOverlay
                    val next = ZoomPresets.next(camera.value.scale, fit, cal)
                    val idx = ZoomPresets.nearestIndex(next, fit, cal)
                    viewModel.setPresetIndex(game, idx)
                    val focalX = canvasSize.width  / 2f
                    val focalY = canvasSize.height / 2f
                    camera.value = camera.value.startFlyTo(
                        toX = camera.value.centerImageX,
                        toY = camera.value.centerImageY,
                        toScale = next,
                        nowNanos = System.nanoTime(),
                        durationMs = 250,
                    )
                },
                onZoomOut = {
                    val fit = ViewportModel.fit(
                        imageWidth = bitmap.width, imageHeight = bitmap.height,
                        screenWidth = canvasSize.width.coerceAtLeast(1),
                        screenHeight = canvasSize.height.coerceAtLeast(1),
                    ).scale
                    val cal = calibration ?: return@ZoomOverlay
                    val prev = ZoomPresets.prev(camera.value.scale, fit, cal)
                    val idx = ZoomPresets.nearestIndex(prev, fit, cal)
                    viewModel.setPresetIndex(game, idx)
                    camera.value = camera.value.startFlyTo(
                        toX = camera.value.centerImageX,
                        toY = camera.value.centerImageY,
                        toScale = prev,
                        nowNanos = System.nanoTime(),
                        durationMs = 250,
                    )
                },
                onFit = {
                    val fit = ViewportModel.fit(
                        imageWidth = bitmap.width, imageHeight = bitmap.height,
                        screenWidth = canvasSize.width.coerceAtLeast(1),
                        screenHeight = canvasSize.height.coerceAtLeast(1),
                    ).scale
                    viewModel.setPresetIndex(game, 0)
                    camera.value = camera.value.startFlyTo(
                        toX = bitmap.width / 2f,
                        toY = bitmap.height / 2f,
                        toScale = fit,
                        nowNanos = System.nanoTime(),
                        durationMs = 300,
                    )
                },
            )
        }

        if (anchorMode && !flow.active) {
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

private fun ivProtagonistFirstName(episode: Int?): String = when (episode) {
    1    -> "Johnny"
    2    -> "Luis"
    else -> "Niko"
}

/** Meters per image pixel from the calibration's affine. The Jacobian
 *  determinant is `a*e - b*d`; |det| is pixels^2 per metre^2 so its
 *  square root is pixels per metre, inverted here for metres/pixel. */
private fun metersPerImagePx(cal: Calibration): Float {
    val det = kotlin.math.abs(cal.a * cal.e - cal.b * cal.d)
    if (det < 1e-12f) return 1f
    return (1.0 / kotlin.math.sqrt(det.toDouble())).toFloat()
}

/** Convert a game-world heading (degrees, 0 = north, clockwise) into
 *  the equivalent **screen** heading (degrees clockwise from screen
 *  up). Goes through the calibration's linear part so a Y-flipped or
 *  slightly rotated atlas image produces a triangle that points in
 *  the direction the player is actually moving on-screen, and an
 *  image rotation that puts that direction at the top. Without this,
 *  an atlas whose calibration maps world +Y to image +Y (instead of
 *  the usual image -Y) scrolls "backwards" when the player walks
 *  forward. Returns a value in [0, 360). */
private fun screenHeadingDeg(cal: Calibration, worldHeadingDeg: Float): Float {
    val rad = Math.toRadians(worldHeadingDeg.toDouble())
    val wx = kotlin.math.sin(rad).toFloat()            // east component
    val wy = kotlin.math.cos(rad).toFloat()            // north component
    // Linear part of the affine: image delta per world delta.
    val imgDx = cal.a * wx + cal.b * wy
    val imgDy = cal.d * wx + cal.e * wy
    // Screen Y is down, so "up on screen" is image -Y. atan2(x, -y)
    // gives the clockwise angle from screen up.
    val rad2 = kotlin.math.atan2(imgDx.toDouble(), -imgDy.toDouble()).toFloat()
    var deg = Math.toDegrees(rad2.toDouble()).toFloat()
    if (deg < 0f) deg += 360f
    return deg
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
    edgeFill: Color,
) {
    // Fill the whole canvas with the corner-sampled tone first so a
    // rotated map's uncovered triangles read as ocean/dim-ground, not
    // the black background behind the atlas page. Overlays and the
    // image itself paint on top.
    if (edgeFill != Color.Black) {
        drawRect(color = edgeFill)
    }
    val cx = bitmap.width / 2f
    val cy = bitmap.height / 2f
    val topLeftX = (-cx + vp.offsetX) * vp.scale + vp.screenWidth / 2f
    val topLeftY = (-cy + vp.offsetY) * vp.scale + vp.screenHeight / 2f
    val pivot = Offset(vp.screenWidth / 2f, vp.screenHeight / 2f)
    withTransform({
        if (vp.rotationDeg != 0f) rotate(vp.rotationDeg, pivot)
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
    trianglePath: Path,
) {
    if (calibration == null) return
    blips.forEach { b ->
        val s = viewport.worldToScreen(calibration, b.x, b.y)
        drawCircle(
            color = Color(0xFFB6CCBC),
            radius = 5f,
            center = Offset(s[0], s[1]),
        )
    }
    if (playerPos != null) {
        val s = viewport.worldToScreen(calibration, playerPos.x, playerPos.y)
        // Triangle rotation: on north-up the triangle rotates with
        // heading (viewport.rotationDeg = 0, so triRot = screenHead).
        // On heading-up the image already rotates to put heading at
        // the top, so triRot = screenHead + (360 - screenHead) = 0.
        // Both cases from one formula; the heading goes through the
        // calibration so a Y-flipped atlas points the triangle in the
        // direction the player is actually moving on-screen.
        val screenHead = screenHeadingDeg(calibration, playerPos.h)
        val triRot = (screenHead + viewport.rotationDeg + headingOffsetDeg) % 360f
        withTransform({
            translate(left = s[0], top = s[1])
            rotate(triRot, pivot = Offset.Zero)
        }) {
            drawPath(path = trianglePath, color = triangleColor)
        }
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
    onOpenSettings: (() -> Unit)?,
    onCenterOnPlayer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Breathing room around the header so the title and icon
            // row aren't flush against the panel edge; the 2 dp used
            // before was too tight once the row grew past four icons.
            .padding(top = 8.dp, start = 12.dp, end = 12.dp, bottom = 4.dp),
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
            icon = Icons.Filled.MyLocation,
            contentDescription = stringResource(R.string.atlas_follow_cd),
            tint = accent,
            onClick = onCenterOnPlayer,
        )
        HeaderIconButton(
            icon = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = stringResource(
                if (isLocked) R.string.map_unlock else R.string.map_lock,
            ),
            tint = accent,
            onClick = onToggleLock,
        )
        if (game == CustomMapStore.Game.IV) {
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
        onOpenSettings?.let { open ->
            HeaderIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings_icon_cd),
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
                progress.isDone -> "done, reload the atlas tab"
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
