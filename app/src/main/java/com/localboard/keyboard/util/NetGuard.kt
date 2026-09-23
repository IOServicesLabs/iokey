package com.localboard.keyboard.util

/**
 * The single chokepoint for outbound network access.
 *
 * This app's whole claim is that typing data never leaves the device. That
 * claim currently rests on "we only call the network in two files, go read
 * them" — true, but unverifiable by the person who has to trust it, and one
 * careless future call site away from being false.
 *
 * NetGuard makes it structural instead:
 *
 *  - every socket-opening path calls [require] first, so a new call site that
 *    forgets is the exception rather than the norm;
 *  - when the user turns networking off, [require] throws before a connection
 *    is attempted — not a UI-level "please don't", an actual refusal;
 *  - every attempt, allowed or blocked, is appended to a ledger the user can
 *    read in Settings. An empty ledger is evidence; a surprising line in it is
 *    a bug report.
 *
 * Deliberately NOT a security boundary against hostile code inside the app —
 * nothing in-process can be. It is a correctness boundary against ourselves,
 * and an honesty mechanism for the user.
 */
object NetGuard {

    private const val MAX_ENTRIES = 200

    @Volatile
    private var prefs: Prefs? = null

    /** Mirrored from prefs so the check on the hot path never touches disk. */
    @Volatile
    var allowed: Boolean = true
        private set

    class BlockedException(message: String) : java.io.IOException(message)

    fun init(p: Prefs) {
        prefs = p
        allowed = p.networkAllowed
    }

    fun setAllowed(on: Boolean) {
        allowed = on
        prefs?.networkAllowed = on
        note("(settings)", if (on) "network enabled" else "network disabled")
    }

    /**
     * Call immediately before opening a connection. Throws [BlockedException]
     * when the user has networking switched off.
     */
    fun require(url: String) {
        val host = hostOf(url)
        if (!allowed) {
            note(host, "BLOCKED")
            throw BlockedException(
                "Network is switched off in IOKey settings — nothing was sent."
            )
        }
        note(host, "request")
    }

    fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url.take(60)
    } catch (_: Throwable) {
        url.substringAfter("//").substringBefore('/').take(60)
    }

    /** Append one ledger line. Never throws: a failure to log must not fail
     *  the download, and must not be a reason to skip the guard. */
    fun note(host: String, outcome: String) {
        val p = prefs ?: return
        try {
            val line = "${System.currentTimeMillis()}\t$host\t$outcome"
            val kept = p.egressLog.lineSequence()
                .filter { it.isNotBlank() }
                .take(MAX_ENTRIES - 1)
                .toList()
            p.egressLog = (listOf(line) + kept).joinToString("\n")
        } catch (_: Throwable) {
            // advisory only
        }
    }

    /** Newest-first ledger entries as (epochMillis, host, outcome). */
    fun entries(): List<Triple<Long, String, String>> {
        val p = prefs ?: return emptyList()
        return p.egressLog.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
            Triple(ts, parts[1], parts[2])
        }.toList()
    }

    fun clear() { prefs?.egressLog = "" }
}
