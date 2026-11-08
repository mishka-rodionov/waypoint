package waypoint.core.terrain

import waypoint.core.geometry.Box
import waypoint.core.grid.GridSpec
import waypoint.core.grid.Rasterizer
import waypoint.core.map.GeometryKind
import waypoint.core.map.VectorMap
import kotlin.math.max

/** Растр относительной скорости: 0 — непроходимо, 1 — белый лес. */
class TerrainGrid(val spec: GridSpec, val speed: FloatArray) {
    init {
        require(speed.size == spec.size)
    }

    fun speedAt(index: Int): Float = if (index < 0) 0f else speed[index]

    companion object {
        /**
         * Строит растр проходимости. Правила наложения:
         * площади — самая медленная из перекрывающихся (или [RunnabilityTable.defaultSpeed]),
         * затем препятствия и мягкие запреты, затем дороги (быстрее местности), затем жёсткие запреты.
         */
        fun build(map: VectorMap, table: RunnabilityTable, cellSize: Double, bounds: Box = map.bounds): TerrainGrid {
            val spec = GridSpec.covering(bounds, cellSize)
            val n = spec.size
            val area = FloatArray(n) { Float.NaN }
            val obstacle = FloatArray(n) { 1f }
            val path = FloatArray(n)
            val soft = BooleanArray(n)
            val hard = BooleanArray(n)

            for (f in map.features) {
                val effect = table.effectOf(f.code) ?: continue
                when (effect) {
                    is TerrainEffect.Area -> if (f.kind == GeometryKind.AREA) {
                        val s = effect.speed.toFloat()
                        Rasterizer.fillPolygon(f.parts, spec) { i ->
                            val cur = area[i]
                            if (cur.isNaN() || s < cur) area[i] = s
                        }
                    }

                    is TerrainEffect.Path -> if (f.kind == GeometryKind.LINE) {
                        val s = effect.speed.toFloat()
                        for (line in f.parts) Rasterizer.strokePolyline(line, effect.width / 2, spec) { i ->
                            if (s > path[i]) path[i] = s
                        }
                    }

                    is TerrainEffect.Obstacle -> {
                        val k = effect.factor.toFloat()
                        val mark: (Int) -> Unit = { i -> if (k < obstacle[i]) obstacle[i] = k }
                        when (f.kind) {
                            GeometryKind.LINE -> for (line in f.parts) Rasterizer.strokePolyline(line, effect.width / 2, spec, mark)
                            GeometryKind.AREA -> Rasterizer.fillPolygon(f.parts, spec, mark)
                            GeometryKind.POINT -> {}
                        }
                    }

                    is TerrainEffect.Forbidden -> {
                        val target = if (effect.hard) hard else soft
                        val mark: (Int) -> Unit = { i -> target[i] = true }
                        when (f.kind) {
                            GeometryKind.LINE -> for (line in f.parts) Rasterizer.strokePolyline(line, effect.width / 2, spec, mark)
                            GeometryKind.AREA -> Rasterizer.fillPolygon(f.parts, spec, mark)
                            GeometryKind.POINT -> {}
                        }
                    }
                }
            }

            val default = table.defaultSpeed.toFloat()
            val speed = FloatArray(n)
            for (i in 0 until n) {
                if (hard[i]) continue
                val base = if (soft[i]) 0f else (if (area[i].isNaN()) default else area[i]) * obstacle[i]
                speed[i] = max(base, path[i])
            }
            return TerrainGrid(spec, speed)
        }
    }
}
