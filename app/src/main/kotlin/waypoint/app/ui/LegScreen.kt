package waypoint.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import waypoint.app.CORRIDOR_METERS
import waypoint.app.GameMode
import waypoint.app.GameViewModel
import waypoint.app.LegPhase
import waypoint.app.LegSession
import waypoint.app.map.DrawingState
import waypoint.app.map.MapColors
import waypoint.app.map.MapOverlay
import waypoint.app.map.MapView
import waypoint.app.map.MapViewport
import waypoint.app.map.OverlayRoute
import waypoint.app.map.TileCache
import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.route.LegAnalysis
import waypoint.core.route.RouteStats
import waypoint.core.route.score
import kotlin.math.max
import kotlin.math.roundToInt

/** Радиус круга КП на местности (3 мм на карте 1:7500): линия, законченная в нём, сразу идёт на разбор. */
private const val CONTROL_RADIUS_M = 22.5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegScreen(vm: GameViewModel, session: LegSession, tiles: TileCache, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val leg = session.leg
    val density = LocalDensity.current
    val viewport = remember(session) { MapViewport(minScale = 0.03, maxScale = 6.0 * density.density) }
    val drawing = remember(session) { DrawingState() }
    var showVariants by rememberSaveable { mutableStateOf(true) }
    val phase = session.phase
    val padPx = with(density) { 28.dp.toPx() }

    // Вписать связку при открытии и все пути — при разборе.
    LaunchedEffect(session, phase is LegPhase.Result) {
        val r = phase as? LegPhase.Result
        val pts = buildList {
            add(leg.from); add(leg.to)
            if (r != null) {
                addAll(r.analysis.optimal.points); addAll(r.analysis.drawn.points)
                r.analysis.intended?.let { addAll(it.points) }
                if (showVariants) leg.options.forEach { addAll(it.points()) }
            }
        }
        viewport.fit(legBox(pts, leg.from.distanceTo(leg.to)), padPx)
    }

    val overlay = MapOverlay(
        from = leg.from, to = leg.to, fromStart = leg.fromStart,
        // «Память»: после показа карта скрыта до разбора.
        hideMap = session.mapHidden && phase !is LegPhase.Result && phase !is LegPhase.Failed,
        revealRadius = CORRIDOR_METERS,
        routes = (phase as? LegPhase.Result)?.let { analysisRoutes(it.analysis, if (showVariants) leg.options.drop(1).map { o -> o.points() } else emptyList()) }.orEmpty(),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(leg.title)
                        Text(session.mode.title, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Список") } },
                actions = {
                    TextButton(onClick = { viewport.fit(legBox(listOf(leg.from, leg.to), leg.from.distanceTo(leg.to)), padPx) }) { Text("Вписать") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MapView(
                    viewport = viewport,
                    tiles = tiles,
                    overlay = overlay,
                    // В разборе линия игрока — из анализа (точно по линии), штрихи не дублируем.
                    drawnStrokes = if (phase is LegPhase.Result) emptyList() else session.strokes,
                    drawing = drawing,
                    drawingEnabled = phase == LegPhase.Drawing,
                    lineEnd = session.lineEnd,
                    onStroke = { pts ->
                        session.strokes.add(pts)
                        val reached = pts.last().distanceTo(leg.to) <= max(CONTROL_RADIUS_M, 12.0 * density.density / viewport.scale)
                        if (reached) vm.finish()
                        reached
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (phase == LegPhase.Drawing && !drawing.active) Hint(session.strokes.isEmpty(), session.mapHidden, Modifier.align(Alignment.TopCenter))
                if (phase is LegPhase.Memorizing) MemorizeBanner(phase.secondsLeft, vm::skipMemorize, Modifier.align(Alignment.TopCenter))
                if (phase == LegPhase.Computing) {
                    Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp, shadowElevation = 4.dp) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Считаю пути…")
                        }
                    }
                }
            }
            when (phase) {
                is LegPhase.Memorizing -> DrawControls(hasLine = false, enabled = false, onUndo = {}, onClear = {}, onDone = {})
                LegPhase.Drawing, LegPhase.Computing -> DrawControls(
                    hasLine = session.strokes.isNotEmpty(),
                    enabled = phase == LegPhase.Drawing,
                    onUndo = vm::undoStroke, onClear = vm::clearStrokes, onDone = vm::finish,
                )
                is LegPhase.Result -> ResultPanel(phase.analysis, phase.thinkingSeconds, session.mode, leg.options, showVariants, { showVariants = it }, vm::retry, vm::nextLeg)
                is LegPhase.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(phase.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = vm::retry) { Text("Ещё раз") }
                }
            }
        }
    }
}

private fun legBox(points: List<Vec2>, legLength: Double): Box = Box.of(points).expand(max(40.0, legLength * 0.12))

private fun analysisRoutes(a: LegAnalysis, variants: List<List<Vec2>>): List<OverlayRoute> = buildList {
    for (v in variants) add(OverlayRoute(v, MapColors.variant, 3f, dashed = true, alpha = 0.85f))
    add(OverlayRoute(a.optimal.points, MapColors.optimal, 5f, alpha = 0.75f))
    a.intended?.let { add(OverlayRoute(it.points, MapColors.intended, 5f, alpha = 0.75f)) }
    add(OverlayRoute(a.drawn.points, MapColors.drawn, 2f, alpha = 0.9f))
}

@Composable
private fun MemorizeBanner(secondsLeft: Int, onSkip: () -> Unit, modifier: Modifier) {
    Surface(modifier.padding(8.dp), shape = RoundedCornerShape(10.dp), color = Color(0xF0FFFFFF), shadowElevation = 3.dp) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Запоминай путь: ", style = MaterialTheme.typography.bodyMedium)
            Text("$secondsLeft с", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSkip) { Text("Запомнил") }
        }
    }
}

@Composable
private fun Hint(first: Boolean, fromMemory: Boolean, modifier: Modifier) {
    Surface(modifier.padding(8.dp), shape = RoundedCornerShape(10.dp), color = Color(0xE6FFFFFF), shadowElevation = 2.dp) {
        Text(
            when {
                first && fromMemory -> "Карта скрыта. Рисуй по памяти от треугольника старта к КП: под линией карта проявится. Время не ограничено."
                first -> "Веди пальцем от треугольника старта к кругу КП. Один палец в другом месте двигает карту, два — масштаб."
                else -> "Продолжай от красного кружка в конце линии. Закончи в круге КП или нажми «Готово»."
            },
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DrawControls(hasLine: Boolean, enabled: Boolean, onUndo: () -> Unit, onClear: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onUndo, enabled = enabled && hasLine) { Text("Шаг назад") }
        OutlinedButton(onClick = onClear, enabled = enabled && hasLine) { Text("Сброс") }
        Spacer(Modifier.weight(1f))
        Button(onClick = onDone, enabled = enabled && hasLine) { Text("Готово") }
    }
}

@Composable
private fun ResultPanel(
    a: LegAnalysis,
    thinking: Double,
    mode: GameMode,
    options: List<waypoint.core.pack.PackRoute>,
    showVariants: Boolean,
    onShowVariants: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    val opt = a.optimal
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${a.score}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(" / 100", Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("Потеря выбора ${formatPct(a.loss)}", fontWeight = FontWeight.SemiBold)
                Text(
                    (if (mode == GameMode.MEMORY) "Рисование по памяти " else "Раздумье ") + "${thinking.roundToInt()} с",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        a.intended?.let { StatLine(MapColors.intended, "Твой выбор", it, opt) }
        StatLine(MapColors.optimal, "Оптимум", opt, null)
        StatLine(MapColors.drawn, "По линии", a.drawn, opt)
        if (a.intended == null) {
            Text("Вдоль линии не пройти: она идёт через непреодолимое.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (a.drawn.violations.isNotEmpty()) {
            Text("Пересечений непреодолимого: ${a.drawn.violations.size}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "«Твой выбор» — лучший путь в коридоре ${CORRIDOR_METERS.roundToInt()} м вокруг линии: неточность пальца не наказывается.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (options.size > 1) {
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = showVariants, onClick = { onShowVariants(!showVariants) }, label = { Text("Варианты") })
                Spacer(Modifier.width(8.dp))
                Text(
                    options.mapIndexed { i, o -> if (i == 0) formatTime(o.seconds) else "${formatTime(o.seconds)} (${formatPct(o.seconds / options[0].seconds - 1)})" }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry) { Text("Ещё раз") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext) { Text("Следующая") }
        }
    }
}

@Composable
private fun StatLine(color: Color, label: String, s: RouteStats, opt: RouteStats?) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.width(104.dp), fontWeight = FontWeight.Medium, maxLines = 1)
        val delta = opt?.let { " (${formatPct(s.seconds / it.seconds - 1)})" }.orEmpty()
        Text(
            "${formatTime(s.seconds)}$delta · ${formatMeters(s.length)} · ↑${s.climb.roundToInt()} м",
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
        )
    }
}
