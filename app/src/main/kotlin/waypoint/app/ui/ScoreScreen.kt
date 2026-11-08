package waypoint.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import waypoint.app.GameViewModel
import waypoint.app.ScoreOutcome
import waypoint.app.ScoreSession
import waypoint.app.map.MapColors
import waypoint.app.map.MapViewport
import waypoint.app.map.TileCache
import waypoint.app.map.drawScoreCourse
import waypoint.app.map.drawTiles
import waypoint.app.map.drawWorldLine
import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.pack.PackScoreProblem
import waypoint.core.route.ScoreO
import waypoint.core.route.ScoreResult
import kotlin.math.roundToInt

/**
 * «По выбору»: нажимай на КП в том порядке, в каком побежишь; финиш — в точке старта.
 * Время до финиша не показывается (только сумма прямых): прикидка, как на настоящем старте.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(vm: GameViewModel, session: ScoreSession, tiles: TileCache, mapScale: Double, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val p = session.problem
    val density = LocalDensity.current
    val viewport = remember(session) { MapViewport(minScale = 0.03, maxScale = 6.0 * density.density) }
    val padPx = with(density) { 24.dp.toPx() }
    val outcome = session.outcome
    LaunchedEffect(session, outcome != null) {
        viewport.fit(Box.of(listOf(p.start) + p.controls.map { it.position }).expand(60.0), padPx)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(p.title)
                        Text("По выбору · лимит ${formatTime(p.limitSeconds)}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Список") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ScoreMap(viewport, tiles, p, session, outcome, mapScale, onTap = vm::toggleControl, modifier = Modifier.fillMaxSize())
                if (outcome == null && session.order.isEmpty()) {
                    Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), shape = RoundedCornerShape(10.dp), color = Color(0xE6FFFFFF), shadowElevation = 2.dp) {
                        Text(
                            "Нажимай на КП в порядке прохождения; повторное нажатие на последний — отмена. " +
                                "Финиш — в точке старта. Опоздание: минус очко за каждые ${ScoreO.PENALTY_SECONDS.roundToInt()} с.",
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (outcome == null) PlanPanel(vm, session) else ScoreResultPanel(outcome, p, vm::retryScore, vm::nextScore)
        }
    }
}

@Composable
private fun PlanPanel(vm: GameViewModel, s: ScoreSession) {
    val p = s.problem
    val points = s.order.sumOf { p.controls[it - 1].points }
    val pts = listOf(p.start) + s.order.map { p.controls[it - 1].position } + p.start
    val straight = pts.zipWithNext { a, b -> a.distanceTo(b) }.sum()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            "КП ${s.order.size} из ${p.controls.size} · очков $points · по прямой ${"%.1f".format(straight / 1000).replace('.', ',')} км",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::undoControl, enabled = s.order.isNotEmpty()) { Text("Шаг назад") }
            OutlinedButton(onClick = vm::clearControls, enabled = s.order.isNotEmpty()) { Text("Сброс") }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::finishScore, enabled = s.order.isNotEmpty()) { Text("Финиш") }
        }
    }
}

@Composable
private fun ScoreResultPanel(o: ScoreOutcome, p: PackScoreProblem, onRetry: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${o.score}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(" / 100", Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("Раздумье ${o.thinkingSeconds.roundToInt()} с", style = MaterialTheme.typography.bodySmall)
        }
        PlanLine(MapColors.drawn, "Твой план", o.player, p.limitSeconds)
        PlanLine(MapColors.optimal, "Лучший", o.best, p.limitSeconds)
        Text(
            "Пути между КП — оптимальные: оценивается выбор и порядок КП, а не рисование.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Row {
            OutlinedButton(onClick = onRetry) { Text("Ещё раз") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext) { Text("Следующая") }
        }
    }
}

@Composable
private fun PlanLine(color: Color, label: String, r: ScoreResult, limit: Double) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.width(92.dp), fontWeight = FontWeight.Medium)
        val late = if (r.seconds > limit) ", опоздание ${formatTime(r.seconds - limit)}, штраф −${r.penalty}" else ""
        Text("${r.net} очков (${r.order.size} КП) · ${formatTime(r.seconds)}$late", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Карта задачи: тап по КП — в план, один палец — сдвиг, два — масштаб. */
@Composable
private fun ScoreMap(
    viewport: MapViewport,
    tiles: TileCache,
    p: PackScoreProblem,
    s: ScoreSession,
    outcome: ScoreOutcome?,
    mapScale: Double,
    onTap: (Int) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val tapRadius = with(density) { 36.dp.toPx() }
    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport.updateSize(it) }
            .pointerInput(viewport, s) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var multi = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            multi = true
                            val pan = event.calculatePan()
                            viewport.pan(pan.x, pan.y)
                            val c = event.calculateCentroid(useCurrent = true)
                            if (c.isSpecified) viewport.zoom(event.calculateZoom(), c)
                        } else {
                            val ch = pressed.first()
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved || multi) {
                                val d = ch.positionChange()
                                viewport.pan(d.x, d.y)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (!moved && !multi) {
                        val t = viewport.transform
                        val hit = p.controls.indices.minByOrNull { (t.toScreen(p.controls[it].position) - down.position).getDistance() }
                        if (hit != null && (t.toScreen(p.controls[hit].position) - down.position).getDistance() <= tapRadius) onTap(hit + 1)
                    }
                }
            },
    ) {
        val t = viewport.transform
        val dp = density.density
        drawRect(MapColors.paper)
        drawTiles(t, tiles)
        val controls = p.controls.map { it.position }
        fun pos(i: Int) = if (i == 0) p.start else controls[i - 1]
        if (outcome != null) {
            // Разбор: пути планов по оптимальным путям пака.
            fun planPaths(order: List<Int>) = (listOf(0) + order + 0).zipWithNext().map { (a, b) -> p.path(a, b)?.points() ?: listOf(pos(a), pos(b)) }
            for (seg in planPaths(outcome.best.order)) drawWorldLine(t, seg, MapColors.optimal, 5 * dp, alpha = 0.75f)
            for (seg in planPaths(outcome.player.order)) drawWorldLine(t, seg, MapColors.drawn, 2.5f * dp, alpha = 0.9f)
        } else {
            val plan = listOf(p.start) + s.order.map { controls[it - 1] }
            drawWorldLine(t, plan, MapColors.course, 2.5f * dp, alpha = 0.8f)
            if (s.order.isNotEmpty()) drawWorldLine(t, listOf(plan.last(), p.start), MapColors.course, 2f * dp, dash = 8 * dp, alpha = 0.6f)
        }
        drawScoreCourse(t, p.start, controls, s.order.toSet(), 10 * dp, mapScale / 1000)
        // Подписи: очки КП, у выбранных — номер в плане.
        val r = maxOf(10 * dp, (3.0 * mapScale / 1000 * t.scale).toFloat())
        p.controls.forEachIndexed { i, c ->
            val o = t.toScreen(c.position)
            val pos = s.order.indexOf(i + 1)
            val label = if (pos >= 0) "${pos + 1}·${c.points}" else "${c.points}"
            val layout = measurer.measure(label, TextStyle(color = MapColors.course, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            val topLeft = Offset(o.x + r * 0.8f, o.y - r * 0.8f - layout.size.height)
            drawRect(Color(0xCCFFFFFF), topLeft - Offset(2 * dp, 0f), androidx.compose.ui.geometry.Size(layout.size.width + 4 * dp, layout.size.height.toFloat()))
            drawText(layout, topLeft = topLeft)
        }
    }
}
