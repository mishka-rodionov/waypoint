package waypoint.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Одна попытка: связка или задача, режим, дисциплина, очки, потеря (для связок). */
@Serializable
data class Attempt(
    val key: String,
    val title: String,
    val pack: String,
    val discipline: String,
    val mode: String,
    val score: Int,
    val loss: Double? = null,
    val time: Long,
)

/** Всё, что приложение помнит между запусками. */
@Serializable
data class ProgressData(
    /** Лучшие очки по ключу результата (пак, режим, дисциплина, связка). */
    val best: Map<String, Int> = emptyMap(),
    /** Последние попытки, новые в конце (не больше [ProgressStore.MAX_ATTEMPTS]). */
    val attempts: List<Attempt> = emptyList(),
    val quizAnswered: Int = 0,
    val quizCorrect: Int = 0,
    /** Открыть все уровни (для тех, кто хочет без прогрессии). */
    val unlockAll: Boolean = false,
    /** Справка «Как играть» уже показана при первом запуске. */
    val seenHelp: Boolean = false,
)

/** Прогресс в SharedPreferences одной JSON-строкой: данных мало, база не нужна. */
class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint-progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): ProgressData = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(ProgressData.serializer(), it) }.getOrNull()
    } ?: ProgressData()

    fun save(data: ProgressData) {
        prefs.edit().putString(KEY, json.encodeToString(ProgressData.serializer(), data)).apply()
    }

    companion object {
        private const val KEY = "progress"
        const val MAX_ATTEMPTS = 500
    }
}
