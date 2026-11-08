package waypoint.tools.pack

import waypoint.core.gen.GeneratorParams
import waypoint.core.gen.MapGenerator
import waypoint.core.gen.TownGenerator
import waypoint.core.gen.TownParams
import waypoint.core.terrain.Discipline
import waypoint.tools.render.IsomRenderer
import java.io.File
import javax.imageio.ImageIO

/**
 * Пак процедурной карты (этап 3): генерация местности, отрисовка в стиле ISOM, отбор интересных связок.
 * Карта своя, прав третьих лиц на неё нет.
 *
 * Запуск: `./gradlew :tools:generatePack -Pargs="--seed 7"`.
 * Параметры: --seed, --size (сторона, м; по умолчанию 2500), --relief (перепад холмов, м), --green (сдвиг густоты леса),
 * --candidates, --count (связок), --min, --max (длина связки, м), --title, --out, --png (сохранить картинку карты),
 * --disciplines (forest, mtbo, ski; по умолчанию все), --score (задач «по выбору» на дисциплину),
 * --type sprint — городская карта для спринта (800×800 м, 1:4000).
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    val seed = opts["seed"]?.toLong() ?: 1L
    if (opts["type"] == "sprint") {
        generateSprint(opts, seed)
        return
    }
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

    val disciplines = PackAssembler.parseDisciplines(opts["disciplines"], listOf(Discipline.FOREST, Discipline.MTBO, Discipline.SKI))
    val scoreCount = opts["score"]?.toInt() ?: 3
    val count = opts["count"]?.toInt() ?: 40
    val data = disciplines.map { d ->
        val network = d != Discipline.FOREST
        PackAssembler.discipline(d, map, 2.0, { t -> generated.elevation.resample(t.spec) }, name, scoreCount, seed) { model, _ ->
            PackAssembler.minedLegs(
                model, map, "$name-${d.id}",
                candidates = opts["candidates"]?.toInt() ?: 500,
                count = if (network) count * 3 / 4 else count,
                // По сети дорог связки длиннее: велосипед и лыжи быстрее бега.
                minLen = opts["min"]?.toDouble() ?: if (network) 400.0 else 250.0,
                maxLen = opts["max"]?.toDouble() ?: if (network) 1500.0 else 900.0,
                seed = seed,
            )
        }
    }
    val heights = generated.elevation.resample(data.first().terrain.spec)

    val image = IsomRenderer().render(map)
    opts["png"]?.let { ImageIO.write(image.image, "png", File(it)); println("Картинка: $it") }
    PackAssembler.write(out, name, opts["title"] ?: "Лес №$seed", map.bounds, 7500.0, data, heights, image)
    println("Готово за ${(System.currentTimeMillis() - started) / 1000} с")
}

/**
 * Спринт: город 800×800 м в масштабе 1:4000, связки 100–450 м, задачи «по выбору» в радиусе 300 м.
 * Запуск: `./gradlew :tools:generatePack -Pargs="--type sprint --seed 3"`.
 */
private fun generateSprint(opts: Map<String, String>, seed: Long) {
    val started = System.currentTimeMillis()
    val size = opts["size"]?.toDouble() ?: 800.0
    val name = "sprint-$seed"
    val out = opts["out"]?.let(::File) ?: File("app/src/main/assets/packs/$name")
    val generated = TownGenerator(TownParams(seed = seed, width = size, height = size)).generate()
    val map = generated.map
    println("Город ${size.toInt()}×${size.toInt()} м, объектов ${map.features.size}")
    val d = Discipline.SPRINT
    // Сетка 1 м: проходы между домами 4–8 м должны быть проходимы и на растре.
    val cell = 1.0
    val data = PackAssembler.discipline(d, map, cell, { t -> generated.elevation.resample(t.spec) }, name, opts["score"]?.toInt() ?: 3, seed, scoreRadius = 300.0) { model, _ ->
        PackAssembler.minedLegs(
            model, map, "$name-${d.id}",
            candidates = opts["candidates"]?.toInt() ?: 500,
            count = opts["count"]?.toInt() ?: 40,
            minLen = opts["min"]?.toDouble() ?: 100.0,
            maxLen = opts["max"]?.toDouble() ?: 450.0,
            seed = seed, spacing = 60.0,
        )
    }
    val heights = generated.elevation.resample(data.terrain.spec)
    // 1:4000 при 300 dpi — 0,339 м в пикселе.
    val image = IsomRenderer(scale = 4000.0).render(map, metersPerPixel = 0.339)
    opts["png"]?.let { ImageIO.write(image.image, "png", File(it)); println("Картинка: $it") }
    PackAssembler.write(out, name, opts["title"] ?: "Город №$seed", map.bounds, 4000.0, listOf(data), heights, image)
    println("Готово за ${(System.currentTimeMillis() - started) / 1000} с")
}
