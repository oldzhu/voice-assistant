package com.example.voiceassistant.tools

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal AccessibilityService — only used for takeScreenshot().
 *
 * No window content access, no event processing.
 * User enables this once in Settings > Accessibility > 猪头助手.
 *
 * After enabling, the bridge in [AccessibilityCaptureManager] handles
 * silent screenshot capture with no permission dialog.
 */
class ScreenCaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenCapAS"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityCaptureManager.service = this
        Log.i(TAG, "Accessibility service connected — screenshot ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op — we only use takeScreenshot(), not event monitoring
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityCaptureManager.service = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityCaptureManager.service = null
        return super.onUnbind(intent)
    }
}
