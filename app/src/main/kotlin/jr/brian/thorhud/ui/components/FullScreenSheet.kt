package jr.brian.thorhud.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R

/**
 * Full-screen slide-up overlay. Replaces [androidx.compose.material3.ModalBottomSheet]
 * for any panel whose content is vertically scrollable — a drag
 * gesture on a sheet otherwise competes with the inner scroll
 * (a flick near the top of the content collapses the sheet
 * instead of scrolling to the top). This overlay has no drag
 * handle, no scrim, and no gestures at all. Dismissal is via the
 * built-in close icon on the far right of the header or the
 * Android back button.
 *
 * Compose this unconditionally in a stable Box slot (not wrapped
 * in `if (visible)`), passing `visible` as a parameter. That way
 * the slide-out animation plays before the content is removed,
 * and internal state (scroll positions, expanded sections, pager
 * page) survives a close + reopen.
 */
@Composable
internal fun FullScreenSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.settings_close),
                        tint = accent,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}
