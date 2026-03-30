package com.msp1974.vacompanion.utils

data class Event(val eventName: String, val oldValue: Any, val newValue: Any)

/**
 * Kotlin identifier or camelCase key -> snake_case for [Event.eventName].
 * Strings that are already lowercase snake_case are returned unchanged.
 */
fun String.toSnakeCaseEventName(): String {
    if (none { it.isUpperCase() }) return this
    val sb = StringBuilder()
    for (i in indices) {
        val c = this[i]
        if (c.isUpperCase()) {
            val prev = getOrNull(i - 1)
            val next = getOrNull(i + 1)
            val prevIsLower = prev?.isLowerCase() == true
            val nextIsLower = next?.isLowerCase() == true
            if (i > 0 && (prevIsLower || (nextIsLower && prev?.isUpperCase() == true))) {
                sb.append('_')
            }
            sb.append(c.lowercaseChar())
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}

interface EventListener {
    fun onEventTriggered(event: Event)
}

class EventNotifier {

    private val listeners: MutableSet<EventListener> = HashSet()

    fun addListener(eventListener: EventListener) {
        listeners.add(eventListener)
    }

    fun removeListener(eventListener: EventListener) {
        listeners.remove(eventListener)
    }

    fun notifyEvent(event: Event) {
        listeners.forEach {
            it.onEventTriggered(event)
        }
    }
}