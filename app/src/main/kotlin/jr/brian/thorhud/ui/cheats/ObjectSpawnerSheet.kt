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

@Composable
internal fun ObjectSpawnerSheet(
    visible: Boolean,
    accent: Color,
    pending: Boolean,
    error: String?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    FullScreenSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.trainer_objects_title),
        accent = accent,
    ) {
        val dim = Color(0xFF6A7A6E)
        val filtered = remember(selectedCategory) {
            if (selectedCategory == null) ObjectHashes.ALL
            else ObjectHashes.ALL.filter { it.category.name == selectedCategory }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.trainer_objects_subtitle),
                color = dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiniChip(
                    label = stringResource(R.string.trainer_objects_all),
                    selected = selectedCategory == null,
                    accent = accent,
                    onClick = { selectedCategory = null },
                )
                for (cat in ObjectHashes.Category.entries) {
                    MiniChip(
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
                items(items = filtered, key = { it.hash }) { entry ->
                    SimpleRow(
                        label = entry.label,
                        sub = entry.name,
                        accent = accent,
                        enabled = !pending,
                        onClick = { onPick(entry.hash) },
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
internal fun MiniChip(
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
        Text(label, color = fg,
             fontFamily = FontFamily.Monospace,
             fontSize = 13.sp,
             fontWeight = FontWeight.Bold,
             maxLines = 1)
    }
}

@Composable
internal fun SimpleRow(
    label: String,
    sub: String?,
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
        if (sub != null) {
            Text(
                text = sub,
                color = Color(0xFF6A7A6E).copy(alpha = alpha),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}
