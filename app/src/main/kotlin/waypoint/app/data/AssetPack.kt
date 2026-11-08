package waypoint.app.data

import android.content.res.AssetManager
import waypoint.core.pack.HeightsCodec
import waypoint.core.pack.MapPack
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackMeta
import waypoint.core.pack.TerrainCodec
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.MovementModel
import java.util.zip.GZIPInputStream

/** Пак карты из assets/packs/<имя>/: метаданные и связки читаются сразу, растры — отдельно ([loadModel]). */
class AssetPack(val dir: String, val meta: PackMeta, val legs: List<PackLeg>) {

    /** Модель движения по растрам пака. Долго (секунды) и много памяти: только в фоне. */
    fun loadModel(assets: AssetManager): MovementModel {
        val spec = meta.grid.toSpec()
        val terrain = TerrainCodec.decode(readGz(assets, "$dir/${MapPack.TERRAIN}"), spec)
        val heights = HeightsCodec.decode(readGz(assets, "$dir/${MapPack.HEIGHTS}"), spec)
        // Высоты уже на сетке проходимости: MovementModel возьмёт массив без пересчёта.
        return MovementModel(terrain, ElevationGrid(spec, heights), maxRelativeSpeed = meta.maxRelativeSpeed)
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
            val legs = MapPack.decodeLegs(readText(assets, "$dir/${MapPack.LEGS}")).legs
            return AssetPack(dir, meta, legs)
        }

        private fun readText(assets: AssetManager, path: String) = assets.open(path).use { it.readBytes().decodeToString() }

        private fun readGz(assets: AssetManager, path: String): ByteArray =
            GZIPInputStream(assets.open(path), 1 shl 16).use { it.readBytes() }
    }
}
