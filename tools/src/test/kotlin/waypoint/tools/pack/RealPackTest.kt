package waypoint.tools.pack

import waypoint.core.relief.ElevationGrid
import waypoint.core.terrain.Discipline
import waypoint.core.route.RouteEvaluator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Путь приложения на собранном паке: чтение растров, модель, оптимум заранее, разбор линии.
 * Пропускается, если пак не собран (./gradlew :tools:buildPack; в репозитории его нет).
 */
class RealPackTest {
    @Test
    fun `разбор связки по собранному паку`() {
        val dir = File("../app/src/main/assets/packs").listFiles()?.firstOrNull { File(it, "meta.json").exists() } ?: return
        var t = System.currentTimeMillis()
        val pack = PackFiles.read(dir)
        val model = Discipline.FOREST.model(pack.terrain, ElevationGrid(pack.spec, pack.heights))
        println("пак ${pack.meta.name}: ${pack.spec.width}×${pack.spec.height}, связок ${pack.legs.legs.size}, чтение ${System.currentTimeMillis() - t} мс")
        assertTrue(model.heights === pack.heights, "высоты на той же сетке не копируются")
        val ev = RouteEvaluator(model)
        for (leg in pack.legs.legs.filter { it.options.size > 1 }.take(3)) {
            t = System.currentTimeMillis()
            val opt = assertNotNull(ev.optimal(leg.from, leg.to))
            val tOpt = System.currentTimeMillis() - t
            // «Игрок» ведёт линию по второму варианту: потеря должна быть близка к разрыву вариантов.
            val drawn = leg.options[1].points().drop(1).dropLast(1)
            t = System.currentTimeMillis()
            val a = assertNotNull(ev.analyze(leg.from, leg.to, drawn, 15.0, opt))
            val gap = leg.options[1].seconds / leg.options[0].seconds - 1
            println("${leg.title}: оптимум ${opt.seconds.toInt()} с ($tOpt мс), выбор ${a.intended?.seconds?.toInt()} с, " +
                "потеря ${"%.1f".format(a.loss * 100)}% при разрыве ${"%.1f".format(gap * 100)}%, разбор ${System.currentTimeMillis() - t} мс")
            assertTrue(a.loss >= -1e-6)
            assertTrue(kotlin.math.abs(a.loss - gap) < 0.05, "потеря ${a.loss} далека от разрыва $gap")
        }
        // Задачи «по выбору»: оптимум из матрицы пака не хуже, чем взять только ближний КП.
        for (d in pack.disciplines) {
            val problem = d.score?.problems?.firstOrNull() ?: continue
            val points = IntArray(problem.size) { if (it == 0) 0 else problem.controls[it - 1].points }
            val best = waypoint.core.route.ScoreO.solve(problem.controls.size, points, problem.limitSeconds, problem::time)
            println("${d.info.title}, ${problem.title}: лучший план ${best.order} — ${best.points} очков, ${best.seconds.toInt()} с из ${problem.limitSeconds.toInt()}")
            assertTrue(best.net > 0)
        }
    }
}
