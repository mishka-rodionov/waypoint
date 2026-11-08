package waypoint.tools.render

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.map.GeometryKind
import waypoint.core.map.MapFeature
import waypoint.core.map.VectorMap
import waypoint.tools.geo.GroundImage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max

/**
 * Отрисовка карты в стиле ISOM 2000 (для сгенерированных карт, у которых нет экспорта Mapper):
 * заливки, штриховка болот, пунктир троп и ручьёв, горизонтали, линии магнитного севера.
 * Толщины и штрихи — в миллиметрах карты масштаба 1:[scale].
 */
class IsomRenderer(private val scale: Double = 7500.0) {
    private val mm = scale / 1000.0 // метров местности в миллиметре карты

    /** [metersPerPixel] 0.635 — как экспорт Mapper при 300 dpi и 1:7500. */
    fun render(map: VectorMap, metersPerPixel: Double = 0.635, frame: Box = map.bounds): GroundImage {
        val w = ceil(frame.width / metersPerPixel).toInt()
        val h = ceil(frame.height / metersPerPixel).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        val k = 1.0 / metersPerPixel
        val px = { v: Vec2 -> (v.x - frame.minX) * k to (v.y - frame.minY) * k }
        fun mmPx(x: Double) = (x * mm * k).toFloat()

        fun path(parts: List<List<Vec2>>, closed: Boolean): Shape {
            val p = Path2D.Double(Path2D.WIND_EVEN_ODD)
            for (part in parts) {
                if (part.size < 2) continue
                val (x0, y0) = px(part[0])
                p.moveTo(x0, y0)
                for (i in 1 until part.size) {
                    val (x, y) = px(part[i])
                    p.lineTo(x, y)
                }
                if (closed) p.closePath()
            }
            return p
        }

        fun stroke(widthMm: Double, dashMm: DoubleArray? = null): BasicStroke =
            if (dashMm == null) BasicStroke(max(0.8f, mmPx(widthMm)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            else BasicStroke(max(0.8f, mmPx(widthMm)), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 4f, dashMm.map { mmPx(it) }.toFloatArray(), 0f)

        // Площади: больший порядок ниже (жёлтое под зелёным, болота выше растительности).
        // Озёра — после горизонталей: по ISOM вода перекрывает коричневый.
        val allAreas = map.features.filter { it.kind == GeometryKind.AREA }.sortedByDescending { it.style.order }
        val water = allAreas.filter { it.code.substringBefore('.') in setOf("301", "302") }
        val areas = allAreas - water.toSet()
        fun drawArea(f: MapFeature) {
            val shape = path(f.parts, closed = true)
            when (val code = f.code.substringBefore('.')) {
                "310", "311" -> hatch(g, shape, if (code == "310") 0.18 else 0.12, if (code == "310") 0.75 else 1.6, ::mmPx)
                "526" -> {
                    g.color = Color(0x8C8C8C)
                    g.fill(shape)
                    g.color = Color.BLACK
                    g.stroke = stroke(0.14)
                    g.draw(shape)
                }
                "301", "302" -> {
                    g.color = Color(0x7FD2FF)
                    g.fill(shape)
                    g.color = Color.BLACK
                    g.stroke = stroke(0.18)
                    g.draw(shape)
                }
                else -> {
                    g.color = Color(f.style.argb, true)
                    g.fill(shape)
                }
            }
        }
        areas.forEach(::drawArea)
        // Линии магнитного севера: синие, каждые 250 м.
        g.color = Color(0x0096E6)
        g.stroke = stroke(0.18)
        var x = frame.minX + 125
        while (x < frame.maxX) {
            val sx = (x - frame.minX) * k
            g.draw(java.awt.geom.Line2D.Double(sx, 0.0, sx, h.toDouble()))
            x += 250
        }
        // Линии: горизонтали, затем вода (озёра поверх горизонталей), затем ручьи и тропы.
        val lines = map.features.filter { it.kind == GeometryKind.LINE }.sortedByDescending { it.style.order }
        fun drawLine(f: MapFeature) {
            val (color, st) = lineStyle(f, ::stroke) ?: return
            g.color = color
            g.stroke = st
            g.draw(path(f.parts, closed = false))
        }
        lines.filter { it.style.order >= 15 }.forEach(::drawLine)
        water.forEach(::drawArea)
        lines.filter { it.style.order < 15 }.forEach(::drawLine)
        g.dispose()
        return GroundImage(img, Box(frame.minX, frame.minY, frame.minX + w * metersPerPixel, frame.minY + h * metersPerPixel))
    }

    private fun lineStyle(f: MapFeature, stroke: (Double, DoubleArray?) -> BasicStroke): Pair<Color, BasicStroke>? {
        val brown = Color(0xC8701E)
        val blue = Color(0x0096E6)
        return when (f.code.substringBefore('.')) {
            "101" -> brown to stroke(0.14, null)
            "102" -> brown to stroke(0.25, null)
            "103" -> brown to stroke(0.14, doubleArrayOf(2.5, 0.25))
            "305" -> blue to stroke(0.25, null)
            "306" -> blue to stroke(0.18, doubleArrayOf(2.0, 0.25))
            "524" -> Color.BLACK to stroke(0.5, null)
            "504" -> Color.BLACK to stroke(0.35, null)
            "505" -> Color.BLACK to stroke(0.35, doubleArrayOf(3.0, 0.25))
            "506" -> Color.BLACK to stroke(0.25, doubleArrayOf(2.0, 0.25))
            "507" -> Color.BLACK to stroke(0.18, doubleArrayOf(1.0, 0.25))
            "508" -> Color.BLACK to stroke(0.18, doubleArrayOf(0.6, 0.6))
            else -> if (f.style.lineWidth > 0) Color(f.style.argb, true) to stroke(f.style.lineWidth / mm, null) else null
        }
    }

    /** Болото: горизонтальная синяя штриховка внутри контура. */
    private fun hatch(g: Graphics2D, shape: Shape, widthMm: Double, stepMm: Double, mmPx: (Double) -> Float) {
        val old = g.clip
        g.clip(shape)
        g.color = Color(0x0096E6)
        g.stroke = BasicStroke(max(0.8f, mmPx(widthMm)))
        val b = shape.bounds2D
        val step = max(1.5f, mmPx(stepMm)).toDouble()
        var y = b.minY - (b.minY % step)
        while (y <= b.maxY) {
            g.draw(java.awt.geom.Line2D.Double(b.minX, y, b.maxX, y))
            y += step
        }
        g.clip = old
    }
}
