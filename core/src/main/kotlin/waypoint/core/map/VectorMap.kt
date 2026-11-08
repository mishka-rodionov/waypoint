package waypoint.core.map

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2

enum class GeometryKind { POINT, LINE, AREA }

/**
 * Как нарисовать объект. Цвет в ARGB, ширина линии в метрах на местности.
 * [order] задаёт порядок отрисовки: больше значит ниже (как приоритет цвета в OMAP).
 */
data class FeatureStyle(
    val argb: Int,
    val lineWidth: Double = 0.0,
    val order: Int = 0,
)

/**
 * Объект карты. [code] — номер символа (например, "406" или "506.1").
 * Для AREA [parts] — внешний контур и дырки (заливка по правилу even-odd),
 * для LINE — одна или несколько ломаных, для POINT — одна точка.
 */
class MapFeature(
    val code: String,
    val kind: GeometryKind,
    val parts: List<List<Vec2>>,
    val style: FeatureStyle,
    val name: String = "",
)

/** Карта в метрах на местности, независимая от исходного формата. */
class VectorMap(
    val features: List<MapFeature>,
) {
    val bounds: Box = Box.of(features.asSequence().flatMap { f -> f.parts.asSequence().flatten() }.asIterable())
}
