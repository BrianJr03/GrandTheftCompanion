package jr.brian.thorhud.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom-sheet replacement for an AlertDialog that carries a title, a
 * paragraph of body text, and one or two action buttons. Styled to the
 * HUD: pure-black container, accent-coloured title, monospace body,
 * actions trailing to the right so a single primary button lands under
 * the thumb.
 *
 * Scrolls automatically — a translated body can wrap longer than the
 * sheet's intrinsic height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoSheet(
    title: String,
    body: AnnotatedString,
    accent: Color,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Color.Black,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                title,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Spacer(Modifier.height(12.dp))
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
    title: String,
    body: String,
    accent: Color,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) = InfoSheet(title, AnnotatedString(body), accent, onDismiss, actions)
