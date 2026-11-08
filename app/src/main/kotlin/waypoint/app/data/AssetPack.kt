package waypoint.app.data

import android.content.res.AssetManager
import waypoint.core.pack.HeightsCodec
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackDiscipline
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackMeta
import waypoint.core.pack.PackScoreProblem
import waypoint.core.pack.TerrainCodec
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.MovementModel
import waypoint.core.terrain.Discipline
import java.util.zip.GZIPInputStream

/**
 * Пак карты из assets/packs/<имя>/: метаданные, связки и задачи «по выбору» всех дисциплин читаются сразу,
 * растры — отдельно ([loadHeights], [loadModel]): это секунды и десятки мегабайт.
 */
class AssetPack(
    val dir: String,
    val meta: PackMeta,
    val legs: Map<String, List<PackLeg>>,
    val scoreProblems: Map<String, List<PackScoreProblem>>,
) {
    val name: String get() = dir.substringAfterLast('/')
    val disciplines: List<PackDiscipline> get() = meta.disciplineList()

    /** Высоты на сетке пака — общие для всех дисциплин. */
    fun loadHeights(assets: AssetManager): FloatArray = HeightsCodec.decode(readGz(assets, "$dir/${MapPack.HEIGHTS}"), meta.grid.toSpec())

    /** Модель движения дисциплины [id] по её растру проходимости и общим высотам. */
    fun loadModel(assets: AssetManager, id: String, heights: FloatArray): MovementModel {
        val spec = meta.grid.toSpec()
        val info = disciplines.first { it.id == id }
        val terrain = TerrainCodec.decode(readGz(assets, "$dir/${info.terrain}"), spec)
        // Высоты уже на сетке проходимости: MovementModel возьмёт массив без пересчёта.
        return (Discipline.byId(id) ?: Discipline.FOREST).model(terrain, ElevationGrid(spec, heights))
    }

    companion object {
        const val ROOT = "packs"

        /** Папки паков в assets (с meta.json). */
        fun list(assets: AssetManager): List<String> =
            assets.list(ROOT).orEmpty().sorted().filter { name -> assets.list("$ROOT/$name").orEmpty().contains(MapPack.META) }

        fun open(assets: AssetManager, name: String): AssetPack {
            val dir = "$ROOT/$name"
            val meta = MapPack.decodeMeta(readText(assets, "$dir/${MapPack.META}"))
            require(meta.version == MapPack.VERSION) { "Версия пака ${meta.version}, приложение понимает ${MapPack.VERSION}" }
            val legs = meta.disciplineList().associate { d -> d.id to MapPack.decodeLegs(readText(assets, "$dir/${d.legs}")).legs }
            val score = meta.disciplineList().associate { d ->
                d.id to (d.scoreO?.let { MapPack.decodeScore(readText(assets, "$dir/$it")).problems } ?: emptyList())
            }
            return AssetPack(dir, meta, legs, score)
        }

        private fun readText(assets: AssetManager, path: String) = assets.open(path).use { it.readBytes().decodeToString() }

        private fun readGz(assets: AssetManager, path: String): ByteArray =
            GZIPInputStream(assets.open(path), 1 shl 16).use { it.readBytes() }
    }
}
