package jr.brian.thorhud.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import kotlinx.coroutines.launch

/** One slide in an [OnboardingSheet]. Title + body are both string
 *  resources so a slide list stays translatable without needing a
 *  Context at construction time. */
internal data class OnboardingSlide(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

/**
 * Reusable first-visit sheet. One slide at a time, dot indicators
 * sized to the list, next/done on the right. [slides] can be any
 * length; a single-slide list shows a single dot and a done button.
 *
 * Compose unconditionally with [visible] so the slide-out animation
 * plays on dismiss, same as every other [FullScreenSheet] in the
 * app. Mimics the shape of the former `CheatsOnboardingSheet` so
 * callers only need to feed slides + strings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnboardingSheet(
    visible: Boolean,
    title: String,
    accent: Color,
    slides: List<OnboardingSlide>,
    onDismiss: () -> Unit,
) {
    val count = slides.size.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = 0) { count }
    val scope = rememberCoroutineScope()
    val dim = Color(0xFF6A7A6E)
    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        accent = accent,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                val slide = slides.getOrNull(page) ?: return@HorizontalPager
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(slide.titleRes),
                        color = Color(0xFFEDEDED),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(slide.bodyRes),
                        color = Color(0xFFCFCFCF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(count) { idx ->
                    val lit = pager.currentPage == idx
                    Box(
                        modifier = Modifier
                            .size(if (lit) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (lit) accent else dim),
                    )
                }
                Spacer(Modifier.weight(1f))
                val isLast = pager.currentPage >= count - 1
                val onPrimary: () -> Unit = {
                    if (!isLast) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    else onDismiss()
                }
                TextButton(onClick = onPrimary) {
                    Text(
                        stringResource(
                            if (isLast) R.string.cheats_onboarding_done
                            else R.string.cheats_onboarding_next,
                        ),
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
