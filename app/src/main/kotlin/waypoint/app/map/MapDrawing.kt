package waypoint.app.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import waypoint.core.geometry.Vec2
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object MapColors {
    val paper = Color(0xFFF2F0EA)
    /** Надпечатка дистанции (ISOM: пурпурный). */
    val course = Color(0xFFC0188F)
    val drawn = Color(0xFFE61414)
    val optimal = Color(0xFF1E5AFF)
    val intended = Color(0xFF00A03C)
    val variant = Color(0xFFE08000)
}


/**
 * Фон карты тайлами. Уровень — самый грубый, у которого пиксель тайла не крупнее пикселя экрана;
 * незагруженный тайл временно закрывается куском более грубого уровня из памяти.
 */
fun DrawScope.drawTiles(t: ViewTransform, cache: TileCache, blendMode: BlendMode = BlendMode.SrcOver) {
    cache.version // подписка на загрузку тайлов
    val set = cache.tiles
    val ts = set.tileSize
    val base = set.levels.first().pixelSize
    val metersPerPx = 1.0 / t.scale
    val wanted = floor(ln(metersPerPx / base) / ln(2.0)).toInt().coerceIn(0, cache.coarsest)
    val level = set.levels[wanted]
    val box = set.box
    val world = t.world
    val tileMeters = ts * level.pixelSize
    val c0 = max(0, floor((world.minX - box.minX) / tileMeters).toInt())
    val r0 = max(0, floor((world.minY - box.minY) / tileMeters).toInt())
    val c1 = min(level.cols(ts) - 1, floor((world.maxX - box.minX) / tileMeters).toInt())
    val r1 = min(level.rows(ts) - 1, floor((world.maxY - box.minY) / tileMeters).toInt())
    for (r in r0..r1) for (c in c0..c1) {
        val w = min(ts, level.width - c * ts)
        val h = min(ts, level.height - r * ts)
        val left = t.toScreenX(box.minX + c * tileMeters).roundToInt()
        val top = t.toScreenY(box.minY + r * tileMeters).roundToInt()
        val right = t.toScreenX(box.minX + c * tileMeters + w * level.pixelSize).roundToInt()
        val bottom = t.toScreenY(box.minY + r * tileMeters + h * level.pixelSize).roundToInt()
        val dstOffset = IntOffset(left, top)
        val dstSize = IntSize(right - left, bottom - top)
        val img = cache.get(wanted, c, r)
        if (img != null) {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), dstOffset, dstSize, filterQuality = FilterQuality.Low, blendMode = blendMode)
            continue
        }
        // Подмена грубым уровнем: тот же кусок местности.
        for (k in 1..cache.coarsest - wanted) {
            val px = (c * ts) shr k
            val py = (r * ts) shr k
            val parent = cache.peek(wanted + k, px / ts, py / ts) ?: continue
            val sx = px % ts
            val sy = py % ts
            val sw = max(1, min(w shr k, parent.width - sx))
            val sh = max(1, min(h shr k, parent.height - sy))
            drawImage(parent, IntOffset(sx, sy), IntSize(sw, sh), dstOffset, dstSize, filterQuality = FilterQuality.Low, blendMode = blendMode)
            break
        }
    }
}

/** Ломаная мира на экране. [dash] — длина штриха в пикселях (0 — сплошная). */
fun DrawScope.drawWorldLine(t: ViewTransform, points: List<Vec2>, color: Color, width: Float, dash: Float = 0f, alpha: Float = 1f) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, p ->
        val o = t.toScreen(p)
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    drawPath(
        path, color, alpha = alpha,
        style = Stroke(
            width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
            pathEffect = if (dash > 0f) PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.7f)) else null,
        ),
    )
}

/**
 * Надпечатка связки по ISOM: треугольник старта (сторона 7 мм), круг КП (6 мм), линия между ними с разрывом у знаков.
 * Размеры — в метрах местности ([metersPerMm] — метров в миллиметре карты: 7.5 при 1:7500), но не мельче [minPx] на экране.
 */
fun DrawScope.drawCourse(t: ViewTransform, from: Vec2, to: Vec2, fromStart: Boolean, minPx: Float, metersPerMm: Double = 7.5) {
    val px = { mm: Double -> max(minPx, (mm * metersPerMm * t.scale).toFloat()) }
    val lineW = max(minPx / 6f, (0.35 * metersPerMm * t.scale).toFloat())
    val stroke = Stroke(width = lineW)
    val a = t.toScreen(from)
    val b = t.toScreen(to)
    val circleR = px(3.0)
    val triR = px(7.0 / sqrt(3.0)) // радиус описанной окружности треугольника
    val fromR = if (fromStart) triR else circleR
    if (fromStart) {
        // Вершина треугольника смотрит на КП.
        val d = (b - a).let { it / max(1f, it.getDistance()) }
        val n = Offset(-d.y, d.x)
        val tip = a + d * triR
        val back = a - d * (triR / 2)
        val half = triR * sqrt(3f) / 2
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(back.x + n.x * half, back.y + n.y * half)
            lineTo(back.x - n.x * half, back.y - n.y * half)
            close()
        }
        drawPath(path, MapColors.course, style = stroke)
    } else {
        drawCircle(MapColors.course, fromR, a, style = stroke)
    }
    drawCircle(MapColors.course, circleR, b, style = stroke)
    val len = (b - a).getDistance()
    if (len > fromR + circleR) {
        val d = (b - a) / len
        drawLine(MapColors.course, a + d * (fromR * 1.1f), b - d * (circleR * 1.1f), lineW, alpha = 0.85f)
    }
}

/**
 * Карта только в полосе вдоль линий [lines] (радиус [radiusM] метров на местности), остальное — бумага.
 * Режим «память»: под нарисованной частью пути карта проявляется. Маска — линии в отдельном слое,
 * тайлы ложатся поверх с [BlendMode.SrcIn], то есть только туда, где маска уже есть.
 */
fun DrawScope.drawRevealedTiles(t: ViewTransform, cache: TileCache, lines: List<List<Vec2>>, radiusM: Double, bounds: Rect) {
    val mask = Path()
    var any = false
    for (line in lines) {
        if (line.isEmpty()) continue
        line.forEachIndexed { i, p ->
            val o = t.toScreen(p)
            if (i == 0) mask.moveTo(o.x, o.y) else mask.lineTo(o.x, o.y)
        }
        if (line.size == 1) t.toScreen(line[0]).let { mask.lineTo(it.x + 0.01f, it.y) }
        any = true
    }
    if (!any) return
    drawContext.canvas.saveLayer(bounds, Paint())
    drawPath(mask, Color.Black, style = Stroke(width = (2 * radiusM * t.scale).toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawTiles(t, cache, BlendMode.SrcIn)
    drawContext.canvas.restore()
}

/** Знаки «по выбору»: треугольник старта и двойной круг финиша в одной точке, круги КП (номера подписывает экран). */
fun DrawScope.drawScoreCourse(t: ViewTransform, start: Vec2, controls: List<Vec2>, selected: Set<Int>, minPx: Float, metersPerMm: Double) {
    val px = { mm: Double -> max(minPx, (mm * metersPerMm * t.scale).toFloat()) }
    val lineW = max(minPx / 6f, (0.35 * metersPerMm * t.scale).toFloat())
    val stroke = Stroke(width = lineW)
    val s = t.toScreen(start)
    val triR = px(7.0 / sqrt(3.0))
    val path = Path().apply {
        moveTo(s.x, s.y - triR)
        lineTo(s.x + triR * sqrt(3f) / 2, s.y + triR / 2)
        lineTo(s.x - triR * sqrt(3f) / 2, s.y + triR / 2)
        close()
    }
    drawPath(path, MapColors.course, style = stroke)
    drawCircle(MapColors.course, px(3.5), s, style = stroke)
    drawCircle(MapColors.course, px(2.5), s, style = stroke)
    controls.forEachIndexed { i, c ->
        val o = t.toScreen(c)
        val r = px(3.0)
        if ((i + 1) in selected) drawCircle(MapColors.course.copy(alpha = 0.22f), r, o)
        drawCircle(MapColors.course, r, o, style = stroke)
    }
}
