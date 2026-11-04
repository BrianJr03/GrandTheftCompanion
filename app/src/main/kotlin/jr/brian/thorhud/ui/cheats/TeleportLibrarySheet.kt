package jr.brian.thorhud.ui.cheats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.FullScreenSheet

/**
 * Trainer teleport library picker. Category chips + LazyColumn of
 * curated map points. Tapping a row sends the preset's wire index
 * through `teleport_preset`; the plugin reads coords from its own
 * mirror table and runs SET_ENTITY_COORDS on the player's ped (or
 * current vehicle, since SET_ENTITY_COORDS is polymorphic).
 */
@Composable
internal fun TeleportLibrarySheet(
    visible: Boolean,
    accent: Color,
    pending: Boolean,
    error: String?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.trainer_teleports_title),
        accent = accent,
    ) {
        val dim = Color(0xFF6A7A6E)
        val categoryOrder = TeleportLocations.Category.entries
        val filtered = remember(selectedCategory) {
            if (selectedCategory == null) TeleportLocations.ALL
            else TeleportLocations.ALL.filter { it.category.name == selectedCategory }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.trainer_teleports_subtitle),
                color = dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))

            // Category chips. No search field; 30 entries fit in one
            // scrolled view, so a chip filter is more useful than
            // free-text search.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TeleportCategoryChip(
                    label = stringResource(R.string.trainer_teleports_all),
                    selected = selectedCategory == null,
                    accent = accent,
                    onClick = { selectedCategory = null },
                )
                for (cat in categoryOrder) {
                    TeleportCategoryChip(
                        label = cat.label,
                        selected = selectedCategory == cat.name,
                        accent = accent,
                        onClick = { selectedCategory = cat.name },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = filtered, key = { it.wire }) { loc ->
                    TeleportRow(
                        label = loc.label,
                        accent = accent,
                        enabled = !pending,
                        onClick = { onPick(loc.wire) },
                    )
                }
            }

            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.common_error_line, error),
                    color = Color(0xFFE07070),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun TeleportCategoryChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) accent else Color.Black
    val fg = if (selected) Color.Black else accent
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TeleportRow(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, accent.copy(alpha = alpha * 0.4f),
                    RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = accent.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
