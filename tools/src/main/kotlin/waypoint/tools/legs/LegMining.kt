package waypoint.tools.legs

import waypoint.core.geometry.Vec2
import waypoint.core.route.LegExplorer
import waypoint.core.route.LegReport
import waypoint.core.route.MovementModel
import waypoint.core.terrain.MappedArea
import waypoint.core.terrain.TerrainGrid
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
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
        log: (String) -> Unit = ::println,
    ): Pair<List<LegReport>, List<LegReport>> {
        val pairs = samplePairs(model.terrain, mapped, candidates, minLen, maxLen, Random(seed))
        log("Кандидатов: ${pairs.size}, разбираю…")
        val reports = explore(model, pairs, log).filterNotNull()
        return select(reports, count) to reports
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

    /** Случайные пары проходимых точек в отснятой части; прямая между ними тоже в отснятой части. */
    fun samplePairs(terrain: TerrainGrid, mapped: MappedArea, n: Int, minLen: Double, maxLen: Double, rnd: Random): List<Pair<Vec2, Vec2>> {
        val b = mapped.spec
        val out = ArrayList<Pair<Vec2, Vec2>>()
        fun ok(p: Vec2) = mapped.contains(p) && terrain.speedAt(terrain.spec.indexOf(p)) > 0f
        var attempts = 0
        while (out.size < n && attempts < n * 200) {
            attempts++
            val a = Vec2(b.originX + rnd.nextDouble() * b.width * b.cellSize, b.originY + rnd.nextDouble() * b.height * b.cellSize)
            if (!ok(a)) continue
            val ang = rnd.nextDouble(0.0, 2 * Math.PI)
            val len = rnd.nextDouble(minLen, maxLen)
            val c = Vec2(a.x + len * cos(ang), a.y + len * sin(ang))
            if (!ok(c)) continue
            if ((1..9).any { k -> !mapped.contains(a + (c - a) * (k / 10.0)) }) continue
            out.add(a to c)
        }
        return out
    }

    /** Лучшие по интересности, не ближе 150 м серединами друг к другу. */
    fun select(reports: List<LegReport>, count: Int): List<LegReport> {
        val chosen = ArrayList<LegReport>()
        for (r in reports.sortedByDescending { it.interest }) {
            if (r.interest <= 0.0 || chosen.size >= count) break
            val mid = (r.from + r.to) * 0.5
            if (chosen.any { ((it.from + it.to) * 0.5).distanceTo(mid) < 150.0 }) continue
            chosen.add(r)
        }
        return chosen
    }
}
