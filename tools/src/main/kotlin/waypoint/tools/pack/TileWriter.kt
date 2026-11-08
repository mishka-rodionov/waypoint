package waypoint.tools.pack

import waypoint.core.pack.MapPack
import waypoint.core.pack.PackBox
import waypoint.core.pack.TileLevel
import waypoint.core.pack.TileSet
import waypoint.tools.geo.GroundImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Нарезка фона карты на тайлы: уровень 0 — исходная картинка, каждый следующий вдвое меньше,
 * пока картинка целиком не влезет в [stopAt] пикселей по большей стороне.
 * Тайлы на краю не дополняются: их размер меньше [tileSize].
 */
object TileWriter {

    fun write(image: GroundImage, dir: File, tileSize: Int = 256, stopAt: Int = 512, log: (String) -> Unit = {}): TileSet {
        val levels = ArrayList<TileLevel>()
        var img = image.image
        val basePixel = image.box.width / img.width
        var level = 0
        while (true) {
            val pixel = basePixel * (1 shl level)
            val info = TileLevel(level, img.width, img.height, pixel)
            levels.add(info)
            var count = 0
            for (row in 0 until info.rows(tileSize)) for (col in 0 until info.cols(tileSize)) {
                val x = col * tileSize
                val y = row * tileSize
                val w = min(tileSize, img.width - x)
                val h = min(tileSize, img.height - y)
                val tile = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                tile.createGraphics().apply {
                    drawImage(img, 0, 0, w, h, x, y, x + w, y + h, null)
                    dispose()
                }
                val f = File(dir, MapPack.tilePath(level, col, row))
                f.parentFile.mkdirs()
                ImageIO.write(tile, "png", f)
                count++
            }
            log("  уровень $level: ${img.width}×${img.height}, ${"%.2f".format(pixel)} м/пикс, $count тайлов")
            if (max(img.width, img.height) <= stopAt) break
            img = half(img)
            level++
        }
        return TileSet(PackBox.of(image.box), tileSize, levels)
    }

    /** Уменьшение вдвое: среднее квадрата 2×2 (на нечётном краю пиксель повторяется). */
    private fun half(src: BufferedImage): BufferedImage {
        val sw = src.width
        val sh = src.height
        val w = ceil(sw / 2.0).toInt()
        val h = ceil(sh / 2.0).toInt()
        val px = src.getRGB(0, 0, sw, sh, null, 0, sw)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = 2 * y
            val y1 = min(y0 + 1, sh - 1)
            for (x in 0 until w) {
                val x0 = 2 * x
                val x1 = min(x0 + 1, sw - 1)
                val a = px[y0 * sw + x0]
                val b = px[y0 * sw + x1]
                val c = px[y1 * sw + x0]
                val d = px[y1 * sw + x1]
                fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((b shr shift) and 0xFF) + ((c shr shift) and 0xFF) + ((d shr shift) and 0xFF) + 2) / 4
                out[y * w + x] = (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, out, 0, w)
        return img
    }
}
