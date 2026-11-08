package waypoint.tools.omap

import waypoint.core.geometry.Vec2
import waypoint.core.map.FeatureStyle
import waypoint.core.map.GeometryKind
import waypoint.core.map.MapFeature
import waypoint.core.map.VectorMap
import waypoint.tools.geo.Georeferencing
import java.io.File
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.math.roundToInt

/** Символ OMAP: только то, что нужно для проходимости и простой отрисовки. */
data class OmapSymbol(
    val id: String,
    val type: Int,
    val code: String,
    val name: String,
    val hidden: Boolean,
    /** Индекс (приоритет) основного цвета или -1. */
    val colorIndex: Int,
    /** Ширина линии в 1/1000 мм бумаги. */
    val lineWidth: Int,
    val combinedParts: List<String> = emptyList(),
    /** Площадь без сплошной заливки, только штриховка или узор. */
    val patternOnly: Boolean = false,
) {
    companion object {
        const val POINT = 1
        const val LINE = 2
        const val AREA = 4
        const val TEXT = 8
        const val COMBINED = 16
    }
}

data class OmapColor(val priority: Int, val argb: Int)

/** Объект в координатах бумаги (мм). [parts] — подконтуры уже с развёрнутыми кривыми Безье. */
data class OmapObject(val type: Int, val symbolId: String, val parts: List<List<Vec2>>, val text: String?)

class OmapDocument(
    val georeferencing: Georeferencing,
    val colors: List<OmapColor>,
    val symbols: Map<String, OmapSymbol>,
    val objects: List<OmapObject>,
) {
    /** Переводит документ в карту ядра: метры на местности, только видимые картографические символы. */
    fun toVectorMap(): VectorMap {
        val features = ArrayList<MapFeature>(objects.size)
        for (o in objects) {
            if (o.type == OBJECT_TEXT) continue
            val sym = symbols[o.symbolId] ?: continue
            if (sym.hidden || !isCartographic(sym.code)) continue
            val kind = when {
                o.type == OBJECT_POINT || sym.type == OmapSymbol.POINT -> GeometryKind.POINT
                sym.type == OmapSymbol.AREA -> GeometryKind.AREA
                sym.type == OmapSymbol.COMBINED && sym.combinedParts.any { symbols[it]?.type == OmapSymbol.AREA } -> GeometryKind.AREA
                else -> GeometryKind.LINE
            }
            val parts = o.parts.map { part -> part.map { georeferencing.mmToGround(it) } }
            features.add(MapFeature(sym.code, kind, parts, styleOf(sym), sym.name))
        }
        return VectorMap(features)
    }

    private fun styleOf(sym: OmapSymbol): FeatureStyle {
        var s = sym
        if (s.type == OmapSymbol.COMBINED) {
            s = s.combinedParts.mapNotNull { symbols[it] }.firstOrNull { it.colorIndex >= 0 } ?: s
        }
        val color = colors.firstOrNull { it.priority == s.colorIndex }
        val width = s.lineWidth / 1000.0 * georeferencing.scale / 1000.0
        var argb = color?.argb ?: 0xFF000000.toInt()
        // Площадь только со штриховкой (болото, полуоткрытое) рисуем полупрозрачной заливкой.
        if (s.patternOnly) argb = (argb and 0x00FFFFFF) or (0x70 shl 24)
        return FeatureStyle(argb, width, order = color?.priority ?: 0)
    }

    companion object {
        const val OBJECT_POINT = 0
        const val OBJECT_PATH = 1
        const val OBJECT_TEXT = 4

        /** Служебные коды (оформление листа 0–99, тексты 900+) на местность не влияют. */
        fun isCartographic(code: String): Boolean {
            val n = code.substringBefore('.').toIntOrNull() ?: return false
            return n in 100..899
        }
    }
}

/**
 * Чтение XML-формата OpenOrienteering Mapper (.omap/.xmap, схема v2).
 * Объекты внутри определений точечных символов пропускаются: читаются только объекты из частей карты.
 */
object OmapReader {

    fun read(file: File): OmapDocument = file.inputStream().buffered().use { read(it) }

    fun read(input: InputStream): OmapDocument {
        val r = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }.createXMLStreamReader(input)

        var scale = 10000.0
        var grivation = 0.0
        var aux = 1.0
        var zone: Int? = null
        var projRef = Vec2(0.0, 0.0)
        var mapRef = Vec2(0.0, 0.0)
        var geoRef: Vec2? = null
        val colors = ArrayList<OmapColor>()
        val symbols = LinkedHashMap<String, OmapSymbol>()
        val objects = ArrayList<OmapObject>()

        val stack = ArrayList<String>()
        var symbol: SymbolBuilder? = null
        var symbolDepth = -1
        var colorPriority = -1
        var inGeoref = false

        var objType = -1
        var objSymbol = ""
        var objDepth = -1
        var objCoords = StringBuilder()
        var objText: StringBuilder? = null
        var readingCoords = false
        var readingText = false
        var readingSpec = false
        val spec = StringBuilder()

        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = r.localName
                    val parent = stack.lastOrNull()
                    stack.add(name)
                    when {
                        name == "georeferencing" && symbol == null -> {
                            inGeoref = true
                            scale = r.attr("scale")?.toDouble() ?: scale
                            grivation = r.attr("grivation")?.toDouble() ?: 0.0
                            aux = r.attr("auxiliary_scale_factor")?.toDouble() ?: 1.0
                        }
                        inGeoref && name == "ref_point" && parent == "georeferencing" ->
                            mapRef = Vec2(r.attr("x")!!.toDouble(), r.attr("y")!!.toDouble())
                        inGeoref && name == "ref_point" && parent == "projected_crs" ->
                            projRef = Vec2(r.attr("x")!!.toDouble(), r.attr("y")!!.toDouble())
                        inGeoref && name == "ref_point_deg" ->
                            geoRef = Vec2(r.attr("lon")!!.toDouble(), r.attr("lat")!!.toDouble())
                        inGeoref && name == "spec" && parent == "projected_crs" -> {
                            readingSpec = true
                            spec.setLength(0)
                        }
                        name == "color" && parent == "colors" -> colorPriority = r.attr("priority")!!.toInt()
                        name == "rgb" && colorPriority >= 0 && parent == "color" -> {
                            val rr = (r.attr("r")!!.toDouble() * 255).roundToInt()
                            val gg = (r.attr("g")!!.toDouble() * 255).roundToInt()
                            val bb = (r.attr("b")!!.toDouble() * 255).roundToInt()
                            colors.add(OmapColor(colorPriority, (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb))
                        }
                        name == "symbol" && parent == "symbols" -> {
                            symbol = SymbolBuilder(
                                id = r.attr("id")!!,
                                type = r.attr("type")!!.toInt(),
                                code = r.attr("code") ?: "",
                                name = r.attr("name") ?: "",
                                hidden = r.attr("is_hidden") == "true",
                            )
                            symbolDepth = stack.size
                        }
                        symbol != null -> symbol.consume(name, r)
                        name == "object" && parent == "objects" -> {
                            objType = r.attr("type")!!.toInt()
                            objSymbol = r.attr("symbol") ?: ""
                            objDepth = stack.size
                            objCoords = StringBuilder()
                            objText = null
                        }
                        objDepth > 0 && name == "coords" && stack.size == objDepth + 1 -> readingCoords = true
                        // Старый формат: <coords><coord x y flags/>…; <pattern><coord/> — начало узора, не вершина.
                        objDepth > 0 && name == "coord" && parent == "coords" -> {
                            objCoords.append(r.attr("x")).append(' ').append(r.attr("y")).append(' ')
                                .append(r.attr("flags") ?: "0").append(';')
                        }
                        objDepth > 0 && name == "text" && stack.size == objDepth + 1 -> {
                            readingText = true
                            objText = StringBuilder()
                        }
                    }
                }

                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> when {
                    readingCoords -> objCoords.append(r.text)
                    readingText -> objText?.append(r.text)
                    readingSpec -> spec.append(r.text)
                }

                XMLStreamConstants.END_ELEMENT -> {
                    val name = r.localName
                    when {
                        name == "georeferencing" && inGeoref -> inGeoref = false
                        name == "spec" && readingSpec -> {
                            readingSpec = false
                            zone = Regex("""\+zone=(\d+)""").find(spec)?.groupValues?.get(1)?.toInt() ?: zone
                        }
                        name == "color" && colorPriority >= 0 -> colorPriority = -1
                        name == "coords" && readingCoords -> readingCoords = false
                        name == "text" && readingText -> readingText = false
                    }
                    if (symbol != null && stack.size == symbolDepth && name == "symbol") {
                        val s = symbol.build()
                        symbols[s.id] = s
                        symbol = null
                        symbolDepth = -1
                    }
                    if (objDepth > 0 && stack.size == objDepth && name == "object") {
                        objects.add(OmapObject(objType, objSymbol, parseCoords(objCoords), objText?.toString()))
                        objDepth = -1
                    }
                    stack.removeAt(stack.size - 1)
                }
            }
        }
        r.close()
        val georef = Georeferencing(scale, grivation, aux, zone, projRef, mapRef, geoRef)
        return OmapDocument(georef, colors, symbols, objects)
    }

    private fun XMLStreamReader.attr(name: String): String? = getAttributeValue(null, name)

    private class SymbolBuilder(val id: String, val type: Int, val code: String, val name: String, val hidden: Boolean) {
        var color = -1
        var width = 0
        var patternOnly = false
        val parts = ArrayList<String>()

        fun consume(element: String, r: XMLStreamReader) {
            when (element) {
                "line_symbol" -> if (color < 0) {
                    r.getAttributeValue(null, "color")?.toInt()?.takeIf { it >= 0 }?.let { color = it }
                    width = r.getAttributeValue(null, "line_width")?.toInt() ?: 0
                }
                "area_symbol" -> if (color < 0) {
                    r.getAttributeValue(null, "inner_color")?.toInt()?.takeIf { it >= 0 }?.let { color = it }
                }
                "pattern" -> if (color < 0) {
                    r.getAttributeValue(null, "color")?.toInt()?.takeIf { it >= 0 }?.let {
                        color = it
                        patternOnly = true
                    }
                }
                "point_symbol" -> if (color < 0) {
                    (r.getAttributeValue(null, "inner_color") ?: r.getAttributeValue(null, "outer_color"))
                        ?.toInt()?.takeIf { it >= 0 }?.let { color = it }
                }
                "part" -> r.getAttributeValue(null, "symbol")?.let { parts.add(it) }
            }
        }

        fun build() = OmapSymbol(id, type, code, name, hidden, color, width, parts, patternOnly)
    }

    private const val CURVE_START = 1
    private const val CLOSE_POINT = 2
    private const val HOLE_POINT = 16

    /**
     * Разбор «x y flags;…» (1/1000 мм). Флаг CurveStart: следующие две точки — контрольные кубической кривой.
     * ClosePoint и HolePoint завершают подконтур.
     */
    internal fun parseCoords(text: CharSequence): List<List<Vec2>> {
        val xs = ArrayList<Long>()
        val ys = ArrayList<Long>()
        val fl = ArrayList<Int>()
        for (chunk in text.split(';')) {
            val t = chunk.trim()
            if (t.isEmpty()) continue
            val f = t.split(' ', '\t', '\n').filter { it.isNotEmpty() }
            xs.add(f[0].toLong())
            ys.add(f[1].toLong())
            fl.add(if (f.size > 2) f[2].toInt() else 0)
        }
        val parts = ArrayList<List<Vec2>>()
        var cur = ArrayList<Vec2>()
        fun p(i: Int) = Vec2(xs[i] / 1000.0, ys[i] / 1000.0)
        var i = 0
        val n = xs.size
        while (i < n) {
            val flags = fl[i]
            if (cur.isEmpty()) cur.add(p(i))
            if (flags and CURVE_START != 0 && i + 3 < n) {
                flattenCubic(p(i), p(i + 1), p(i + 2), p(i + 3), cur)
                i += 3
                if (fl[i] and (CLOSE_POINT or HOLE_POINT) != 0) {
                    parts.add(cur)
                    cur = ArrayList()
                    i++
                }
                continue
            }
            if (cur.last() != p(i)) cur.add(p(i))
            if (flags and (CLOSE_POINT or HOLE_POINT) != 0) {
                parts.add(cur)
                cur = ArrayList()
            }
            i++
        }
        if (cur.isNotEmpty()) parts.add(cur)
        return parts
    }

    /** Кубическая кривая Безье ломаной; число шагов по длине контрольного многоугольника (~0.3 мм бумаги). */
    private fun flattenCubic(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, out: MutableList<Vec2>) {
        val len = p0.distanceTo(p1) + p1.distanceTo(p2) + p2.distanceTo(p3)
        val steps = (len / 0.3).toInt().coerceIn(2, 64)
        for (k in 1..steps) {
            val t = k.toDouble() / steps
            val u = 1 - t
            val b0 = u * u * u
            val b1 = 3 * u * u * t
            val b2 = 3 * u * t * t
            val b3 = t * t * t
            out.add(Vec2(
                b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x,
                b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y,
            ))
        }
    }
}
