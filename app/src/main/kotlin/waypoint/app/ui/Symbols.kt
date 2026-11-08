package waypoint.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.TerrainEffect
import kotlin.math.roundToInt

/** Условный знак для справочника и теста: код ISOM, название, как нарисовать образец. */
class MapSymbol(val code: String, val name: String, val hint: String, val draw: DrawScope.() -> Unit) {
    /** Что знак значит для движка: доля скорости бега по белому лесу или запрет. */
    val effect: String
        get() = when (val e = ISOM_2000.effectOf(code)) {
            is TerrainEffect.Area -> "бег ${(e.speed * 100).roundToInt()}% от скорости по белому лесу"
            is TerrainEffect.Path -> "по ней ${(e.speed * 100).roundToInt()}% от скорости по белому лесу"
            is TerrainEffect.Obstacle -> "преодолимо: скорость ×${"%.2f".format(e.factor).replace('.', ',')} при пересечении"
            is TerrainEffect.Forbidden -> if (e.hard) "непреодолимо, даже по тропе" else "запрещено; дорога или мост пропускают"
            null -> if (code.startsWith("10")) "рельеф: подъём замедляет, лёгкий спуск ускоряет" else "на скорость не влияет"
        }
}

private val brown = Color(0xFFC8701E)
private val blue = Color(0xFF0096E6)
private val black = Color.Black

private fun DrawScope.fill(c: Color) = drawRect(c, size = size)

private fun DrawScope.line(c: Color, widthFrac: Float, dash: FloatArray? = null, wave: Boolean = false) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.08f, h * 0.65f)
        if (wave) {
            cubicTo(w * 0.35f, h * 0.2f, w * 0.6f, h * 0.95f, w * 0.92f, h * 0.35f)
        } else {
            lineTo(w * 0.92f, h * 0.35f)
        }
    }
    drawPath(path, c, style = Stroke(width = w * widthFrac, pathEffect = dash?.let { d -> PathEffect.dashPathEffect(d.map { it * w }.toFloatArray()) }))
}

private fun DrawScope.hatch(c: Color, stepFrac: Float, widthFrac: Float) {
    var y = 0f
    while (y < size.height) {
        drawLine(c, Offset(0f, y), Offset(size.width, y), size.width * widthFrac)
        y += size.height * stepFrac
    }
}

/** Линия с зубцами (обрыв, скала): зубцы вниз по склону. */
private fun DrawScope.ticked(c: Color, widthFrac: Float, tickFrac: Float) {
    val w = size.width
    val h = size.height
    val y = h * 0.4f
    drawLine(c, Offset(w * 0.08f, y), Offset(w * 0.92f, y), w * widthFrac)
    var x = w * 0.14f
    while (x < w * 0.9f) {
        drawLine(c, Offset(x, y), Offset(x, y + h * tickFrac), w * widthFrac * 0.7f)
        x += w * 0.12f
    }
}

/** Знаки ISOM 2000, которые встречаются на картах игры. */
val SYMBOLS: List<MapSymbol> = listOf(
    MapSymbol("101", "Горизонталь", "линия равной высоты; сечение 2,5 м") { line(brown, 0.025f, wave = true) },
    MapSymbol("102", "Утолщённая горизонталь", "каждая пятая горизонталь") { line(brown, 0.05f, wave = true) },
    MapSymbol("106", "Земляной обрыв", "крутой уступ; зубцы — вниз") { ticked(brown, 0.04f, 0.18f) },
    MapSymbol("201", "Непреодолимая скала", "обходить") { ticked(black, 0.07f, 0.22f) },
    MapSymbol("301", "Озеро", "непреодолимо, обходить") {
        fill(Color(0xFF7FD2FF))
        drawRect(black, style = Stroke(width = size.width * 0.04f))
    },
    MapSymbol("305", "Ручей", "пересекается с потерей времени") { line(blue, 0.045f, wave = true) },
    MapSymbol("306", "Малый ручей", "пунктирная синяя линия") { line(blue, 0.03f, floatArrayOf(0.12f, 0.03f), wave = true) },
    MapSymbol("310", "Болото", "медленно, но проходимо") { hatch(blue, 0.12f, 0.025f) },
    MapSymbol("311", "Заболоченность", "редкая штриховка") { hatch(blue, 0.22f, 0.018f) },
    MapSymbol("401", "Открытое пространство", "поле, луг: быстрее леса") { fill(Color(0xFFFFBA35)) },
    MapSymbol("403", "Полуоткрытое пространство", "высокая трава, вырубка") { fill(Color(0xFFFFDC9A)) },
    MapSymbol("405", "Лес (белый)", "хорошо пробегаемый лес") {
        fill(Color.White)
        drawRect(Color(0xFFCCCCCC), style = Stroke(width = size.width * 0.02f))
    },
    MapSymbol("406", "Лес, медленный бег", "светло-зелёный") { fill(Color(0xFFC5FFB9)) },
    MapSymbol("408", "Лес, трудный бег", "зелёный") { fill(Color(0xFF8BFF74)) },
    MapSymbol("410", "Лес, очень трудный бег", "тёмно-зелёный: обходить") { fill(Color(0xFF3DFF17)) },
    MapSymbol("504", "Грунтовая дорога", "сплошная чёрная линия") { line(black, 0.05f) },
    MapSymbol("506", "Тропа", "длинный пунктир") { line(black, 0.035f, floatArrayOf(0.2f, 0.03f)) },
    MapSymbol("507", "Малая тропа", "короткий пунктир") { line(black, 0.025f, floatArrayOf(0.1f, 0.03f)) },
    MapSymbol("508", "Слабозаметная тропа", "редкий пунктир") { line(black, 0.025f, floatArrayOf(0.06f, 0.06f)) },
    MapSymbol("524", "Непреодолимый забор", "толстая чёрная линия: не перелезть") { line(black, 0.07f) },
    MapSymbol("526", "Здание", "непреодолимо") {
        clipRect(size.width * 0.15f, size.height * 0.15f, size.width * 0.85f, size.height * 0.85f) {
            drawRect(Color(0xFF8C8C8C), size = Size(size.width, size.height))
        }
        drawRect(black, Offset(size.width * 0.15f, size.height * 0.15f), Size(size.width * 0.7f, size.height * 0.7f), style = Stroke(width = size.width * 0.02f))
    },
    MapSymbol("529", "Площадка с покрытием", "асфальт, плитка: быстрее всего") { fill(Color(0xFFE8D2B0)) },
)
