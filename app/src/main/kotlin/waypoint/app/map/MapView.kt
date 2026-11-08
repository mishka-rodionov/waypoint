package waypoint.app.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import waypoint.core.geometry.Vec2
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

/** Что нарисовать поверх карты (кроме линии игрока, её ведёт [DrawingState]). */
data class MapOverlay(
    val from: Vec2,
    val to: Vec2,
    val fromStart: Boolean,
    /** Линии разбора: (точки, цвет, ширина в dp, пунктир). */
    val routes: List<OverlayRoute> = emptyList(),
    /** Скрыть фон (режим «память»): видны только КП, линия и карта в полосе вдоль линии. */
    val hideMap: Boolean = false,
    /** Полуширина полосы, где при скрытой карте она проявляется под линией, м. */
    val revealRadius: Double = 15.0,
)

data class OverlayRoute(val points: List<Vec2>, val color: Color, val widthDp: Float, val dashed: Boolean = false, val alpha: Float = 0.9f)

/**
 * Штрихи линии игрока. Линия рисуется по частям: каждый новый штрих начинается от конца предыдущего,
 * между штрихами карту можно сдвигать и масштабировать.
 */
@Stable
class DrawingState {
    /** Текущий штрих в метрах; первая точка — конец уже нарисованной линии. */
    val current = mutableStateListOf<Vec2>()
    /** Палец на экране во время штриха — для лупы. */
    var finger by mutableStateOf<Offset?>(null)
    val active: Boolean get() = finger != null
}

private enum class Mode { DRAW, PAN, TRANSFORM }

/** Масштаб карты на экране при рисовании (1:6000): мельче — неудобно попадать пальцем, крупнее — мало видно. */
const val DRAWING_MAP_SCALE = 6000.0

/**
 * Куда сдвинуть карту после штриха, чтобы продолжать было удобно; null — сдвигать не нужно.
 * Конец линии [end] уводится от края: на 30% меньшей стороны экрана от центра в сторону, противоположную КП [to].
 * Если КП близко, в центр ставится середина между концом линии и КП.
 */
internal fun followShift(t: ViewTransform, end: Vec2, to: Vec2): Pair<Float, Float>? {
    val w = t.width.toFloat()
    val h = t.height.toFloat()
    if (w <= 0f || h <= 0f) return null
    val m = min(w, h)
    val e = t.toScreen(end)
    val k = t.toScreen(to)
    val toK = k - e
    val dist = toK.getDistance()
    fun inside(p: Offset, margin: Float) = p.x in w * margin..w * (1 - margin) && p.y in h * margin..h * (1 - margin)
    val center = Offset(w / 2, h / 2)
    val targetE: Offset
    if (dist < 0.6f * m) {
        if (inside(e, 0.12f) && inside(k, 0.12f)) return null
        targetE = center - toK / 2f
    } else {
        val dir = toK / dist
        if (inside(e, 0.2f) && roomAhead(e, dir, w, h) >= 0.3f * m) return null
        targetE = center - dir * (0.3f * m)
    }
    val d = targetE - e
    return if (d.getDistance() < 8f) null else d.x to d.y
}

/** Сколько пикселей от точки [p] до края экрана по направлению [dir]. */
private fun roomAhead(p: Offset, dir: Offset, w: Float, h: Float): Float {
    var t = Float.POSITIVE_INFINITY
    if (dir.x > 1e-6f) t = min(t, (w - p.x) / dir.x)
    if (dir.x < -1e-6f) t = min(t, -p.x / dir.x)
    if (dir.y > 1e-6f) t = min(t, (h - p.y) / dir.y)
    if (dir.y < -1e-6f) t = min(t, -p.y / dir.y)
    return max(0f, t)
}

/**
 * Карта связки: тайлы, надпечатка, линии разбора и рисование пальцем.
 *
 * Жесты (главная UX-задача этапа):
 * - один палец **у конца линии** (кружок-«ручка»; в начале — треугольник старта) рисует;
 * - один палец в другом месте двигает карту — нечаянного рисования нет;
 * - два пальца — масштаб и сдвиг, в том числе посреди штриха (штрих при этом сохраняется);
 * - длинная линия рисуется несколькими штрихами; пока палец ведёт штрих, карта не двигается
 *   (автопрокрутка у края пробовалась и убрана: карта уезжала из-под пальца, и линия искажалась);
 * - если карта мельче масштаба рисования (длинная связка вписана целиком), при касании ручки она плавно
 *   приближается вокруг пальца до 1:[DRAWING_MAP_SCALE]; точка под пальцем остаётся на месте;
 * - после штриха карта плавно сдвигается: конец линии ближе к центру, впереди — место в сторону КП
 *   (или в кадре и конец линии, и КП, если он близко); любой жест прерывает это движение;
 * - во время штриха в верхнем углу (подальше от пальца) — лупа ×2 с перекрестием: видно, что под пальцем.
 */
@Composable
fun MapView(
    viewport: MapViewport,
    tiles: TileCache,
    overlay: MapOverlay,
    drawnStrokes: List<List<Vec2>>,
    drawing: DrawingState,
    drawingEnabled: Boolean,
    lineEnd: Vec2,
    /** Штрих закончен; вернуть true, если связка на этом завершена (тогда карта не подстраивается). */
    onStroke: (List<Vec2>) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleRadius = with(density) { 22.dp.toPx() }
    val grabRadius = with(density) { 40.dp.toPx() }
    val minStep = with(density) { 2.dp.toPx() }
    // Масштаб для рисования — как карта 1:DRAWING_MAP_SCALE на экране: 1 мм экрана = DRAWING_MAP_SCALE / 1000 м.
    val drawScale = density.density * 160.0 / 25.4 / (DRAWING_MAP_SCALE / 1000.0)
    val enabled by rememberUpdatedState(drawingEnabled)
    val end by rememberUpdatedState(lineEnd)
    val target by rememberUpdatedState(overlay.to)
    val strokeCallback by rememberUpdatedState(onStroke)
    val scope = rememberCoroutineScope()

    fun addPoint(screen: Offset) {
        val t = viewport.transform
        val w = t.toWorld(screen)
        val last = drawing.current.lastOrNull()
        if (last == null || last.distanceTo(w) * t.scale >= minStep) drawing.current.add(w)
    }

    /** [follow]: после штриха подвинуть карту, чтобы было куда рисовать дальше (не при жесте двумя пальцами). */
    fun endStroke(follow: Boolean) {
        val pts = drawing.current.toList()
        drawing.current.clear()
        drawing.finger = null
        // Совсем короткий штрих — скорее касание перед жестом двумя пальцами.
        val lengthPx = pts.zipWithNext { a, b -> a.distanceTo(b) }.sum() * viewport.scale
        if (pts.size < 2 || lengthPx <= 3 * minStep) return
        val finished = strokeCallback(pts)
        if (follow && !finished) {
            followShift(viewport.transform, pts.last(), target)?.let { (dx, dy) ->
                scope.launch { viewport.animatePan(dx, dy) }
            }
        }
    }

    Canvas(
        modifier
            // Canvas сам не обрезает рисунок: без этого тайлы вылезают под панели экрана.
            .clipToBounds()
            .onSizeChanged { viewport.updateSize(it) }
            .pointerInput(viewport, drawing) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val handle = viewport.transform.toScreen(end)
                    var mode = if (enabled && (down.position - handle).getDistance() <= grabRadius) Mode.DRAW else Mode.PAN
                    if (mode == Mode.DRAW) {
                        drawing.current.clear()
                        drawing.current.add(end)
                        drawing.finger = down.position
                        addPoint(down.position)
                        // Длинная связка вписана мелко: приближаем вокруг пальца до масштаба рисования.
                        if (viewport.scale < drawScale * 0.7) scope.launch { viewport.animateZoom(drawScale, down.position) }
                    }
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (mode == Mode.DRAW) endStroke(follow = false)
                            mode = Mode.TRANSFORM
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val pan = event.calculatePan()
                            viewport.pan(pan.x, pan.y)
                            if (centroid.isSpecified) viewport.zoom(event.calculateZoom(), centroid)
                        } else {
                            val ch = pressed.first()
                            if (mode == Mode.DRAW) {
                                drawing.finger = ch.position
                                addPoint(ch.position)
                            } else {
                                val d = ch.positionChange()
                                viewport.pan(d.x, d.y)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (mode == Mode.DRAW) endStroke(follow = true)
                }
            },
    ) {
        val t = viewport.transform
        drawRect(MapColors.paper)
        drawMapLayer(t, tiles, overlay, drawnStrokes, drawing.current, Rect(Offset.Zero, size))
        drawScene(t, overlay, drawnStrokes, drawing.current, density.density)
        if (drawingEnabled && !drawing.active) {
            // Ручка: отсюда продолжается линия.
            val h = t.toScreen(lineEnd)
            drawCircle(MapColors.drawn.copy(alpha = 0.18f), handleRadius, h)
            drawCircle(MapColors.drawn, handleRadius, h, style = Stroke(width = 2.dp.toPx()))
        }
        drawing.finger?.let { f -> drawLoupe(t, f, tiles, overlay, drawnStrokes, drawing.current) }
    }
}

/** Фон карты: целиком или (карта скрыта) только в полосе вдоль нарисованной линии. */
private fun DrawScope.drawMapLayer(
    t: ViewTransform, tiles: TileCache, overlay: MapOverlay,
    strokes: List<List<Vec2>>, current: List<Vec2>, bounds: Rect,
) {
    if (!overlay.hideMap) drawTiles(t, tiles)
    else drawRevealedTiles(t, tiles, strokes + listOf(current), overlay.revealRadius, bounds)
}

/** Надпечатка, линии разбора и линия игрока в виде [t]. */
private fun DrawScope.drawScene(t: ViewTransform, overlay: MapOverlay, strokes: List<List<Vec2>>, current: List<Vec2>, dp: Float) {
    drawCourse(t, overlay.from, overlay.to, overlay.fromStart, minPx = 10 * dp)
    for (r in overlay.routes) {
        drawWorldLine(t, r.points, r.color, r.widthDp * dp, dash = if (r.dashed) 9 * dp else 0f, alpha = r.alpha)
    }
    for (s in strokes) drawWorldLine(t, s, MapColors.drawn, 3 * dp)
    drawWorldLine(t, current, MapColors.drawn, 3 * dp)
}

/** Лупа ×2: в верхнем углу, противоположном пальцу; если палец сам наверху — внизу. */
private fun DrawScope.drawLoupe(
    t: ViewTransform, finger: Offset, tiles: TileCache, overlay: MapOverlay,
    strokes: List<List<Vec2>>, current: List<Vec2>,
) {
    val d = 150.dp.toPx()
    val margin = 12.dp.toPx()
    val left = if (finger.x > size.width / 2) margin else size.width - d - margin
    val top = if (finger.y < d + margin + 48.dp.toPx()) size.height - d - margin else margin
    val lt = t.magnified(t.toWorld(finger), 2.0, d.toInt())
    translate(left, top) {
        val clip = Path().apply { addOval(Rect(0f, 0f, d, d)) }
        clipPath(clip) {
            drawRect(MapColors.paper, size = androidx.compose.ui.geometry.Size(d, d))
            drawMapLayer(lt, tiles, overlay, strokes, current, Rect(0f, 0f, d, d))
            drawScene(lt, overlay, strokes, current, density)
            val c = Offset(d / 2, d / 2)
            val arm = 10.dp.toPx()
            val w = max(1f, 1.dp.toPx())
            drawLine(Color.Black, c - Offset(arm, 0f), c + Offset(arm, 0f), w)
            drawLine(Color.Black, c - Offset(0f, arm), c + Offset(0f, arm), w)
        }
        drawCircle(Color(0xFF333333), d / 2, Offset(d / 2, d / 2), style = Stroke(width = 2.dp.toPx()))
    }
}
