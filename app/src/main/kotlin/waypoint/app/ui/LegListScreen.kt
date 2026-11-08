package waypoint.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import waypoint.app.GameMode
import waypoint.app.data.AssetPack
import waypoint.app.scoreKey
import waypoint.core.pack.PackLeg
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegListScreen(
    pack: AssetPack,
    packNames: List<String>,
    onPack: (String) -> Unit,
    modelReady: Boolean,
    bestScores: Map<String, Int>,
    mode: GameMode,
    onMode: (GameMode) -> Unit,
    memorySeconds: Int,
    onMemorySeconds: (Int) -> Unit,
    onOpen: (PackLeg) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pack.meta.title)
                        Text(
                            if (modelReady) "Связок: ${pack.legs.size}" else "Готовлю карту…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    if (!modelReady) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "mode") {
                if (packNames.size > 1) PackPicker(pack, packNames, onPack)
                ModePicker(mode, onMode, memorySeconds, onMemorySeconds)
                HorizontalDivider()
            }
            items(pack.legs, key = { it.id }) { leg ->
                LegRow(leg, bestScores[scoreKey(mode, leg.id)], enabled = modelReady) { onOpen(leg) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PackPicker(pack: AssetPack, names: List<String>, onPack: (String) -> Unit) {
    val current = pack.dir.substringAfterLast('/')
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Карта:", style = MaterialTheme.typography.bodySmall)
        for (n in names) {
            // Названия других паков не читаем заранее: показываем имя папки, у текущего — заголовок.
            val label = if (n == current) pack.meta.title else packLabel(n)
            FilterChip(selected = n == current, onClick = { onPack(n) }, label = { Text(label) })
        }
    }
}

/** Имя папки пака для показа: gen-7 → «Лес №7». */
private fun packLabel(name: String): String = if (name.startsWith("gen-")) "Лес №${name.removePrefix("gen-")}" else name.replaceFirstChar { it.uppercase() }

@Composable
private fun ModePicker(mode: GameMode, onMode: (GameMode) -> Unit, memorySeconds: Int, onMemorySeconds: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in GameMode.entries) FilterChip(selected = m == mode, onClick = { onMode(m) }, label = { Text(m.title) })
        }
        if (mode == GameMode.MEMORY) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Показ карты:", style = MaterialTheme.typography.bodySmall)
                for (sec in listOf(10, 20, 30, 60)) {
                    FilterChip(selected = sec == memorySeconds, onClick = { onMemorySeconds(sec) }, label = { Text("$sec с") })
                }
            }
        }
        Text(
            when (mode) {
                GameMode.ROUTE -> "Карта видна всё время. Нарисуй самый быстрый путь от старта к КП."
                GameMode.MEMORY -> "Карта показывается на время и скрывается: путь рисуешь по памяти, время не ограничено."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegRow(leg: PackLeg, best: Int?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(leg.title, style = MaterialTheme.typography.titleMedium)
            val parts = buildList {
                add("напрямик ${leg.from.distanceTo(leg.to).roundToInt()} м")
                if (leg.options.size > 1) add("вариантов ${leg.options.size}")
                leg.options.firstOrNull()?.let { add("оптимум ${formatTime(it.seconds)}") }
                if (leg.options.isNotEmpty() && leg.straight == null) add("прямая перекрыта")
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (best != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Text("$best", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("очков", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
