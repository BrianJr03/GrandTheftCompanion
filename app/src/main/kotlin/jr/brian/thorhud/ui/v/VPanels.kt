package jr.brian.thorhud.ui.v

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import jr.brian.thorhud.ui.components.BigValue
import jr.brian.thorhud.ui.components.CardAlign
import jr.brian.thorhud.ui.components.HudCard
import jr.brian.thorhud.ui.components.HudPalette
import jr.brian.thorhud.ui.components.textAlignOf

// GTA V counterparts of components/{CoinPanel,WeaponPanel,VehiclePanel,
// RadioPanel}.kt. Same cards, same type scale, same positions — only
// the content rules differ, and each difference is noted where it is.

// GET_VEHICLE_CLASS values with special handling.
private const val CLASS_CYCLES = 13
private const val CLASS_HELICOPTERS = 15
private const val CLASS_PLANES = 16

private fun formatMoney(amount: Long): String =
    String.format(java.util.Locale.US, "%,d", amount)

@Composable
private fun cashText(s: GameState?, character: VCharacter): String =
    // GTA V: cash belongs to the character, not the save. A ped that is
    // not one of the three has no cash stat at all — show a dash rather
    // than the previous character's balance.
    s?.money?.takeIf { character.isProtagonist }
        ?.let { stringResource(R.string.money_value, formatMoney(it)) }
        ?: stringResource(R.string.money_empty)

@Composable
private fun cashTitle(character: VCharacter, characterKnown: Boolean): String =
    if (characterKnown) {
        stringResource(R.string.v_panel_cash, stringResource(character.nameRes))
    } else {
        stringResource(R.string.panel_coin)
    }

/** Only the protagonist name span of the cash title ("michael" of
 *  "michael · cash") picks up the accent; the "· cash" tail stays
 *  grey. For the non-protagonist "coin" fallback we return null and
 *  HudCard uses its default rendering. */
@Composable
private fun cashTitleAnnotated(
    character: VCharacter,
    characterKnown: Boolean,
): AnnotatedString? {
    if (!characterKnown || !character.isProtagonist) return null
    val name = stringResource(character.nameRes)
    val full = stringResource(R.string.v_panel_cash, name)
    val nameIdx = full.indexOf(name).coerceAtLeast(0)
    return buildAnnotatedString {
        append(full)
        addStyle(SpanStyle(color = character.palette.accent),
                 nameIdx, nameIdx + name.length)
    }
}

@Composable
internal fun VCashPanel(
    s: GameState?,
    character: VCharacter,
    characterKnown: Boolean,
    align: CardAlign = CardAlign.Start,
    verticalCenter: Boolean = false,
    modifier: Modifier = Modifier,
) {
    HudCard(modifier, cashTitle(character, characterKnown),
            align = align, verticalCenter = verticalCenter,
            titleAnnotated = cashTitleAnnotated(character, characterKnown)) {
        BigValue(
            labelRes = null,
            value = cashText(s, character),
            textAlign = textAlignOf(align),
        )
    }
}

/** In-vehicle variant: cash + current station stacked in one column,
 *  mirroring GTA IV's MoneyRadioPanel. */
@Composable
internal fun VCashRadioPanel(
    s: GameState?,
    character: VCharacter,
    characterKnown: Boolean,
    stations: List<GameState.Station>,
    modifier: Modifier = Modifier,
) {
    HudCard(modifier, cashTitle(character, characterKnown),
            titleAnnotated = cashTitleAnnotated(character, characterKnown)) {
        BigValue(labelRes = null, value = cashText(s, character))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.label_radio),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        VRadioValue(radioText(s, stations), textAlign = TextAlign.Start)
    }
}

/** Single-line radio value with ellipsis — long station labels like
 *  "Non-Stop-Pop FM" clip cleanly instead of breaking mid-word under
 *  the monospace font's character-boundary wrap. */
@Composable
private fun VRadioValue(value: String, textAlign: TextAlign) {
    Text(
        value,
        color = Color(0xFFEDEDED),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun radioText(
    s: GameState?,
    stations: List<GameState.Station>,
): String {
    // GTA V: bicycles, some emergency and military vehicles have no
    // radio at all; say so instead of "off".
    val v = s?.vehicle?.takeIf { s.inVehicle }
    if (v != null && v.hasRadio == false) {
        return stringResource(R.string.v_radio_none)
    }
    return vCurrentStationLabel(s, stations)
        ?: stringResource(R.string.radio_off)
}

@Composable
internal fun VRadioPanel(
    s: GameState?,
    stations: List<GameState.Station>,
    align: CardAlign = CardAlign.Start,
    modifier: Modifier = Modifier,
) {
    HudCard(modifier, stringResource(R.string.label_radio), align = align) {
        VRadioValue(radioText(s, stations), textAlign = textAlignOf(align))
    }
}

@Composable
internal fun VWeaponPanel(
    s: GameState?,
    align: CardAlign = CardAlign.Center,
    verticalCenter: Boolean = false,
    // When true, draws a pencil glyph in front of the weapon name to
    // advertise the long-press-to-Ammu-Nation gesture. False on
    // notice screens, in a vehicle, or when the plugin can't accept
    // armory commands — matches the parent's weapon-card gesture.
    showEditGlyph: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val textAlign = textAlignOf(align)
    HudCard(modifier, stringResource(R.string.panel_weapon),
            align = align, verticalCenter = verticalCenter) {
        if (showEditGlyph && s != null && !vIsUnarmed(s.weaponName, s.weaponHash)) {
            val cd = stringResource(R.string.v_weapon_edit_cd)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = when (align) {
                    CardAlign.End    -> Arrangement.End
                    CardAlign.Center -> Arrangement.Center
                    CardAlign.Start  -> Arrangement.Start
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = cd,
                    tint = Color(0xFF6A7A6E),
                    modifier = Modifier
                        .size(14.dp)
                        .semantics { contentDescription = cd },
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    vWeaponLabel(s.weaponName, s.weaponHash),
                    color = Color(0xFFEDEDED),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        } else {
            Text(
                vWeaponLabel(s?.weaponName, s?.weaponHash),
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
        if (s == null || vIsUnarmed(s.weaponName, s.weaponHash)) return@HudCard

        // GTA V: melee weapons have no ammo, and thrown weapons have a
        // count but no clip. Only show what applies.
        val clipSize = s.clipSize ?: 0
        val total = s.ammoTotal ?: 0
        val (labelRes, value) = when {
            clipSize > 1 -> R.string.label_clip to
                stringResource(R.string.clip_value, s.ammoClip ?: 0, total)
            total > 0    -> R.string.v_label_ammo to total.toString()
            else         -> return@HudCard
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(labelRes),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            value,
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun VVehiclePanel(
    s: GameState?,
    palette: HudPalette,
    align: CardAlign = CardAlign.End,
    modifier: Modifier = Modifier,
) {
    val textAlign = textAlignOf(align)
    val v = s?.vehicle?.takeIf { s.inVehicle }
    // GTA V: the card title carries the vehicle class ("vehicle · plane")
    // since V spans bicycles to jets and the rest of the card adapts.
    val classNames = stringArrayResource(R.array.v_vehicle_classes)
    val className = v?.vehicleClass?.let { classNames.getOrNull(it) }
    val title = if (className != null) {
        stringResource(R.string.v_panel_vehicle_class, className)
    } else {
        stringResource(R.string.panel_vehicle)
    }

    HudCard(modifier, title, align = align) {
        if (v == null) {
            Text(stringResource(R.string.on_foot),
                 color = Color(0xFF6A7A6E), fontSize = 20.sp,
                 textAlign = textAlign,
                 modifier = Modifier.fillMaxWidth())
            return@HudCard
        }
        // Edit glyph tells the player this card is actionable. The
        // long-press hook that opens Customs lives on the parent
        // card modifier (ui/v/VMainScreen.kt); the glyph is a visual
        // affordance only. Always sits in front of the name, no
        // matter the card alignment — leaving it trailing on
        // End-aligned cards read as decoration, not an affordance.
        val cd = stringResource(R.string.v_vehicle_edit_cd)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = when (align) {
                CardAlign.End    -> Arrangement.End
                CardAlign.Center -> Arrangement.Center
                CardAlign.Start  -> Arrangement.Start
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = cd,
                tint = Color(0xFF6A7A6E),
                modifier = Modifier
                    .size(14.dp)
                    .semantics { contentDescription = cd },
            )
            Spacer(Modifier.size(6.dp))
            Text(
                v.name.ifBlank {
                    stringResource(R.string.v_model_fallback, v.model)
                },
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.label_speed),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        val mph = v.speedMps * 2.237f
        Text(
            stringResource(R.string.mph_value, mph.toInt()),
            fontFamily = FontFamily.Monospace, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, color = Color(0xFFEDEDED),
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        // GTA V: aircraft get an altitude readout (height above ground).
        val isAircraft = v.vehicleClass == CLASS_HELICOPTERS ||
            v.vehicleClass == CLASS_PLANES
        val altitude = v.altitudeM
        if (isAircraft && altitude != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.v_altitude_value,
                               (altitude * 3.281f).toInt()),
                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, color = Color(0xFFB6CCBC),
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** True for vehicles with no engine to report on (bicycles). */
internal fun vHasEngine(v: GameState.Vehicle?): Boolean =
    v != null && v.vehicleClass != CLASS_CYCLES
