package waypoint.app.map

import waypoint.core.geometry.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Сдвиг карты после штриха: экран 1000×2000 px, 1 px = 1 м, мир совпадает с экраном. */
class FollowShiftTest {
    private val t = ViewTransform(0.0, 0.0, 1.0, 1000, 2000)

    @Test
    fun `конец линии в середине и впереди есть место — не двигаем`() {
        assertNull(followShift(t, Vec2(500.0, 1500.0), Vec2(500.0, -3000.0)))
    }

    @Test
    fun `конец линии у края по пути к далёкому КП — уводим к центру с запасом вперёд`() {
        // КП далеко вверху, конец линии у верхнего края: центр минус 300 px в сторону КП → конец на y = 1300.
        val (dx, dy) = assertNotNull(followShift(t, Vec2(500.0, 100.0), Vec2(500.0, -3000.0)))
        assertEquals(0f, dx, 1e-3f)
        assertEquals(1200f, dy, 1e-3f)
    }

    @Test
    fun `КП близко и оба в кадре — не двигаем`() {
        assertNull(followShift(t, Vec2(500.0, 1200.0), Vec2(600.0, 900.0)))
    }

    @Test
    fun `КП близко, но за краем — середина между ними в центр`() {
        val (dx, dy) = assertNotNull(followShift(t, Vec2(900.0, 1000.0), Vec2(1150.0, 1000.0)))
        // Середина (1025, 1000) должна встать в центр (500, 1000).
        assertEquals(-525f, dx, 1e-3f)
        assertEquals(0f, dy, 1e-3f)
        assertTrue(dx < 0)
    }
}
