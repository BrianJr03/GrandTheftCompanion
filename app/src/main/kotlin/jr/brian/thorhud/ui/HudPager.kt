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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal enum class HudPage(@StringRes val labelRes: Int) {
    Hud(R.string.tab_hud),
    Map(R.string.tab_map),
}

@Composable
internal fun rememberHudPagerState(initialPage: Int) = rememberPagerState(
    initialPage = initialPage.coerceIn(0, HudPage.entries.lastIndex),
) { HudPage.entries.size }

@Composable
internal fun HudPager(
    pagerState: PagerState,
    onPageSettled: (Int) -> Unit,
    hud: @Composable () -> Unit,
    map: @Composable () -> Unit,
) {
    // settledPage only changes once a swipe has come to rest, so a
    // half-finished drag is never saved. drop(1) skips the initial
    // emission (the restored value, not a settle).
    val currentOnPageSettled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { currentOnPageSettled(it) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
    ) { page ->
        when (HudPage.entries[page]) {
            HudPage.Hud -> hud()
            HudPage.Map -> map()
        }
    }
}
