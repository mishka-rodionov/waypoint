package waypoint.core

import waypoint.core.geometry.Vec2
import waypoint.core.route.ScoreO
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreOTest {
    // Старт в (0,0), КП на прямой: 1 м = 1 с.
    private val pts = listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(200.0, 0.0), Vec2(-300.0, 0.0), Vec2(0.0, 50.0))
    private val time = { a: Int, b: Int -> pts[a].distanceTo(pts[b]) }

    @Test
    fun `в пределах лимита берутся самые выгодные КП`() {
        val points = intArrayOf(0, 10, 20, 30, 10)
        // Лимит 470 с: КП 1, 2, 4 — 0→1→2→4→0 = 100+100+206+50 ≈ 456 с, 40 очков; КП 3 (600 с туда-обратно) не успеть.
        val best = ScoreO.solve(4, points, 470.0, time)
        assertEquals(40, best.points)
        assertEquals(0, best.penalty)
        assertEquals(setOf(1, 2, 4), best.order.toSet())
    }

    @Test
    fun `опоздание штрафуется и учитывается в оптимуме`() {
        val points = intArrayOf(0, 10, 20, 30, 10)
        // Лимит 420: КП 1, 2, 4 (≈456 с, 40 очков) с опозданием 36 с — штраф 8 (по очку за начатые 5 с), итого 32:
        // лучше, чем 1+2 без штрафа (30).
        val best = ScoreO.solve(4, points, 420.0, time)
        assertEquals(40, best.points)
        assertEquals(8, best.penalty)
        assertEquals(32, best.net)
        val withThree = ScoreO.evaluate(listOf(3), points, 590.0, time) // 600 с при лимите 590
        assertEquals(2, withThree.penalty)
        assertEquals(28, withThree.net)
    }

    @Test
    fun `все КП самым быстрым порядком`() {
        // Оптимальный обход: 1 → 2 → 4 → 3 → старт (или наоборот): 100+100+√(200²+50²)+√(300²+50²)+300.
        val t = ScoreO.allControlsTime(4, time)
        val expected = 100 + 100 + Vec2(200.0, 0.0).distanceTo(Vec2(0.0, 50.0)) + Vec2(0.0, 50.0).distanceTo(Vec2(-300.0, 0.0)) + 300
        assertEquals(expected, t, 1e-6)
    }
}
