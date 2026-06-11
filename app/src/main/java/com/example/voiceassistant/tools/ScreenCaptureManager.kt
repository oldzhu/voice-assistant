package com.example.voiceassistant.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/**
 * Static bridge between VoiceService (tool) and MainActivity (UI) for screen capture.
 *
 * Flow:
 *   1. Tool calls [requestCapture] → launches system permission dialog via MainActivity
 *   2. User grants permission → MainActivity calls [onActivityResult]
 *   3. MediaProjection captures screen → bitmap → completes [pendingResult]
 *   4. Tool reads the bitmap, converts to base64, sends to vision API
 *
 * Only one capture can be pending at a time.
 */
object ScreenCaptureManager {
    private const val TAG = "ScreenCapture"
    private const val CAPTURE_TIMEOUT_MS = 30000L
    private const val REQUEST_CODE = 9001

    @Volatile
    private var pendingResult: CompletableDeferred<Bitmap?>? = null

    /** The activity that will handle the permission dialog. Set by MainActivity. */
    @Volatile
    var activity: Activity? = null

    /** Callback to launch the screen capture intent. Set by MainActivity. */
    @Volatile
    var launchIntent: ((Intent) -> Unit)? = null

    /**
     * Request a screen capture. Suspends until the user grants permission
     * and the screen is captured, or times out.
     *
     * @return The captured bitmap, or null if failed/timed out.
     */
    suspend fun requestCapture(context: Context): Bitmap? {
        val act = activity ?: run {
            Log.e(TAG, "No activity registered — cannot request screen capture")
            return null
        }
        val deferred = CompletableDeferred<Bitmap?>()
        pendingResult = deferred

        try {
            // Launch MediaProjection permission dialog via MainActivity's launcher
            val launcher = launchIntent
            if (launcher == null) {
                Log.e(TAG, "No launchIntent registered")
                deferred.complete(null)
                return null
            }
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val intent = mgr.createScreenCaptureIntent()
            act.runOnUiThread {
                try {
                    launcher(intent)
                    Log.i(TAG, "Screen capture permission dialog launched")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch screen capture intent", e)
                    deferred.complete(null)
                }
            }

            // Wait for result (or timeout)
            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                deferred.await()
            }
        } finally {
            pendingResult = null
        }
    }

    /**
     * Called by MainActivity.onActivityResult when the user responds to
     * the screen capture permission dialog.
     */
    fun onActivityResult(resultCode: Int, data: Intent?) {
        val deferred = pendingResult ?: return
        val act = activity ?: run {
            deferred.complete(null)
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "User denied screen capture permission")
            deferred.complete(null)
            return
        }

        try {
            val mgr = act.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)

            // Delay 3 seconds to let user switch back to their target app
            Log.i(TAG, "Permission granted — waiting 3s for user to switch back...")
            Handler(Looper.getMainLooper()).postDelayed({
                val bitmap = captureScreen(act, projection)
                projection.stop()
                deferred.complete(bitmap)
                Log.i(TAG, "Screen captured: ${bitmap?.width}x${bitmap?.height}")
            }, 3000L)
        } catch (e: Exception) {
            Log.e(TAG, "Screen capture failed", e)
            deferred.complete(null)
        }
    }

    private fun captureScreen(context: Context, projection: MediaProjection): Bitmap? {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display: VirtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, Handler(Looper.getMainLooper())
        )

        // Wait for the image to be available
        var capturedBitmap: Bitmap? = null
        try {
            val image: Image = reader.acquireLatestImage()
                ?: reader.acquireNextImage()
                ?: return null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            capturedBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            capturedBitmap!!.copyPixelsFromBuffer(buffer)

            // Crop to actual width (remove padding)
            if (rowPadding > 0) {
                capturedBitmap = Bitmap.createBitmap(capturedBitmap!!, 0, 0, width, height)
            }

            image.close()
        } catch (e: Exception) {
            Log.e(TAG, "Image capture error", e)
        } finally {
            display.release()
            reader.close()
        }

        return capturedBitmap
    }
}
