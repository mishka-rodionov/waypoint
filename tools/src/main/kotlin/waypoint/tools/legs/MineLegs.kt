package waypoint.tools.legs

import waypoint.core.route.MovementModel
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.MappedArea
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.MapLoader
import java.io.File

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

    val started = System.currentTimeMillis()
    val (chosen, reports) = LegMining.mine(model, mapped, candidates, count, minLen, maxLen, seed)
    println("Разбор: ${(System.currentTimeMillis() - started) / 1000} с")
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

