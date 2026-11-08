package waypoint.core.terrain

import waypoint.core.relief.ElevationGrid
import waypoint.core.route.MovementModel
import waypoint.core.route.SlopeModel

/**
 * Дисциплина: таблица проходимости, базовая скорость и чувствительность к уклону.
 * Движок оценки один для всех; MTBO и лыжи — «граф» дорог и троп на растре: вне сети скорость 0.
 */
data class Discipline(
    val id: String,
    val title: String,
    val table: RunnabilityTable,
    /** Скорость, м/с, при относительной скорости 1 на ровном месте. */
    val baseSpeed: Double,
    /** Коэффициент функции Тоблера: больше — сильнее тормозит подъём. */
    val slopeSteepness: Double,
) {
    fun model(terrain: TerrainGrid, elevation: ElevationGrid?) = MovementModel(
        terrain, elevation,
        slope = SlopeModel.tobler(slopeSteepness),
        baseSpeed = baseSpeed,
        maxRelativeSpeed = table.maxSpeed,
    )

    companion object {
        /** Заданное направление (кросс): бег по любой местности, 6:00 мин/км по белому лесу. */
        val FOREST = Discipline("forest", "Кросс", ISOM_2000, 1000.0 / 360, 3.5)

        /** Велоориентирование: только дороги и тропы, 18 км/ч по хорошей дороге, подъёмы тяжелее, чем бегом. */
        val MTBO = Discipline("mtbo", "Вело", MTBO_ISOM, 5.0, 6.0)

        /** Лыжи: сеть лыжней (в прототипе — дороги и тропы карты), 12 км/ч по широкой лыжне. */
        val SKI = Discipline("ski", "Лыжи", SKI_ISOM, 3.33, 5.0)

        /** Спринт: городская карта, 4:00 мин/км по асфальту. */
        val SPRINT = Discipline("sprint", "Спринт", ISOM_2000, 1000.0 / 240 / 1.3, 3.5)

        val ALL = listOf(FOREST, MTBO, SKI, SPRINT)

        fun byId(id: String): Discipline? = ALL.firstOrNull { it.id == id }
    }
}

/**
 * Проходимость для велосипеда по ISOM 2000: двигаться можно только по дорогам и тропам
 * (ширина коридора на растре 5 м, чтобы сеть была связной для ходов по диагонали).
 * Мосты над водой — дорога поверх мягкого запрета. Скорости — доля от хорошей дороги.
 */
val MTBO_ISOM = RunnabilityTable(
    name = "MTBO (ISOM 2000)",
    entries = mapOf(
        "501" to TerrainEffect.Path(1.0, 6.0),
        "502" to TerrainEffect.Path(1.0, 6.0),
        "503" to TerrainEffect.Path(1.0, 6.0),
        "504" to TerrainEffect.Path(0.9, 5.0),
        "505" to TerrainEffect.Path(0.8, 5.0),
        "506" to TerrainEffect.Path(0.65, 5.0),
        "507" to TerrainEffect.Path(0.5, 5.0),
        "508" to TerrainEffect.Path(0.35, 5.0),
        "529" to TerrainEffect.Area(0.9),
        "201" to TerrainEffect.Forbidden(hard = true, width = 2.0),
        "526" to TerrainEffect.Forbidden(hard = true),
        "524" to TerrainEffect.Forbidden(hard = true, width = 1.5),
        "527" to TerrainEffect.Forbidden(hard = false),
        "707" to TerrainEffect.Forbidden(hard = true, width = 1.0),
        "709" to TerrainEffect.Forbidden(hard = false),
    ),
    defaultSpeed = 0.0,
)

/** Лыжи: та же сеть, разница между классами меньше (узкая лыжня не так плоха, как узкая тропа для велосипеда). */
val SKI_ISOM = RunnabilityTable(
    name = "Лыжи (ISOM 2000)",
    entries = mapOf(
        "501" to TerrainEffect.Path(1.0, 6.0),
        "502" to TerrainEffect.Path(1.0, 6.0),
        "503" to TerrainEffect.Path(1.0, 6.0),
        "504" to TerrainEffect.Path(1.0, 5.0),
        "505" to TerrainEffect.Path(0.9, 5.0),
        "506" to TerrainEffect.Path(0.8, 5.0),
        "507" to TerrainEffect.Path(0.7, 5.0),
        "508" to TerrainEffect.Path(0.6, 5.0),
        "529" to TerrainEffect.Area(0.8),
        "201" to TerrainEffect.Forbidden(hard = true, width = 2.0),
        "526" to TerrainEffect.Forbidden(hard = true),
        "524" to TerrainEffect.Forbidden(hard = true, width = 1.5),
        "527" to TerrainEffect.Forbidden(hard = false),
        "707" to TerrainEffect.Forbidden(hard = true, width = 1.0),
        "709" to TerrainEffect.Forbidden(hard = false),
    ),
    defaultSpeed = 0.0,
)
