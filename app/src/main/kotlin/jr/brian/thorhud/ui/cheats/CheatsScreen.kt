package jr.brian.thorhud.ui.cheats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
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
 * The cheats tab. A scrolling grid of text buttons: one-shots on top,
 * toggles below. Toggle button lights come from [cheatsMask] alone —
 * never from the tap — so a toggle the plugin refused never looks on.
 * The last refusal shows as a single line at the bottom; cleared on
 * the next tap. When the connected plugin build does not support the
 * cheat command the screen is just a one-line notice.
 */
@Composable
internal fun CheatsScreen(
    isGtaV: Boolean,
    cheatsSupported: Boolean?,
    cheatsMask: Int?,
    accent: Color,
    lastResult: CheatResult?,
    onRunCheat: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dim = Color(0xFF6A7A6E)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.cheats_title),
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (cheatsSupported != true) {
            Text(
                stringResource(R.string.cheats_unsupported),
                color = dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            return@Column
        }

        val cheats = visibleCheats(isGtaV)
        val (oneShots, toggles) = cheats.partition { !it.isToggle }
        val pendingId = lastResult?.takeIf { it.error == null }?.cheatId

        if (oneShots.isNotEmpty()) {
            SectionHeader(stringResource(R.string.cheats_section_actions), accent)
            CheatGrid(
                items = oneShots,
                accent = accent,
                pendingId = pendingId,
                isOn = { false },
                onTap = onRunCheat,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (toggles.isNotEmpty()) {
            SectionHeader(stringResource(R.string.cheats_section_toggles), accent)
            CheatGrid(
                items = toggles,
                accent = accent,
                pendingId = pendingId,
                isOn = { isOn(cheatsMask, it) },
                onTap = onRunCheat,
            )
            Spacer(Modifier.height(12.dp))
        }

        // Last refusal, verbatim from the plugin. Blank line when the
        // last tap was accepted (or nothing has been tapped).
        val err = lastResult?.error
        if (!err.isNullOrBlank()) {
            Text(
                stringResource(R.string.cheats_error, err),
                color = Color(0xFFE07070),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String, accent: Color) {
    Text(
        text,
        color = accent,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun CheatGrid(
    items: List<CheatDef>,
    accent: Color,
    pendingId: Int?,
    isOn: (Int) -> Boolean,
    onTap: (Int) -> Unit,
) {
    // Two columns of buttons. Pairs the list up and lets any trailing
    // odd entry take a column of its own. The 540 x 468 dp Thor bottom
    // panel comfortably fits 2 x ~250 dp buttons at this font size.
    val rows = items.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { def ->
                    CheatButton(
                        def = def,
                        accent = accent,
                        on = isOn(def.id),
                        pending = pendingId == def.id,
                        onTap = onTap,
                        modifier = Modifier.weight(1f),
                    )
                }
                // When the row has only one entry, pad the second slot
                // so the surviving button keeps its half-width look.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CheatButton(
    def: CheatDef,
    accent: Color,
    on: Boolean,
    pending: Boolean,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(def.labelRes)
    val shared = modifier.heightIn(min = 44.dp)
    val content: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (on) Color.Black else accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
    }
    if (def.isToggle && on) {
        FilledTonalButton(
            onClick = { if (!pending) onTap(def.id) },
            enabled = !pending,
            modifier = shared,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = accent,
                contentColor = Color.Black,
                disabledContainerColor = accent.copy(alpha = 0.5f),
                disabledContentColor = Color.Black,
            ),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = { if (!pending) onTap(def.id) },
            enabled = !pending,
            modifier = shared,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Black,
                contentColor = accent,
                disabledContainerColor = Color.Black,
                disabledContentColor = accent.copy(alpha = 0.5f),
            ),
        ) { content() }
    }
}
