package waypoint.core

import waypoint.core.pack.Difficulty
import waypoint.core.pack.Levels
import waypoint.core.pack.PackLeg
import waypoint.core.pack.PackRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelsTest {
    private fun leg(id: String, length: Double, gap: Double?, blocked: Boolean = false): PackLeg {
        val r = PackRoute(100.0, length, 0.0, listOf(0.0, 0.0, length, 0.0))
        return PackLeg(
            id, id, 0.0, 0.0, length, 0.0, choiceGap = gap,
            options = listOf(r, r.copy(seconds = 100.0 * (1 + (gap ?: 0.5)))),
            straight = if (blocked) null else r,
        )
    }

    @Test
    fun `близкие варианты и длинная связка сложнее`() {
        val easy = Difficulty.of(leg("a", 200.0, 0.3))
        val hard = Difficulty.of(leg("b", 900.0, 0.02, blocked = true))
        assertTrue(easy < hard, "$easy < $hard")
        assertEquals(5, hard)
        assertTrue(easy in 1..2)
    }

    @Test
    fun `уровни открываются по очереди`() {
        val legs = (1..20).map { leg("l$it", 200.0 + it * 40, if (it % 2 == 0) 0.05 else 0.2) }
        val levels = Levels.group(legs)
        assertEquals(listOf(8, 8, 4), levels.map { it.legs.size })
        assertTrue(levels[0].difficulty <= levels[2].difficulty)
        val best = HashMap<String, Int>()
        assertEquals(1, Levels.unlocked(levels) { best[it] })
        // Шесть связок первого уровня на 60+ — открыт второй.
        levels[0].legs.take(6).forEach { best[it.id] = 75 }
        assertEquals(2, Levels.unlocked(levels) { best[it] })
        levels[1].legs.take(5).forEach { best[it.id] = 90 }
        levels[1].legs[5].let { best[it.id] = 40 } // ниже порога — не считается
        assertEquals(2, Levels.unlocked(levels) { best[it] })
        levels[1].legs[6].let { best[it.id] = 60 }
        assertEquals(3, Levels.unlocked(levels) { best[it] })
    }
}
