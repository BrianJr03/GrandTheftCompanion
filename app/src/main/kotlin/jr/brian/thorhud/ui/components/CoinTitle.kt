package jr.brian.thorhud.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import jr.brian.thorhud.R

/**
 * Pure helper that picks the IV protagonist for a given
 * `GET_CURRENT_EPISODE` value. 0 → Niko, 1 → Johnny, 2 → Luis,
 * everything else (including null while episode has not been read
 * yet) → null, meaning the HUD falls back to the plain "coin" title.
 *
 * Lives in `ui.components` so unit tests can import it; no Android
 * imports here so [IvProtagonistTest] runs on a plain JVM.
 */
enum class IvProtagonist(@StringRes val nameRes: Int) {
    Niko(R.string.iv_char_niko),
    Johnny(R.string.iv_char_johnny),
    Luis(R.string.iv_char_luis),
}

/**
 * Map a `GET_CURRENT_EPISODE` value to the matching protagonist, or
 * null when the episode is unknown. Base IV = 0, TLAD = 1, TBoGT = 2.
 */
fun ivProtagonist(episode: Int?): IvProtagonist? = when (episode) {
    0    -> IvProtagonist.Niko
    1    -> IvProtagonist.Johnny
    2    -> IvProtagonist.Luis
    else -> null
}

/**
 * The coin card's title text + optional accent-highlighted name span.
 * Pass [nameRes] = null for the plain "coin" fallback (unknown
 * protagonist, non-protagonist V ped). [accent] colours only the name
 * span; the "· coin" tail stays grey via [HudCard]'s default.
 *
 * The format is the single [R.string.hud_panel_coin_named]
 * `"%1$s · %2$s"` — the trailing word comes from [R.string.panel_coin]
 * so each locale's own word for money appears without a second table.
 */
@Composable
fun coinTitleAnnotated(
    @StringRes nameRes: Int?,
    accent: Color,
): AnnotatedString? {
    if (nameRes == null) return null
    val name = stringResource(nameRes)
    val coin = stringResource(R.string.panel_coin)
    val full = stringResource(R.string.hud_panel_coin_named, name, coin)
    val nameIdx = full.indexOf(name).coerceAtLeast(0)
    return buildAnnotatedString {
        append(full)
        addStyle(SpanStyle(color = accent), nameIdx, nameIdx + name.length)
    }
}

/**
 * Plain-text title used when the Compose layer wants the un-styled
 * version (TalkBack, placeholder before the first state frame). Falls
 * back to just [R.string.panel_coin] on null.
 */
@Composable
fun coinTitlePlain(@StringRes nameRes: Int?): String {
    if (nameRes == null) return stringResource(R.string.panel_coin)
    val name = stringResource(nameRes)
    val coin = stringResource(R.string.panel_coin)
    return stringResource(R.string.hud_panel_coin_named, name, coin)
}
