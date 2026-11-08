package waypoint.tools.legs

import waypoint.core.geometry.Vec2
import java.io.File
import java.util.Locale

/** Отобранная связка: точки в метрах на местности карты и метрики интересности. */
data class MinedLeg(
    val from: Vec2,
    val to: Vec2,
    val interest: Double,
    val choiceGap: Double?,
    val straightLoss: Double?,
    /** Время непохожих вариантов, с; первый — оптимум. */
    val optionSeconds: List<Double>,
)

/**
 * Файл отобранных связок `<карта>.legs.json`: JSON с одной связкой на строку,
 * чтобы читать его без библиотек и смотреть глазами.
 */
object LegsFile {

    fun write(file: File, mapName: String, legs: List<MinedLeg>) {
        fun num(x: Double) = String.format(Locale.ROOT, "%.2f", x)
        fun opt(x: Double?) = x?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "null"
        file.bufferedWriter().use { w ->
            w.write("{\"map\": \"$mapName\", \"legs\": [\n")
            legs.forEachIndexed { i, l ->
                w.write(
                    "{\"from\": [${num(l.from.x)}, ${num(l.from.y)}], \"to\": [${num(l.to.x)}, ${num(l.to.y)}], " +
                        "\"interest\": ${opt(l.interest)}, \"choiceGap\": ${opt(l.choiceGap)}, \"straightLoss\": ${opt(l.straightLoss)}, " +
                        "\"options\": [${l.optionSeconds.joinToString(", ") { num(it) }}]}",
                )
                w.write(if (i < legs.size - 1) ",\n" else "\n")
            }
            w.write("]}\n")
        }
    }

    fun read(file: File): List<MinedLeg> = file.readLines().mapNotNull { line ->
        if (!line.trimStart().startsWith("{\"from\"")) return@mapNotNull null
        fun pair(key: String): Vec2 {
            val m = Regex("\"$key\": \\[([-0-9.]+), ([-0-9.]+)]").find(line) ?: error("Нет $key в: $line")
            return Vec2(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        fun value(key: String): Double? =
            Regex("\"$key\": (null|[-0-9.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        val options = Regex("\"options\": \\[([^]]*)]").find(line)?.groupValues?.get(1)
            ?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }.orEmpty()
        MinedLeg(pair("from"), pair("to"), value("interest") ?: 0.0, value("choiceGap"), value("straightLoss"), options)
    }
}
