package waypoint.tools.pack

import waypoint.core.gen.GeneratorParams
import waypoint.core.gen.MapGenerator
import waypoint.core.pack.PackLeg
import waypoint.core.relief.ElevationGrid
import waypoint.core.route.MovementModel
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.MappedArea
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.legs.LegMining
import waypoint.tools.render.IsomRenderer
import java.io.File
import javax.imageio.ImageIO

/**
 * Пак процедурной карты (этап 3): генерация местности, отрисовка в стиле ISOM, отбор интересных связок.
 * Карта своя, прав третьих лиц на неё нет.
 *
 * Запуск: `./gradlew :tools:generatePack -Pargs="--seed 7"`.
 * Параметры: --seed, --size (сторона, м; по умолчанию 2500), --relief (перепад холмов, м), --green (сдвиг густоты леса),
 * --candidates, --count (связок), --min, --max (длина связки, м), --title, --out, --png (сохранить картинку карты).
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    val seed = opts["seed"]?.toLong() ?: 1L
    val size = opts["size"]?.toDouble() ?: 2500.0
    val name = "gen-$seed"
    val out = opts["out"]?.let(::File) ?: File("app/src/main/assets/packs/$name")
    val started = System.currentTimeMillis()

    val params = GeneratorParams(
        seed = seed, width = size, height = size,
        relief = opts["relief"]?.toDouble() ?: 22.0,
        greenBias = opts["green"]?.toDouble() ?: 0.0,
    )
    val generated = MapGenerator(params).generate()
    val map = generated.map
    println("Карта ${size.toInt()}×${size.toInt()} м, объектов ${map.features.size}: ${System.currentTimeMillis() - started} мс")

    val terrain = TerrainGrid.build(map, ISOM_2000, 2.0)
    val spec = terrain.spec
    val heights = generated.elevation.resample(spec)
    val model = MovementModel(terrain, ElevationGrid(spec, heights), maxRelativeSpeed = ISOM_2000.maxSpeed)
    val mapped = MappedArea.of(map)

    val count = opts["count"]?.toInt() ?: 40
    val (chosen, all) = LegMining.mine(
        model, mapped,
        candidates = opts["candidates"]?.toInt() ?: 500,
        count = count,
        minLen = opts["min"]?.toDouble() ?: 250.0,
        maxLen = opts["max"]?.toDouble() ?: 900.0,
        seed = seed,
    )
    println("Связок с выбором: ${all.count { it.choiceGap != null }} из ${all.size}, отобрано ${chosen.size}")
    val legs = chosen.mapIndexed { i, r ->
        PackAssembler.withReport(
            PackLeg(
                id = "gen-$seed-${i + 1}", title = "Связка ${i + 1}",
                fromX = r.from.x, fromY = r.from.y, toX = r.to.x, toY = r.to.y,
                interest = r.interest, choiceGap = r.choiceGap, straightLoss = r.straightLoss,
            ),
            r,
        )
    }

    val image = IsomRenderer().render(map)
    opts["png"]?.let { ImageIO.write(image.image, "png", File(it)); println("Картинка: $it") }
    PackAssembler.write(out, name, opts["title"] ?: "Лес №$seed", map.bounds, terrain, heights, ISOM_2000, legs, image)
    println("Готово за ${(System.currentTimeMillis() - started) / 1000} с")
}
