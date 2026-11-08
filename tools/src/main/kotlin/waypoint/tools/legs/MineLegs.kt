package waypoint.tools.legs

import waypoint.core.geometry.Vec2
import waypoint.core.route.LegExplorer
import waypoint.core.route.LegReport
import waypoint.core.route.MovementModel
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.MappedArea
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.MapLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Добыча интересных связок на карте: случайные пары точек в отснятой части,
 * разбор каждой ([LegExplorer]), отбор лучших по интересности с разнесением по карте.
 *
 * Запуск: `./gradlew :tools:mineLegs -Pargs="--candidates 400 --count 40"`.
 * Параметры: --map, --contours, --interval, --candidates, --count, --min, --max (длина, м), --seed, --out.
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    val mapFile = opts["map"]?.let(::File) ?: File("maps").listFiles { f -> f.extension == "omap" }?.firstOrNull()
        ?: error("Нет карты: --map путь.omap или положите её в maps/")
    val contours = opts["contours"]?.let(::File) ?: File("maps").listFiles { f -> f.extension == "shp" }?.firstOrNull()
    val candidates = opts["candidates"]?.toInt() ?: 400
    val count = opts["count"]?.toInt() ?: 40
    val minLen = opts["min"]?.toDouble() ?: 250.0
    val maxLen = opts["max"]?.toDouble() ?: 800.0
    val seed = opts["seed"]?.toLong() ?: 1L
    val out = opts["out"]?.let(::File) ?: File(mapFile.parentFile, mapFile.nameWithoutExtension + ".legs.json")

    val loaded = MapLoader.load(mapFile, contours, opts["interval"]?.toDouble() ?: 2.5, log = ::println)
    val terrain = TerrainGrid.build(loaded.map, ISOM_2000, 2.0)
    val model = MovementModel(terrain, loaded.elevation, maxRelativeSpeed = ISOM_2000.maxSpeed)
    val mapped = MappedArea.of(loaded.map)

    val pairs = samplePairs(terrain, mapped, candidates, minLen, maxLen, Random(seed))
    println("Кандидатов: ${pairs.size}, разбираю…")
    val started = System.currentTimeMillis()
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val pool = Executors.newFixedThreadPool(threads)
    val explorers = ThreadLocal.withInitial { LegExplorer(model) }
    val done = AtomicInteger()
    val reports = pairs.map { (a, b) ->
        pool.submit<LegReport?> {
            explorers.get().explore(a, b).also {
                val n = done.incrementAndGet()
                if (n % 50 == 0) println("  $n/${pairs.size}")
            }
        }
    }.mapNotNull { it.get() }
    pool.shutdown()
    println("Разбор: ${(System.currentTimeMillis() - started) / 1000} с, $threads потоков")

    val chosen = select(reports, count)
    val legs = chosen.map { r ->
        MinedLeg(r.from, r.to, r.interest, r.choiceGap, r.straightLoss, r.options.map { it.seconds })
    }
    LegsFile.write(out, loaded.name, legs)
    val withChoice = reports.count { it.choiceGap != null }
    println("С альтернативами: $withChoice из ${reports.size}; интересность > 0.5: ${reports.count { it.interest > 0.5 }}")
    for (l in legs.take(10)) {
        println("  интерес %.2f, разрыв %s, прямая %s, варианты %s".format(
            l.interest, l.choiceGap?.let { "%+.1f%%".format(it * 100) } ?: "—",
            l.straightLoss?.let { "%+.1f%%".format(it * 100) } ?: "—",
            l.optionSeconds.joinToString { "%.0f с".format(it) },
        ))
    }
    println("Записано ${legs.size} связок: $out")
}

/** Случайные пары проходимых точек в отснятой части; прямая между ними тоже в отснятой части. */
private fun samplePairs(terrain: TerrainGrid, mapped: MappedArea, n: Int, minLen: Double, maxLen: Double, rnd: Random): List<Pair<Vec2, Vec2>> {
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
private fun select(reports: List<LegReport>, count: Int): List<LegReport> {
    val chosen = ArrayList<LegReport>()
    for (r in reports.sortedByDescending { it.interest }) {
        if (r.interest <= 0.0 || chosen.size >= count) break
        val mid = (r.from + r.to) * 0.5
        if (chosen.any { ((it.from + it.to) * 0.5).distanceTo(mid) < 150.0 }) continue
        chosen.add(r)
    }
    return chosen
}
