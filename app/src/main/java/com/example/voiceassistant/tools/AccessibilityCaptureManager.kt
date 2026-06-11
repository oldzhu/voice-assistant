package com.example.voiceassistant.tools

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Static bridge between VoiceService and ScreenCaptureAccessibilityService.
 *
 * Takes screenshots via AccessibilityService.takeScreenshot() (API 30+).
 * Zero runtime permission dialog — just needs one-time enable in Settings > Accessibility.
 *
 * Flow:
 *   1. Tool calls [requestCapture] → sets up CompletableDeferred
 *   2. AccessibilityService calls back with bitmap
 *   3. deferred completes → tool receives bitmap
 *
 * Falls back to ScreenCaptureManager (MediaProjection) if accessibility not enabled.
 */
object AccessibilityCaptureManager {
    private const val TAG = "AccessibilityCapture"
    private const val CAPTURE_TIMEOUT_MS = 10_000L

    @Volatile
    private var pendingResult: CompletableDeferred<Bitmap?>? = null

    /** The accessibility service instance. Set by ScreenCaptureAccessibilityService. */
    @Volatile
    var service: ScreenCaptureAccessibilityService? = null

    /** Whether the accessibility service is enabled and ready. */
    val isAvailable: Boolean get() = service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Request a screenshot via AccessibilityService.
     * @return Bitmap, or null if not available / failed / timed out.
     */
    suspend fun requestCapture(): Bitmap? {
        val svc = service ?: run {
            Log.w(TAG, "AccessibilityService not available")
            return null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+")
            return null
        }

        val deferred = CompletableDeferred<Bitmap?>()
        pendingResult = deferred

        try {
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    svc.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        Executors.newSingleThreadExecutor(),
                        TakeScreenshotCallback { bitmap ->
                            if (bitmap != null) {
                                Log.i(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")
                            }
                            deferred.complete(bitmap)
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "takeScreenshot threw", e)
                    if (!deferred.isCompleted) deferred.complete(null)
                }
            }

            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                deferred.await()
            }
        } finally {
            pendingResult = null
        }
    }

    /**
     * Wrapper to bridge the AccessibilityService callback to our CompletableDeferred.
     * Uses reflection-like approach since TakeScreenshotCallback is an abstract class in newer APIs.
     */
    @Suppress("DEPRECATION")
    private class TakeScreenshotCallback(
        private val onResult: (Bitmap?) -> Unit
    ) : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {

        override fun onSuccess(screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
            try {
                val hwBuffer: HardwareBuffer = screenshotResult.hardwareBuffer
                val colorSpace: ColorSpace = screenshotResult.colorSpace
                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                onResult(bitmap)
                hwBuffer.close()
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot processing failed", e)
                onResult(null)
            }
        }

        override fun onFailure(errorCode: Int) {
            Log.e(TAG, "takeScreenshot failed: errorCode=$errorCode")
            onResult(null)
        }
    }
}
