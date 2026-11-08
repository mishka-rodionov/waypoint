package waypoint.tools.relief

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Горизонталь из шейпфайла: высота и точки (долгота, широта). */
class LonLatContour(val height: Double, val lon: DoubleArray, val lat: DoubleArray)

/**
 * Чтение горизонталей из ESRI Shapefile (тип PolyLine) с высотой в поле DBF [heightField].
 * Ожидаются координаты WGS84 в градусах, как у горизонталей SRTM, построенных в QGIS.
 */
object ShapefileContours {

    fun read(
        shp: File,
        heightField: String = "ELEV",
        minLon: Double = -180.0, minLat: Double = -90.0,
        maxLon: Double = 180.0, maxLat: Double = 90.0,
    ): List<LonLatContour> {
        val heights = readDbfColumn(File(shp.path.removeSuffix(".shp") + ".dbf"), heightField)
        val result = ArrayList<LonLatContour>()
        RandomAccessFile(shp, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            var pos = 100
            var record = 0
            while (pos + 8 <= buf.limit()) {
                buf.order(ByteOrder.BIG_ENDIAN)
                val contentWords = buf.getInt(pos + 4)
                val content = pos + 8
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val type = buf.getInt(content)
                if (type == 3 || type == 13 || type == 23) {
                    val bMinX = buf.getDouble(content + 4)
                    val bMinY = buf.getDouble(content + 12)
                    val bMaxX = buf.getDouble(content + 20)
                    val bMaxY = buf.getDouble(content + 28)
                    if (bMaxX >= minLon && bMinX <= maxLon && bMaxY >= minLat && bMinY <= maxLat) {
                        val numParts = buf.getInt(content + 36)
                        val numPoints = buf.getInt(content + 40)
                        val partsAt = content + 44
                        val pointsAt = partsAt + numParts * 4
                        for (p in 0 until numParts) {
                            val from = buf.getInt(partsAt + p * 4)
                            val to = if (p + 1 < numParts) buf.getInt(partsAt + (p + 1) * 4) else numPoints
                            val lon = DoubleArray(to - from)
                            val lat = DoubleArray(to - from)
                            for (k in from until to) {
                                lon[k - from] = buf.getDouble(pointsAt + k * 16)
                                lat[k - from] = buf.getDouble(pointsAt + k * 16 + 8)
                            }
                            result.add(LonLatContour(heights[record], lon, lat))
                        }
                    }
                }
                pos = content + contentWords * 2
                record++
            }
        }
        return result
    }

    private fun readDbfColumn(dbf: File, field: String): DoubleArray {
        val bytes = dbf.readBytes()
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        val count = u32(4)
        val headerLen = u16(8)
        val recordLen = u16(10)
        var offset = 1
        var fieldOffset = -1
        var fieldLen = 0
        var at = 32
        while (bytes[at].toInt() != 0x0D) {
            val name = String(bytes, at, 11, Charsets.US_ASCII).trimEnd('\u0000')
            val len = bytes[at + 16].toInt() and 0xFF
            if (name.equals(field, ignoreCase = true)) {
                fieldOffset = offset
                fieldLen = len
            }
            offset += len
            at += 32
        }
        require(fieldOffset > 0) { "В ${dbf.name} нет поля $field" }
        return DoubleArray(count) { i ->
            val start = headerLen + i * recordLen + fieldOffset
            String(bytes, start, fieldLen, Charsets.US_ASCII).trim().toDoubleOrNull() ?: Double.NaN
        }
    }
}
