package com.example.voiceassistant.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityService supporting screen capture, gestures, and window content access.
 *
 * Capabilities:
 * - takeScreenshot() — silent screen capture (API 30+)
 * - dispatchGesture() — tap, swipe, long-press (API 24+)
 * - window content — read UI tree, find nodes by text/id/content-desc
 * - TYPE_VIEW_TEXT_CHANGED / ACTION_SET_TEXT — type into text fields
 *
 * User enables this once in Settings > Accessibility > 猪头助手.
 */
class ScreenCaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenCapAS"
    }

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityCaptureManager.service = this
        GestureManager.service = this
        Log.i(TAG, "Accessibility service connected — screenshot + gestures + window content ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Feed events to GestureManager for element-waiting
        GestureManager.onAccessibilityEvent(event)

        // Log window state changes for debugging
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val cls = event.className?.toString() ?: ""
                Log.d(TAG, "Window: $pkg / $cls")
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: ""
                Log.d(TAG, "Clicked: $text")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityCaptureManager.service = null
        GestureManager.service = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityCaptureManager.service = null
        GestureManager.service = null
        return super.onUnbind(intent)
    }
}
