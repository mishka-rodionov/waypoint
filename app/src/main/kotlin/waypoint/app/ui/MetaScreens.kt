package waypoint.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import waypoint.app.CORRIDOR_METERS
import waypoint.app.GameMode
import waypoint.app.GameViewModel
import waypoint.app.Screen
import waypoint.core.route.ScoreO
import waypoint.core.terrain.Discipline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Назад") } })
        },
    ) { padding -> content(Modifier.padding(padding).fillMaxSize()) }
}

// ---------- итоги ----------

@Composable
fun StatsScreen(vm: GameViewModel) {
    val p = vm.progress
    var confirmReset by remember { mutableStateOf(false) }
    SubScreen("Итоги", onBack = { vm.screen = Screen.LIST }) { modifier ->
        LazyColumn(modifier.padding(horizontal = 16.dp)) {
            item {
                val attempts = p.attempts
                Spacer(Modifier.size(8.dp))
                Text("Попыток: ${attempts.size}", style = MaterialTheme.typography.titleMedium)
                if (attempts.isNotEmpty()) {
                    Text("Средние очки: ${attempts.map { it.score }.average().roundToInt()}")
                    val losses = attempts.mapNotNull { it.loss }
                    if (losses.isNotEmpty()) Text("Средняя потеря выбора: ${formatPct(losses.average())}")
                    Text("Связок пройдено на ${waypoint.core.pack.Levels.PASS_SCORE}+: ${p.best.count { it.value >= waypoint.core.pack.Levels.PASS_SCORE }}")
                }
                if (p.quizAnswered > 0) Text("Тест по знакам: ${p.quizCorrect} из ${p.quizAnswered} верно")
                Spacer(Modifier.size(12.dp))
                Text("По дисциплинам и режимам", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val groups = attempts.groupBy { it.discipline to it.mode }.toList().sortedBy { it.first.first + it.first.second }
                if (groups.isEmpty()) Text("Пока пусто: пройди связку.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((k, list) in groups) {
                    val d = Discipline.byId(k.first)?.title ?: k.first
                    val m = runCatching { GameMode.valueOf(k.second).title }.getOrDefault(k.second)
                    Text("$d · $m: ${list.size} попыток, в среднем ${list.map { it.score }.average().roundToInt()} очков")
                }
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Открыть все уровни")
                        Text("без прогрессии, все связки сразу", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = p.unlockAll, onCheckedChange = vm::setUnlockAll)
                }
                Spacer(Modifier.size(12.dp))
                Text("Последние попытки", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
            items(p.attempts.takeLast(30).reversed()) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title)
                        val d = Discipline.byId(a.discipline)?.title ?: a.discipline
                        val m = runCatching { GameMode.valueOf(a.mode).title }.getOrDefault(a.mode)
                        Text("$d · $m · ${fmt.format(Date(a.time))}" + (a.loss?.let { " · потеря ${formatPct(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${a.score}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
            }
            item {
                Spacer(Modifier.size(16.dp))
                OutlinedButton(onClick = { confirmReset = true }) { Text("Сбросить прогресс") }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить прогресс?") },
            text = { Text("Удалятся лучшие очки, история попыток и итоги теста. Отменить нельзя.") },
            confirmButton = { TextButton(onClick = { vm.resetProgress(); confirmReset = false }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}

// ---------- знаки ----------

@Composable
private fun SymbolSample(symbol: MapSymbol, sizeDp: Int) {
    Canvas(Modifier.size(sizeDp.dp)) {
        drawRect(Color.White)
        symbol.draw(this)
    }
}

@Composable
fun SymbolsScreen(vm: GameViewModel) {
    SubScreen("Условные знаки", onBack = { vm.screen = Screen.LIST }) { modifier ->
        LazyColumn(modifier) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Знаки спортивных карт (ISOM 2000) и как они влияют на скорость в игре.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { vm.screen = Screen.QUIZ }) { Text("Проверить себя: тест") }
                }
                HorizontalDivider()
            }
            items(SYMBOLS, key = { it.code }) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SymbolSample(s, 56)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(s.hint, style = MaterialTheme.typography.bodySmall)
                        Text(s.effect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(s.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

/** Тест: 10 знаков, к каждому четыре названия. */
@Composable
fun QuizScreen(vm: GameViewModel) {
    val questions = remember { SYMBOLS.shuffled().take(10) }
    val options = remember {
        questions.map { q -> (SYMBOLS.filter { it.code != q.code }.shuffled().take(3) + q).shuffled() }
    }
    var index by remember { mutableIntStateOf(0) }
    var correct by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf<String?>(null) }
    val finished = index >= questions.size
    // Итог записывается один раз, когда тест закончен (не из композиции).
    LaunchedEffect(finished) { if (finished) vm.recordQuiz(questions.size, correct) }
    SubScreen("Тест по знакам", onBack = { vm.screen = Screen.SYMBOLS }) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            if (finished) {
                Spacer(Modifier.size(24.dp))
                Text("$correct из ${questions.size}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        correct == questions.size -> "Отлично: все знаки узнаны."
                        correct >= questions.size * 0.7 -> "Хорошо. Ошибки — в справочнике знаков."
                        else -> "Загляни в справочник знаков и попробуй ещё раз."
                    },
                )
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.screen = Screen.SYMBOLS }) { Text("К знакам") }
                    Button(onClick = { vm.screen = Screen.LIST }) { Text("Готово") }
                }
                return@Column
            }
            val q = questions[index]
            Text("Вопрос ${index + 1} из ${questions.size}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(12.dp))
            SymbolSample(q, 140)
            Spacer(Modifier.size(16.dp))
            Text("Что это за знак?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            for (o in options[index]) {
                val chosen = answer
                val color = when {
                    chosen == null -> null
                    o.code == q.code -> Color(0xFF00A03C)
                    o.code == chosen -> MaterialTheme.colorScheme.error
                    else -> null
                }
                OutlinedButton(
                    onClick = {
                        if (answer == null) {
                            answer = o.code
                            if (o.code == q.code) correct++
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    border = color?.let { BorderStroke(2.dp, it) } ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) { Text(o.name) }
            }
            if (answer != null) {
                Spacer(Modifier.size(8.dp))
                Text(q.effect.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                Button(onClick = { index++; answer = null }) { Text(if (index + 1 < questions.size) "Дальше" else "Итог") }
            }
        }
    }
}

// ---------- как играть ----------

@Composable
fun HelpScreen(vm: GameViewModel) {
    SubScreen("Как играть", onBack = { vm.screen = Screen.LIST }) { modifier ->
        Column(modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            @Composable
            fun H(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

            @Composable
            fun P(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            P("Тренажёр выбора пути в спортивном ориентировании: смотришь на карту, прокладываешь путь от старта (треугольник) к КП (круг), игра сравнивает его с самым быстрым.")
            H("Рисование")
            P("• Веди пальцем от треугольника старта. Линия рисуется штрихами: следующий начинается от красного кружка в конце линии.")
            P("• Один палец в другом месте двигает карту, два пальца — масштаб. Длинную связку карта сама приблизит при касании и подвинет после штриха.")
            P("• Во время штриха в углу — лупа: видно, что под пальцем. Закончи линию в круге КП или нажми «Готово».")
            H("Оценка")
            P("• «Твой выбор» — лучший путь в коридоре ${CORRIDOR_METERS.roundToInt()} м вокруг линии: неточность пальца не наказывается, оценивается выбор.")
            P("• Очки: 100 минус удвоенный процент потери времени относительно оптимума. Оранжевым пунктиром — другие разумные варианты.")
            P("• Скорость зависит от растительности, дорог, болот и уклона; непреодолимое (озёра, скалы, заборы, здания) обходится.")
            H("Режимы")
            P("• Выбор пути — карта видна всё время.")
            P("• Память — карта показывается на время и скрывается; под нарисованной линией она проявляется полосой.")
            P("• По выбору — выбери КП и порядок, чтобы набрать больше очков и успеть на финиш. Опоздание — минус очко за каждые ${ScoreO.PENALTY_SECONDS.roundToInt()} с.")
            H("Дисциплины")
            P("• Кросс — бег по любой местности. Вело и лыжи — только по дорогам и тропам. Спринт — город в масштабе 1:4000.")
            H("Уровни")
            P("• Связки разбиты на уровни по сложности (звёзды). Следующий уровень открывается, когда 6 из 8 связок пройдены на 60+ очков. В «Итогах» можно открыть все сразу.")
            Spacer(Modifier.size(16.dp))
            Button(onClick = { vm.screen = Screen.LIST }) { Text("Понятно") }
            Spacer(Modifier.size(24.dp))
        }
    }
}
