import com.localboard.keyboard.memory.Speaker
import com.localboard.keyboard.memory.Turn

internal var failed = 0
internal var passed = 0

internal fun check(name: String, ok: Boolean, detail: Any? = null) {
    if (ok) {
        passed++
    } else {
        failed++
        println("FAIL  $name" + (detail?.let { "\n      $it" } ?: ""))
    }
}

internal fun me(s: String, ts: Long = 0) = Turn(Speaker.ME, s, ts = ts)
internal fun them(s: String, ts: Long = 0, who: String = "") = Turn(Speaker.THEM, s, who, ts)

private val tally = ArrayList<String>()

private fun section(name: String, body: () -> Unit) {
    val p = passed
    val f = failed
    body()
    tally += "%-11s passed %d, failed %d".format(name, passed - p, failed - f)
}

fun main() {
    section("core", ::coreChecks)
    section("sanitizer", ::sanitizerChecks)
    section("goldens", ::goldenChecks)
    section("edges", ::edgeChecks)
    section("properties", ::propertyChecks)
    println()
    tally.forEach(::println)
    println("passed $passed, failed $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
