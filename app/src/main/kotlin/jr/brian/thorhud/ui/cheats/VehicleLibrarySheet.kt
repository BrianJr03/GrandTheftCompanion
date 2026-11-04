package jr.brian.thorhud.ui.cheats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.FullScreenSheet

/**
 * Trainer vehicle library picker. Search field + category chip rail +
 * LazyColumn of filtered rows. Tapping a row sends a spawn_vehicle
 * with the entry's joaat hash in arg0; the plugin runs the same async
 * spawn state machine that powers the curated car cheat ids. Open
 * from the Trainer section of the cheats tab.
 */
@Composable
internal fun VehicleLibrarySheet(
    visible: Boolean,
    accent: Color,
    pending: Boolean,
    error: String?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // A null filter means "all"; otherwise filter by category.
    var selectedCategory by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var query by rememberSaveable { mutableStateOf("") }

    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.trainer_vehicles_title),
        accent = accent,
    ) {
        val dim = Color(0xFF6A7A6E)
        val categoryOrder = VehicleHashes.Category.entries
        val queryLower = query.trim().lowercase()
        // Filter pipeline: category selection first, then prefix /
        // substring match on label or model name. Rebuilds whenever
        // the inputs change; ~200 entries is tiny for a filter per
        // keystroke.
        val filtered = remember(selectedCategory, queryLower) {
            VehicleHashes.ALL.asSequence()
                .filter { entry ->
                    selectedCategory == null ||
                        entry.category.name == selectedCategory
                }
                .filter { entry ->
                    queryLower.isEmpty() ||
                        entry.label.lowercase().contains(queryLower) ||
                        entry.name.contains(queryLower)
                }
                .toList()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Search field. OutlinedTextField matches the plate-input
            // pattern from LSCustomsScreen so the two feel uniform.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                ),
                placeholder = {
                    Text(
                        text = stringResource(R.string.trainer_vehicles_search_hint),
                        color = dim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = accent.copy(alpha = 0.5f),
                    focusedTextColor = accent,
                    unfocusedTextColor = accent,
                    cursorColor = accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // Horizontally scrollable category rail. "all" is the
            // reset chip; selected chip paints a filled background.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CategoryChip(
                    label = stringResource(R.string.trainer_vehicles_all),
                    selected = selectedCategory == null,
                    accent = accent,
                    onClick = { selectedCategory = null },
                )
                for (cat in categoryOrder) {
                    CategoryChip(
                        label = cat.label,
                        selected = selectedCategory == cat.name,
                        accent = accent,
                        onClick = { selectedCategory = cat.name },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.trainer_vehicles_empty),
                    color = dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                )
            } else {
                // Scrolling list. 200+ entries filters to at most a
                // category-worth of rows, so LazyColumn's recycling
                // keeps the picker snappy even if the user wipes the
                // filter mid-scroll.
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items = filtered, key = { it.hash }) { entry ->
                        VehicleRow(
                            label = entry.label,
                            model = entry.name,
                            accent = accent,
                            enabled = !pending,
                            onClick = { onPick(entry.hash) },
                        )
                    }
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
private fun CategoryChip(
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
            label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun VehicleRow(
    label: String,
    model: String,
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
            modifier = Modifier.weight(1f),
        )
        Text(
            text = model,
            color = Color(0xFF6A7A6E).copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}
