package waypoint.app.map

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import waypoint.core.pack.MapPack
import waypoint.core.pack.TileSet

/**
 * Тайлы фона из assets с подгрузкой в фоне и LRU-кэшем.
 * Самый грубый уровень держится в памяти целиком: им закрываются места, где нужный тайл ещё не загружен.
 */
class TileCache(
    private val assets: AssetManager,
    private val dir: String,
    val tiles: TileSet,
    private val scope: CoroutineScope,
    maxBytes: Int = 48 shl 20,
) {
    /** Растёт при каждой загрузке тайла: чтение в отрисовке заставляет её повториться. */
    var version by mutableIntStateOf(0)
        private set

    private val cache = object : LruCache<Long, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }
    private val pinned = HashMap<Long, ImageBitmap>()
    private val pending = HashSet<Long>()
    private val io = Dispatchers.IO.limitedParallelism(3)

    val coarsest: Int get() = tiles.levels.last().level

    init {
        scope.launch {
            val level = tiles.levels.last()
            val loaded = withContext(io) {
                buildList {
                    for (r in 0 until level.rows(tiles.tileSize)) for (c in 0 until level.cols(tiles.tileSize)) {
                        decode(level.level, c, r)?.let { add(key(level.level, c, r) to it) }
                    }
                }
            }
            loaded.forEach { (k, b) -> pinned[k] = b }
            version++
        }
    }

    /** Тайл из кэша; если его нет — запускает загрузку и возвращает null. Вызывать из главного потока. */
    fun get(level: Int, col: Int, row: Int): ImageBitmap? {
        val k = key(level, col, row)
        pinned[k]?.let { return it }
        cache.get(k)?.let { return it }
        if (pending.add(k)) {
            scope.launch {
                val bmp = withContext(io) { decode(level, col, row) }
                pending.remove(k)
                if (bmp != null) {
                    cache.put(k, bmp)
                    version++
                }
            }
        }
        return null
    }

    /** Тайл только из памяти, без загрузки (для подмены грубым уровнем). */
    fun peek(level: Int, col: Int, row: Int): ImageBitmap? {
        val k = key(level, col, row)
        return pinned[k] ?: cache.get(k)
    }

    private fun decode(level: Int, col: Int, row: Int): ImageBitmap? = runCatching {
        assets.open("$dir/${MapPack.tilePath(level, col, row)}").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()

    private fun key(level: Int, col: Int, row: Int): Long = (level.toLong() shl 40) or (col.toLong() shl 20) or row.toLong()
}
