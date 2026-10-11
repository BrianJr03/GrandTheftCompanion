package jr.brian.thorhud.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState

@Composable
internal fun WeaponPanel(
    s: GameState?,
    align: CardAlign = CardAlign.Center,
    verticalCenter: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val textAlign = textAlignOf(align)
    HudCard(modifier, stringResource(R.string.panel_weapon),
            align = align, verticalCenter = verticalCenter) {
        Text(
            weaponName(s?.weaponId),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        if ((s?.weaponId ?: 0) > 0) {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.label_clip),
                color = Color(0xFF6A7A6E),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.clip_value,
                               s?.ammoClip ?: 0, s?.ammoTotal ?: 0),
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// GTA IV eWeapon enum, authoritative from Aru's ScriptHook SDK
// (violetfrost/gtaiv-scripthook-sdk, ScriptHook/ScriptingEnums.h). The
// mapping lives in res/values/strings.xml as a string-array so it can
// be localised / corrected without a code change.
@Composable
private fun weaponName(id: Int?): String {
    val table = stringArrayResource(R.array.weapon_names)
    return when {
        id == null       -> table[0]
        id in table.indices -> table[id]
        id in 21..38     -> stringResource(R.string.weapon_episodic, id - 20)
        else             -> stringResource(R.string.weapon_unknown, id)
    }
}
