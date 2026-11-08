package waypoint.sandbox

import waypoint.core.course.Control
import waypoint.core.course.ControlType
import waypoint.core.course.Leg
import waypoint.core.geometry.Vec2
import waypoint.core.route.LegAnalysis
import waypoint.core.route.LegExplorer
import waypoint.core.route.LegReport
import waypoint.core.route.MovementModel
import waypoint.core.route.RouteEvaluator
import waypoint.core.route.RouteStats
import waypoint.core.route.score
import waypoint.core.terrain.ISOM_2000
import waypoint.core.terrain.MappedArea
import waypoint.core.terrain.TerrainGrid
import waypoint.tools.GpxReader
import waypoint.tools.LoadedMap
import waypoint.tools.MapLoader
import waypoint.tools.geo.WorldImage
import waypoint.tools.legs.LegsFile
import waypoint.tools.legs.MinedLeg
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.exitProcess

private const val CELL_SIZE = 2.0

fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val mapFile = opts["map"]?.let(::File) ?: File("maps").listFiles { f -> f.extension == "omap" || f.extension == "xmap" }?.firstOrNull()
    if (mapFile == null || !mapFile.exists()) {
        System.err.println("Укажите карту: --map путь/к/карте.omap (или положите её в maps/)")
        exitProcess(1)
    }
    val contours = opts["contours"]?.let(::File) ?: File("maps").listFiles { f -> f.extension == "shp" }?.firstOrNull()
    println("Карта: $mapFile, горизонтали: ${contours ?: "нет (местность ровная)"}")

    val loaded = MapLoader.load(mapFile, contours, contourInterval = opts["interval"]?.toDouble() ?: 2.5, log = ::println)
    var t = System.currentTimeMillis()
    val terrain = TerrainGrid.build(loaded.map, ISOM_2000, CELL_SIZE)
    println("Проходимость ${terrain.spec.width}×${terrain.spec.height}: ${System.currentTimeMillis() - t} мс")
    t = System.currentTimeMillis()
    val mapLayer = Layers.map(loaded.map, 1.0)
    println("Отрисовка карты: ${System.currentTimeMillis() - t} мс")
    // Фон, отрисованный Mapper: картинка с world-файлом рядом с картой (карта.png + карта.pgw) или --background.
    val backgroundFile = opts["background"]?.let(::File)
        ?: File(mapFile.parentFile, mapFile.nameWithoutExtension + ".png").takeIf { it.exists() && WorldImage.worldFileFor(it) != null }
    val background = backgroundFile?.let {
        t = System.currentTimeMillis()
        val img = WorldImage.read(it, loaded.georef)
        println("Фон $it: ${img.image.width}×${img.image.height}, ${System.currentTimeMillis() - t} мс")
        Layer(img.image, img.box)
    }
    // Отобранные связки (./gradlew :tools:mineLegs): карта.legs.json рядом с картой.
    val mined = File(mapFile.parentFile, mapFile.nameWithoutExtension + ".legs.json").takeIf { it.exists() }
        ?.let { LegsFile.read(it) }.orEmpty()
    if (mined.isNotEmpty()) println("Отобранных связок: ${mined.size}")
    val gpx = opts["gpx"]?.let { path -> GpxReader.read(File(path)).map { (lat, lon) -> loaded.georef.latLonToGround(lat, lon) } }.orEmpty()

    opts["snapshot"]?.let { out ->
        snapshot(
            loaded, terrain, background ?: mapLayer, mapLayer, gpx, File(out),
            opts["leg"]?.toInt() ?: 0, opts["layer"], opts["wiggle"]?.toDouble() ?: 0.0,
            opts["mined"]?.toInt()?.let { mined.getOrNull(it)?.toLeg() },
        )
        return
    }
    SwingUtilities.invokeLater { Sandbox(loaded, terrain, background, mapLayer, gpx, mined).show() }
}

private fun MinedLeg.toLeg() = Leg(Control("S", ControlType.START, from), Control("F", ControlType.CONTROL, to))

private fun minedTitle(i: Int, l: MinedLeg) =
    "Отбор ${i + 1}: интерес %.2f, разрыв %s".format(l.interest, l.choiceGap?.let { "%+.0f%%".format(it * 100) } ?: "—")

/**
 * Рисует связку без окна: оптимум и «нарисованную» прямую с разбором — для проверки отрисовки и привязки.
 * [layerName]: map (фон Mapper, если есть), simple, terrain или relief.
 * [wiggle] > 0: вместо прямой «рисуется» змейка с таким размахом в метрах (проверка коридора).
 * [minedLeg] — отобранная связка вместо связки дистанции; на снимке видны все её варианты.
 */
private fun snapshot(
    loaded: LoadedMap, terrain: TerrainGrid, mapLayer: Layer, simpleLayer: Layer, gpx: List<Vec2>, out: File,
    legIndex: Int, layerName: String?, wiggle: Double, minedLeg: Leg?,
) {
    val legs = loaded.courses.filter { !it.freeOrder }.flatMap { it.legs }
    // Отрицательный номер связки: вся карта без разбора.
    val leg = minedLeg ?: legs.getOrNull(legIndex)
    val model = MovementModel(terrain, loaded.elevation, maxRelativeSpeed = ISOM_2000.maxSpeed)
    val drawn = if (leg == null || wiggle <= 0) emptyList() else {
        val a = leg.from.position
        val b = leg.to.position
        val len = a.distanceTo(b)
        val dir = (b - a) * (1.0 / len)
        val normal = Vec2(-dir.y, dir.x)
        (1 until (len / 5).toInt()).map { i ->
            val t = i * 5.0
            a + dir * t + normal * (wiggle * sin(t / 60.0 * 2 * Math.PI))
        }
    }
    val analysis = leg?.let { RouteEvaluator(model).analyze(it.from.position, it.to.position, drawn, 15.0) }
    val panel = MapPanel(loaded.map.bounds)
    panel.setSize(1400, 1000)
    panel.setLayer(
        when (layerName) {
            "simple" -> simpleLayer
            "terrain" -> Layers.terrain(terrain)
            "relief" -> loaded.elevation?.let { Layers.hillshade(it) } ?: mapLayer
            else -> mapLayer
        },
    )
    panel.gpx = gpx
    panel.leg = leg
    panel.analysis = analysis
    val explored = leg?.let { LegExplorer(model).explore(it.from.position, it.to.position) }
    panel.alternatives = explored?.options?.drop(1).orEmpty()
    explored?.let { r ->
        println("варианты: ${r.options.joinToString { "%.0f с / %.0f м".format(it.seconds, it.length) }}; напрямик ${r.straight?.seconds?.roundToInt()} с; интерес %.2f".format(r.interest))
    }
    if (drawn.isNotEmpty()) panel.showDrawn(drawn)
    if (leg != null) panel.fitLeg() else panel.fitTo(loaded.map.bounds)
    val img = java.awt.image.BufferedImage(1400, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    panel.paint(g)
    g.dispose()
    javax.imageio.ImageIO.write(img, "png", out)
    analysis?.let {
        println("${leg!!.title}: оптимум ${it.optimal.seconds.roundToInt()} с, ${it.optimal.length.roundToInt()} м; " +
            "линия ${it.drawn.seconds.roundToInt()} с / ${it.drawn.length.roundToInt()} м, набор ${it.drawn.climb.roundToInt()} м; " +
            "выбор ${it.intended?.seconds?.roundToInt()} с / ${it.intended?.length?.roundToInt()} м; " +
            "оптимум набор ${it.optimal.climb.roundToInt()} м; запретов ${it.drawn.violations.size}")
    }
    println("Снимок: $out")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--") && i + 1 < args.size) {
            out[a.removePrefix("--")] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return out
}

private class Sandbox(
    val loaded: LoadedMap,
    val terrain: TerrainGrid,
    val background: Layer?,
    val simpleLayer: Layer,
    gpx: List<Vec2>,
    mined: List<MinedLeg>,
) {
    private val mapLayer = background ?: simpleLayer
    private val panel = MapPanel(loaded.map.bounds).also { it.gpx = gpx }
    private val result = JEditorPane("text/html", "").apply {
        isEditable = false
        preferredSize = Dimension(300, 200)
    }
    private val legs: List<Pair<String, Leg>> = mined.mapIndexed { i, l -> minedTitle(i, l) to l.toLeg() } +
        loaded.courses.filter { !it.freeOrder }.flatMap { c ->
            c.legs.mapIndexed { i, l -> "${c.name}: ${i + 1}. ${l.title}" to l }
        }
    private val mapped = MappedArea.of(loaded.map)
    private val legBox = JComboBox(legs.map { it.first }.toTypedArray())
    private val layerNames = listOfNotNull(
        background?.let { "Карта (Mapper)" }, "Карта (упрощённая)", "Проходимость", "Рельеф",
    )
    private val layerBox = JComboBox(layerNames.toTypedArray())
    private val corridor = JSpinner(SpinnerNumberModel(15, 3, 60, 1))
    private val pace = JSpinner(SpinnerNumberModel(6.0, 3.0, 15.0, 0.25))
    private val optimalBox = JCheckBox("Показывать оптимум", true)
    private val memoryBox = JCheckBox("Память")
    private val memorySeconds = JSpinner(SpinnerNumberModel(15, 3, 120, 1))
    private val status = JLabel(" ")

    private var model = buildModel()
    private var evaluator = RouteEvaluator(model)
    private var explorer = LegExplorer(model)
    private val terrainLayer by lazy { Layers.terrain(terrain) }
    private val reliefLayer by lazy { loaded.elevation?.let { Layers.hillshade(it) } }
    private var memoryTimer: Timer? = null
    private var legShownAt = System.currentTimeMillis()

    private fun buildModel() = MovementModel(
        terrain = terrain,
        elevation = loaded.elevation,
        baseSpeed = 1000.0 / ((pace.value as Double) * 60),
        maxRelativeSpeed = ISOM_2000.maxSpeed,
    )

    fun show() {
        val frame = JFrame("waypoint — песочница: ${loaded.name}")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

        val random = JButton("Случайная связка")
        val showOpt = JButton("Оптимум без рисования")
        val fit = JButton("Вписать связку")
        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Связка:")); add(legBox); add(random); add(fit)
            add(JLabel("Слой:")); add(layerBox)
        }
        val side = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(row(JLabel("Коридор, м:"), corridor))
            add(row(JLabel("Темп по лесу, мин/км:"), pace))
            add(row(optimalBox))
            add(row(memoryBox, JLabel("показ, с:"), memorySeconds))
            add(row(showOpt))
            add(result)
        }
        frame.layout = BorderLayout()
        frame.add(top, BorderLayout.NORTH)
        frame.add(panel, BorderLayout.CENTER)
        frame.add(side, BorderLayout.EAST)
        frame.add(status, BorderLayout.SOUTH)

        panel.setLayer(mapLayer)
        panel.onRouteDrawn = ::evaluate
        legBox.addActionListener { legs.getOrNull(legBox.selectedIndex)?.let { selectLeg(it.second) } }
        random.addActionListener { randomLeg()?.let { legBox.selectedIndex = -1; selectLeg(it) } }
        fit.addActionListener { panel.fitLeg() }
        showOpt.addActionListener { evaluate(emptyList()) }
        layerBox.addActionListener {
            panel.setLayer(
                when (layerNames[layerBox.selectedIndex]) {
                    "Карта (упрощённая)" -> simpleLayer
                    "Проходимость" -> terrainLayer
                    "Рельеф" -> reliefLayer ?: mapLayer
                    else -> mapLayer
                },
            )
        }
        optimalBox.addActionListener { panel.showOptimal = optimalBox.isSelected }
        pace.addChangeListener {
            model = buildModel()
            evaluator = RouteEvaluator(model)
            explorer = LegExplorer(model)
        }

        frame.setSize(1400, 900)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        SwingUtilities.invokeLater {
            legs.firstOrNull()?.let { selectLeg(it.second) } ?: panel.fitTo(loaded.map.bounds)
        }
        status.text = "Левая кнопка — рисовать путь от старта к КП. Колесо — масштаб, правая кнопка или пробел+левая — сдвиг."
    }

    private fun row(vararg c: java.awt.Component) = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        c.forEach { add(it) }
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun selectLeg(leg: Leg) {
        memoryTimer?.stop()
        panel.leg = leg
        panel.hideMap = false
        panel.fitLeg()
        legShownAt = System.currentTimeMillis()
        result.text = html("Прямая: <b>${leg.from.position.distanceTo(leg.to.position).roundToInt()} м</b>. Нарисуй путь.")
        if (memoryBox.isSelected) {
            val seconds = memorySeconds.value as Int
            var left = seconds
            status.text = "Запоминай: $left с"
            memoryTimer = Timer(1000) {
                left--
                if (left <= 0) {
                    memoryTimer?.stop()
                    panel.hideMap = true
                    status.text = "Карта скрыта. Рисуй по памяти, время не ограничено."
                } else {
                    status.text = "Запоминай: $left с"
                }
            }.also { it.start() }
        }
    }

    /** Случайная связка 250–900 м между проходимыми точками отснятой части карты. */
    private fun randomLeg(): Leg? {
        val b = loaded.map.bounds
        val rnd = Random(System.nanoTime())
        repeat(200) {
            val a = Vec2(rnd.nextDouble(b.minX, b.maxX), rnd.nextDouble(b.minY, b.maxY))
            val ang = rnd.nextDouble(0.0, 2 * Math.PI)
            val len = rnd.nextDouble(250.0, 900.0)
            val c = Vec2(a.x + len * cos(ang), a.y + len * sin(ang))
            if (c.x !in b.minX..b.maxX || c.y !in b.minY..b.maxY) return@repeat
            if (!mapped.contains(a) || !mapped.contains(c)) return@repeat
            if (terrain.speedAt(terrain.spec.indexOf(a)) <= 0f || terrain.speedAt(terrain.spec.indexOf(c)) <= 0f) return@repeat
            return Leg(Control("S", ControlType.START, a), Control("1", ControlType.CONTROL, c))
        }
        return null
    }

    private fun evaluate(route: List<Vec2>) {
        val leg = panel.leg ?: return
        val thinking = (System.currentTimeMillis() - legShownAt) / 1000.0
        memoryTimer?.stop()
        panel.hideMap = false
        status.text = "Считаю…"
        val radius = (corridor.value as Int).toDouble()
        val ev = evaluator
        val ex = explorer
        object : SwingWorker<Pair<Any?, LegReport?>, Unit>() {
            override fun doInBackground(): Pair<Any?, LegReport?> {
                val t = System.currentTimeMillis()
                val r = if (route.isEmpty()) ev.optimal(leg.from.position, leg.to.position)
                else ev.analyze(leg.from.position, leg.to.position, route, radius)
                val variants = ex.explore(leg.from.position, leg.to.position)
                status.text = "Расчёт: ${System.currentTimeMillis() - t} мс"
                return r to variants
            }

            override fun done() {
                val (r, variants) = get()
                panel.alternatives = variants?.options?.drop(1).orEmpty()
                val extra = variants?.let { variantsHtml(it) }.orEmpty()
                when (r) {
                    is LegAnalysis -> {
                        panel.analysis = r
                        result.text = report(r, radius, thinking, extra)
                    }
                    is RouteStats -> {
                        panel.analysis = LegAnalysis(r, r, null)
                        result.text = html("<b style='color:#1e5aff'>Оптимум</b><br>${line(r)}<br><br>$extra")
                    }
                    else -> result.text = html("Путь не найден: КП недостижим.")
                }
            }
        }.execute()
    }

    /** Варианты пути (оранжевые на карте) и цена бега напрямик. */
    private fun variantsHtml(r: LegReport): String {
        val opt = r.optimum.seconds
        val sb = StringBuilder("<b style='color:#e08000'>Варианты</b><br>")
        r.options.forEachIndexed { i, o ->
            sb.append("${i + 1}. ${time(o.seconds)}, ${o.length.roundToInt()} м, набор ${o.climb.roundToInt()} м")
            if (i > 0) sb.append(" ${delta(o.seconds, opt)}")
            sb.append("<br>")
        }
        val straight = r.straight
        sb.append(if (straight != null) "Напрямик: ${time(straight.seconds)} ${delta(straight.seconds, opt)}<br>" else "Напрямик не пройти<br>")
        sb.append("Интересность связки: %.2f".format(r.interest))
        return sb.toString()
    }

    private fun report(a: LegAnalysis, radius: Double, thinking: Double, extra: String = ""): String {
        val opt = a.optimal
        val sb = StringBuilder()
        sb.append("<b style='color:#1e5aff'>Оптимум</b><br>${line(opt)}<br><br>")
        a.intended?.let {
            sb.append("<b style='color:#00a03c'>Твой выбор</b> (коридор ${radius.roundToInt()} м)<br>")
            sb.append("${line(it)} ${delta(it.seconds, opt.seconds)}<br><br>")
        }
        sb.append("<b style='color:#e61414'>Точно по линии</b><br>${line(a.drawn)} ${delta(a.drawn.seconds, opt.seconds)}<br>")
        if (a.drawn.violations.isNotEmpty()) {
            sb.append("<b>Пересечений непреодолимого: ${a.drawn.violations.size}</b> (×)<br>")
        }
        val score = a.score
        sb.append("<br>Потеря выбора: <b>${pct(a.loss)}</b><br>Очки: <b>$score</b> / 100<br>")
        sb.append("Время на раздумье: ${thinking.roundToInt()} с")
        if (extra.isNotEmpty()) sb.append("<br><br>").append(extra)
        return html(sb.toString())
    }

    private fun line(s: RouteStats) = "${time(s.seconds)}, ${s.length.roundToInt()} м, набор ${s.climb.roundToInt()} м"
    private fun delta(t: Double, opt: Double) = "(${pct(t / opt - 1)})"
    private fun pct(x: Double) = (if (x >= 0) "+" else "") + "%.1f%%".format(x * 100)
    private fun time(sec: Double): String {
        val s = sec.roundToInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
    private fun html(body: String) = "<html><body style='font-family:sans-serif;font-size:12pt'>$body</body></html>"
}
