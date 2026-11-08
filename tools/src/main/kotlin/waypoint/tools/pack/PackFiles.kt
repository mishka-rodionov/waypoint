package waypoint.tools.pack

import waypoint.core.grid.GridSpec
import waypoint.core.pack.HeightsCodec
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackLegs
import waypoint.core.pack.PackMeta
import waypoint.core.pack.TerrainCodec
import waypoint.core.terrain.TerrainGrid
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Пак, прочитанный с диска (без тайлов). */
class LoadedPack(val meta: PackMeta, val legs: PackLegs, val terrain: TerrainGrid, val heights: FloatArray) {
    val spec: GridSpec get() = terrain.spec
}

/** Запись и чтение файлов пака (кроме тайлов) в папке; формат — [MapPack]. */
object PackFiles {

    fun write(dir: File, meta: PackMeta, legs: PackLegs, terrain: TerrainGrid, heights: FloatArray) {
        require(terrain.spec == meta.grid.toSpec()) { "Сетка растра не совпадает с метаданными" }
        dir.mkdirs()
        File(dir, MapPack.META).writeText(MapPack.encodeMeta(meta))
        File(dir, MapPack.LEGS).writeText(MapPack.encodeLegs(legs))
        writeGz(File(dir, MapPack.TERRAIN), TerrainCodec.encode(terrain))
        writeGz(File(dir, MapPack.HEIGHTS), HeightsCodec.encode(heights, terrain.spec.width, terrain.spec.height))
    }

    fun read(dir: File): LoadedPack {
        val meta = MapPack.decodeMeta(File(dir, MapPack.META).readText())
        require(meta.version == MapPack.VERSION) { "Версия пака ${meta.version}, ожидается ${MapPack.VERSION}" }
        val spec = meta.grid.toSpec()
        val legs = MapPack.decodeLegs(File(dir, MapPack.LEGS).readText())
        val terrain = TerrainCodec.decode(readGz(File(dir, MapPack.TERRAIN)), spec)
        val heights = HeightsCodec.decode(readGz(File(dir, MapPack.HEIGHTS)), spec)
        return LoadedPack(meta, legs, terrain, heights)
    }

    private fun writeGz(file: File, bytes: ByteArray) =
        GZIPOutputStream(file.outputStream().buffered(1 shl 16)).use { it.write(bytes) }

    private fun readGz(file: File): ByteArray = GZIPInputStream(file.inputStream().buffered(1 shl 16)).use { it.readBytes() }
}
