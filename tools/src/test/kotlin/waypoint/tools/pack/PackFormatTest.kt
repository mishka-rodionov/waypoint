package waypoint.tools.pack

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.geometry.simplifyPolyline
import waypoint.core.grid.GridSpec
import waypoint.core.pack.HeightsCodec
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackBox
import waypoint.core.pack.PackGrid
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackLegs
import waypoint.core.pack.PackMeta
import waypoint.core.pack.PackRoute
import waypoint.core.pack.TerrainCodec
import waypoint.core.pack.TileLevel
import waypoint.core.pack.TileSet
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.geo.GroundImage
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackFormatTest {
    private val spec = GridSpec(-120.0, 40.0, 2.0, 37, 23)

    private fun terrain(values: FloatArray, rnd: Random = Random(7)) =
        TerrainGrid(spec, FloatArray(spec.size) { values[rnd.nextInt(values.size)] })

    @Test
    fun `проходимость с палитрой читается точно`() {
        val grid = terrain(floatArrayOf(0f, 0.15f, 0.4f, 0.7f * 0.6f, 1f, 1.15f, 1.3f))
        val back = TerrainCodec.decode(TerrainCodec.encode(grid), spec)
        assertContentEquals(grid.speed, back.speed)
    }

    @Test
    fun `больше 256 скоростей квантуются с малой ошибкой`() {
        val rnd = Random(3)
        val grid = TerrainGrid(spec, FloatArray(spec.size) { rnd.nextFloat() * 1.3f })
        val back = TerrainCodec.decode(TerrainCodec.encode(grid), spec)
        val max = grid.speed.max()
        for (i in grid.speed.indices) assertTrue(abs(grid.speed[i] - back.speed[i]) <= max / 255f / 2 + 1e-6f)
    }

    @Test
    fun `высоты читаются с точностью до сантиметра`() {
        val rnd = Random(5)
        val h = FloatArray(spec.size) { 140f + rnd.nextFloat() * 90f }
        val back = HeightsCodec.decode(HeightsCodec.encode(h, spec.width, spec.height), spec)
        for (i in h.indices) assertEquals(h[i], back[i], 0.0051f)
        // Большой перепад: шаг крупнее, ошибка не больше половины шага.
        val big = FloatArray(spec.size) { -50f + rnd.nextFloat() * 2000f }
        val backBig = HeightsCodec.decode(HeightsCodec.encode(big, spec.width, spec.height), spec)
        for (i in big.indices) assertEquals(big[i], backBig[i], 2050f / 65535f)
    }

    @Test
    fun `чужой размер сетки не читается`() {
        val bytes = TerrainCodec.encode(terrain(floatArrayOf(1f)))
        val other = spec.copy(width = spec.width + 1)
        assertTrue(runCatching { TerrainCodec.decode(bytes, other) }.isFailure)
    }

    @Test
    fun `пак записался — прочитался`() {
        val dir = Files.createTempDirectory("pack").toFile()
        try {
            val grid = terrain(floatArrayOf(0f, 0.5f, 1f, 1.2f))
            val heights = FloatArray(spec.size) { 100f + it * 0.013f }
            val meta = PackMeta(
                name = "test", title = "Тест",
                mapBounds = PackBox(-100.0, 50.0, -60.0, 80.0),
                grid = PackGrid.of(spec),
                tiles = TileSet(PackBox(-120.0, 40.0, -40.0, 90.0), 256, listOf(TileLevel(0, 600, 300, 0.63), TileLevel(1, 300, 150, 1.26))),
                runnability = "ISOM 2000", maxRelativeSpeed = 1.3,
            )
            val route = PackRoute.of(201.234, 612.0, 14.5, listOf(Vec2(-100.0, 50.0), Vec2(-80.123, 60.456), Vec2(-60.0, 80.0)))
            val legs = PackLegs(
                "test",
                listOf(
                    PackLeg("mined-1", "Связка 1", -100.0, 50.0, -60.0, 80.0, interest = 0.8, choiceGap = 0.06, options = listOf(route), straight = null),
                    PackLeg("course-A-1", "A: S → 1", -90.0, 55.0, -70.0, 75.0, fromStart = false),
                ),
            )
            PackFiles.write(dir, meta, legs, grid, heights)
            val back = PackFiles.read(dir)
            assertEquals(meta, back.meta)
            assertEquals(legs, back.legs)
            assertEquals(spec, back.spec)
            assertContentEquals(grid.speed, back.terrain.speed)
            for (i in heights.indices) assertEquals(heights[i], back.heights[i], 0.0051f)
            assertEquals(Vec2(-80.12, 60.46), back.legs.legs[0].options[0].points()[1])
            assertTrue(MapPack.decodeMeta(File(dir, MapPack.META).readText()).tiles.levels.size == 2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `фон режется на тайлы по уровням`() {
        val dir = Files.createTempDirectory("tiles").toFile()
        try {
            val img = BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 300) for (x in 0 until 600) img.setRGB(x, y, if ((x / 10 + y / 10) % 2 == 0) 0xFFFFFF else 0x000000)
            val set = TileWriter.write(GroundImage(img, Box(0.0, 0.0, 600 * 0.5, 300 * 0.5)), dir, tileSize = 256, stopAt = 200)
            // 600×300 → 300×150 → 150×75: последний уровень влез в 200 пикселей.
            assertEquals(listOf(600 to 300, 300 to 150, 150 to 75), set.levels.map { it.width to it.height })
            assertEquals(listOf(0.5, 1.0, 2.0), set.levels.map { it.pixelSize })
            val l0 = set.levels[0]
            assertEquals(3, l0.cols(256))
            assertEquals(2, l0.rows(256))
            val corner = ImageIO.read(File(dir, MapPack.tilePath(0, 2, 1)))
            assertEquals(600 - 512, corner.width)
            assertEquals(300 - 256, corner.height)
            assertTrue(File(dir, MapPack.tilePath(2, 0, 0)).exists())
            // Шахматка 10×10 при уменьшении вдвое остаётся чёрно-белой (клетка 5×5 пикселей).
            val l1 = ImageIO.read(File(dir, MapPack.tilePath(1, 0, 0)))
            assertEquals(0xFFFFFF, l1.getRGB(1, 1) and 0xFFFFFF)
            assertEquals(0x000000, l1.getRGB(6, 1) and 0xFFFFFF)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `упрощение ломаной убирает точки на прямой`() {
        val pts = (0..20).map { Vec2(it * 2.0, 0.0) } + Vec2(40.0, 10.0)
        assertEquals(listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(40.0, 10.0)), simplifyPolyline(pts, 0.5))
    }
}
