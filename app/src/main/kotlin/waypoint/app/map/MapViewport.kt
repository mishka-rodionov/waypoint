package waypoint.app.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import kotlin.math.min
import kotlin.math.pow
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween

/** Неизменяемый снимок вида: мир (метры) → экран (пиксели). */
data class ViewTransform(val originX: Double, val originY: Double, val scale: Double, val width: Int, val height: Int) {
    fun toScreen(p: Vec2) = Offset(((p.x - originX) * scale).toFloat(), ((p.y - originY) * scale).toFloat())
    fun toScreenX(x: Double) = ((x - originX) * scale).toFloat()
    fun toScreenY(y: Double) = ((y - originY) * scale).toFloat()
    fun toWorld(o: Offset) = Vec2(originX + o.x / scale, originY + o.y / scale)

    /** Видимая часть мира. */
    val world: Box get() = Box(originX, originY, originX + width / scale, originY + height / scale)

    /** Тот же вид, увеличенный в [zoom] раз вокруг точки мира [center], в окне [size]×[size]. */
    fun magnified(center: Vec2, zoom: Double, size: Int): ViewTransform {
        val s = scale * zoom
        return ViewTransform(center.x - size / 2.0 / s, center.y - size / 2.0 / s, s, size, size)
    }
}

/**
 * Вид карты: левый верхний угол экрана в метрах ([originX], [originY]) и масштаб — пикселей на метр.
 * Состояние наблюдаемое: отрисовка, читающая его, повторяется при сдвиге и масштабе.
 */
@Stable
class MapViewport(private val minScale: Double, private val maxScale: Double) {
    var originX by mutableDoubleStateOf(0.0)
        private set
    var originY by mutableDoubleStateOf(0.0)
        private set
    var scale by mutableDoubleStateOf(1.0)
        private set
    var size by mutableStateOf(IntSize.Zero)
        private set

    /** Рамка, которую надо вписать: держится, пока игрок сам не тронет карту (переживает смену размера окна). */
    private var pendingFit: Pair<Box, Float>? = null

    /** Растёт при каждом изменении вида; анимация, увидев чужое изменение, останавливается. */
    private var generation = 0

    val transform: ViewTransform get() = ViewTransform(originX, originY, scale, size.width, size.height)

    fun updateSize(newSize: IntSize) {
        if (newSize == size) return
        val old = size
        size = newSize
        val fit = pendingFit
        if (fit != null) {
            applyFit(fit.first, fit.second)
        } else if (old.width > 0 && old.height > 0) {
            // Центр экрана остаётся на месте.
            originX += (old.width - newSize.width) / 2.0 / scale
            originY += (old.height - newSize.height) / 2.0 / scale
        }
    }

    fun pan(dx: Float, dy: Float) {
        pendingFit = null
        generation++
        originX -= dx / scale
        originY -= dy / scale
    }

    fun zoom(factor: Float, focus: Offset) {
        pendingFit = null
        generation++
        val w = transform.toWorld(focus)
        scale = (scale * factor).coerceIn(minScale, maxScale)
        originX = w.x - focus.x / scale
        originY = w.y - focus.y / scale
    }

    /** Вписать рамку мира [box] с отступом [paddingPx] со всех сторон. */
    fun fit(box: Box, paddingPx: Float) {
        generation++
        pendingFit = box to paddingPx
        if (size.width > 0 && size.height > 0) applyFit(box, paddingPx)
    }

    /**
     * Плавно приблизить до масштаба [target] вокруг точки экрана [focus]: точка мира под ней остаётся на месте.
     * Прерывается любым другим изменением вида (жест, вписывание).
     */
    suspend fun animateZoom(target: Double, focus: Offset, durationMs: Int = 280) {
        val gen = ++generation
        pendingFit = null
        val s0 = scale
        val s1 = target.coerceIn(minScale, maxScale)
        val w = transform.toWorld(focus)
        animate(0f, 1f, animationSpec = tween(durationMs, easing = FastOutSlowInEasing)) { v, _ ->
            if (generation != gen) throw CancellationException("вид изменён")
            val s = s0 * (s1 / s0).pow(v.toDouble())
            scale = s
            originX = w.x - focus.x / s
            originY = w.y - focus.y / s
        }
    }

    /** Плавно сдвинуть содержимое на ([dx], [dy]) пикселей экрана. Прерывается как [animateZoom]. */
    suspend fun animatePan(dx: Float, dy: Float, durationMs: Int = 320) {
        val gen = ++generation
        pendingFit = null
        val x0 = originX
        val y0 = originY
        val x1 = x0 - dx / scale
        val y1 = y0 - dy / scale
        animate(0f, 1f, animationSpec = tween(durationMs, easing = FastOutSlowInEasing)) { v, _ ->
            if (generation != gen) throw CancellationException("вид изменён")
            originX = x0 + (x1 - x0) * v
            originY = y0 + (y1 - y0) * v
        }
    }

    private fun applyFit(box: Box, paddingPx: Float) {
        val w = (size.width - 2 * paddingPx).coerceAtLeast(1f)
        val h = (size.height - 2 * paddingPx).coerceAtLeast(1f)
        scale = min(w / box.width.coerceAtLeast(1.0), h / box.height.coerceAtLeast(1.0)).coerceIn(minScale, maxScale)
        val cx = (box.minX + box.maxX) / 2
        val cy = (box.minY + box.maxY) / 2
        originX = cx - size.width / 2.0 / scale
        originY = cy - size.height / 2.0 / scale
    }
}
