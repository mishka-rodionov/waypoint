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
import kotlinx.coroutines.CancellationException
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
import waypoint.core.pack.PackScoreProblem
import waypoint.core.route.LegAnalysis
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteEvaluator
import waypoint.core.route.RouteStats
import waypoint.core.route.ScoreO
import waypoint.core.route.ScoreResult
import waypoint.core.route.score

sealed interface PackState {
    data object Loading : PackState
    data object Missing : PackState
    data class Failed(val message: String) : PackState
    data class Ready(val pack: AssetPack) : PackState
}

/** Режим игры: выбор пути, «память» (карта показывается на время) или «по выбору» (порядок КП). */
enum class GameMode(val title: String) { ROUTE("Выбор пути"), MEMORY("Память"), SCORE("По выбору") }

sealed interface LegPhase {
    /** Режим «память»: карта видна, идёт обратный отсчёт; рисовать ещё нельзя. */
    data class Memorizing(val secondsLeft: Int) : LegPhase
    data object Drawing : LegPhase
    data object Computing : LegPhase
    data class Result(val analysis: LegAnalysis, val thinkingSeconds: Double) : LegPhase
    data class Failed(val message: String) : LegPhase
}

private const val PREF_PACK = "pack"
private const val PREF_DISCIPLINE = "discipline"

/** Ширина коридора «твоего выбора», м: погрешность пальца не наказывается. */
const val CORRIDOR_METERS = 15.0

/**
 * Одна попытка связки: нарисованные штрихи (каждый начинается с конца предыдущего) и стадия разбора.
 * Оптимум считается в фоне сразу при открытии связки, пока игрок думает и рисует.
 */
class LegSession(val leg: PackLeg, val mode: GameMode, val discipline: String, val optimal: Deferred<RouteStats?>) {
    val strokes = mutableStateListOf<List<Vec2>>()
    var phase by mutableStateOf<LegPhase>(LegPhase.Drawing)

    /** Карта скрыта (режим «память» после показа). В разборе карта видна всегда. */
    var mapHidden by mutableStateOf(false)

    /** С какого момента считается время на раздумье (в «памяти» — с момента, когда карта скрылась). */
    var shownAt = SystemClock.elapsedRealtime()

    /** Ключ результата: очки в разных режимах и дисциплинах не смешиваются. */
    val scoreKey: String get() = scoreKey(mode, discipline, leg.id)

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

/** Итог задачи «по выбору»: план игрока и лучший план. Очки 0–100 — доля от лучшего результата. */
data class ScoreOutcome(val player: ScoreResult, val best: ScoreResult, val thinkingSeconds: Double) {
    val score: Int get() = if (best.net <= 0) 100 else (100.0 * player.net / best.net).toInt().coerceIn(0, 100)
}

/** Попытка задачи «по выбору»: порядок КП (номера 1…n), итог — после «Финиш». */
class ScoreSession(val problem: PackScoreProblem, val discipline: String) {
    val order = mutableStateListOf<Int>()
    var outcome by mutableStateOf<ScoreOutcome?>(null)
    var shownAt = SystemClock.elapsedRealtime()
        private set

    val scoreKey: String get() = scoreKey(GameMode.SCORE, discipline, problem.id)

    val points: IntArray get() = IntArray(problem.size) { if (it == 0) 0 else problem.controls[it - 1].points }

    fun restart() {
        order.clear()
        outcome = null
        shownAt = SystemClock.elapsedRealtime()
    }
}

fun scoreKey(mode: GameMode, discipline: String, id: String) = "${mode.name}:$discipline:$id"

class GameViewModel(app: Application) : AndroidViewModel(app) {
    var packState by mutableStateOf<PackState>(PackState.Loading)
        private set

    /** Все паки в приложении (папки assets/packs/). */
    var packNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** Дисциплина внутри пака: свой растр проходимости, свои связки и задачи. */
    var discipline by mutableStateOf("forest")
        private set

    /** Модель движения грузится в фоне после списка связок. */
    var modelReady by mutableStateOf(false)
        private set
    private var model: Deferred<MovementModel>? = null
    private var heights: Deferred<FloatArray>? = null

    var session by mutableStateOf<LegSession?>(null)
        private set
    var scoreSession by mutableStateOf<ScoreSession?>(null)
        private set

    /** Лучшие очки; ключ — [scoreKey]. */
    val bestScores = mutableStateMapOf<String, Int>()

    var mode by mutableStateOf(GameMode.ROUTE)

    /** Сколько секунд показывать карту в режиме «память». */
    var memorySeconds by mutableStateOf(20)

    private var analysisJob: Job? = null
    private var memoryJob: Job? = null
    private var loadJob: Job? = null
    private val prefs = app.getSharedPreferences("waypoint", android.content.Context.MODE_PRIVATE)

    val pack: AssetPack? get() = (packState as? PackState.Ready)?.pack

    /** Связки текущей дисциплины. */
    val legs: List<PackLeg> get() = pack?.legs?.get(discipline).orEmpty()

    /** Задачи «по выбору» текущей дисциплины. */
    val scoreProblems: List<PackScoreProblem> get() = pack?.scoreProblems?.get(discipline).orEmpty()

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
        if (pack?.name == name) return
        closeLeg()
        loadJob?.cancel()
        model?.cancel()
        heights?.cancel()
        model = null
        heights = null
        modelReady = false
        packState = PackState.Loading
        prefs.edit().putString(PREF_PACK, name).apply()
        loadJob = viewModelScope.launch {
            val assets = getApplication<Application>().assets
            try {
                val p = withContext(Dispatchers.IO) { AssetPack.open(assets, name) }
                heights = viewModelScope.async(Dispatchers.Default) { p.loadHeights(assets) }
                val saved = prefs.getString(PREF_DISCIPLINE, null)
                discipline = p.disciplines.firstOrNull { it.id == saved }?.id ?: p.disciplines.first().id
                packState = PackState.Ready(p)
                loadModel(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                packState = PackState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun selectDiscipline(id: String) {
        val p = pack ?: return
        if (id == discipline || p.disciplines.none { it.id == id }) return
        closeLeg()
        discipline = id
        prefs.edit().putString(PREF_DISCIPLINE, id).apply()
        if (mode == GameMode.SCORE && scoreProblems.isEmpty()) mode = GameMode.ROUTE
        loadModel(p)
    }

    private fun loadModel(p: AssetPack) {
        model?.cancel()
        modelReady = false
        val id = discipline
        val assets = getApplication<Application>().assets
        val h = heights ?: return
        val d = viewModelScope.async(Dispatchers.Default) { p.loadModel(assets, id, h.await()) }
        model = d
        viewModelScope.launch {
            runCatching { d.await() }
                .onSuccess { if (model === d) modelReady = true }
                .onFailure { e -> if (e !is CancellationException) packState = PackState.Failed("Не удалось загрузить растры пака: ${e.message}") }
        }
    }

    // ---------- связки ----------

    fun openLeg(leg: PackLeg) {
        analysisJob?.cancel()
        val m = model ?: return
        val optimal = viewModelScope.async(Dispatchers.Default) { RouteEvaluator(m.await()).optimal(leg.from, leg.to) }
        val s = LegSession(leg, if (mode == GameMode.SCORE) GameMode.ROUTE else mode, discipline, optimal)
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
        scoreSession = null
    }

    /** Следующая связка списка после текущей (по кругу). */
    fun nextLeg() {
        val list = legs
        val cur = session?.leg ?: return
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == cur.id }
        session?.optimal?.cancel()
        openLeg(list[(i + 1) % list.size])
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
                        record(s.scoreKey, a.score)
                        LegPhase.Result(a, thinking)
                    }
                },
                onFailure = { LegPhase.Failed("Ошибка расчёта: ${it.message}") },
            )
        }
    }

    // ---------- «по выбору» ----------

    fun openScore(problem: PackScoreProblem) {
        closeLeg()
        scoreSession = ScoreSession(problem, discipline)
    }

    /** Нажатие на КП [index] (1…n): добавить в конец плана; повторное нажатие на последний — убрать. */
    fun toggleControl(index: Int) {
        val s = scoreSession ?: return
        if (s.outcome != null) return
        when {
            s.order.lastOrNull() == index -> s.order.removeAt(s.order.lastIndex)
            index !in s.order -> s.order.add(index)
        }
    }

    fun undoControl() {
        val s = scoreSession ?: return
        if (s.outcome == null && s.order.isNotEmpty()) s.order.removeAt(s.order.lastIndex)
    }

    fun clearControls() {
        val s = scoreSession ?: return
        if (s.outcome == null) s.order.clear()
    }

    /** Финиш: оценка плана игрока и лучший план — по матрице времён пака, без поиска пути. */
    fun finishScore() {
        val s = scoreSession ?: return
        if (s.outcome != null) return
        val thinking = (SystemClock.elapsedRealtime() - s.shownAt) / 1000.0
        viewModelScope.launch {
            val p = s.problem
            val points = s.points
            val (player, best) = withContext(Dispatchers.Default) {
                ScoreO.evaluate(s.order.toList(), points, p.limitSeconds, p::time) to
                    ScoreO.solve(p.controls.size, points, p.limitSeconds, p::time)
            }
            if (scoreSession !== s) return@launch
            val outcome = ScoreOutcome(player, best, thinking)
            s.outcome = outcome
            record(s.scoreKey, outcome.score)
        }
    }

    fun retryScore() = scoreSession?.restart()

    fun nextScore() {
        val list = scoreProblems
        val cur = scoreSession?.problem ?: return
        if (list.isEmpty()) return
        openScore(list[(list.indexOfFirst { it.id == cur.id } + 1) % list.size])
    }

    private fun record(key: String, score: Int) {
        bestScores[key] = maxOf(bestScores[key] ?: 0, score)
    }
}
