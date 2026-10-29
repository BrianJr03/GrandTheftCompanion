package jr.brian.thorhud.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R

/**
 * Count of currently-open [FullScreenSheet]s. The HudPager reads this
 * to disable horizontal page swipes while a sheet is up — a drag on
 * the color wheel (or any sheet-internal gesture) would otherwise
 * compete with the pager, pulling the whole tab sideways while the
 * user tries to pick a color. Provided by MainScreen at the top of
 * the compose tree; defaults to a stray state for previews/tests.
 */
internal val LocalFullScreenSheetCount =
    compositionLocalOf<MutableState<Int>> { mutableStateOf(0) }

/**
 * Shared base for the companion's slide-up overlays. Replaces
 * [androidx.compose.material3.ModalBottomSheet] for any panel whose
 * content is vertically scrollable — a drag gesture on a sheet
 * otherwise competes with the inner scroll (a flick near the top of
 * the content collapses the sheet instead of scrolling to the top).
 * No drag handle, no gestures, dismissal via the close icon on the
 * header or the Android back button.
 *
 * [heightFraction] picks the vertical reach: 1f = full screen,
 * 0.5f = bottom half, anything in between is honoured. `null`
 * wraps the content's intrinsic height (bounded by the screen),
 * so the sheet is only as tall as it needs to be. For sub-full
 * sheets the top portion is a translucent scrim that dismisses
 * the sheet on tap (so a quick miss above the sheet acts like
 * Material's modal scrim).
 *
 * Compose this unconditionally in a stable Box slot (not wrapped
 * in `if (visible)`), passing `visible` as a parameter. That way
 * the slide-out animation plays before the content is removed,
 * and internal state (scroll positions, expanded sections, pager
 * page) survives a close + reopen.
 *
 * Prefer the [FullScreenSheet] / [SizableScreenSheet] wrappers at
 * call sites — they lock in the right vertical reach + default
 * padding.
 */
@Composable
internal fun ScreenSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    accent: Color,
    showCloseIcon: Boolean = true,
    heightFraction: Float? = 1f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    // Extra icons that render in the header between the title and
    // the close icon. Space them with HeaderIconButton so they match
    // the close icon's tap target and tint. Empty by default.
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // Back + close icon both honour showCloseIcon so a caller can
    // temporarily block dismissal (e.g. UpdateSheet mid-download).
    BackHandler(enabled = visible && showCloseIcon, onBack = onDismiss)
    // Participate in the sheet-open ref count so the HudPager locks
    // its horizontal swipe while any sheet is up. DisposableEffect
    // keyed on `visible` enters when the sheet becomes visible and
    // its onDispose fires when `visible` flips back or the sheet
    // leaves composition (tab change, process death). Nested sheets
    // stack naturally — the count only drops to zero when all are
    // closed.
    val sheetCount = LocalFullScreenSheetCount.current
    if (visible) {
        DisposableEffect(Unit) {
            sheetCount.value = sheetCount.value + 1
            onDispose { sheetCount.value = sheetCount.value - 1 }
        }
    }
    val isFull = heightFraction != null && heightFraction >= 0.999f
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        // Full-size parent so a partial sheet can stick to the bottom
        // and the free space above hosts a tap-to-dismiss scrim.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (!isFull && showCloseIcon) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onDismiss),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let {
                        // Null fraction = wrap content; the parent Box's
                        // fillMaxSize still caps the Column at screen
                        // height, so an overlong content list stays
                        // bounded (verticalScroll inside handles overflow).
                        if (heightFraction != null) {
                            it.fillMaxHeight(heightFraction.coerceIn(0.1f, 1f))
                        } else {
                            it
                        }
                    }
                    .background(Color.Black)
                    // Drain any pointer event that reaches the sheet's root
                    // in the Final pass. Inner composables (icons, buttons,
                    // the picker's wheel) handle their events on the Main
                    // pass before we see them here, so this only catches
                    // taps that would otherwise fall through gaps (header
                    // row padding, the spacer between header and content).
                    // Without this, a miss on the Save or Close icon lands
                    // on whatever was behind the sheet.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Final)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    }
                    .padding(contentPadding),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    headerActions()
                    if (showCloseIcon) {
                        HeaderIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_close),
                            tint = accent,
                            onClick = onDismiss,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                content()
            }
        }
    }
}

/**
 * Full-screen slide-up overlay — the historical shape. Thin wrapper
 * around [ScreenSheet] that locks the vertical reach at the full
 * screen and keeps the 20 dp content padding. Prefer this for
 * dense panels (settings, blips, update, color wheel).
 */
@Composable
internal fun FullScreenSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    accent: Color,
    showCloseIcon: Boolean = true,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) = ScreenSheet(
    visible = visible,
    onDismiss = onDismiss,
    title = title,
    accent = accent,
    showCloseIcon = showCloseIcon,
    heightFraction = 1f,
    headerActions = headerActions,
    content = content,
)

/**
 * Resizable slide-up overlay for quick in-game picks (radio,
 * one-tap toggles) where the player wants to keep the game visible
 * above the sheet. Defaults to half-screen; pass [heightFraction]
 * to override (1f = full, `null` = wrap content so the sheet is
 * only as tall as its content plus padding). Tighter horizontal
 * padding than [FullScreenSheet] so tap targets inside have more
 * room. Tap above the sheet to dismiss (acts like Material's
 * modal scrim).
 */
@Composable
internal fun SizableScreenSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    accent: Color,
    showCloseIcon: Boolean = true,
    heightFraction: Float? = 0.5f,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) = ScreenSheet(
    visible = visible,
    onDismiss = onDismiss,
    title = title,
    accent = accent,
    showCloseIcon = showCloseIcon,
    heightFraction = heightFraction,
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
    headerActions = headerActions,
    content = content,
)

/**
 * The shared pill icon used in every header across the app. 32 dp
 * tap target with a 20 dp glyph, matching the chip cluster on the
 * HUD StatusBar so a player's eye lands on the same shape wherever
 * these icons appear.
 */
@Composable
internal fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}
