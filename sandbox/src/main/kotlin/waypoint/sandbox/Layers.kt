package waypoint.sandbox

import waypoint.core.geometry.Box
import waypoint.core.map.GeometryKind
import waypoint.core.map.VectorMap
import waypoint.core.relief.ElevationGrid
import waypoint.core.terrain.TerrainGrid
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Растровый слой, привязанный к местности: левый верхний угол [origin] и [metersPerPixel]. */
class Layer(val image: BufferedImage, val box: Box) {
    val metersPerPixel: Double get() = box.width / image.width
}

object Layers {

    /** Простая отрисовка карты: заливки и линии цветом символа, порядок по приоритету цвета. */
    fun map(map: VectorMap, metersPerPixel: Double): Layer {
        val box = map.bounds
        val w = (box.width / metersPerPixel).toInt() + 1
        val h = (box.height / metersPerPixel).toInt() + 1
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        val k = 1.0 / metersPerPixel
        fun px(x: Double) = (x - box.minX) * k
        fun py(y: Double) = (y - box.minY) * k

        // Больший приоритет цвета в OMAP рисуется ниже; при равенстве площади под линиями.
        val sorted = map.features.sortedWith(
            compareByDescending<waypoint.core.map.MapFeature> { it.style.order }.thenBy { it.kind != GeometryKind.AREA },
        )
        for (f in sorted) {
            g.color = Color(f.style.argb, true)
            when (f.kind) {
                GeometryKind.AREA -> {
                    val path = Path2D.Double(Path2D.WIND_EVEN_ODD)
                    for (ring in f.parts) {
                        if (ring.size < 3) continue
                        path.moveTo(px(ring[0].x), py(ring[0].y))
                        for (i in 1 until ring.size) path.lineTo(px(ring[i].x), py(ring[i].y))
                        path.closePath()
                    }
                    g.fill(path)
                }
                GeometryKind.LINE -> {
                    g.stroke = BasicStroke(max(1f, (f.style.lineWidth * k).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    for (line in f.parts) {
                        if (line.size < 2) continue
                        val path = Path2D.Double()
                        path.moveTo(px(line[0].x), py(line[0].y))
                        for (i in 1 until line.size) path.lineTo(px(line[i].x), py(line[i].y))
                        g.draw(path)
                    }
                }
                GeometryKind.POINT -> {
                    val p = f.parts.firstOrNull()?.firstOrNull() ?: continue
                    val r = 2.0 * k
                    g.fill(Ellipse2D.Double(px(p.x) - r, py(p.y) - r, 2 * r, 2 * r))
                }
            }
        }
        g.dispose()
        return Layer(img, Box(box.minX, box.minY, box.minX + w * metersPerPixel, box.minY + h * metersPerPixel))
    }

    /** Проходимость: чёрный — непроходимо, от тёмно-зелёного к белому — лес, оранжевый — быстрее леса. */
    fun terrain(t: TerrainGrid): Layer {
        val s = t.spec
        val img = BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until s.size) {
            val v = t.speed[i]
            val rgb = when {
                v <= 0f -> 0x000000
                v <= 1f -> {
                    val a = sqrt(v.toDouble())
                    val r = (40 + 215 * a).toInt()
                    val gg = (90 + 165 * a).toInt()
                    val b = (40 + 215 * a).toInt()
                    (r shl 16) or (gg shl 8) or b
                }
                else -> {
                    val a = min(1.0, (v - 1.0) / 0.3)
                    val r = 255
                    val gg = (255 - 120 * a).toInt()
                    val b = (255 - 230 * a).toInt()
                    (r shl 16) or (gg shl 8) or b
                }
            }
            img.setRGB(s.col(i), s.row(i), rgb)
        }
        return Layer(img, Box(s.originX, s.originY, s.originX + s.width * s.cellSize, s.originY + s.height * s.cellSize))
    }

    /** Отмывка рельефа (свет с северо-запада) поверх раскраски по высоте. */
    fun hillshade(e: ElevationGrid): Layer {
        val s = e.spec
        val h = e.heights
        val img = BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_RGB)
        val lo = h.min()
        val hi = h.max()
        for (r in 0 until s.height) for (c in 0 until s.width) {
            val i = s.index(c, r)
            val dzdx = (h[s.index(min(c + 1, s.width - 1), r)] - h[s.index(max(c - 1, 0), r)]) / (2 * s.cellSize)
            val dzdy = (h[s.index(c, min(r + 1, s.height - 1))] - h[s.index(c, max(r - 1, 0))]) / (2 * s.cellSize)
            // Нормаль (−dz/dx, −dz/dy, 1); свет с северо-запада и сверху.
            val nx = -dzdx * 3
            val ny = -dzdy * 3
            val len = sqrt(nx * nx + ny * ny + 1.0)
            val shade = ((-nx * 0.5 - ny * 0.5 + 0.7) / len).coerceIn(0.0, 1.0)
            val t = if (hi > lo) ((h[i] - lo) / (hi - lo)).toDouble() else 0.5
            val base = Color.HSBtoRGB((0.33 - 0.28 * t).toFloat(), 0.45f, 1f)
            val rr = (((base shr 16) and 0xFF) * shade).toInt()
            val gg = (((base shr 8) and 0xFF) * shade).toInt()
            val bb = ((base and 0xFF) * shade).toInt()
            img.setRGB(c, r, (rr shl 16) or (gg shl 8) or bb)
        }
        return Layer(img, Box(s.originX, s.originY, s.originX + s.width * s.cellSize, s.originY + s.height * s.cellSize))
    }
}
