package waypoint.core.grid

import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import kotlin.math.ceil
import kotlin.math.floor

/** Регулярная сетка над местностью: левый верхний угол [originX], [originY], размер ячейки [cellSize] метров. */
data class GridSpec(
    val originX: Double,
    val originY: Double,
    val cellSize: Double,
    val width: Int,
    val height: Int,
) {
    val size: Int get() = width * height

    fun index(col: Int, row: Int): Int = row * width + col
    fun col(index: Int): Int = index % width
    fun row(index: Int): Int = index / width
    fun contains(col: Int, row: Int): Boolean = col in 0 until width && row in 0 until height

    fun colOf(x: Double): Int = floor((x - originX) / cellSize).toInt()
    fun rowOf(y: Double): Int = floor((y - originY) / cellSize).toInt()

    fun cellCenter(col: Int, row: Int) = Vec2(originX + (col + 0.5) * cellSize, originY + (row + 0.5) * cellSize)
    fun cellCenter(index: Int) = cellCenter(col(index), row(index))

    /** Индекс ячейки с точкой [p] или -1, если точка вне сетки. */
    fun indexOf(p: Vec2): Int {
        val c = colOf(p.x)
        val r = rowOf(p.y)
        return if (contains(c, r)) index(c, r) else -1
    }

    companion object {
        fun covering(box: Box, cellSize: Double) = GridSpec(
            originX = box.minX,
            originY = box.minY,
            cellSize = cellSize,
            width = ceil(box.width / cellSize).toInt().coerceAtLeast(1),
            height = ceil(box.height / cellSize).toInt().coerceAtLeast(1),
        )
    }
}
