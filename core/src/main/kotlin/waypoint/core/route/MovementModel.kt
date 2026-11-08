package waypoint.core.route

import waypoint.core.grid.GridSpec
import waypoint.core.relief.ElevationGrid
import waypoint.core.terrain.TerrainGrid
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Во сколько раз уклон замедляет движение по сравнению с ровным местом. */
fun interface SlopeModel {
    fun factor(grade: Double): Double

    companion object {
        /**
         * Функция Тоблера, нормированная на ровное место: быстрее всего лёгкий спуск (−5%),
         * подъём и крутой спуск замедляют. [steepness] 3.5 — исходный коэффициент Тоблера.
         */
        fun tobler(steepness: Double = 3.5) = SlopeModel { g -> exp(steepness * (abs(g + 0.05) - 0.05)) }

        val FLAT = SlopeModel { 1.0 }
    }
}

/**
 * Модель движения бегуна по растру: скорость по местности, рельеф и базовый темп.
 * [baseSpeed] — скорость в м/с по белому лесу на ровном месте.
 */
class MovementModel(
    val terrain: TerrainGrid,
    val elevation: ElevationGrid?,
    val slope: SlopeModel = SlopeModel.tobler(),
    val baseSpeed: Double = 1000.0 / (6.0 * 60), // 6:00 мин/км
    val maxRelativeSpeed: Double,
) {
    val spec: GridSpec get() = terrain.spec

    /** Высоты в центрах ячеек растра проходимости. */
    val heights: FloatArray? = elevation?.resample(terrain.spec)

    /** Нижняя граница времени на метр пути: для допустимой эвристики A*. */
    companion object {
        const val MAX_GRADE = 1.0
    }

    val minSecondsPerMeter: Double = run {
        var minFactor = 1.0
        var g = -1.0
        while (g <= 1.0) {
            val f = slope.factor(g)
            if (f < minFactor) minFactor = f
            g += 0.005
        }
        minFactor / (baseSpeed * maxRelativeSpeed)
    }

    /** Уклон ограничен ±100%: круче — это обрыв, его задаёт проходимость, а не рельеф. */
    fun gradeFactor(dh: Double, horizontal: Double): Double =
        if (horizontal <= 0.0) 1.0 else slope.factor((dh / horizontal).coerceIn(-MAX_GRADE, MAX_GRADE))

    /** Время пути по последовательности ячеек, соседних по одному из ходов [Move.ALL]. */
    fun timeOf(cells: IntArray): Double {
        var t = 0.0
        for (i in 1 until cells.size) {
            val dc = spec.col(cells[i]) - spec.col(cells[i - 1])
            val dr = spec.row(cells[i]) - spec.row(cells[i - 1])
            val move = Move.ALL.first { it.dc == dc && it.dr == dr }
            t += moveTime(cells[i - 1], move)
        }
        return t
    }

    /**
     * Время перехода из ячейки [from] по ходу [move] или NaN, если путь перекрыт.
     * Время по местности — сумма долей длины хода по пересекаемым ячейкам.
     */
    fun moveTime(from: Int, move: Move): Double {
        val s = spec
        val c = s.col(from)
        val r = s.row(from)
        val tc = c + move.dc
        val tr = r + move.dr
        if (!s.contains(tc, tr)) return Double.NaN
        val length = move.length * s.cellSize
        var t = 0.0
        for (k in move.cellDc.indices) {
            val cc = c + move.cellDc[k]
            val rr = r + move.cellDr[k]
            val v = terrain.speed[s.index(cc, rr)]
            if (v <= 0f) return Double.NaN
            if (move.fraction[k] > 0.0) t += move.fraction[k] * length / (baseSpeed * v)
        }
        val h = heights ?: return t
        val to = s.index(tc, tr)
        return t * gradeFactor((h[to] - h[from]).toDouble(), length)
    }
}

/**
 * Ход по сетке: смещение и ячейки, которые он пересекает, с долями длины.
 * Ячейки с долей 0 — «боковые» для диагонали: должны быть проходимы, чтобы не срезать угол стены.
 */
class Move(
    val dc: Int,
    val dr: Int,
    val cellDc: IntArray,
    val cellDr: IntArray,
    val fraction: DoubleArray,
) {
    val length: Double = sqrt((dc * dc + dr * dr).toDouble())

    companion object {
        /** 16 направлений: 4 прямых, 4 диагонали, 8 «ходов конём» — путь получается почти без ломаных зигзагов. */
        val ALL: List<Move> = buildList {
            for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                add(Move(dc, dr, intArrayOf(0, dc), intArrayOf(0, dr), doubleArrayOf(0.5, 0.5)))
            }
            for (sc in intArrayOf(-1, 1)) for (sr in intArrayOf(-1, 1)) {
                add(Move(sc, sr, intArrayOf(0, sc, sc, 0), intArrayOf(0, sr, 0, sr), doubleArrayOf(0.5, 0.5, 0.0, 0.0)))
            }
            for (sc in intArrayOf(-1, 1)) for (sr in intArrayOf(-1, 1)) {
                add(Move(sc, 2 * sr, intArrayOf(0, 0, sc, sc), intArrayOf(0, sr, sr, 2 * sr), doubleArrayOf(0.25, 0.25, 0.25, 0.25)))
                add(Move(2 * sc, sr, intArrayOf(0, sc, sc, 2 * sc), intArrayOf(0, 0, sr, sr), doubleArrayOf(0.25, 0.25, 0.25, 0.25)))
            }
        }
    }
}
