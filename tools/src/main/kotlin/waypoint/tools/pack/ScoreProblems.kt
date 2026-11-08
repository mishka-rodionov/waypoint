package waypoint.tools.pack

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.pack.PackRoute
import waypoint.core.pack.PackScoreControl
import waypoint.core.pack.PackScoreProblem
import waypoint.core.pack.PackScoreSet
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteEvaluator
import waypoint.core.route.ScoreO
import waypoint.core.terrain.MappedArea
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Задачи «по выбору»: старт (он же финиш), [controls] КП вокруг него с очками по удалённости,
 * матрица оптимальных времён между всеми точками (A* в обе стороны: уклон делает её несимметричной)
 * и лимит — доля [limitShare] от времени обхода всех КП, чтобы пришлось выбирать.
 */
object ScoreProblems {

    fun build(
        model: MovementModel,
        mapped: MappedArea,
        bounds: Box,
        count: Int,
        seed: Long,
        controls: Int = 10,
        radius: Double = 800.0,
        limitShare: Double = 0.6,
        idPrefix: String = "score",
        log: (String) -> Unit = ::println,
    ): PackScoreSet {
        val rnd = Random(seed)
        val problems = ArrayList<PackScoreProblem>()
        var attempts = 0
        while (problems.size < count && attempts < count * 20) {
            attempts++
            val p = tryBuild(model, mapped, bounds, rnd, controls, radius, limitShare, "$idPrefix-${problems.size + 1}", "Задача ${problems.size + 1}") ?: continue
            problems.add(p)
            log("  задача ${problems.size}: ${p.controls.size} КП, лимит ${(p.limitSeconds / 60).roundToInt()} мин")
        }
        return PackScoreSet(problems)
    }

    private fun tryBuild(
        model: MovementModel, mapped: MappedArea, bounds: Box, rnd: Random,
        n: Int, radius: Double, limitShare: Double, id: String, title: String,
    ): PackScoreProblem? {
        val terrain = model.terrain
        fun ok(p: Vec2) = mapped.contains(p) && terrain.speedAt(terrain.spec.indexOf(p)) > 0f
        val inner = bounds.expand(-radius * 0.6)
        if (inner.width <= 0 || inner.height <= 0) return null
        val start = (0 until 200).map { Vec2(inner.minX + rnd.nextDouble() * inner.width, inner.minY + rnd.nextDouble() * inner.height) }.firstOrNull(::ok) ?: return null
        val pts = ArrayList<Vec2>()
        var tries = 0
        while (pts.size < n && tries < 2000) {
            tries++
            val ang = rnd.nextDouble(0.0, 2 * Math.PI)
            val d = 150 + rnd.nextDouble() * (radius - 150)
            val c = Vec2(start.x + d * cos(ang), start.y + d * sin(ang))
            if (!ok(c) || pts.any { it.distanceTo(c) < 120 }) continue
            pts.add(c)
        }
        if (pts.size < n) return null
        val all = listOf(start) + pts
        val size = all.size
        // Матрица оптимальных путей: каждая пара в обе стороны, в несколько потоков.
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
        val evaluators = ThreadLocal.withInitial { RouteEvaluator(model) }
        val routes = try {
            (0 until size * size).map { k ->
                val i = k / size
                val j = k % size
                pool.submit<waypoint.core.route.RouteStats?> { if (i == j) null else evaluators.get().optimal(all[i], all[j]) }
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        if ((0 until size * size).any { k -> k / size != k % size && routes[k] == null }) return null
        val seconds = (0 until size * size).map { routes[it]?.seconds ?: 0.0 }
        val time = { a: Int, b: Int -> seconds[a * size + b] }
        val points = IntArray(size) { i ->
            if (i == 0) 0 else {
                val d = start.distanceTo(all[i])
                when {
                    d < radius * 0.45 -> 10
                    d < radius * 0.75 -> 20
                    else -> 30
                }
            }
        }
        val full = ScoreO.allControlsTime(n, time)
        val limit = (full * limitShare / 60).roundToInt().coerceAtLeast(1) * 60.0
        return PackScoreProblem(
            id = id, title = title, startX = start.x, startY = start.y, limitSeconds = limit,
            controls = pts.mapIndexed { i, c -> PackScoreControl("${31 + i}", c.x, c.y, points[i + 1]) },
            seconds = seconds.map { (it * 100).roundToInt() / 100.0 },
            paths = routes.map { r -> r?.let { PackAssembler.route(it, tolerance = 1.5) } },
        )
    }
}
