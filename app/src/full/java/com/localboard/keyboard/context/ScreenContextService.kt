package com.localboard.keyboard.context

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.localboard.keyboard.memory.ChatCapture
import com.localboard.keyboard.memory.ConversationKeyResolver
import com.localboard.keyboard.memory.ConversationSignals
import com.localboard.keyboard.memory.KeyConfidence
import com.localboard.keyboard.memory.Speaker
import com.localboard.keyboard.util.Prefs

/**
 * Optional accessibility service that reads the text VISIBLE on screen —
 * both sides of a chat conversation, which the input connection can never
 * provide. This is the difference between the AI drafting from the actual
 * exchange and drafting blind.
 *
 * What it reads lives in [ScreenBuffer]: RAM only, fresh for 10 minutes, at
 * most 6 apps held. The one thing that goes further is a chat's title, which
 * names the conversation in the per-chat word lists, the typing log and the
 * notes built from them, all stored on this device.
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
            // A window with no title of its own must not keep the last chat's.
            if (t.isNullOrBlank()) windowTitles.remove(pkg) else windowTitles[pkg] = t
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

    /** Window titles seen per app (TYPE_WINDOW_STATE_CHANGED). Written on the main
     *  thread and read on the walk's. */
    private val windowTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** App names, only touched on the walk's thread. */
    private val appLabels = HashMap<String, String>()

    private fun appLabel(pkg: String): String? = appLabels[pkg] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
            .toString().also { appLabels[pkg] = it }
    } catch (_: Throwable) {
        null
    }

    /** What one walk of the tree has found so far. */
    private class Walk {
        val texts = ArrayList<ChatCapture.TextNode>(48)
        val boxes = ArrayList<ChatCapture.ScrollBox>(4)
        var chars = 0
    }

    private fun captureRoot(root: AccessibilityNodeInfo) {
        val pkg = root.packageName?.toString()
        try {
            if (pkg == null || pkg == packageName ||
                pkg.startsWith("com.android.systemui") || prefs.isAppBlocked(pkg)
            ) return
            val walk = Walk()
            collectNodes(root, walk, 0, -1)
            if (walk.texts.isEmpty()) return
            val dm = resources.displayMetrics
            val shaped = ChatCapture.shape(walk.texts, walk.boxes, dm.widthPixels, dm.heightPixels)
            if (shaped.turns.isEmpty()) return
            ScreenBuffer.put(pkg, shaped.turns, threadTitle(pkg, shaped), shaped.atBottom)
        } catch (_: Throwable) {
            // This walk shares a process with the keyboard. A node that went stale in a way
            // the walk did not expect costs one capture, and must not cost the keyboard.
        } finally {
            root.recycle()
        }
    }

    /** Who this conversation is with, or "" when nothing on screen says. A line that is
     *  really the app's own wording ("Messages", "online") would put every thread of the app
     *  under one name, so it is refused here. With a list the bar above it is the better
     *  witness; without one the top band can hold a message, so the window title goes first. */
    private fun threadTitle(pkg: String, shaped: ChatCapture.Result): String {
        val label = appLabel(pkg)
        fun usable(signals: ConversationSignals): String? =
            ConversationKeyResolver.resolve(signals)
                .takeIf { it.confidence != KeyConfidence.LOW }?.title
        val fromWindow = usable(ConversationSignals(pkg, label, windowTitle = windowTitles[pkg]))
        if (!shaped.listFound && fromWindow != null) return fromWindow
        return shaped.titleCandidates.firstNotNullOfOrNull {
            usable(ConversationSignals(pkg, label, toolbarTitle = it))
        } ?: fromWindow ?: ""
    }

    /** A pager or a sideways strip scrolls too, but messages are not laid out along it. */
    private fun isVerticalList(node: AccessibilityNodeInfo, cls: String): Boolean {
        if (cls.contains("ViewPager") || cls.contains("HorizontalScrollView")) return false
        val info = node.collectionInfo
        if (info != null && info.rowCount == 1 && info.columnCount != 1) return false
        return node.isScrollable || info != null ||
            cls.endsWith("RecyclerView") || cls.endsWith("ListView")
    }

    private fun canScrollForward(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id ||
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        }

    /** A view id only speaks for the side through its own name. The package in front of it
     *  could say anything, and "sent" is inside plenty of ordinary words. */
    private fun sideFromViewId(viewId: String?): Speaker? {
        val name = viewId?.substringAfter(":id/") ?: return null
        return when {
            OUTGOING_RE.containsMatchIn(name) -> Speaker.ME
            INCOMING_RE.containsMatchIn(name) -> Speaker.THEM
            else -> null
        }
    }

    /** Depth-first walk collecting text WITH attribution metadata, and noting every list on
     *  the way so the caller can keep only what is inside the conversation. Bounded by depth,
     *  node count and characters, so a huge view tree can't stall the service. Password fields
     *  are never exposed by the framework, so they can't leak in here. */
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        walk: Walk,
        depth: Int,
        box: Int
    ): Boolean {
        if (node == null || depth > 22) return false
        if (walk.texts.size > 120 || walk.chars > ScreenBuffer.MAX_CHARS * 2) return true
        try {
            val cls = node.className?.toString() ?: ""
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val isList = isVerticalList(node, cls)
            val myBox = if (isList) {
                walk.boxes += ChatCapture.ScrollBox(
                    parent = box,
                    depth = depth,
                    area = bounds.width().toLong() * bounds.height(),
                    top = bounds.top,
                    canScrollForward = canScrollForward(node)
                )
                walk.boxes.lastIndex
            } else box
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
                val badgeLike = viewId != null && BADGE_ID_RE.containsMatchIn(viewId.substringAfter(":id/"))
                // A container that merely repeats its children's text would
                // double every line — only emit when no direct child speaks.
                val childSpeaks = children?.any { (it?.text?.length ?: 0) > 1 } == true
                if (!childSpeaks && !TIME_RE.matches(t) &&
                    !(badgeLike && BADGE_RE.matches(t))
                ) {
                    walk.texts += ChatCapture.TextNode(
                        t, bounds.top, bounds.left, bounds.right, sideFromViewId(viewId), myBox
                    )
                    walk.chars += t.length
                }
            }
            if (children != null) {
                // Newest first in a list, by where each child sits and not its index, because
                // a list can lay out from the bottom. The caps above then cost the oldest lines.
                val order = if (isList) children.sortedByDescending { bottomOf(it) } else children
                var stop = false
                for (child in order) {
                    if (child == null) continue
                    if (!stop) stop = collectNodes(child, walk, depth + 1, myBox)
                    child.recycle()
                }
                if (stop) return true
            }
        } catch (_: Throwable) {
            // A stale node (window changed mid-walk) — just stop this branch.
        }
        return false
    }

    private fun bottomOf(node: AccessibilityNodeInfo?): Int {
        if (node == null) return Int.MIN_VALUE
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return r.bottom
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
        /** Chat-row timestamps ("3:42", "3:42 PM"). Numeric-only texts are
         *  only chrome when the view-id says badge/count — "42" is a legal
         *  message (P0). */
        private val TIME_RE = Regex("\\d{1,2}:\\d{2}(\\s?[APap][Mm])?")
        private val BADGE_RE = Regex("\\d{1,2}")
        private val BADGE_ID_RE = Regex("badge|count|unread")
        /** View-id fragments that declare a bubble's side outright. */
        private val OUTGOING_RE = Regex("(^|_)(outgoing|sent)(_|$)|from_me|my_message|_me$")
        private val INCOMING_RE = Regex("(^|_)(incoming|received)(_|$)|from_them|their_message")

        /** Same-process handle so the IME can ask for a capture on show. */
        @Volatile
        var instance: ScreenContextService? = null

        /** This build ships the screen reader. The Play build does not. */
        const val AVAILABLE = true
    }
}
