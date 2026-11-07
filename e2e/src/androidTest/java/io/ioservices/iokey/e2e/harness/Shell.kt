package io.ioservices.iokey.e2e.harness

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import java.io.InputStream
import kotlin.concurrent.thread

/** Commands run as the adb shell user, through UiAutomation, so no new JVM starts for each one. */
class Shell(private val ua: UiAutomation) {

    /**
     * One command with plain arguments. UiAutomation hands the string to Runtime.exec, which splits on
     * whitespace and knows nothing about quotes, pipes or redirection. Use [sh] for those.
     */
    fun exec(command: String): String {
        val fd = ua.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    /** A script fed to /system/bin/sh on stdin, with full shell syntax including here-documents. */
    fun sh(script: String): String {
        val fds = ua.executeShellCommandRwe("sh")
        val stdout = fds[0]
        val stdin = fds[1]
        val stderr = fds[2]
        // Drain stderr on the side so a chatty command can never block on a full pipe.
        val errorText = StringBuilder()
        val drain = thread(name = "shell-stderr") {
            ParcelFileDescriptor.AutoCloseInputStream(stderr).use { s: InputStream ->
                errorText.append(s.readBytes().decodeToString())
            }
        }
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write((script + "\nexit\n").toByteArray()) }
        val out = ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes().decodeToString() }
        drain.join(5_000)
        return if (errorText.isEmpty()) out else out + errorText
    }
}
