package waypoint.tools.legs

import waypoint.core.geometry.Vec2
import waypoint.core.route.LegExplorer
import waypoint.core.route.LegReport
import waypoint.core.route.MovementModel
import waypoint.core.terrain.MappedArea
import waypoint.core.terrain.TerrainGrid
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Добыча интересных связок: случайные пары точек в отснятой части карты, разбор каждой ([LegExplorer])
 * в нескольких потоках, отбор лучших по интересности с разнесением по карте.
 */
object LegMining {

    fun mine(
        model: MovementModel,
        mapped: MappedArea,
        candidates: Int,
        count: Int,
        minLen: Double = 250.0,
        maxLen: Double = 800.0,
        seed: Long = 1,
        /** Не ближе этого серединами связок друг к другу, м. */
        spacing: Double = 150.0,
        log: (String) -> Unit = ::println,
    ): Pair<List<LegReport>, List<LegReport>> {
        val pairs = samplePairs(model.terrain, mapped, candidates, minLen, maxLen, Random(seed))
        log("Кандидатов: ${pairs.size}, разбираю…")
        val reports = explore(model, pairs, log).filterNotNull()
        return select(reports, count, spacing) to reports
    }

    /** Разбор пар в нескольких потоках; порядок результатов — как у [pairs]. */
    fun explore(model: MovementModel, pairs: List<Pair<Vec2, Vec2>>, log: (String) -> Unit = ::println): List<LegReport?> {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(threads)
        val explorers = ThreadLocal.withInitial { LegExplorer(model) }
        val done = AtomicInteger()
        val step = maxOf(10, pairs.size / 10)
        try {
            return pairs.map { (a, b) ->
                pool.submit<LegReport?> {
                    explorers.get().explore(a, b).also {
                        val n = done.incrementAndGet()
                        if (n % step == 0) log("  $n/${pairs.size}")
                    }
                }
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Случайные пары проходимых точек в отснятой части; прямая между ними тоже в отснятой части.
     * Концы берутся из списка проходимых ячеек: для велосипеда и лыж проходима только узкая сеть дорог,
     * и точки наугад по карте почти никогда на неё не попадают.
     */
    fun samplePairs(terrain: TerrainGrid, mapped: MappedArea, n: Int, minLen: Double, maxLen: Double, rnd: Random): List<Pair<Vec2, Vec2>> {
        val s = terrain.spec
        val cells = IntArrayBuilder()
        for (i in 0 until s.size) if (terrain.speed[i] > 0f && mapped.contains(s.cellCenter(i))) cells.add(i)
        if (cells.size < 2) return emptyList()
        val out = ArrayList<Pair<Vec2, Vec2>>()
        var attempts = 0
        while (out.size < n && attempts < n * 200) {
            attempts++
            val a = s.cellCenter(cells[rnd.nextInt(cells.size)])
            val c = s.cellCenter(cells[rnd.nextInt(cells.size)])
            val len = a.distanceTo(c)
            if (len < minLen || len > maxLen) continue
            if ((1..9).any { k -> !mapped.contains(a + (c - a) * (k / 10.0)) }) continue
            out.add(a to c)
        }
        return out
    }

    private class IntArrayBuilder {
        private var data = IntArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int) = data[i]
    }

    /** Лучшие по интересности, не ближе [spacing] метров серединами друг к другу. */
    fun select(reports: List<LegReport>, count: Int, spacing: Double = 150.0): List<LegReport> {
        val chosen = ArrayList<LegReport>()
        for (r in reports.sortedByDescending { it.interest }) {
            if (r.interest <= 0.0 || chosen.size >= count) break
            val mid = (r.from + r.to) * 0.5
            if (chosen.any { ((it.from + it.to) * 0.5).distanceTo(mid) < spacing }) continue
            chosen.add(r)
        }
        return chosen
    }
}
