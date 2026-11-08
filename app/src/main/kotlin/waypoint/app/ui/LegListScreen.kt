package waypoint.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import waypoint.app.GameViewModel
import waypoint.app.data.AssetPack
import waypoint.app.scoreKey
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackScoreProblem
import kotlin.math.roundToInt

/** Главный экран: карта, дисциплина, режим и список связок (или задач «по выбору»). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegListScreen(vm: GameViewModel, pack: AssetPack, actions: @Composable () -> Unit = {}) {
    val mode = vm.mode
    val discipline = vm.discipline
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pack.meta.title)
                        Text(
                            when {
                                mode == GameMode.SCORE -> "Задач: ${vm.scoreProblems.size}"
                                vm.modelReady -> "Связок: ${vm.legs.size}"
                                else -> "Готовлю карту…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    if (!vm.modelReady && mode != GameMode.SCORE) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
                    actions()
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "header") {
                if (vm.packNames.size > 1) {
                    ChipRow("Карта:") {
                        for (n in vm.packNames) {
                            // Названия других паков не читаем заранее: у текущего — заголовок, у остальных — имя папки.
                            val label = if (n == pack.name) pack.meta.title else packLabel(n)
                            FilterChip(selected = n == pack.name, onClick = { vm.selectPack(n) }, label = { Text(label) })
                        }
                    }
                }
                if (pack.disciplines.size > 1) {
                    ChipRow("Дисциплина:") {
                        for (d in pack.disciplines) FilterChip(selected = d.id == discipline, onClick = { vm.selectDiscipline(d.id) }, label = { Text(d.title) })
                    }
                }
                ModePicker(vm, hasScore = vm.scoreProblems.isNotEmpty())
                HorizontalDivider()
            }
            if (mode == GameMode.SCORE) {
                items(vm.scoreProblems, key = { it.id }) { p ->
                    ProblemRow(p, vm.bestScores[scoreKey(mode, discipline, p.id)]) { vm.openScore(p) }
                    HorizontalDivider()
                }
            } else {
                items(vm.legs, key = { it.id }) { leg ->
                    LegRow(leg, vm.bestScores[scoreKey(mode, discipline, leg.id)], enabled = vm.modelReady) { vm.openLeg(leg) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        content()
    }
}

/** Имя папки пака для показа: gen-7 → «Лес №7», sprint-3 → «Город №3». */
private fun packLabel(name: String): String = when {
    name.startsWith("gen-") -> "Лес №${name.removePrefix("gen-")}"
    name.startsWith("sprint-") -> "Город №${name.removePrefix("sprint-")}"
    else -> name.replaceFirstChar { it.uppercase() }
}

@Composable
private fun ModePicker(vm: GameViewModel, hasScore: Boolean) {
    val mode = vm.mode
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in GameMode.entries) {
                if (m == GameMode.SCORE && !hasScore) continue
                FilterChip(selected = m == mode, onClick = { vm.mode = m }, label = { Text(m.title) })
            }
        }
        if (mode == GameMode.MEMORY) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Показ карты:", style = MaterialTheme.typography.bodySmall)
                for (sec in listOf(10, 20, 30, 60)) {
                    FilterChip(selected = sec == vm.memorySeconds, onClick = { vm.memorySeconds = sec }, label = { Text("$sec с") })
                }
            }
        }
        Text(
            when (mode) {
                GameMode.ROUTE -> "Карта видна всё время. Нарисуй самый быстрый путь от старта к КП."
                GameMode.MEMORY -> "Карта показывается на время и скрывается: путь рисуешь по памяти, время не ограничено."
                GameMode.SCORE -> "Набери больше очков и вернись на финиш в пределах лимита: выбери КП и порядок."
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
        BestBadge(best)
    }
}

@Composable
private fun ProblemRow(p: PackScoreProblem, best: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "КП ${p.controls.size} · очков всего ${p.controls.sumOf { it.points }} · лимит ${formatTime(p.limitSeconds)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        BestBadge(best)
    }
}

@Composable
private fun BestBadge(best: Int?) {
    if (best == null) return
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
        Text("$best", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("очков", style = MaterialTheme.typography.bodySmall)
    }
}
