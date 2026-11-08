package waypoint.tools.geo

import waypoint.core.geometry.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Привязка карты OMAP: перевод между координатами бумаги (мм, ось y вниз)
 * и проекцией UTM. Формула повторяет Georeferencing::updateTransformation в Mapper.
 */
class Georeferencing(
    val scale: Double,
    val grivationDeg: Double,
    val auxiliaryScaleFactor: Double,
    val utmZone: Int?,
    val projectedRef: Vec2,
    val mapRefMm: Vec2 = Vec2(0.0, 0.0),
    /** Опорная точка в градусах: x — долгота, y — широта. */
    val geographicRef: Vec2? = null,
) {
    private val g = grivationDeg * PI / 180
    private val cosG = cos(g)
    private val sinG = sin(g)

    /** Масштабный множитель сетки UTM в опорной точке, умноженный на вспомогательный (как combined_scale_factor). */
    private val combinedScaleFactor: Double = run {
        val dx = projectedRef.x - 500_000.0
        Utm.K0 * (1 + dx * dx / (2 * 6_381_000.0 * 6_381_000.0)) * auxiliaryScaleFactor
    }
    private val metersPerMm = combinedScaleFactor * scale / 1000.0

    fun mmToProjected(mm: Vec2): Vec2 {
        val x1 = metersPerMm * (mm.x - mapRefMm.x)
        val y1 = -metersPerMm * (mm.y - mapRefMm.y)
        return Vec2(projectedRef.x + cosG * x1 + sinG * y1, projectedRef.y - sinG * x1 + cosG * y1)
    }

    fun projectedToMm(p: Vec2): Vec2 {
        val de = p.x - projectedRef.x
        val dn = p.y - projectedRef.y
        val x1 = cosG * de - sinG * dn
        val y1 = sinG * de + cosG * dn
        return Vec2(x1 / metersPerMm + mapRefMm.x, -y1 / metersPerMm + mapRefMm.y)
    }

    /** Миллиметры бумаги → метры на местности в системе координат карты. */
    fun mmToGround(mm: Vec2): Vec2 = Vec2(mm.x * scale / 1000.0, mm.y * scale / 1000.0)
    fun groundToMm(m: Vec2): Vec2 = Vec2(m.x * 1000.0 / scale, m.y * 1000.0 / scale)

    /** Широта/долгота WGS84 → метры на местности в системе координат карты. */
    fun latLonToGround(lat: Double, lon: Double): Vec2 {
        val zone = requireNotNull(utmZone) { "Карта не привязана к UTM" }
        return mmToGround(projectedToMm(Utm.forward(lat, lon, zone)))
    }
}

/** Прямая проекция WGS84 → UTM (северное полушарие), формулы Снайдера. */
object Utm {
    const val K0 = 0.9996
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)
    private const val EP2 = E2 / (1 - E2)

    fun forward(latDeg: Double, lonDeg: Double, zone: Int): Vec2 {
        val phi = latDeg * PI / 180
        val lon0 = ((zone - 1) * 6 - 180 + 3) * PI / 180
        val lam = lonDeg * PI / 180
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val n = A / sqrt(1 - E2 * sinPhi * sinPhi)
        val t = tan(phi) * tan(phi)
        val c = EP2 * cosPhi * cosPhi
        val a = cosPhi * (lam - lon0)
        val e4 = E2 * E2
        val e6 = e4 * E2
        val m = A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
            (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
            (35 * e6 / 3072) * sin(6 * phi))
        val a2 = a * a
        val x = K0 * n * (a + (1 - t + c) * a2 * a / 6 + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a2 * a2 * a / 120) + 500_000.0
        val y = K0 * (m + n * tan(phi) * (a2 / 2 + (5 - t + 9 * c + 4 * c * c) * a2 * a2 / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a2 * a2 * a2 / 720))
        return Vec2(x, y)
    }
}
