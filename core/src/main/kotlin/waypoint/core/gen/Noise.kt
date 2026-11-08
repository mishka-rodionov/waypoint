package waypoint.core.gen

import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/** Градиентный шум Перлина на плоскости, детерминированный по [seed]. Значения примерно в −1…1. */
class PerlinNoise(seed: Long) {
    private val perm = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rnd = Random(seed)
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    fun noise(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = fade(xf)
        val v = fade(yf)
        val a = perm[(xi and 255)] + (yi and 255)
        val b = perm[((xi + 1) and 255)] + (yi and 255)
        val n00 = grad(perm[a], xf, yf)
        val n01 = grad(perm[a + 1], xf, yf - 1)
        val n10 = grad(perm[b], xf - 1, yf)
        val n11 = grad(perm[b + 1], xf - 1, yf - 1)
        val x0 = n00 + u * (n10 - n00)
        val x1 = n01 + u * (n11 - n01)
        return (x0 + v * (x1 - x0)) * 1.4
    }

    /** Фрактальный шум: [octaves] слоёв, каждый вдвое мельче и в [gain] раз слабее. Нормирован примерно в −1…1. */
    fun fbm(x: Double, y: Double, octaves: Int, gain: Double = 0.5, lacunarity: Double = 2.0): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var fx = x
        var fy = y
        for (o in 0 until octaves) {
            sum += amp * noise(fx + o * 17.31, fy - o * 9.77)
            norm += amp
            amp *= gain
            fx *= lacunarity
            fy *= lacunarity
        }
        return sum / norm
    }

    /** «Хребтовый» шум 0…1: острые гребни там, где обычный шум проходит через ноль. */
    fun ridged(x: Double, y: Double, octaves: Int): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var fx = x
        var fy = y
        for (o in 0 until octaves) {
            val n = 1.0 - abs(noise(fx + o * 5.1, fy + o * 3.7))
            sum += amp * n * n
            norm += amp
            amp *= 0.5
            fx *= 2.0
            fy *= 2.0
        }
        return sum / norm
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun grad(hash: Int, x: Double, y: Double): Double = when (hash and 7) {
        0 -> x + y
        1 -> -x + y
        2 -> x - y
        3 -> -x - y
        4 -> x
        5 -> -x
        6 -> y
        else -> -y
    }
}
