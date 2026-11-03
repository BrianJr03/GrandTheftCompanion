package jr.brian.thorhud.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState

@Composable
internal fun CoinPanel(
    s: GameState?,
    align: CardAlign = CardAlign.Start,
    verticalCenter: Boolean = false,
    accent: Color = Color(0xFFEDEDED),
    modifier: Modifier = Modifier,
) {
    // The IV coin card titles itself "<name> · coin" when the plugin
    // has reported an episode (0 Niko / 1 Johnny / 2 Luis); until then
    // (or for a dev build with a non-episode save) it falls back to
    // the plain [panel_coin] label. Only the name span picks up the
    // episode's accent; the "· coin" tail stays grey via HudCard's
    // default rendering.
    val proto = ivProtagonist(s?.episode)
    HudCard(
        modifier,
        coinTitlePlain(proto?.nameRes),
        align = align,
        verticalCenter = verticalCenter,
        titleAnnotated = coinTitleAnnotated(proto?.nameRes, accent),
    ) {
        BigValue(
            labelRes = null,
            value = s?.money?.let { stringResource(R.string.money_value, formatMoney(it)) }
                ?: stringResource(R.string.money_empty),
            textAlign = textAlignOf(align),
        )
    }
}

/**
 * In-vehicle variant that stacks coin + radio in a single tall column,
 * freeing the middle/right columns for weapon + vehicle. On foot we
 * split them into separate [CoinPanel] + [RadioPanel] in a 2×2 grid.
 */
@Composable
internal fun MoneyRadioPanel(
    s: GameState?,
    accent: Color = Color(0xFFEDEDED),
    modifier: Modifier = Modifier,
) {
    val proto = ivProtagonist(s?.episode)
    HudCard(
        modifier,
        coinTitlePlain(proto?.nameRes),
        titleAnnotated = coinTitleAnnotated(proto?.nameRes, accent),
    ) {
        BigValue(
            labelRes = null,
            value = s?.money?.let { stringResource(R.string.money_value, formatMoney(it)) }
                ?: stringResource(R.string.money_empty),
        )
        Spacer(Modifier.height(20.dp))
        // Convert local → enum so the radio NAME matches the station
        // playing. The picker now works in local-index space but the
        // name lookup table in strings.xml is enum-indexed.
        BigValue(
            labelRes = R.string.label_radio,
            value = radioName(gameRadioIndexToEnumIndex(s?.episode, s?.radio)),
        )
    }
}

// US grouping on purpose — Liberty City is dollar-denominated and we
// show "$1,234,567" regardless of device locale.
private fun formatMoney(amount: Long): String =
    String.format(java.util.Locale.US, "%,d", amount)
