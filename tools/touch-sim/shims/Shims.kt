// The little of the app that KeyboardLayouts.kt reaches for, so the real layouts compile
// on a plain JVM without AAPT or an Android jar. Only the junit mode of run.sh uses this.
package com.localboard.keyboard

object R {
    object drawable {
        const val ic_shift = 1
        const val ic_backspace = 2
        const val ic_enter = 3
        const val ic_emoji = 4
    }
}
