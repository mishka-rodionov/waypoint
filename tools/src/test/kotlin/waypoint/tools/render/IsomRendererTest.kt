package waypoint.tools.render

import waypoint.core.gen.GeneratorParams
import waypoint.core.gen.MapGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsomRendererTest {
    @Test
    fun `сгенерированная карта рисуется в стиле ISOM`() {
        val gen = MapGenerator(GeneratorParams(seed = 3, width = 600.0, height = 600.0)).generate()
        val img = IsomRenderer().render(gen.map, metersPerPixel = 1.0)
        val b = gen.map.bounds
        assertEquals(kotlin.math.ceil(b.width).toInt(), img.image.width)
        // Есть коричневые горизонтали и белый лес.
        var brown = 0
        var white = 0
        for (y in 0 until img.image.height step 3) for (x in 0 until img.image.width step 3) {
            val rgb = img.image.getRGB(x, y) and 0xFFFFFF
            if (rgb == 0xFFFFFF) white++
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val bl = rgb and 0xFF
            if (r > 150 && g in 80..150 && bl < 90) brown++
        }
        assertTrue(brown > 50, "горизонтали: $brown")
        assertTrue(white > 1000, "белый лес: $white")
    }
}
