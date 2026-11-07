package com.localboard.keyboard.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last few measurements of the keyboard's window, for the diagnostics screen.
 *
 * How the window comes up was worked out from the stock Android source, and the
 * phones it matters most on run a vendor's framework whose source is not public. This
 * is how what they really do gets seen, on a release build with no cable attached:
 * after a bad show, open the screen and read what the window did.
 *
 * Numbers and a closed set of event names, by construction. Nothing here accepts a
 * string, so typed text has no way in. It lives in memory only, ends with the
 * process and goes nowhere. A keyboard that was just killed therefore starts with
 * an empty log, which is the point: the first show after that is the one to see.
 */
object GeometryLog {

    enum class Event(val label: String) {
        NEW_VIEW("new view"),
        SHOW("show"),
        DISPATCH("insets"),
        LAYOUT("layout"),
        HEAL("repair"),
        HEALED("repaired")
    }

    /** Heights in px, or [UNKNOWN] where there is no answer to give. */
    data class Geometry(
        val window: Int,
        val root: Int,
        val rootWanted: Int,
        val rootTooSmall: Boolean,
        val stripLayout: Int,
        val stripTop: Int,
        val stripHeight: Int,
        val navigationBar: Int,
        val captionBar: Int,
        val tappable: Int,
        val gestures: Int,
        val display: Int,
        val dispatch: Int,
        val lastGood: Int
    )

    const val UNKNOWN = -1

    /** About a dozen shows, since a show writes three to six of these. */
    const val CAPACITY = 64

    private class Entry(val atMs: Long, val event: Event, val geometry: Geometry)

    private val entries = ArrayDeque<Entry>(CAPACITY)

    @Synchronized
    fun record(event: Event, geometry: Geometry, atMs: Long = System.currentTimeMillis()) {
        if (entries.size == CAPACITY) entries.removeFirst()
        entries.addLast(Entry(atMs, event, geometry))
    }

    @Synchronized
    fun clear() = entries.clear()

    /** Oldest first, one line per measurement. */
    @Synchronized
    fun lines(): List<String> {
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return entries.map { "${clock.format(Date(it.atMs))} ${describe(it.event, it.geometry)}" }
    }

    /** Every recorded line, or an empty string when there are none. */
    fun summary(): String = lines().joinToString("\n")

    /** One measurement without its time, which is also what goes to the system log. */
    fun describe(event: Event, g: Geometry): String = buildString {
        append(event.label)
        append(" window=").append(g.window)
        append(" root=").append(g.root)
        append(" wants=").append(g.rootWanted)
        if (g.rootTooSmall) append(" cut")
        append(" strip=").append(g.stripLayout)
        append(" top=").append(g.stripTop)
        append(" laidout=").append(g.stripHeight)
        append(" nav=").append(g.navigationBar)
        append(" caption=").append(g.captionBar)
        append(" tappable=").append(g.tappable)
        append(" gesture=").append(g.gestures)
        append(" display=").append(g.display)
        append(" dispatch=").append(g.dispatch)
        append(" good=").append(g.lastGood)
    }
}
