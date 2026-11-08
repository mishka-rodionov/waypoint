package waypoint.core.terrain

/**
 * Влияние символа карты на движение. Скорости относительные: 1.0 — бег по белому лесу.
 */
sealed interface TerrainEffect {
    /** Площадной тип местности. Перекрывающиеся площади: берётся самая медленная. */
    data class Area(val speed: Double) : TerrainEffect

    /** Дорога или тропа: поднимает скорость в коридоре шириной [width] метров до [speed]. */
    data class Path(val speed: Double, val width: Double) : TerrainEffect

    /**
     * Препятствие, которое можно преодолеть с потерей времени (ручей, забор, канава):
     * умножает скорость по местности на [factor]. На дороге и тропе не действует (мост, калитка).
     */
    data class Obstacle(val factor: Double, val width: Double) : TerrainEffect

    /**
     * Непреодолимое или запрещённое. [hard]: перекрывает и дороги (здание, скала, высокий забор).
     * Мягкий запрет (водоём, запрещённая зона) снимается дорогой или тропой поверх него.
     */
    data class Forbidden(val hard: Boolean, val width: Double = 0.0) : TerrainEffect
}

/** Таблица «код символа → влияние». Поиск сначала по полному коду ("406.1"), затем по номеру ("406"). */
class RunnabilityTable(
    val name: String,
    private val entries: Map<String, TerrainEffect>,
    /** Скорость там, где нет площадных символов (белый лес). */
    val defaultSpeed: Double = 1.0,
) {
    fun effectOf(code: String): TerrainEffect? =
        entries[code] ?: entries[code.substringBefore('.')]

    /** Максимальная относительная скорость в таблице: нужна для эвристики A*. */
    val maxSpeed: Double = (entries.values.mapNotNull {
        when (it) {
            is TerrainEffect.Area -> it.speed
            is TerrainEffect.Path -> it.speed
            else -> null
        }
    } + defaultSpeed).max()
}

/**
 * Нумерация ISOM 2000 (наборы символов OCAD), как в карте «Мача».
 * Значения — первое приближение, их будем подбирать по ощущениям от игры.
 */
val ISOM_2000 = RunnabilityTable(
    name = "ISOM 2000",
    entries = mapOf(
        // Рельеф
        "106" to TerrainEffect.Obstacle(0.6, 2.0),   // земляной обрыв
        "107" to TerrainEffect.Obstacle(0.8, 2.0),   // земляной вал
        "108" to TerrainEffect.Obstacle(0.9, 1.5),   // малый земляной вал
        "109" to TerrainEffect.Obstacle(0.6, 3.0),   // овраг
        "110" to TerrainEffect.Obstacle(0.85, 2.0),  // промоина
        // Скалы и камни
        "201" to TerrainEffect.Forbidden(hard = true, width = 2.0), // непреодолимая скала
        "202" to TerrainEffect.Forbidden(hard = true),               // скала, столб (площадь)
        "203" to TerrainEffect.Obstacle(0.5, 2.0),                   // преодолимая скала
        // Вода и болота
        "301" to TerrainEffect.Forbidden(hard = false), // озеро
        "302" to TerrainEffect.Forbidden(hard = false), // пруд
        "305" to TerrainEffect.Obstacle(0.5, 3.0),      // преодолимый ручей
        "306" to TerrainEffect.Obstacle(0.7, 2.0),      // малый ручей
        "307" to TerrainEffect.Obstacle(0.85, 1.5),     // канава
        "308" to TerrainEffect.Obstacle(0.6, 3.0),      // узкое болото
        "309" to TerrainEffect.Forbidden(hard = false), // непроходимое болото
        "310" to TerrainEffect.Area(0.5),               // болото
        "311" to TerrainEffect.Area(0.8),               // заболоченность
        // Растительность
        "401" to TerrainEffect.Area(1.15), // открытое пространство
        "402" to TerrainEffect.Area(1.1),  // открытое с отдельными деревьями
        "403" to TerrainEffect.Area(0.85), // полуоткрытое (высокая трава, вырубка)
        "404" to TerrainEffect.Area(0.8),
        "405" to TerrainEffect.Area(1.0),  // лес
        "406" to TerrainEffect.Area(0.7),  // медленный бег
        "407" to TerrainEffect.Area(0.8),  // подлесок, медленный бег
        "408" to TerrainEffect.Area(0.4),  // трудный бег
        "409" to TerrainEffect.Area(0.5),  // подлесок, трудный бег
        "410" to TerrainEffect.Area(0.15), // очень трудный бег
        "411" to TerrainEffect.Area(0.5),  // проходимо в одном направлении
        "412" to TerrainEffect.Area(1.0),  // пашня
        "413" to TerrainEffect.Area(0.9),  // сад
        "414" to TerrainEffect.Obstacle(0.95, 1.0), // граница культур
        // Дороги и искусственные объекты
        "501" to TerrainEffect.Path(1.3, 8.0),
        "502" to TerrainEffect.Path(1.3, 6.0),
        "503" to TerrainEffect.Path(1.3, 5.0),
        "504" to TerrainEffect.Path(1.25, 4.0),
        "505" to TerrainEffect.Path(1.2, 3.0),
        "506" to TerrainEffect.Path(1.2, 2.0),  // тропа
        "507" to TerrainEffect.Path(1.15, 1.5), // малая тропа
        "508" to TerrainEffect.Path(1.05, 1.5), // слабозаметная тропа
        "509" to TerrainEffect.Path(1.0, 1.5),  // просека
        "519" to TerrainEffect.Obstacle(0.6, 2.0),  // каменная стена
        "522" to TerrainEffect.Obstacle(0.3, 1.5),  // забор
        "524" to TerrainEffect.Forbidden(hard = true, width = 1.5), // высокий забор
        "526" to TerrainEffect.Forbidden(hard = true),  // здание
        "527" to TerrainEffect.Forbidden(hard = false), // населённый пункт (запрещено)
        "529" to TerrainEffect.Area(1.3),               // площадка с покрытием
        "533" to TerrainEffect.Obstacle(0.6, 2.0),      // преодолимый трубопровод
        "534" to TerrainEffect.Forbidden(hard = true, width = 2.0), // непреодолимый трубопровод
        // Надпечатка дистанции
        "707" to TerrainEffect.Forbidden(hard = true, width = 1.0),
        "709" to TerrainEffect.Forbidden(hard = false),
        "710" to TerrainEffect.Forbidden(hard = false),
    ),
)
