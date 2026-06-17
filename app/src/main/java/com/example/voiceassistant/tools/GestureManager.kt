package com.example.voiceassistant.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Static bridge for accessibility gestures and window content access.
 *
 * After the AccessibilityService is enabled, [service] is set by
 * [ScreenCaptureAccessibilityService.onServiceConnected].
 *
 * Tools call these methods to interact with the foreground app:
 * - [tap] / [longPress] / [swipe] — touch gestures
 * - [typeText] — input text into focused field
 * - [getInteractiveElements] — get clickable/typeable elements with positions
 * - [waitForElement] — wait until element with matching text appears
 * - [performBack] / [performHome] / [performRecents] — system navigation
 */
object GestureManager {
    private const val TAG = "GestureManager"
    private const val DEFAULT_TIMEOUT_MS = 5000L

    @Volatile
    var service: AccessibilityService? = null

    val isAvailable: Boolean get() = service != null

    // ── Event watching for waitForElement ─────────────────────

    @Volatile
    private var eventWatcher: CompletableDeferred<Unit>? = null
    @Volatile
    private var eventWatchTarget: String = ""

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val watcher = eventWatcher ?: return
        if (watcher.isCompleted) return
        val target = eventWatchTarget.lowercase()

        // Check event text
        for (i in 0 until event.text.size) {
            if (event.text[i].toString().lowercase().contains(target)) {
                eventWatcher = null
                watcher.complete(Unit)
                return
            }
        }
        // Check content description
        val desc = event.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains(target)) {
            eventWatcher = null
            watcher.complete(Unit)
        }
    }

    // ── Gestures ──────────────────────────────────────────────

    /**
     * Tap at coordinates (center of screen by default).
     */
    suspend fun tap(x: Float, y: Float): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50L)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, desc, "tap(${x.toInt()},${y.toInt()})")
    }

    /**
     * Long-press at coordinates.
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 800L): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, desc, "longPress(${x.toInt()},${y.toInt()})")
    }

    /**
     * Swipe from (x1,y1) to (x2,y2).
     */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, desc, "swipe(${x1.toInt()},${y1.toInt()}→${x2.toInt()},${y2.toInt()})")
    }

    /**
     * Built-in swipe directions using display size.
     */
    suspend fun swipeDirection(direction: String): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        // Get display size from root node or use reasonable defaults
        val root = svc.rootInActiveWindow
        val displayW: Int
        val displayH: Int
        if (root != null) {
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            displayW = bounds.width()
            displayH = bounds.height()
            root.recycle()
        } else {
            displayW = svc.resources.displayMetrics.widthPixels
            displayH = svc.resources.displayMetrics.heightPixels
        }

        val cx = displayW / 2f
        val cy = displayH / 2f

        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy * 1.4f, cx, cy * 0.6f)
            "down" -> swipe(cx, cy * 0.6f, cx, cy * 1.4f)
            "left" -> swipe(cx * 1.6f, cy, cx * 0.4f, cy)
            "right" -> swipe(cx * 0.4f, cy, cx * 1.6f, cy)
            else -> Result.failure(Exception("未知滑动方向: $direction。可用: up, down, left, right"))
        }
    }

    private suspend fun dispatchGesture(
        svc: AccessibilityService,
        desc: GestureDescription,
        label: String
    ): Result<Unit> = suspendCancellableCoroutine { cont ->
        val dispatched = svc.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "$label → OK")
                if (cont.isActive) cont.resume(Result.success(Unit))
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "$label → CANCELLED")
                if (cont.isActive) cont.resume(Result.failure(Exception("手势被取消")))
            }
        }, null)
        if (!dispatched) {
            Log.e(TAG, "$label → dispatchGesture returned false")
            if (cont.isActive) cont.resume(Result.failure(Exception("手势发送失败(dispatchGesture=false)")))
        }
    }

    // ── Text Input ────────────────────────────────────────────

    /**
     * Type text into the currently focused editable field.
     * Falls back to finding any editable field in the window.
     */
    fun typeText(text: String): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))

        // Method 1: Find focused editable node
        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return setTextOnNode(focused, text)
        }

        // Method 2: Find any editable node
        val root = svc.rootInActiveWindow ?: return Result.failure(Exception("无法获取窗口内容"))
        val editable = findFirstEditable(root)
        root.recycle()

        if (editable != null) {
            return setTextOnNode(editable, text)
        }

        return Result.failure(Exception("未找到可输入的文本框"))
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) {
                // Recycle child if found is a deeper descendant, not child itself
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Result<Unit> {
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            node.recycle()
            if (ok) {
                Log.d(TAG, "text typed: ${text.take(30)}")
                Result.success(Unit)
            } else {
                Result.failure(Exception("ACTION_SET_TEXT 执行失败"))
            }
        } catch (e: Exception) {
            try { node.recycle() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    // ── Window Content ────────────────────────────────────────

    /**
     * Represents a UI element visible on screen.
     */
    data class ScreenElement(
        val text: String,
        val contentDesc: String,
        val className: String,
        val resourceId: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val bounds: Rect    // screen coordinates
    ) {
        fun centerX(): Int = bounds.centerX()
        fun centerY(): Int = bounds.centerY()

        fun summary(): String = buildString {
            val label = when {
                text.isNotBlank() -> text.take(40)
                contentDesc.isNotBlank() -> "[$contentDesc]".take(40)
                else -> className.substringAfterLast('.')
            }
            append("$label @(${centerX()},${centerY()})")
            val tags = mutableListOf<String>()
            if (isClickable) tags.add("可点击")
            if (isEditable) tags.add("可输入")
            if (isScrollable) tags.add("可滚动")
            if (tags.isNotEmpty()) append(" [${tags.joinToString()}]")
        }
    }

    /**
     * Get all interactive (clickable/editable/scrollable) elements from the window.
     * Returns a list of [ScreenElement] with positions for tap targeting.
     */
    fun getInteractiveElements(maxResults: Int = 30): Result<List<ScreenElement>> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val root = svc.rootInActiveWindow ?: return Result.failure(Exception("无法获取窗口内容"))

        val elements = mutableListOf<ScreenElement>()
        collectInteractiveElements(root, elements, maxResults)
        root.recycle()

        Log.i(TAG, "Found ${elements.size} interactive elements")
        return Result.success(elements)
    }

    private fun collectInteractiveElements(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenElement>,
        max: Int
    ) {
        if (out.size >= max) return

        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable ||
            node.isCheckable || node.isFocusable

        if (isInteractive || text.isNotBlank() || contentDesc.isNotBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Only include elements that are actually visible on screen
            if (bounds.width() > 0 && bounds.height() > 0) {
                out.add(ScreenElement(
                    text = text,
                    contentDesc = contentDesc,
                    className = node.className?.toString() ?: "",
                    resourceId = node.viewIdResourceName ?: "",
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    bounds = bounds
                ))
            }
        }

        for (i in 0 until node.childCount) {
            if (out.size >= max) break
            val child = node.getChild(i) ?: continue
            collectInteractiveElements(child, out, max)
            child.recycle()
        }
    }

    /**
     * Find the first element whose text or content description matches [target].
     */
    fun findElement(target: String): ScreenElement? {
        val result = getInteractiveElements(maxResults = 100)
        val elements = result.getOrNull() ?: return null
        val t = target.lowercase().trim()
        return elements.find {
            it.text.lowercase().contains(t) ||
                it.contentDesc.lowercase().contains(t) ||
                it.resourceId.lowercase().contains(t)
        }
    }

    // ── Waiting ───────────────────────────────────────────────

    /**
     * Wait for an element containing [text] to appear on screen.
     * Listens to accessibility events and polls the window tree.
     */
    suspend fun waitForElement(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<ScreenElement> {
        val target = text.lowercase().trim()
        val pollIntervalMs = 500L
        val deadline = System.currentTimeMillis() + timeoutMs

        // Start event watcher
        val watcher = CompletableDeferred<Unit>()
        eventWatcher = watcher
        eventWatchTarget = target

        try {
            while (System.currentTimeMillis() < deadline) {
                // Check window content now
                val el = findElement(target)
                if (el != null) {
                    Log.i(TAG, "waitForElement '$target' → found after ${timeoutMs - (deadline - System.currentTimeMillis())}ms")
                    return Result.success(el)
                }

                // Wait for next event or poll interval
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break

                withTimeoutOrNull(remaining.coerceAtMost(pollIntervalMs)) {
                    watcher.await()
                }
            }

            Log.w(TAG, "waitForElement '$target' → timeout after ${timeoutMs}ms")
            return Result.failure(Exception("超时：${timeoutMs / 1000}秒内未找到「$text」"))
        } finally {
            eventWatcher = null
        }
    }

    // ── System Navigation ─────────────────────────────────────

    fun performBack(): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("返回键执行失败"))
    }

    fun performHome(): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("Home键执行失败"))
    }

    fun performRecents(): Result<Unit> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("最近任务键执行失败"))
    }

    fun performEnter(): Result<Unit> {
        // Dispatch an Enter key event via ACTION_NEXT_AT_MOVEMENT_GRANULARITY + IME_ACTION_SEARCH
        // Alternatively, find focused node and send KEYCODE_ENTER
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return Result.failure(Exception("无焦点输入框"))
        // Try IME search action (works in search fields)
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        focused.recycle()
        // Fall back to tapping search button if present — but that's handled by LLM via tap_screen
        return if (ok) Result.success(Unit)
        else Result.failure(Exception("回车操作失败"))
    }

    // ── App Management ────────────────────────────────────────

    /**
     * Check which app is currently in the foreground.
     */
    fun getCurrentPackageName(): Result<String> {
        val svc = service ?: return Result.failure(Exception("无障碍服务未开启"))
        val root = svc.rootInActiveWindow ?: return Result.failure(Exception("无法获取窗口内容"))
        val pkg = root.packageName?.toString() ?: ""
        root.recycle()
        return if (pkg.isNotBlank()) Result.success(pkg)
        else Result.failure(Exception("无法获取当前应用包名"))
    }
}
