package jr.brian.thorhud.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class CardAlign { Start, Center, End }

internal fun textAlignOf(align: CardAlign): TextAlign = when (align) {
    CardAlign.Start  -> TextAlign.Start
    CardAlign.Center -> TextAlign.Center
    CardAlign.End    -> TextAlign.End
}

@Composable
internal fun HudCard(
    modifier: Modifier,
    title: String,
    align: CardAlign = CardAlign.Start,
    verticalCenter: Boolean = false,
    // GTA V's cash card wants only the character-name span in the
    // protagonist's accent (the "· cash" tail stays grey). Pass a
    // pre-styled AnnotatedString here to override the plain title.
    titleAnnotated: AnnotatedString? = null,
    // Discovery-ring driver for the TITLE label on cards whose
    // long-press is live right now. Zero = no ring (default); a
    // caller animates to 1 to play the one-shot. The ring draws
    // around a pill fitted to the label text (not the whole card)
    // so the hint reads as "this label is the gesture target", not
    // "this card is a button".
    titleRingProgress: Float = 0f,
    titleRingAccent: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val textAlign = when (align) {
        CardAlign.Start  -> TextAlign.Start
        CardAlign.Center -> TextAlign.Center
        CardAlign.End    -> TextAlign.End
    }
    val horizontal = when (align) {
        CardAlign.Start  -> Alignment.Start
        CardAlign.Center -> Alignment.CenterHorizontally
        CardAlign.End    -> Alignment.End
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(10.dp),
    ) {
        val columnMod = if (verticalCenter) {
            Modifier.padding(14.dp).fillMaxWidth().fillMaxHeight()
        } else {
            Modifier.padding(14.dp).fillMaxWidth()
        }
        Column(
            modifier = columnMod,
            horizontalAlignment = horizontal,
            verticalArrangement = if (verticalCenter) Arrangement.Center
                                  else Arrangement.Top,
        ) {
            // Pill shape fitted to the label text. The ring draws
            // around this; wrapContentWidth so the pill hugs the
            // actual title string, not the full card width.
            val pillShape = RoundedCornerShape(50)
            val labelModifier = Modifier
                .wrapContentWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .discoveryRing(
                    progress = titleRingProgress,
                    accent = titleRingAccent,
                    shape = pillShape,
                )
            if (titleAnnotated != null) {
                Text(
                    titleAnnotated,
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = textAlign,
                    modifier = labelModifier,
                )
            } else {
                Text(
                    title,
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = textAlign,
                    modifier = labelModifier,
                )
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/**
 * Label (small, grey) rendered ABOVE the bold value. labelRes=null
 * skips the label row — used by money + model where the panel title
 * already names the field.
 */
@Composable
internal fun BigValue(
    @StringRes labelRes: Int?,
    value: String,
    textAlign: TextAlign = TextAlign.Start,
) {
    if (labelRes != null) {
        Text(
            stringResource(labelRes),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // Clip overlong values with an ellipsis instead of letting them
    // overflow their column (e.g. "K109 The Studio" in a narrow radio
    // slot renders as "K109 The…").
    Text(
        value,
        color = Color(0xFFEDEDED),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
    )
}
