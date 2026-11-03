package jr.brian.thorhud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import jr.brian.thorhud.ui.components.LocalFullScreenSheetCount

@Composable
internal fun rememberHudPagerState(
    hasAtlas: Boolean = false,
    endless: Boolean = false,
) = run {
    val pages = hudPages(hasAtlas)
    rememberPagerState(
        // Every app load lands on the HUD tab regardless of whether the
        // atlas is present. In-session swipes still update the state,
        // but the next cold start resets to HUD so the user always
        // knows where they are. Endless mode starts in the middle lap
        // so there is as much room to swipe left as right.
        initialPage = initialVirtualPage(pages, endless, HudPage.Hud),
    ) { pagerPageCount(pages, endless) }
}

@Composable
internal fun HudPager(
    pagerState: PagerState,
    pages: List<HudPage>,
    hud: @Composable () -> Unit,
    map: @Composable () -> Unit,
    cheats: @Composable () -> Unit,
    atlas: (@Composable () -> Unit)? = null,
) {
    // Lock the horizontal swipe while any FullScreenSheet is open so
    // sheet-internal gestures (color-wheel drag, radio picker scroll)
    // don't drag the whole tab sideways under the user's finger.
    val sheetsOpen = LocalFullScreenSheetCount.current.value > 0
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !sheetsOpen,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
    ) { page ->
        when (currentHudPage(page, pages)) {
            HudPage.Hud    -> hud()
            HudPage.Map    -> map()
            HudPage.Cheats -> cheats()
            HudPage.Atlas  -> atlas?.invoke() ?: hud()
            null           -> hud()
        }
    }
}
