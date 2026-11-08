package waypoint.core.terrain

import waypoint.core.geometry.Vec2
import waypoint.core.grid.GridSpec
import waypoint.core.grid.Rasterizer
import waypoint.core.map.GeometryKind
import waypoint.core.map.VectorMap

/**
 * Отснятая часть карты: внутри площадных объектов или рядом с любыми линиями местности.
 * Белое поле за краем карты для движка выглядит как лес, поэтому связки ставим только здесь.
 */
class MappedArea(val spec: GridSpec, val mask: BooleanArray) {

    fun contains(p: Vec2): Boolean {
        val i = spec.indexOf(p)
        return i >= 0 && mask[i]
    }

    companion object {
        fun of(map: VectorMap, cellSize: Double = 10.0, radius: Double = 30.0): MappedArea {
            val spec = GridSpec.covering(map.bounds, cellSize)
            val mask = BooleanArray(spec.size)
            for (f in map.features) {
                // Только местность: рельеф, скалы, вода, растительность, искусственные объекты (100–599).
                val n = f.code.substringBefore('.').toIntOrNull() ?: continue
                if (n !in 100..599) continue
                when (f.kind) {
                    GeometryKind.AREA -> Rasterizer.fillPolygon(f.parts, spec) { mask[it] = true }
                    GeometryKind.LINE -> for (line in f.parts) Rasterizer.strokePolyline(line, radius, spec) { mask[it] = true }
                    GeometryKind.POINT -> {}
                }
            }
            return MappedArea(spec, mask)
        }
    }
}
