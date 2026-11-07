package ranktune

import kotlin.math.ln

/** The switch the build's patched copy of FastEngine reads. It exists only in that
 *  copy. The app has exactly one formula. */
object RankTune {
    @JvmField var ln = false

    const val APP_CAP = 1.5
    const val STYLE_SCALE = 0.25

    fun term(count: Int): Double = if (ln) ln(1.0 + count) else count.toDouble()
}
