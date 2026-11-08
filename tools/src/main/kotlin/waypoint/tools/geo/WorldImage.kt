package waypoint.tools.geo

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/** Растр, выровненный по осям системы координат карты: [box] в метрах на местности. */
class GroundImage(val image: BufferedImage, val box: Box)

/**
 * Картинка с world-файлом (`.pgw`/`.pngw`/`.wld`) в координатах проекции карты,
 * например экспорт карты из Mapper. Переводится в метры на местности карты;
 * если оси картинки не совпадают с осями карты, картинка пересчитывается с поворотом.
 */
object WorldImage {

    fun worldFileFor(image: File): File? =
        listOf("pgw", "pngw", "wld").map { File(image.parentFile, image.nameWithoutExtension + "." + it) }.firstOrNull { it.exists() }

    fun read(image: File, georef: Georeferencing): GroundImage {
        val world = requireNotNull(worldFileFor(image)) { "Нет world-файла для ${image.name}" }
        val w = world.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() }
        require(w.size >= 6) { "В ${world.name} должно быть 6 чисел" }
        val (a, d, b, e, c, f) = w

        // Непрерывные координаты картинки (u, v): пиксель (col, row) занимает [col, col+1].
        // World-файл задаёт центр левого верхнего пикселя.
        fun ground(u: Double, v: Double): Vec2 {
            val x = a * (u - 0.5) + b * (v - 0.5) + c
            val y = d * (u - 0.5) + e * (v - 0.5) + f
            return georef.mmToGround(georef.projectedToMm(Vec2(x, y)))
        }
        val o = ground(0.0, 0.0)
        val ux = ground(1.0, 0.0) - o
        val vy = ground(0.0, 1.0) - o

        val src = ImageIO.read(image) ?: error("Не удалось прочитать ${image.name}")
        val axisAligned = abs(ux.y) < 1e-3 * abs(ux.x) && abs(vy.x) < 1e-3 * abs(vy.y) && ux.x > 0 && vy.y > 0
        if (axisAligned) {
            val box = Box(o.x, o.y, o.x + ux.x * src.width, o.y + vy.y * src.height)
            return GroundImage(onWhite(src, src.width, src.height, AffineTransform()), box)
        }

        // Общий случай: пересчитываем в картинку, выровненную по осям карты, с тем же размером пикселя.
        val corners = listOf(o, ground(src.width.toDouble(), 0.0), ground(0.0, src.height.toDouble()), ground(src.width.toDouble(), src.height.toDouble()))
        val box = Box.of(corners)
        val mpp = hypot(ux.x, ux.y)
        val outW = ceil(box.width / mpp).toInt()
        val outH = ceil(box.height / mpp).toInt()
        // Пиксели картинки → пиксели результата.
        val t = AffineTransform(ux.x / mpp, ux.y / mpp, vy.x / mpp, vy.y / mpp, (o.x - box.minX) / mpp, (o.y - box.minY) / mpp)
        return GroundImage(onWhite(src, outW, outH, t), Box(box.minX, box.minY, box.minX + outW * mpp, box.minY + outH * mpp))
    }

    /** Прозрачные места экспорта — белая бумага. */
    private fun onWhite(src: BufferedImage, w: Int, h: Int, t: AffineTransform): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, t, null)
        g.dispose()
        return out
    }

    private operator fun List<Double>.component6() = this[5]
}
