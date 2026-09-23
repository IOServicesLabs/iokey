package com.localboard.keyboard.context

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.localboard.keyboard.util.Prefs

/**
 * Optional accessibility service that reads the text VISIBLE on screen —
 * both sides of a chat conversation, which the input connection can never
 * provide. This is the difference between the AI drafting from the actual
 * exchange and drafting blind.
 *
 * Privacy by construction: everything lives in [ScreenBuffer] — RAM only,
 * never written to disk, fresh for 10 minutes, at most 6 apps held.
 * Block-listed apps are never captured. The service does nothing until the
 * user explicitly turns it on in system Accessibility settings.
 */
class ScreenContextService : AccessibilityService() {

    @Volatile
    private var lastCaptureAt = 0L

    /** Constructed once — building it per event (they fire constantly)
     *  would allocate on every window change. */
    private val prefs by lazy { Prefs(this) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Node-tree walks are dozens of binder round-trips — far too expensive
    // for the main thread (they froze the keyboard's first open). The root
    // is fetched on the main thread (cheap), the walk runs here.
    private val workerThread =
        android.os.HandlerThread("screen-capture-walk").apply { start() }
    private val worker = android.os.Handler(workerThread.looper)

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // THE battery gate: the buffer is only ever read while the keyboard
        // is on screen (AI context assembly), so capturing in the background
        // burned CPU walking view trees for data nobody could consume.
        if (!ScreenBuffer.imeVisible) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return                       // ourselves
        if (pkg.startsWith("com.android.systemui")) return   // shade, launcher UI
        if (prefs.isAppBlocked(pkg)) return

        // B2: remember the window title the app announces — a stable "which
        // conversation is this" hint that doesn't move as the user scrolls.
        // (Title tracking stays on even when the screen STREAM is paused in
        // settings — thread keys are metadata, not message content.)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val t = event.text?.firstOrNull()?.toString()
                ?: event.contentDescription?.toString()
            if (!t.isNullOrBlank()) windowTitles[pkg] = t
        }

        // P5: the user paused the screen stream in settings — titles still
        // track, but no message content is collected at all.
        if (!prefs.screenStreamEnabled) return

        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < CAPTURE_INTERVAL_MS) return
        val root = rootInActiveWindow ?: return
        lastCaptureAt = now
        worker.post { captureRoot(root) }
    }

    /** The keyboard just opened (or is about to draft): grab one fresh
     *  capture immediately. B5: returns a handle the caller can AWAIT —
     *  startWifm used to sleep a fixed 280 ms and hope the walk (a binder
     *  round-trip per node, routinely slower on a populated chat) finished
     *  first; now the draft waits on the actual capture, capped upstream. */
    fun captureSoon(): kotlinx.coroutines.CompletableDeferred<Boolean> {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        // P5: stream paused in settings — resolve immediately with "no data".
        if (!prefs.screenStreamEnabled) {
            done.complete(false)
            return done
        }
        handler.post {
            val root = rootInActiveWindow
            if (root == null) {
                done.complete(false)
                return@post
            }
            lastCaptureAt = SystemClock.uptimeMillis()
            worker.post {
                try {
                    captureRoot(root)
                    done.complete(true)
                } catch (t: Throwable) {
                    done.complete(false)
                }
            }
        }
        return done
    }

    /** Keyboard hidden: drop any queued/queued-behind walks. The buffer is
     *  only ever read while the IME is up, so a walk that lands after hide
     *  is pure battery waste (a full view-tree traversal for nobody). */
    fun cancelPending() {
        worker.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
    }

    /** One visible text fragment with the metadata that attributes it. */
    private data class NodeText(
        val text: String,
        val top: Int,
        val left: Int,
        val right: Int,
        val centerX: Int,
        /** true = definitely the user's bubble, false = definitely theirs,
         *  null = no view-id verdict (geometry decides). */
        val outgoing: Boolean?,
        /** view-id contained a badge/count marker — numeric-only texts are
         *  chrome then ("3" unread), real messages otherwise ("42"). */
        val badgeLike: Boolean
    )

    /** Window titles seen per app (TYPE_WINDOW_STATE_CHANGED) — the most
     *  stable conversation identity available without app-specific hacks. */
    private val windowTitles = HashMap<String, String>()

    private fun captureRoot(root: AccessibilityNodeInfo) {
        val pkg = root.packageName?.toString()
        try {
            if (pkg == null || pkg == packageName ||
                pkg.startsWith("com.android.systemui") || prefs.isAppBlocked(pkg)
            ) return
            val nodes = ArrayList<NodeText>(48)
            collectNodes(root, nodes, 0)
            if (nodes.isEmpty()) return
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            // B2: the thread key — who this conversation is WITH. Prefer the
            // window title the app itself announced; fall back to the topmost
            // text sitting in the toolbar band (the chat title in every
            // mainstream messenger). Hashed downstream; "" when unknown.
            // P0: window title first (as documented), and NEVER a status
            // line — "online"/"typing…" churns the key every few seconds,
            // scattering one thread's rows across fake threads.
            val title = windowTitles[pkg]?.takeIf { !STATUS_RE.matches(it) }
                ?: nodes.filter {
                    it.top in (screenH * 0.03).toInt()..(screenH * 0.16).toInt() &&
                        it.text.length in 2..42 && !STATUS_RE.matches(it.text)
                }.maxByOrNull { it.text.length }?.text  // names outlive "online"
                ?: ""
            // B4/P0: label each line by speaker. View-id verdicts first;
            // then EDGE alignment (bubbles hug screen edges; a long incoming
            // bubble's CENTER can cross the midline and mis-attribute); raw
            // center is the last fallback. Labels emit only when the sorted
            // lines alternate speakers at least 3 times — a settings screen
            // or a one-sided pane never gets fake "Them:" prefixes.
            val labeled = nodes.map { n ->
                val you = n.outgoing ?: when {
                    n.left < screenW * 0.15f -> false
                    n.right > screenW * 0.85f -> true
                    else -> n.centerX > screenW / 2
                }
                n to you
            }
            val sorted = labeled.sortedWith(compareBy({ it.first.top }, { it.first.left }))
            var alternations = 0
            for (i in 1 until sorted.size) {
                if (sorted[i].second != sorted[i - 1].second) alternations++
            }
            val bothSides = alternations >= 3
            val text = sorted.joinToString("\n") { (n, you) ->
                if (bothSides) (if (you) "You: " else "Them: ") + n.text else n.text
            }
            if (text.length >= 24) ScreenBuffer.put(pkg, text, title)
        } finally {
            root.recycle()
        }
    }

    /** Depth-first walk collecting text WITH attribution metadata. Bounded by
     *  depth and node count, so a huge view tree can't stall the service.
     *  Password fields are never exposed by the framework, so they can't
     *  leak in here. */
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<NodeText>,
        depth: Int
    ): Boolean {
        if (node == null || depth > 22) return false
        if (out.size > 120 || out.sumOf { it.text.length } > ScreenBuffer.MAX_CHARS * 2) return true
        try {
            val cls = node.className?.toString() ?: ""
            // Buttons/images/editors carry no conversation text; the editor's
            // own text is the user's draft, already passed as contextBefore.
            val containerOnly = cls.contains("Button") || cls.contains("Image") ||
                cls.contains("EditText") || cls.contains("ProgressBar") ||
                cls.contains("Switch") || cls.contains("CheckBox") ||
                cls.contains("SeekBar")
            // P0: fetch each child ONCE — the old speaks-check fetched and
            // recycled every child of every text node, then the recursion
            // fetched them all again (2× the binder round-trips per walk).
            val childCount = node.childCount
            val children = if (childCount > 0) {
                ArrayList<AccessibilityNodeInfo?>(childCount).also { list ->
                    for (i in 0 until childCount) list.add(node.getChild(i))
                }
            } else null
            val t = if (containerOnly) null else node.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length > 1) {
                val viewId = node.viewIdResourceName?.lowercase()
                val badgeLike = viewId != null && BADGE_ID_RE.containsMatchIn(viewId)
                // A container that merely repeats its children's text would
                // double every line — only emit when no direct child speaks.
                val childSpeaks = children?.any { (it?.text?.length ?: 0) > 1 } == true
                if (!childSpeaks && !TIME_RE.matches(t) &&
                    !(badgeLike && BADGE_RE.matches(t))
                ) {
                    val b = android.graphics.Rect()
                    node.getBoundsInScreen(b)
                    val outgoing = when {
                        viewId == null -> null
                        OUTGOING_RE.containsMatchIn(viewId) -> true
                        INCOMING_RE.containsMatchIn(viewId) -> false
                        else -> null
                    }
                    out += NodeText(t, b.top, b.left, b.right, b.centerX(), outgoing, badgeLike)
                }
            }
            if (children != null) {
                for (child in children) {
                    if (child == null) continue
                    val stop = collectNodes(child, out, depth + 1)
                    child.recycle()
                    if (stop) return true
                }
            }
        } catch (_: Throwable) {
            // A stale node (window changed mid-walk) — just stop this branch.
        }
        return false
    }

    override fun onInterrupt() { /* no feedback to interrupt */ }

    override fun onServiceConnected() {
        ScreenBuffer.serviceRunning = true
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ScreenBuffer.serviceRunning = false
        instance = null
        ScreenBuffer.clearAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenBuffer.serviceRunning = false
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Minimum time between captures: window-content events fire many
         *  times per second in a chat app. */
        private const val CAPTURE_INTERVAL_MS = 3500L
        private val WS = Regex("\\s+")
        /** Chat-row timestamps ("3:42", "3:42 PM"). Numeric-only texts are
         *  only chrome when the view-id says badge/count — "42" is a legal
         *  message (P0). */
        private val TIME_RE = Regex("\\d{1,2}:\\d{2}(\\s?[APap][Mm])?")
        private val BADGE_RE = Regex("\\d{1,2}")
        private val BADGE_ID_RE = Regex("badge|count|unread")
        /** Toolbar status lines — never a thread title ("online", "typing…",
         *  "last seen recently", "active now"). */
        private val STATUS_RE = Regex(
            "(online|typing…?|last seen.*|active now|away|busy)",
            RegexOption.IGNORE_CASE
        )
        /** View-id fragments that declare a bubble's side outright. */
        private val OUTGOING_RE = Regex("outgoing|sent|from_me|my_message|_me\\b")
        private val INCOMING_RE = Regex("incoming|received|from_them|their_message")

        /** Same-process handle so the IME can ask for a capture on show. */
        @Volatile
        var instance: ScreenContextService? = null

        /** This build ships the screen reader. The Play build does not. */
        const val AVAILABLE = true
    }
}
