package jr.brian.thorhud.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Carries a title, a paragraph of body text, and one or two action
 * buttons. Full-screen slide-up styled to the HUD: pure-black
 * container, accent-coloured title, monospace body, actions trailing
 * to the right so a single primary button lands under the thumb.
 *
 * Scrolls automatically — a translated body can wrap longer than the
 * screen's intrinsic height. Compose this unconditionally with
 * `visible = <state>` so the slide-out animation plays before the
 * content is removed.
 */
@Composable
internal fun InfoSheet(
    visible: Boolean,
    title: String,
    body: AnnotatedString,
    accent: Color,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        accent = accent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                body,
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                content = actions,
            )
        }
    }
}

@Composable
internal fun InfoSheet(
    visible: Boolean,
    title: String,
    body: String,
    accent: Color,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) = InfoSheet(visible, title, AnnotatedString(body), accent, onDismiss, actions)
