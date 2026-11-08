package waypoint.app

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import waypoint.app.data.AssetPack
import waypoint.core.geometry.Vec2
import waypoint.core.pack.PackLeg
import waypoint.core.route.LegAnalysis
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteEvaluator
import waypoint.core.route.RouteStats
import waypoint.core.route.score

sealed interface PackState {
    data object Loading : PackState
    data object Missing : PackState
    data class Failed(val message: String) : PackState
    data class Ready(val pack: AssetPack) : PackState
}

/** Режим игры: карта видна всё время или показывается на время и скрывается («память»). */
enum class GameMode(val title: String) { ROUTE("Выбор пути"), MEMORY("Память") }

sealed interface LegPhase {
    /** Режим «память»: карта видна, идёт обратный отсчёт; рисовать ещё нельзя. */
    data class Memorizing(val secondsLeft: Int) : LegPhase
    data object Drawing : LegPhase
    data object Computing : LegPhase
    data class Result(val analysis: LegAnalysis, val thinkingSeconds: Double) : LegPhase
    data class Failed(val message: String) : LegPhase
}

private const val PREF_PACK = "pack"

/** Ширина коридора «твоего выбора», м: погрешность пальца не наказывается. */
const val CORRIDOR_METERS = 15.0

/**
 * Одна попытка связки: нарисованные штрихи (каждый начинается с конца предыдущего) и стадия разбора.
 * Оптимум считается в фоне сразу при открытии связки, пока игрок думает и рисует.
 */
class LegSession(val leg: PackLeg, val mode: GameMode, val optimal: Deferred<RouteStats?>) {
    val strokes = mutableStateListOf<List<Vec2>>()
    var phase by mutableStateOf<LegPhase>(LegPhase.Drawing)

    /** Карта скрыта (режим «память» после показа). В разборе карта видна всегда. */
    var mapHidden by mutableStateOf(false)

    /** С какого момента считается время на раздумье (в «памяти» — с момента, когда карта скрылась). */
    var shownAt = SystemClock.elapsedRealtime()

    /** Ключ результата: очки в разных режимах не смешиваются. */
    val scoreKey: String get() = scoreKey(mode, leg.id)

    /** Откуда продолжать линию: конец последнего штриха или старт. */
    val lineEnd: Vec2 get() = strokes.lastOrNull()?.lastOrNull() ?: leg.from

    /** Вся линия без старта: [RouteEvaluator.analyze] добавляет старт и КП сам. */
    fun route(): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (s in strokes) for (p in s) if (out.lastOrNull() != p) out.add(p)
        if (out.firstOrNull() == leg.from) out.removeAt(0)
        return out
    }

    fun restart() {
        strokes.clear()
        mapHidden = false
        phase = LegPhase.Drawing
        shownAt = SystemClock.elapsedRealtime()
    }
}

fun scoreKey(mode: GameMode, legId: String) = "${mode.name}:$legId"

class GameViewModel(app: Application) : AndroidViewModel(app) {
    var packState by mutableStateOf<PackState>(PackState.Loading)
        private set

    /** Модель движения грузится в фоне после списка связок. */
    var modelReady by mutableStateOf(false)
        private set
    private var model: Deferred<MovementModel>? = null

    var session by mutableStateOf<LegSession?>(null)
        private set

    /** Лучшие очки по связкам за запуск приложения; ключ — [scoreKey]. */
    val bestScores = mutableStateMapOf<String, Int>()

    var mode by mutableStateOf(GameMode.ROUTE)

    /** Сколько секунд показывать карту в режиме «память». */
    var memorySeconds by mutableStateOf(20)

    private var analysisJob: Job? = null
    private var memoryJob: Job? = null

    /** Все паки в приложении (папки assets/packs/). */
    var packNames by mutableStateOf<List<String>>(emptyList())
        private set

    private val prefs = app.getSharedPreferences("waypoint", android.content.Context.MODE_PRIVATE)
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val assets = getApplication<Application>().assets
            val names = runCatching { withContext(Dispatchers.IO) { AssetPack.list(assets) } }.getOrElse {
                packState = PackState.Failed(it.message ?: it.toString())
                return@launch
            }
            packNames = names
            if (names.isEmpty()) {
                packState = PackState.Missing
                return@launch
            }
            // Последняя выбранная карта, иначе первая.
            selectPack(prefs.getString(PREF_PACK, null)?.takeIf { it in names } ?: names.first())
        }
    }

    /** Переключиться на другой пак: прежняя модель отпускается, новая грузится в фоне. */
    fun selectPack(name: String) {
        if ((packState as? PackState.Ready)?.pack?.dir?.endsWith("/$name") == true) return
        closeLeg()
        loadJob?.cancel()
        model?.cancel()
        model = null
        modelReady = false
        packState = PackState.Loading
        prefs.edit().putString(PREF_PACK, name).apply()
        loadJob = viewModelScope.launch {
            val assets = getApplication<Application>().assets
            packState = try {
                val pack = withContext(Dispatchers.IO) { AssetPack.open(assets, name) }
                val d = viewModelScope.async(Dispatchers.Default) { pack.loadModel(assets) }
                model = d
                launch {
                    runCatching { d.await() }
                        .onSuccess { modelReady = true }
                        .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) packState = PackState.Failed("Не удалось загрузить растры пака: ${e.message}") }
                }
                PackState.Ready(pack)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                PackState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun openLeg(leg: PackLeg) {
        analysisJob?.cancel()
        val m = model ?: return
        val optimal = viewModelScope.async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            RouteEvaluator(m.await()).optimal(leg.from, leg.to)
        }
        val s = LegSession(leg, mode, optimal)
        session = s
        startAttempt(s)
    }

    /** Начало попытки: в «памяти» — показ карты с обратным отсчётом, затем карта скрывается. */
    private fun startAttempt(s: LegSession) {
        memoryJob?.cancel()
        s.restart()
        if (s.mode != GameMode.MEMORY) return
        s.phase = LegPhase.Memorizing(memorySeconds)
        memoryJob = viewModelScope.launch {
            for (left in memorySeconds downTo 1) {
                s.phase = LegPhase.Memorizing(left)
                delay(1000)
            }
            hideMap(s)
        }
    }

    /** «Запомнил»: скрыть карту досрочно. */
    fun skipMemorize() {
        val s = session ?: return
        if (s.phase !is LegPhase.Memorizing) return
        memoryJob?.cancel()
        hideMap(s)
    }

    private fun hideMap(s: LegSession) {
        s.mapHidden = true
        s.phase = LegPhase.Drawing
        s.shownAt = SystemClock.elapsedRealtime()
    }

    fun closeLeg() {
        memoryJob?.cancel()
        analysisJob?.cancel()
        session?.optimal?.cancel()
        session = null
    }

    /** Следующая связка списка после текущей (по кругу). */
    fun nextLeg() {
        val legs = (packState as? PackState.Ready)?.pack?.legs ?: return
        val cur = session?.leg ?: return
        val i = legs.indexOfFirst { it.id == cur.id }
        session?.optimal?.cancel()
        openLeg(legs[(i + 1) % legs.size])
    }

    fun undoStroke() {
        val s = session ?: return
        if (s.phase == LegPhase.Drawing && s.strokes.isNotEmpty()) s.strokes.removeAt(s.strokes.lastIndex)
    }

    fun clearStrokes() {
        val s = session ?: return
        if (s.phase == LegPhase.Drawing) s.strokes.clear()
    }

    fun retry() {
        session?.let(::startAttempt)
    }

    /** Разбор нарисованной линии в фоне. */
    fun finish() {
        val s = session ?: return
        if (s.phase != LegPhase.Drawing || s.strokes.isEmpty()) return
        val m = model ?: return
        val route = s.route()
        val thinking = (SystemClock.elapsedRealtime() - s.shownAt) / 1000.0
        s.phase = LegPhase.Computing
        analysisJob = viewModelScope.launch {
            val result = runCatching {
                val opt = s.optimal.await()
                withContext(Dispatchers.Default) {
                    RouteEvaluator(m.await()).analyze(s.leg.from, s.leg.to, route, CORRIDOR_METERS, opt)
                }
            }
            if (session !== s) return@launch
            s.phase = result.fold(
                onSuccess = { a ->
                    if (a == null) {
                        LegPhase.Failed("КП недостижим: путь не найден.")
                    } else {
                        bestScores[s.scoreKey] = maxOf(bestScores[s.scoreKey] ?: 0, a.score)
                        LegPhase.Result(a, thinking)
                    }
                },
                onFailure = { LegPhase.Failed("Ошибка расчёта: ${it.message}") },
            )
        }
    }
}
