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
internal fun HudPager(
    accent: Color,
    initialPage: Int,
    onPageSettled: (Int) -> Unit,
    hud: @Composable () -> Unit,
    map: @Composable () -> Unit,
    trailingHeader: @Composable (currentPage: Int) -> Unit = {},
) {
    val pages = HudPage.entries
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, pages.lastIndex),
    ) { pages.size }
    val scope = rememberCoroutineScope()

    // settledPage only changes once a swipe has come to rest, so a
    // half-finished drag is never saved. drop(1) skips the initial
    // emission (the restored value, not a settle).
    val currentOnPageSettled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { currentOnPageSettled(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Pages used to own the top inset via their own Scaffolds;
            // the tab strip is topmost now, so apply it here.
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
    ) {
        HudTabStrip(
            pages = pages,
            selectedIndex = pagerState.currentPage,
            accent = accent,
            onSelect = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            trailing = { trailingHeader(pagerState.currentPage) },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (pages[page]) {
                HudPage.Hud -> hud()
                HudPage.Map -> map()
            }
        }
    }
}

@Composable
private fun HudTabStrip(
    pages: List<HudPage>,
    selectedIndex: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pages.forEachIndexed { index, page ->
            val selected = index == selectedIndex
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(page.labelRes),
                    color = if (selected) accent else Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .background(
                            color = if (selected) accent else Color.Transparent,
                            shape = RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}
