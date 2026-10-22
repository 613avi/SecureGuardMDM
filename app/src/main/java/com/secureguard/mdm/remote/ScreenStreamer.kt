package com.secureguard.mdm.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.secureguard.mdm.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

object RemoteScreenInfo {
    @Suppress("DEPRECATION")
    fun realMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    }
}

/**
 * Captures the screen and emits scaled JPEG frames to the portal for live support.
 *
 * Two capture backends share one frame-encode/throttle path:
 *  - MediaProjection: one system consent prompt per session; works on all supported API levels.
 *  - Accessibility takeScreenshot (API 30+): no prompt, used for the unattended-support mode.
 */
class ScreenStreamer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFrame: (bytes: ByteArray, width: Int, height: Int, seq: Int) -> Unit,
    private val onState: (state: String, message: String?) -> Unit
) {
    private var job: Job? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var seq = 0

    @Volatile private var latest: Bitmap? = null
    private val latestLock = Any()

    var mode: String = "auto"; private set
    private var maxWidth = 720
    private var quality = 50
    private var fps = 4

    val isRunning: Boolean get() = job?.isActive == true

    fun startProjection(mp: MediaProjection, maxWidth: Int, quality: Int, fps: Int) {
        stop()
        this.mode = "projection"; this.maxWidth = maxWidth; this.quality = quality; this.fps = fps
        projection = mp
        val metrics = RemoteScreenInfo.realMetrics(context)
        val (w, h) = scaledSize(metrics.widthPixels, metrics.heightPixels)
        handlerThread = HandlerThread("rc-projection").also { it.start() }
        val handler = Handler(handlerThread!!.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { onState("stopped", "projection_stopped") }
        }, handler)
        virtualDisplay = mp.createVirtualDisplay(
            "rc-display", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h).also { bmp.recycle() }
                setLatest(cropped)
            } catch (e: Exception) {
                AppLogger.e(TAG, "projection frame error", e)
            } finally {
                image.close()
            }
        }, handler)
        onState("streaming", "projection")
        startEmitLoop()
    }

    fun startAccessibility(maxWidth: Int, quality: Int, fps: Int) {
        stop()
        this.mode = "accessibility"; this.maxWidth = maxWidth; this.quality = quality; this.fps = fps.coerceAtMost(3)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || RemoteInputService.instance == null) {
            onState("unavailable", "accessibility_unavailable")
            return
        }
        onState("streaming", "accessibility")
        job = scope.launch {
            while (isActive) {
                val svc = RemoteInputService.instance
                if (svc == null) { onState("unavailable", "accessibility_unavailable"); break }
                svc.captureScreen { bmp -> if (bmp != null) setLatest(scaleIfNeeded(bmp)) }
                emitLatest()
                delay((1000L / this@ScreenStreamer.fps).coerceAtLeast(350))
            }
        }
    }

    private fun startEmitLoop() {
        job = scope.launch {
            while (isActive) {
                emitLatest()
                delay((1000L / fps).coerceAtLeast(60))
            }
        }
    }

    private fun setLatest(bmp: Bitmap) {
        synchronized(latestLock) {
            latest?.recycle()
            latest = bmp
        }
    }

    private fun emitLatest() {
        val bmp = synchronized(latestLock) { latest } ?: return
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        onFrame(baos.toByteArray(), bmp.width, bmp.height, seq++)
    }

    private fun scaledSize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= maxWidth) return w to h
        val ratio = maxWidth.toDouble() / w
        val sw = (w * ratio).toInt() and 0x7FFFFFFE
        val sh = (h * ratio).toInt() and 0x7FFFFFFE
        return sw to sh
    }

    private fun scaleIfNeeded(bmp: Bitmap): Bitmap {
        if (bmp.width <= maxWidth) return bmp
        val (sw, sh) = scaledSize(bmp.width, bmp.height)
        return Bitmap.createScaledBitmap(bmp, sw, sh, true).also { if (it !== bmp) bmp.recycle() }
    }

    fun stop() {
        job?.cancel(); job = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        handlerThread?.quitSafely(); handlerThread = null
        synchronized(latestLock) { latest?.recycle(); latest = null }
        seq = 0
    }

    companion object {
        private const val TAG = "ScreenStreamer"
    }
}
