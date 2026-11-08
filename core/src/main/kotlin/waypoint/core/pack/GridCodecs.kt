package waypoint.core.pack

import waypoint.core.grid.GridSpec
import waypoint.core.terrain.TerrainGrid
import kotlin.math.roundToInt

/**
 * Растр проходимости в байтах: `WPT1`, ширина, высота, размер палитры, палитра (float),
 * затем по байту на ячейку — номер скорости в палитре.
 *
 * Скорости — произведения значений из таблицы проходимости, разных обычно несколько десятков,
 * поэтому палитра точная. Если разных значений больше 256, они квантуются на 256 уровней.
 */
object TerrainCodec {
    private const val MAGIC = 0x57505431 // WPT1

    fun encode(grid: TerrainGrid): ByteArray {
        val speed = grid.speed
        val distinct = speed.toHashSet()
        val palette: FloatArray = if (distinct.size <= 256) {
            distinct.sorted().toFloatArray()
        } else {
            val max = distinct.max()
            FloatArray(256) { it * max / 255f }
        }
        val lookup = HashMap<Float, Int>(palette.size * 2)
        palette.forEachIndexed { i, v -> lookup[v] = i }
        val step = if (palette.size == 256 && distinct.size > 256) palette[255] / 255f else 0f
        val w = ByteWriter(16 + palette.size * 4 + speed.size)
        w.int(MAGIC)
        w.int(grid.spec.width)
        w.int(grid.spec.height)
        w.int(palette.size)
        for (v in palette) w.float(v)
        for (v in speed) {
            val idx = lookup[v] ?: (v / step).roundToInt().coerceIn(0, 255)
            w.byte(idx)
        }
        return w.bytes()
    }

    fun decode(bytes: ByteArray, spec: GridSpec): TerrainGrid {
        val r = ByteReader(bytes)
        require(r.int() == MAGIC) { "Не растр проходимости пака" }
        val width = r.int()
        val height = r.int()
        require(width == spec.width && height == spec.height) { "Размер растра $width×$height не совпадает с сеткой ${spec.width}×${spec.height}" }
        val palette = FloatArray(r.int()) { r.float() }
        val speed = FloatArray(width * height) { palette[r.ubyte()] }
        return TerrainGrid(spec, speed)
    }
}

/**
 * Высоты в байтах: `WPH1`, ширина, высота, база и шаг (float), затем по 16-битному числу на ячейку.
 * Число q — высота в шагах от базы: высота = база + шаг × q. Шаг — сантиметр, если перепад высот меньше 655 м, иначе крупнее.
 * Записывается не q, а разность с соседом слева (по модулю 2¹⁶; у первой ячейки строки — с ячейкой над ней):
 * поверхность гладкая, разности малы, и gzip сжимает их в разы лучше.
 */
object HeightsCodec {
    private const val MAGIC = 0x57504831 // WPH1

    fun encode(heights: FloatArray, width: Int, height: Int): ByteArray {
        require(heights.size == width * height)
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (h in heights) {
            if (h < min) min = h
            if (h > max) max = h
        }
        if (heights.isEmpty()) { min = 0f; max = 0f }
        val step = maxOf(0.01f, (max - min) / 65535f)
        val w = ByteWriter(20 + heights.size * 2)
        w.int(MAGIC)
        w.int(width)
        w.int(height)
        w.float(min)
        w.float(step)
        // Разности считаются на лету: помним только предыдущую ячейку и первую ячейку прошлой строки.
        var left = 0
        var rowStart = 0
        for (i in heights.indices) {
            val q = ((heights[i] - min) / step).roundToInt().coerceIn(0, 65535)
            val prev = if (i % width == 0) rowStart else left
            w.short((q - prev) and 0xFFFF)
            if (i % width == 0) rowStart = q
            left = q
        }
        return w.bytes()
    }

    /** Высоты в центрах ячеек сетки [spec]. */
    fun decode(bytes: ByteArray, spec: GridSpec): FloatArray {
        val r = ByteReader(bytes)
        require(r.int() == MAGIC) { "Не растр высот пака" }
        val width = r.int()
        val height = r.int()
        require(width == spec.width && height == spec.height) { "Размер высот $width×$height не совпадает с сеткой ${spec.width}×${spec.height}" }
        val base = r.float()
        val step = r.float()
        val out = FloatArray(width * height)
        var left = 0
        var rowStart = 0
        for (i in out.indices) {
            val prev = if (i % width == 0) rowStart else left
            val q = (prev + r.ushort()) and 0xFFFF
            if (i % width == 0) rowStart = q
            left = q
            out[i] = base + step * q
        }
        return out
    }
}

/** Запись чисел в big-endian без java.nio. */
internal class ByteWriter(capacity: Int) {
    private var buf = ByteArray(capacity.coerceAtLeast(16))
    private var pos = 0

    private fun ensure(n: Int) {
        if (pos + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, pos + n))
    }

    fun byte(v: Int) {
        ensure(1)
        buf[pos++] = v.toByte()
    }

    fun short(v: Int) {
        ensure(2)
        buf[pos++] = (v ushr 8).toByte()
        buf[pos++] = v.toByte()
    }

    fun int(v: Int) {
        ensure(4)
        buf[pos++] = (v ushr 24).toByte()
        buf[pos++] = (v ushr 16).toByte()
        buf[pos++] = (v ushr 8).toByte()
        buf[pos++] = v.toByte()
    }

    fun float(v: Float) = int(v.toRawBits())

    fun bytes(): ByteArray = buf.copyOf(pos)
}

internal class ByteReader(private val buf: ByteArray) {
    private var pos = 0

    fun ubyte(): Int = buf[pos++].toInt() and 0xFF

    fun ushort(): Int = (ubyte() shl 8) or ubyte()

    fun int(): Int = (ubyte() shl 24) or (ubyte() shl 16) or (ubyte() shl 8) or ubyte()

    fun float(): Float = Float.fromBits(int())
}
