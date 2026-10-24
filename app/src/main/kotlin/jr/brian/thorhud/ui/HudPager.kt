package jr.brian.thorhud.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.LocalFullScreenSheetCount
import kotlinx.coroutines.launch

internal enum class HudPage(@StringRes val labelRes: Int) {
    // Order dictates left-to-right tab layout. HUD stays in the
    // middle (one swipe either way) and is the default landing
    // page. MainScreen's auto-swipe-to-map / return-to-pre-drive
    // logic uses HudPage.*.ordinal rather than literal indices so
    // this ordering stays authoritative.
    Cheats(R.string.tab_cheats),
    Hud(R.string.tab_hud),
    Map(R.string.tab_map),
}

@Composable
internal fun rememberHudPagerState() = rememberPagerState(
    // Every app load lands on the HUD tab (middle of the three).
    // In-session swipes still update the state, but the next cold
    // start resets to HUD so the user always knows where they are.
    initialPage = HudPage.Hud.ordinal,
) { HudPage.entries.size }

@Composable
internal fun HudPager(
    pagerState: PagerState,
    hud: @Composable () -> Unit,
    map: @Composable () -> Unit,
    cheats: @Composable () -> Unit,
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
        when (HudPage.entries[page]) {
            HudPage.Hud    -> hud()
            HudPage.Map    -> map()
            HudPage.Cheats -> cheats()
        }
    }
}
