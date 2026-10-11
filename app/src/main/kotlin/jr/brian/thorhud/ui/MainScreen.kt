package jr.brian.thorhud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jr.brian.thorhud.model.GameState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 5 HUD. Landscape layout for the Thor's 1240×1080 bottom
 * screen: three panels side-by-side (stats | weapon+radio | vehicle),
 * with a radio-station picker row at the bottom when the player is in
 * a vehicle. All interaction routes through the ViewModel back into
 * GameStateRepository.setRadio / .setWeapon.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val hello by viewModel.hello.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastUpdateMs by viewModel.lastUpdateMs.collectAsStateWithLifecycle()

    // Recompute "stale" each second — a frame-less connection means the
    // ASI stopped or the game froze. We tick a sentinel to force
    // recomposition.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val isStale = lastUpdateMs > 0 && (now - lastUpdateMs) > 1500
    val isDisconnected = lastUpdateMs == 0L
    val liveState = if (!isDisconnected && !isStale) state else null
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    // When the socket has never connected, show a focused setup
    // walkthrough instead of three empty cards. Keeps someone trying
    // GTC for the first time from staring at zeros wondering what's
    // wrong.
    if (isDisconnected) {
        SetupScreen(lastError)
        return
    }

    // True-OLED black: #000000 means those AMOLED subpixels power off,
    // which both saves battery on the Thor's bottom panel and gives
    // perfect contrast against the Compose chrome.
    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            StatusBar(
                hello = hello,
                isDisconnected = isDisconnected,
                isStale = isStale,
                inGame = liveState?.inGame == true,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatsPanel(liveState, Modifier.weight(1f).fillMaxHeight())
                WeaponRadioPanel(liveState, Modifier.weight(1f).fillMaxHeight())
                VehiclePanel(liveState, Modifier.weight(1f).fillMaxHeight())
            }
            // Radio picker only when in a vehicle.
            AnimatedVisibility(liveState?.inVehicle == true) {
                RadioPicker(
                    currentStation = liveState?.radio ?: -1,
                    onStationTap = { station -> viewModel.setRadio(station) },
                )
            }
        }
    }
}

@Composable
private fun StatusBar(
    hello: GameState?,
    isDisconnected: Boolean,
    isStale: Boolean,
    inGame: Boolean,
) {
    val mono = FontFamily.Monospace
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("GTC", style = MaterialTheme.typography.titleMedium,
             color = Color(0xFF78DC9F))
        val (label, color) = when {
            isDisconnected -> "DISCONNECTED" to Color(0xFFB04040)
            isStale        -> "STALE"        to Color(0xFFC08A3C)
            !inGame        -> "WAITING"      to Color(0xFF8EA096)
            else           -> "LIVE"         to Color(0xFF78DC9F)
        }
        Text(label, color = color, fontFamily = mono, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        hello?.fileVersion?.let { Text("GTAIV $it", color = Color(0xFF6A7A6E),
                                       fontFamily = mono, fontSize = 11.sp) }
    }
}

@Composable
private fun StatsPanel(s: GameState?, modifier: Modifier = Modifier) {
    HudCard(modifier, "stats") {
        BigValue(label = "money", value = s?.money?.let { "$$it" } ?: "—")
        Spacer(Modifier.height(10.dp))
        Bar(label = "health", current = s?.health ?: 0f, max = 200f,
            fill = Color(0xFF78DC9F))
        Spacer(Modifier.height(6.dp))
        Bar(label = "armor",
            current = s?.armor?.toFloat() ?: 0f,
            max = (s?.maxArmor ?: 100).coerceAtLeast(100).toFloat(),
            fill = Color(0xFF4F8FD0))
        Spacer(Modifier.height(10.dp))
        WantedStars(level = s?.wanted ?: 0)
    }
}

@Composable
private fun WeaponRadioPanel(s: GameState?, modifier: Modifier = Modifier) {
    HudCard(modifier, "weapon · radio") {
        val wname = weaponName(s?.weaponId)
        BigValue(label = "weapon", value = wname)
        if ((s?.weaponId ?: 0) > 0) {
            Text(
                "clip ${s?.ammoClip ?: 0} / ${s?.ammoTotal ?: 0}",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                color = Color(0xFFB6CCBC),
            )
        }
        Spacer(Modifier.height(14.dp))
        BigValue(label = "radio", value = radioName(s?.radio))
    }
}

@Composable
private fun VehiclePanel(s: GameState?, modifier: Modifier = Modifier) {
    HudCard(modifier, "vehicle") {
        val v = s?.vehicle?.takeIf { s.inVehicle }
        if (v == null) {
            Text("on foot", color = Color(0xFF6A7A6E), fontSize = 20.sp)
            return@HudCard
        }
        BigValue(label = "model", value = v.name.ifBlank { "model ${v.model}" })
        Spacer(Modifier.height(8.dp))
        // GTA IV reports m/s. Convert to mph for display (× 2.237).
        val mph = v.speedMps * 2.237f
        Text(
            "${mph.toInt()} mph",
            fontFamily = FontFamily.Monospace, fontSize = 30.sp,
            fontWeight = FontWeight.Bold, color = Color(0xFF78DC9F),
        )
        Spacer(Modifier.height(8.dp))
        Bar(label = "engine", current = v.engineHp.coerceAtLeast(0f),
            max = 1000f, fill = Color(0xFFE09B3C))
    }
}

@Composable
private fun RadioPicker(currentStation: Int, onStationTap: (Int) -> Unit) {
    val count = 18  // 0..13 = IV, 14..17 = TBoGT; be generous
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (station in 0 until count) {
            val isCurrent = station == currentStation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        color = if (isCurrent) Color(0xFF78DC9F) else Color(0xFF1F2A24),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable { onStationTap(station) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    station.toString(),
                    color = if (isCurrent) Color(0xFF003919) else Color(0xFFB6CCBC),
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
    // On a true-black backdrop the cards would disappear with pure
    // #000000 fill, so keep them a hair above black — enough to read as
    // a card edge without lighting up a solid rectangle of AMOLED pixels.
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF070A08)),
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

@Composable
private fun BigValue(label: String, value: String) {
    Text(
        value,
        color = Color(0xFFEDEDED),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
    )
    Text(
        label,
        color = Color(0xFF6A7A6E),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
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
private fun WantedStars(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(6) { i ->
            val lit = i < level
            Box(
                modifier = Modifier.size(14.dp).background(
                    color = if (lit) Color(0xFFE09B3C) else Color(0xFF263029),
                    shape = RoundedCornerShape(3.dp),
                ),
            )
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
                "GTC: not connected",
                color = Color(0xFF78DC9F),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Expecting ThorBridge.asi on 127.0.0.1:47801.",
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (!lastError.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Last error: $lastError",
                    color = Color(0xFFB04040),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "setup checklist",
                color = Color(0xFF6A7A6E),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            ChecklistItem(
                step = "1",
                title = "GTA IV: Complete Edition 1.2.0.59",
                detail = "The ASI pattern-scans this exact build. Other versions won't light up the HUD.",
            )
            ChecklistItem(
                step = "2",
                title = "Winlator-family launcher with env vars",
                detail = "GameHub Lite, Winlator / Winlator Cmod, or GameNative. Add " +
                        "WINEDLLOVERRIDES=dinput8=n,b in the per-game Environment variables.",
            )
            ChecklistItem(
                step = "3",
                title = "ThorBridge.asi + dinput8.dll in the game folder",
                detail = "Push both next to GTAIV.exe. dinput8.dll is Ultimate ASI Loader " +
                        "(ThirteenAG, 32-bit); download from the UAL releases page.",
            )
            ChecklistItem(
                step = "4",
                title = "Launch GTA IV and load a save",
                detail = "The ASI stays in safe mode until the script engine is up. On " +
                        "a loaded save this screen clears automatically.",
            )
        }
    }
}

@Composable
private fun ChecklistItem(step: String, title: String, detail: String) {
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
                color = Color(0xFF78DC9F),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Column {
            Text(
                title,
                color = Color(0xFFEDEDED),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                detail,
                color = Color(0xFF8EA096),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

// --- cosmetic lookup tables -------------------------------------------

private fun weaponName(id: Int?): String = when (id) {
    null, 0 -> "unarmed"
    1 -> "baseball bat"
    2 -> "pool cue"
    3 -> "knife"
    4 -> "grenade"
    5 -> "pistol"
    6 -> "combat pistol"
    7 -> "SMG"
    8 -> "carbine SMG"
    9 -> "pump shotgun"
    10 -> "combat shotgun"
    11 -> "AK-47"
    12 -> "M4"
    13 -> "sniper"
    14 -> "combat sniper"
    15 -> "RPG"
    else -> "weapon $id"
}

private fun radioName(id: Int?): String = when (id) {
    null, -1 -> "off"
    0 -> "The Vibe"
    1 -> "The Beat"
    2 -> "Rock 97.8"
    3 -> "The Journey"
    4 -> "Fusion"
    5 -> "Jazz Nation"
    6 -> "Vladivostok"
    7 -> "Mega Hitz"
    8 -> "San Juan Sounds"
    9 -> "WKTT"
    10 -> "Classics"
    11 -> "K109 The Studio"
    12 -> "RamJam"
    13 -> "Int'l Funk 99"
    14 -> "Vice City FM"
    15 -> "LCPR / Police"
    16 -> "Self Radio"
    else -> "station $id"
}
