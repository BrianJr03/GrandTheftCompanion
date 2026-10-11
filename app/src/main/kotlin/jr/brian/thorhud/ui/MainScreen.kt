package jr.brian.thorhud.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.R
import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.delay

// --- episode-driven accent palette ------------------------------------

/**
 * Accent colours tied to the currently-loaded GTA IV episode. The ASI
 * reports `episode` via GET_CURRENT_EPISODE (0 = IV, 1 = The Lost and
 * Damned, 2 = The Ballad of Gay Tony). Each episode gets its own
 * signature accent so the HUD mirrors the mood of what's on the top
 * screen. `labelRes` is a string resource rather than a String so the
 * palette instances can live at file-top scope (which can't call
 * stringResource).
 */
private data class HudPalette(
    @StringRes val labelRes: Int,
    val accent: Color,
    val speed: Color,
    val health: Color,
    val armor: Color,
    val stars: Color,
    val engine: Color,
)

private val PaletteIV = HudPalette(
    labelRes = R.string.episode_iv,
    accent   = Color(0xFFEDEDED),  // base IV: clean white as the default accent
    speed    = Color(0xFFEDEDED),
    health   = Color(0xFFEDEDED),
    armor    = Color(0xFF4F8FD0),
    stars    = Color(0xFFE09B3C),
    engine   = Color(0xFFE09B3C),
)
private val PaletteTLAD = HudPalette(
    labelRes = R.string.episode_tlad,
    accent   = Color(0xFFC46A2A),  // biker-rust orange
    speed    = Color(0xFFE2963B),
    health   = Color(0xFFC46A2A),
    armor    = Color(0xFF7A8E92),  // gunmetal
    stars    = Color(0xFFD9CF4A),
    engine   = Color(0xFFD9CF4A),
)
private val PaletteTBoGT = HudPalette(
    labelRes = R.string.episode_tbogt,
    accent   = Color(0xFFFF4FBF),  // Tony neon-magenta
    speed    = Color(0xFFFF4FBF),
    health   = Color(0xFFFF8EDC),
    armor    = Color(0xFF59F0E8),  // cyan to complement the magenta
    stars    = Color(0xFFF8E850),  // gold
    engine   = Color(0xFFF8E850),
)

private fun paletteFor(episode: Int?): HudPalette = when (episode) {
    1    -> PaletteTLAD
    2    -> PaletteTBoGT
    else -> PaletteIV
}

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val hello by viewModel.hello.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastUpdateMs by viewModel.lastUpdateMs.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val isStale = lastUpdateMs > 0 && (now - lastUpdateMs) > 1500
    val isDisconnected = lastUpdateMs == 0L
    val liveState = if (!isDisconnected && !isStale) state else null

    if (isDisconnected) {
        SetupScreen(lastError)
        return
    }

    val palette = paletteFor(liveState?.episode ?: state?.episode)

    if (state?.paused == true) {
        PausedScreen(hello = hello, palette = palette)
        return
    }

    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            StatusBar(
                isDisconnected = isDisconnected,
                isStale = isStale,
                inGame = liveState?.inGame == true,
                wantedLevel = liveState?.wanted ?: 0,
                palette = palette,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MoneyRadioPanel(liveState, Modifier.weight(1f).fillMaxHeight())
                WeaponPanel(liveState, palette, Modifier.weight(1f).fillMaxHeight())
                VehiclePanel(liveState, palette, Modifier.weight(1f).fillMaxHeight())
            }
            // Radio picker (vehicle-only) sits BETWEEN the top columns
            // and the full-width bars now, so your eye flows: value
            // headlines → quick-tap pickers → full-screen gauges.
            AnimatedVisibility(liveState?.inVehicle == true) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    RadioPicker(
                        currentStation = liveState?.radio ?: -1,
                        palette = palette,
                        onStationTap = { station -> viewModel.setRadio(station) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FullWidthStats(liveState, palette)
        }
    }
}

@Composable
private fun StatusBar(
    isDisconnected: Boolean,
    isStale: Boolean,
    inGame: Boolean,
    wantedLevel: Int,
    palette: HudPalette,
) {
    val mono = FontFamily.Monospace
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.brand),
             style = MaterialTheme.typography.titleMedium,
             color = palette.accent)
        val badge: Pair<Int, Color>? = when {
            isDisconnected -> R.string.status_disconnected to Color(0xFFB04040)
            isStale        -> R.string.status_stale        to Color(0xFFC08A3C)
            !inGame        -> R.string.status_waiting      to Color(0xFF8EA096)
            else           -> null
        }
        badge?.let { (labelRes, color) ->
            Text(stringResource(labelRes), color = color,
                 fontFamily = mono, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        WantedStars(level = wantedLevel, palette = palette)
    }
}

@Composable
private fun MoneyRadioPanel(s: GameState?, modifier: Modifier = Modifier) {
    HudCard(modifier, stringResource(R.string.panel_coin)) {
        BigValue(
            labelRes = null,
            value = s?.money?.let { stringResource(R.string.money_value, it) }
                ?: stringResource(R.string.money_empty),
        )
        Spacer(Modifier.height(20.dp))
        BigValue(labelRes = R.string.label_radio, value = radioName(s?.radio))
    }
}

@Composable
private fun WeaponPanel(s: GameState?, palette: HudPalette, modifier: Modifier = Modifier) {
    HudCard(modifier, stringResource(R.string.panel_weapon)) {
        Text(
            weaponName(s?.weaponId),
            color = Color(0xFFEDEDED),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        if ((s?.weaponId ?: 0) > 0) {
            Spacer(Modifier.height(20.dp))
            // Label-over-value, matches the money/radio BigValue style.
            Text(
                stringResource(R.string.label_clip),
                color = Color(0xFF6A7A6E),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Text(
                stringResource(R.string.clip_value,
                               s?.ammoClip ?: 0, s?.ammoTotal ?: 0),
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun VehiclePanel(s: GameState?, palette: HudPalette, modifier: Modifier = Modifier) {
    HudCard(modifier, stringResource(R.string.panel_vehicle)) {
        val v = s?.vehicle?.takeIf { s.inVehicle }
        if (v == null) {
            Text(stringResource(R.string.on_foot),
                 color = Color(0xFF6A7A6E), fontSize = 20.sp)
            return@HudCard
        }
        BigValue(
            labelRes = null,
            value = v.name.ifBlank {
                stringResource(R.string.model_fallback, v.model.toInt())
            },
        )
        Spacer(Modifier.height(20.dp))
        // Label-over-value for speed, matching the clip/radio treatment.
        Text(
            stringResource(R.string.label_speed),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        val mph = v.speedMps * 2.237f
        Text(
            stringResource(R.string.mph_value, mph.toInt()),
            fontFamily = FontFamily.Monospace, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, color = Color(0xFFEDEDED),
        )
    }
}

@Composable
private fun FullWidthStats(s: GameState?, palette: HudPalette) {
    val v = s?.vehicle?.takeIf { s.inVehicle }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
    ) {
        Bar(label = stringResource(R.string.label_health),
            current = s?.health ?: 0f, max = 200f,
            fill = palette.health)
        Spacer(Modifier.height(6.dp))
        Bar(label = stringResource(R.string.label_armor),
            current = s?.armor?.toFloat() ?: 0f,
            max = (s?.maxArmor ?: 100).coerceAtLeast(100).toFloat(),
            fill = palette.armor)
        if (v != null) {
            Spacer(Modifier.height(6.dp))
            Bar(label = stringResource(R.string.label_engine),
                current = v.engineHp.coerceAtLeast(0f),
                max = 1000f,
                fill = palette.engine)
        }
    }
}

@Composable
private fun RadioPicker(
    currentStation: Int,
    palette: HudPalette,
    onStationTap: (Int) -> Unit,
) {
    // 18 stations split into two rows of 9. Keeps tap targets wider on
    // the Thor's bottom panel and matches GTA IV's own radio wheel
    // where IV stations and TBoGT DLC stations stack visually.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioPickerRow(
            start = 0, endExclusive = 9,
            currentStation = currentStation, palette = palette,
            onStationTap = onStationTap,
        )
        RadioPickerRow(
            start = 9, endExclusive = 18,
            currentStation = currentStation, palette = palette,
            onStationTap = onStationTap,
        )
    }
}

@Composable
private fun RadioPickerRow(
    start: Int,
    endExclusive: Int,
    currentStation: Int,
    palette: HudPalette,
    onStationTap: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (station in start until endExclusive) {
            val isCurrent = station == currentStation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        color = if (isCurrent) palette.accent else Color(0xFF1F2A24),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onStationTap(station) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    station.toString(),
                    color = if (isCurrent) Color(0xFF000000) else Color(0xFFB6CCBC),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// --- primitives --------------------------------------------------------

@Composable
private fun HudCard(
    modifier: Modifier,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                color = Color(0xFF6A7A6E),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
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
private fun BigValue(@StringRes labelRes: Int?, value: String) {
    if (labelRes != null) {
        Text(
            stringResource(labelRes),
            color = Color(0xFF6A7A6E),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
    Text(
        value,
        color = Color(0xFFEDEDED),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
    )
}

@Composable
private fun Bar(label: String, current: Float, max: Float, fill: Color) {
    val pct = if (max > 0) (current / max).coerceIn(0f, 1f) else 0f
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color(0xFF6A7A6E),
                 fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("${current.toInt()}/${max.toInt()}",
                 color = Color(0xFFB6CCBC),
                 fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color(0xFF263029), RoundedCornerShape(5.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .height(10.dp)
                    .background(fill, RoundedCornerShape(5.dp)),
            )
        }
    }
}

@Composable
private fun WantedStars(level: Int, palette: HudPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(6) { i ->
            val lit = i < level
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(
                    if (lit) R.string.wanted_star_lit else R.string.wanted_star_dim, i,
                ),
                tint = if (lit) palette.stars else Color(0xFF2A332D),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// --- paused screen ----------------------------------------------------

@Composable
private fun PausedScreen(hello: GameState?, palette: HudPalette) {
    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.paused),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 72.sp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(palette.labelRes),
                color = palette.accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
            )
            hello?.fileVersion?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.paused_version, it),
                    color = Color(0xFF6A7A6E),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// --- setup screen -----------------------------------------------------

@Composable
private fun SetupScreen(lastError: String?) {
    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                stringResource(R.string.setup_title),
                color = PaletteIV.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.setup_subtitle),
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (!lastError.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.setup_last_error, lastError),
                    color = Color(0xFFB04040),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.setup_section),
                color = Color(0xFF6A7A6E),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            ChecklistItem("1", R.string.setup_step1_title, R.string.setup_step1_detail)
            ChecklistItem("2", R.string.setup_step2_title, R.string.setup_step2_detail)
            ChecklistItem("3", R.string.setup_step3_title, R.string.setup_step3_detail)
            ChecklistItem("4", R.string.setup_step4_title, R.string.setup_step4_detail)
        }
    }
}

@Composable
private fun ChecklistItem(
    step: String,
    @StringRes titleRes: Int,
    @StringRes detailRes: Int,
) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF1F2A24), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                step,
                color = PaletteIV.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Column {
            Text(
                stringResource(titleRes),
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(detailRes),
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

// --- cosmetic lookup tables -------------------------------------------

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

@Composable
private fun radioName(id: Int?): String {
    val table = stringArrayResource(R.array.radio_names)
    return when {
        id == null || id == -1    -> stringResource(R.string.radio_off)
        id in table.indices        -> table[id]
        else                       -> stringResource(R.string.radio_unknown, id)
    }
}
