package com.cone.agent.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the MediaProjection session and serves real-pixel screenshots on demand. Frames stream into
 * an [ImageReader]; [capture] returns a safe copy of the most recent frame.
 */
@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var latest: Bitmap? = null
    private val frameLock = Any()

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val isRunning: Boolean get() = projection != null

    @Suppress("DEPRECATION")
    fun start(resultCode: Int, data: Intent) {
        if (projection != null) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        projection = projectionManager.getMediaProjection(resultCode, data)?.also { mp ->
            handlerThread = HandlerThread("cone-capture").apply { start() }
            handler = Handler(handlerThread!!.looper)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    teardown()
                }
            }, handler)

            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 2,
            ).apply {
                setOnImageAvailableListener({ reader -> onFrame(reader) }, handler)
            }

            virtualDisplay = mp.createVirtualDisplay(
                "cone-virtual-display",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                handler,
            )
            _running.value = true
        }
    }

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bmpWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bmpWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (bmpWidth != screenWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also { bitmap.recycle() }
            } else {
                bitmap
            }
            synchronized(frameLock) {
                val previous = latest
                latest = cropped
                if (previous != null && previous !== cropped && !previous.isRecycled) previous.recycle()
            }
        } catch (_: Throwable) {
            // Drop the frame; the next one will replace it.
        } finally {
            image.close()
        }
    }

    /** Returns a defensive copy of the freshest frame, waiting briefly for the first one. */
    suspend fun capture(timeoutMs: Long = 1500L): Bitmap? {
        if (projection == null) return null
        // Polled finely: the wait only happens before the very first frame of a session, and a coarse
        // interval would sit idle for most of it on a frame that had already landed.
        var waited = 0L
        while (latest == null && waited < timeoutMs) {
            delay(FRAME_POLL_MS)
            waited += FRAME_POLL_MS
        }
        // Copy while holding the lock so a new frame can't recycle this bitmap mid-copy.
        return synchronized(frameLock) {
            val current = latest ?: return null
            runCatching { current.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
    }

    fun stop() {
        projection?.stop()
        teardown()
    }

    private companion object {
        const val FRAME_POLL_MS = 20L
    }

    private fun teardown() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { handlerThread?.quitSafely() }
        synchronized(frameLock) {
            latest?.let { if (!it.isRecycled) it.recycle() }
            latest = null
        }
        virtualDisplay = null
        imageReader = null
        handler = null
        handlerThread = null
        projection = null
        _running.value = false
    }
}
