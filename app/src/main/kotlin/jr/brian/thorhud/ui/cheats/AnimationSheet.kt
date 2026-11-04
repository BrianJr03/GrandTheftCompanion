package jr.brian.thorhud.ui.cheats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jr.brian.thorhud.R
import jr.brian.thorhud.ui.components.FullScreenSheet

@Composable
internal fun AnimationSheet(
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
        title = stringResource(R.string.trainer_anims_title),
        accent = accent,
    ) {
        val dim = Color(0xFF6A7A6E)
        val filtered = remember(selectedCategory) {
            if (selectedCategory == null) AnimationLibrary.ALL
            else AnimationLibrary.ALL.filter { it.category.name == selectedCategory }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.trainer_anims_subtitle),
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
                    label = stringResource(R.string.trainer_anims_all),
                    selected = selectedCategory == null,
                    accent = accent,
                    onClick = { selectedCategory = null },
                )
                for (cat in AnimationLibrary.Category.entries) {
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
                items(items = filtered, key = { it.wire }) { anim ->
                    SimpleRow(
                        label = anim.label,
                        sub = null,
                        accent = accent,
                        enabled = !pending,
                        onClick = { onPick(anim.wire) },
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
