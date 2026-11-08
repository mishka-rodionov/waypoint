package waypoint.core.pack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.grid.GridSpec
import kotlin.math.ceil

/**
 * Пак карты — всё, что нужно приложению без OMAP и SRTM. Папка пака:
 *
 * - `meta.json` — [PackMeta]: сетка растров, рамка карты, уровни тайлов, масштаб, дисциплины;
 * - `legs.json` — [PackLegs]: связки с заранее найденными вариантами пути (для других дисциплин — `legs-<id>.json`);
 * - `terrain.zbin` — проходимость ([TerrainCodec]), сжатая gzip (для других дисциплин — `terrain-<id>.zbin`);
 * - `scoreo-<id>.json` — [PackScoreSet]: задачи «по выбору» с матрицей времён между КП (если есть);
 * - `heights.zbin` — высоты на той же сетке ([HeightsCodec]), сжатые gzip;
 * - `tiles/<уровень>/<столбец>_<строка>.png` — фон карты; уровень 0 — исходное разрешение экспорта, каждый следующий вдвое грубее.
 *
 * Сжатие и чтение файлов — забота вызывающего: модуль без java.*.
 * Расширение не `.gz`: сборщик Android распаковывает такие ассеты и отрезает расширение.
 */
object MapPack {
    const val VERSION = 1
    const val META = "meta.json"
    const val LEGS = "legs.json"
    const val TERRAIN = "terrain.zbin"
    const val HEIGHTS = "heights.zbin"
    const val TILES = "tiles"

    fun tilePath(level: Int, col: Int, row: Int) = "$TILES/$level/${col}_$row.png"

    /** Файлы дисциплины: у кросса — прежние имена (паки без списка дисциплин читаются как раньше). */
    fun terrainFile(discipline: String) = if (discipline == "forest") TERRAIN else "terrain-$discipline.zbin"
    fun legsFile(discipline: String) = if (discipline == "forest") LEGS else "legs-$discipline.json"
    fun scoreFile(discipline: String) = "scoreo-$discipline.json"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeMeta(meta: PackMeta): String = prettyJson.encodeToString(PackMeta.serializer(), meta)
    fun decodeMeta(text: String): PackMeta = json.decodeFromString(PackMeta.serializer(), text)
    fun encodeLegs(legs: PackLegs): String = json.encodeToString(PackLegs.serializer(), legs)
    fun decodeLegs(text: String): PackLegs = json.decodeFromString(PackLegs.serializer(), text)
    fun encodeScore(set: PackScoreSet): String = json.encodeToString(PackScoreSet.serializer(), set)
    fun decodeScore(text: String): PackScoreSet = json.decodeFromString(PackScoreSet.serializer(), text)

    private val prettyJson = Json(json) { prettyPrint = true }
}

@Serializable
data class PackBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun toBox() = Box(minX, minY, maxX, maxY)

    companion object {
        fun of(b: Box) = PackBox(b.minX, b.minY, b.maxX, b.maxY)
    }
}

/** Сетка растров проходимости и высот. */
@Serializable
data class PackGrid(val originX: Double, val originY: Double, val cellSize: Double, val width: Int, val height: Int) {
    fun toSpec() = GridSpec(originX, originY, cellSize, width, height)

    companion object {
        fun of(s: GridSpec) = PackGrid(s.originX, s.originY, s.cellSize, s.width, s.height)
    }
}

/** Один уровень тайлов: картинка [width]×[height] пикселей по [pixelSize] метров. */
@Serializable
data class TileLevel(val level: Int, val width: Int, val height: Int, val pixelSize: Double) {
    fun cols(tileSize: Int) = ceil(width / tileSize.toDouble()).toInt()
    fun rows(tileSize: Int) = ceil(height / tileSize.toDouble()).toInt()
}

/** Фон карты: [box] — где на местности лежит картинка (у всех уровней одна рамка). */
@Serializable
data class TileSet(val box: PackBox, val tileSize: Int, val levels: List<TileLevel>)

@Serializable
data class PackMeta(
    val version: Int = MapPack.VERSION,
    val name: String,
    val title: String,
    /** Рамка объектов карты в метрах на местности. */
    val mapBounds: PackBox,
    val grid: PackGrid,
    val tiles: TileSet,
    /** Название таблицы проходимости, по которой построен растр. */
    val runnability: String,
    /** Максимальная относительная скорость таблицы: нужна для эвристики A*. */
    val maxRelativeSpeed: Double,
    /** Масштаб карты (знаменатель): размеры надпечатки и удобный масштаб рисования. */
    val scale: Double = 7500.0,
    /** Дисциплины пака; пусто — только кросс (старые паки). */
    val disciplines: List<PackDiscipline> = emptyList(),
) {
    fun disciplineList(): List<PackDiscipline> =
        disciplines.ifEmpty { listOf(PackDiscipline("forest", "Кросс", MapPack.TERRAIN, MapPack.LEGS)) }
}

/**
 * Дисциплина в паке: свои растр проходимости и связки (рельеф и тайлы общие).
 * Скорости и уклон берутся из [waypoint.core.terrain.Discipline] по [id].
 */
@Serializable
data class PackDiscipline(
    val id: String,
    val title: String,
    val terrain: String,
    val legs: String,
    /** Файл задач «по выбору» или null. */
    val scoreO: String? = null,
)

/** Путь в паке: ломаная [xy] (x0, y0, x1, y1, …) в метрах на местности. */
@Serializable
data class PackRoute(val seconds: Double, val length: Double, val climb: Double, val xy: List<Double>) {
    fun points(): List<Vec2> = List(xy.size / 2) { Vec2(xy[2 * it], xy[2 * it + 1]) }

    companion object {
        /** Координаты округляются до сантиметра, чтобы JSON был короче. */
        fun of(seconds: Double, length: Double, climb: Double, points: List<Vec2>) = PackRoute(
            round2(seconds), round2(length), round2(climb),
            points.flatMap { listOf(round2(it.x), round2(it.y)) },
        )
    }
}

@Serializable
data class PackLeg(
    val id: String,
    val title: String,
    val fromX: Double,
    val fromY: Double,
    val toX: Double,
    val toY: Double,
    /** Старт связки — треугольник старта (иначе — круг предыдущего КП). */
    val fromStart: Boolean = true,
    val interest: Double? = null,
    val choiceGap: Double? = null,
    val straightLoss: Double? = null,
    /** Непохожие варианты пути ([waypoint.core.route.LegExplorer]), по возрастанию времени; первый — оптимум. */
    val options: List<PackRoute> = emptyList(),
    /** Лучший путь вдоль прямой; null — прямая перекрыта. */
    val straight: PackRoute? = null,
) {
    val from: Vec2 get() = Vec2(fromX, fromY)
    val to: Vec2 get() = Vec2(toX, toY)
}

@Serializable
data class PackLegs(val map: String, val legs: List<PackLeg>)

private fun round2(x: Double): Double = kotlin.math.round(x * 100.0) / 100.0

/** КП задачи «по выбору»: [points] очков за взятие. */
@Serializable
data class PackScoreControl(val id: String, val x: Double, val y: Double, val points: Int) {
    val position: Vec2 get() = Vec2(x, y)
}

/**
 * Задача «по выбору» (рогейн в миниатюре): старт и финиш в одной точке, КП с очками, лимит времени.
 * [seconds] — матрица времён (n+1)×(n+1) по строкам, индекс 0 — старт, i — КП i−1;
 * [paths] — оптимальные пути той же матрицы (null на диагонали) для показа в разборе.
 */
@Serializable
data class PackScoreProblem(
    val id: String,
    val title: String,
    val startX: Double,
    val startY: Double,
    val limitSeconds: Double,
    val controls: List<PackScoreControl>,
    val seconds: List<Double>,
    val paths: List<PackRoute?>,
) {
    val start: Vec2 get() = Vec2(startX, startY)
    val size: Int get() = controls.size + 1

    fun time(from: Int, to: Int): Double = seconds[from * size + to]
    fun path(from: Int, to: Int): PackRoute? = paths[from * size + to]
}

@Serializable
data class PackScoreSet(val problems: List<PackScoreProblem>)
