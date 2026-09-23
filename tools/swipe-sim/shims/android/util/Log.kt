package android.util

/** Simulator stand-in: prints to stderr. */
object Log {
    @JvmStatic fun i(tag: String, msg: String): Int { System.err.println("I/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { System.err.println("W/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable): Int { System.err.println("W/$tag: $msg: $t"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null): Int { System.err.println("E/$tag: $msg: $t"); return 0 }
    @JvmStatic fun d(tag: String, msg: String): Int = 0
}
