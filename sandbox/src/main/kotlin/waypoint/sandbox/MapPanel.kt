package waypoint.sandbox

import waypoint.core.course.ControlType
import waypoint.core.course.Leg
import waypoint.core.geometry.Box
import waypoint.core.geometry.Vec2
import waypoint.core.route.LegAnalysis
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Карта с зумом и прокруткой. Левая кнопка — рисовать маршрут,
 * правая/средняя кнопка или пробел + левая — двигать карту, колесо — масштаб.
 */
class MapPanel(private val worldBounds: Box) : JPanel() {
    private var pyramid: List<Layer> = emptyList()
    private var zoom = 1.0 // пикселей на метр
    private var viewX = worldBounds.minX
    private var viewY = worldBounds.minY

    var leg: Leg? = null
        set(value) { field = value; drawn.clear(); analysis = null; alternatives = emptyList(); repaint() }
    var analysis: LegAnalysis? = null
        set(value) { field = value; repaint() }
    var showOptimal = true
        set(value) { field = value; repaint() }
    /** Другие непохожие пути связки (кроме оптимума): рисуются оранжевым пунктиром. */
    var alternatives: List<waypoint.core.route.RouteStats> = emptyList()
        set(value) { field = value; repaint() }
    var gpx: List<Vec2> = emptyList()
        set(value) { field = value; repaint() }
    /** Скрыть карту (режим «память»: остаются только КП и линия). */
    var hideMap = false
        set(value) { field = value; repaint() }
    var onRouteDrawn: (List<Vec2>) -> Unit = {}

    private val drawn = ArrayList<Vec2>()
    private var drawing = false
    private var panFrom: Pair<Int, Int>? = null
    private var spaceDown = false

    init {
        background = Color(0xEEEEEE)
        isFocusable = true
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                if (SwingUtilities.isLeftMouseButton(e) && !spaceDown && leg != null) {
                    drawing = true
                    drawn.clear()
                    analysis = null
                    drawn.add(toWorld(e.x, e.y))
                } else {
                    panFrom = e.x to e.y
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                if (drawing) {
                    val p = toWorld(e.x, e.y)
                    if (p.distanceTo(drawn.last()) * zoom >= 3) drawn.add(p)
                    repaint()
                    return
                }
                val from = panFrom ?: return
                viewX -= (e.x - from.first) / zoom
                viewY -= (e.y - from.second) / zoom
                panFrom = e.x to e.y
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (drawing) {
                    drawing = false
                    if (drawn.size >= 2) onRouteDrawn(drawn.toList())
                }
                panFrom = null
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                zoomAt(e.x, e.y, 1.15.pow(-e.preciseWheelRotation))
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        bindKey("pressed SPACE") { spaceDown = true }
        bindKey("released SPACE") { spaceDown = false }
        bindKey("EQUALS") { zoomAt(width / 2, height / 2, 1.3) }
        bindKey("MINUS") { zoomAt(width / 2, height / 2, 1 / 1.3) }
    }

    private fun bindKey(stroke: String, action: () -> Unit) {
        val key = KeyStroke.getKeyStroke(stroke) ?: KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, stroke)
        actionMap.put(stroke, object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
        })
    }

    /** Новый фон: строим «пирамиду» уменьшенных копий, чтобы отдалённый вид рисовался быстро и без ряби. */
    fun setLayer(layer: Layer) {
        val levels = ArrayList<Layer>()
        levels.add(layer)
        var cur = layer
        while (cur.image.width > 1500) {
            val w = cur.image.width / 2
            val h = cur.image.height / 2
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(cur.image, 0, 0, w, h, null)
            g.dispose()
            cur = Layer(img, cur.box)
            levels.add(cur)
        }
        pyramid = levels
        repaint()
    }

    fun fitTo(box: Box) {
        if (width == 0 || height == 0) return
        zoom = min(width / box.width, height / box.height) * 0.9
        viewX = (box.minX + box.maxX) / 2 - width / 2 / zoom
        viewY = (box.minY + box.maxY) / 2 - height / 2 / zoom
        repaint()
    }

    fun fitLeg() {
        val l = leg ?: return fitTo(worldBounds)
        fitTo(Box.of(listOf(l.from.position, l.to.position)).expand(max(150.0, l.from.position.distanceTo(l.to.position) * 0.35)))
    }

    /** Показать готовую нарисованную линию (для снимков). */
    fun showDrawn(points: List<Vec2>) {
        drawn.clear()
        drawn.addAll(points)
        repaint()
    }

    private fun zoomAt(sx: Int, sy: Int, factor: Double) {
        val before = toWorld(sx, sy)
        zoom = (zoom * factor).coerceIn(0.02, 20.0)
        viewX = before.x - sx / zoom
        viewY = before.y - sy / zoom
        repaint()
    }

    private fun toWorld(sx: Int, sy: Int) = Vec2(viewX + sx / zoom, viewY + sy / zoom)

    override fun paintComponent(g0: Graphics) {
        super.paintComponent(g0)
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (!hideMap && pyramid.isNotEmpty()) {
            // Самый мелкий уровень, у которого пиксель ещё не крупнее пикселя экрана.
            val layer = pyramid.lastOrNull { it.metersPerPixel <= 1.0 / zoom } ?: pyramid.first()
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (zoom * layer.metersPerPixel > 2) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            val b = layer.box
            g.drawImage(
                layer.image,
                ((b.minX - viewX) * zoom).toInt(), ((b.minY - viewY) * zoom).toInt(),
                (b.width * zoom).toInt(), (b.height * zoom).toInt(), null,
            )
        } else if (hideMap) {
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
        }
        g.translate(-viewX * zoom, -viewY * zoom)
        g.scale(zoom, zoom)
        val px = 1.0 / zoom

        if (gpx.size > 1) polyline(g, gpx, Color(255, 0, 200, 150), 2 * px)

        if (showOptimal) for (alt in alternatives) {
            polyline(g, alt.points, Color(240, 130, 0, 200), 4 * px, dashed = true)
        }
        analysis?.let { a ->
            if (showOptimal) polyline(g, a.optimal.points, Color(30, 90, 255, 170), 5 * px)
            a.intended?.let { polyline(g, it.points, Color(0, 160, 60, 200), 3 * px) }
            for (v in a.drawn.violations) cross(g, v, 12 * px)
        }
        if (drawn.size > 1) polyline(g, drawn, Color(230, 20, 20, 220), 3 * px)

        leg?.let { l ->
            val purple = Color(200, 0, 160)
            g.color = purple
            g.stroke = BasicStroke((2.5 * px).toFloat())
            val r = max(30.0, 12 * px)
            for (c in listOf(l.from, l.to)) {
                val p = c.position
                if (c.type == ControlType.START) {
                    val tri = Path2D.Double()
                    for (k in 0 until 3) {
                        val ang = -Math.PI / 2 + k * 2 * Math.PI / 3
                        val x = p.x + r * 1.15 * cos(ang)
                        val y = p.y + r * 1.15 * sin(ang)
                        if (k == 0) tri.moveTo(x, y) else tri.lineTo(x, y)
                    }
                    tri.closePath()
                    g.draw(tri)
                } else {
                    g.draw(Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r))
                }
            }
            val a = l.from.position
            val b = l.to.position
            val d = a.distanceTo(b)
            if (d > 2.2 * r) {
                val u = (b - a) * (1.0 / d)
                g.stroke = BasicStroke((1.5 * px).toFloat())
                g.draw(Line2D.Double(a.x + u.x * r * 1.2, a.y + u.y * r * 1.2, b.x - u.x * r, b.y - u.y * r))
            }
        }
        g.dispose()
    }

    private fun polyline(g: Graphics2D, pts: List<Vec2>, color: Color, width: Double, dashed: Boolean = false) {
        val path = Path2D.Double()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        g.color = color
        g.stroke = if (dashed) {
            BasicStroke(width.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf((width * 3).toFloat(), (width * 2).toFloat()), 0f)
        } else {
            BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        }
        g.draw(path)
    }

    private fun cross(g: Graphics2D, p: Vec2, size: Double) {
        g.color = Color.BLACK
        g.stroke = BasicStroke((size / 4).toFloat())
        g.draw(Line2D.Double(p.x - size, p.y - size, p.x + size, p.y + size))
        g.draw(Line2D.Double(p.x - size, p.y + size, p.x + size, p.y - size))
    }
}
