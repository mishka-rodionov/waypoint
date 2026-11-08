package waypoint.tools

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** Точки треков GPX (широта, долгота) — для проверки привязки карты. */
object GpxReader {
    fun read(file: File): List<Pair<Double, Double>> = file.inputStream().buffered().use { input ->
        val r = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }.createXMLStreamReader(input)
        val points = ArrayList<Pair<Double, Double>>()
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "trkpt") {
                points.add(r.getAttributeValue(null, "lat").toDouble() to r.getAttributeValue(null, "lon").toDouble())
            }
        }
        r.close()
        points
    }
}
