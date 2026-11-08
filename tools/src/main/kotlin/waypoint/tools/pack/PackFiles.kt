package waypoint.tools.pack

import waypoint.core.grid.GridSpec
import waypoint.core.pack.HeightsCodec
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackDiscipline
import waypoint.core.pack.PackLegs
import waypoint.core.pack.PackMeta
import waypoint.core.pack.PackScoreSet
import waypoint.core.pack.TerrainCodec
import waypoint.core.terrain.TerrainGrid
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Данные одной дисциплины пака: растр проходимости, связки, задачи «по выбору». */
class DisciplineData(val info: PackDiscipline, val terrain: TerrainGrid, val legs: PackLegs, val score: PackScoreSet? = null)

/** Пак, прочитанный с диска (без тайлов). */
class LoadedPack(val meta: PackMeta, val disciplines: List<DisciplineData>, val heights: FloatArray) {
    val spec: GridSpec get() = disciplines.first().terrain.spec

    /** Первая дисциплина (кросс у карт из OMAP). */
    val terrain: TerrainGrid get() = disciplines.first().terrain
    val legs: PackLegs get() = disciplines.first().legs

    fun discipline(id: String): DisciplineData? = disciplines.firstOrNull { it.info.id == id }
}

/** Запись и чтение файлов пака (кроме тайлов) в папке; формат — [MapPack]. */
object PackFiles {

    /** Пак с одной дисциплиной — кроссом (как до появления дисциплин). */
    fun write(dir: File, meta: PackMeta, legs: PackLegs, terrain: TerrainGrid, heights: FloatArray) =
        write(dir, meta, listOf(DisciplineData(meta.disciplineList().first(), terrain, legs)), heights)

    fun write(dir: File, meta: PackMeta, disciplines: List<DisciplineData>, heights: FloatArray) {
        val spec = meta.grid.toSpec()
        for (d in disciplines) require(d.terrain.spec == spec) { "Сетка растра «${d.info.id}» не совпадает с метаданными" }
        dir.mkdirs()
        File(dir, MapPack.META).writeText(MapPack.encodeMeta(meta))
        for (d in disciplines) {
            File(dir, d.info.legs).writeText(MapPack.encodeLegs(d.legs))
            writeGz(File(dir, d.info.terrain), TerrainCodec.encode(d.terrain))
            val score = d.score
            val scoreFile = d.info.scoreO
            if (score != null && scoreFile != null) File(dir, scoreFile).writeText(MapPack.encodeScore(score))
        }
        writeGz(File(dir, MapPack.HEIGHTS), HeightsCodec.encode(heights, spec.width, spec.height))
    }

    fun read(dir: File): LoadedPack {
        val meta = MapPack.decodeMeta(File(dir, MapPack.META).readText())
        require(meta.version == MapPack.VERSION) { "Версия пака ${meta.version}, ожидается ${MapPack.VERSION}" }
        val spec = meta.grid.toSpec()
        val disciplines = meta.disciplineList().map { info ->
            DisciplineData(
                info,
                TerrainCodec.decode(readGz(File(dir, info.terrain)), spec),
                MapPack.decodeLegs(File(dir, info.legs).readText()),
                info.scoreO?.let { File(dir, it) }?.takeIf { it.exists() }?.let { MapPack.decodeScore(it.readText()) },
            )
        }
        val heights = HeightsCodec.decode(readGz(File(dir, MapPack.HEIGHTS)), spec)
        return LoadedPack(meta, disciplines, heights)
    }

    private fun writeGz(file: File, bytes: ByteArray) =
        GZIPOutputStream(file.outputStream().buffered(1 shl 16)).use { it.write(bytes) }

    private fun readGz(file: File): ByteArray = GZIPInputStream(file.inputStream().buffered(1 shl 16)).use { it.readBytes() }
}
