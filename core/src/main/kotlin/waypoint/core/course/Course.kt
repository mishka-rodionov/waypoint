package waypoint.core.course

import waypoint.core.geometry.Vec2

enum class ControlType { START, CONTROL, FINISH }

data class Control(val id: String, val type: ControlType, val position: Vec2)

data class Leg(val from: Control, val to: Control) {
    val title: String get() = "${from.id} → ${to.id}"
}

/** Дистанция: КП в порядке прохождения (для «по выбору» — в порядке списка). */
data class Course(val name: String, val controls: List<Control>, val freeOrder: Boolean = false) {
    val legs: List<Leg> get() = controls.zipWithNext { a, b -> Leg(a, b) }
}
