package waypoint.tools.omap

import waypoint.core.course.Control
import waypoint.core.course.ControlType
import waypoint.core.course.Course
import waypoint.core.geometry.Vec2
import waypoint.tools.geo.Georeferencing
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Чтение файла дистанций форка Mapper (`<карта>.omap.courses`):
 * `<control id type x y>` в 1/1000 мм и `<course name type><entry control/>`.
 */
object CoursesReader {

    fun read(file: File, georef: Georeferencing): List<Course> = file.inputStream().buffered().use { input ->
        val r = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }.createXMLStreamReader(input)
        val controls = LinkedHashMap<String, Control>()
        val courses = ArrayList<Course>()
        var courseName: String? = null
        var freeOrder = false
        val entries = ArrayList<String>()
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "control" -> {
                        val id = r.getAttributeValue(null, "id")
                        val type = when (r.getAttributeValue(null, "type")) {
                            "start" -> ControlType.START
                            "finish" -> ControlType.FINISH
                            else -> ControlType.CONTROL
                        }
                        val mm = Vec2(
                            r.getAttributeValue(null, "x").toDouble() / 1000.0,
                            r.getAttributeValue(null, "y").toDouble() / 1000.0,
                        )
                        controls[id] = Control(id, type, georef.mmToGround(mm))
                    }
                    "course" -> {
                        courseName = r.getAttributeValue(null, "name")
                        freeOrder = r.getAttributeValue(null, "type") == "free_order"
                        entries.clear()
                    }
                    "entry" -> r.getAttributeValue(null, "control")?.let { entries.add(it) }
                }
                XMLStreamConstants.END_ELEMENT -> if (r.localName == "course" && courseName != null) {
                    courses.add(Course(courseName, entries.mapNotNull { controls[it] }, freeOrder))
                    courseName = null
                }
            }
        }
        r.close()
        courses
    }
}
