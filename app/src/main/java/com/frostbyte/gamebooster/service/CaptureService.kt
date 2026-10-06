package com.frostbyte.gamebooster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frostbyte.gamebooster.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screenshot + screen recording with Android's official MediaProjection.
 * Every capture needs your tap on Android's own prompt. A visible notification is shown the whole time,
 * recording has a STOP button, and files are saved to Pictures/FrostByte and Movies/FrostByte.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_SHOT = "com.frostbyte.gamebooster.CAPTURE_SHOT"
        const val ACTION_REC = "com.frostbyte.gamebooster.CAPTURE_REC"
        const val ACTION_STOP = "com.frostbyte.gamebooster.CAPTURE_STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CH = "capture"
        private const val NOTIF = 11

        @Volatile
        var recording = false
            private set

        fun begin(ctx: Context, mode: String, code: Int, data: Intent) {
            val action = if (mode == "rec") ACTION_REC else ACTION_SHOT
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, CaptureService::class.java).setAction(action).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }

        fun stopRecording(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var recStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent
        if (i == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (i.action) {
            ACTION_SHOT, ACTION_REC -> {
                if (projection != null) return START_NOT_STICKY
                val rec = i.action == ACTION_REC
                startForeground(NOTIF, notification(rec), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                val data = i.getParcelableExtra<Intent>(EXTRA_DATA)
                val code = i.getIntExtra(EXTRA_CODE, 0)
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = if (data == null) null else try { mpm.getMediaProjection(code, data) } catch (_: Exception) { null }
                if (p == null) {
                    toast("Couldn't start capture")
                    finishUp()
                    return START_NOT_STICKY
                }
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        handler.post { if (recording) stopRecording() }
                    }
                }, handler)
                projection = p
                // short pause so Android's prompt is fully gone before we capture
                handler.postDelayed({ if (rec) startRecording() else takeScreenshot() }, 600)
            }
            ACTION_STOP -> if (recording) stopRecording() else finishUp()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ screenshot

    private fun takeScreenshot() {
        val p = projection ?: return finishUp()
        val (w, h, dpi) = screenSize()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        var done = false
        r.setOnImageAvailableListener({ ir ->
            if (done) return@setOnImageAvailableListener
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            done = true
            Thread {
                val ok = try { saveScreenshot(img) } catch (_: Exception) { false } finally { img.close() }
                handler.post {
                    toast(if (ok) "Screenshot saved to Pictures/FrostByte" else "Screenshot failed")
                    finishUp()
                }
            }.start()
        }, handler)
        display = p.createVirtualDisplay("frostbyte-shot", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
        handler.postDelayed({
            if (!done) {
                done = true
                toast("Screenshot failed")
                finishUp()
            }
        }, 4000)
    }

    private fun saveScreenshot(img: Image): Boolean {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val wide = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        wide.copyPixelsFromBuffer(plane.buffer)
        val out = Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, stamp("FrostByte_", "jpg"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FrostByte")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val wrote = contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        if (!wrote) contentResolver.delete(uri, null, null)
        wide.recycle()
        out.recycle()
        return wrote
    }

    // ------------------------------------------------------------ recording (video only)

    private fun startRecording() {
        val p = projection ?: return finishUp()
        try {
            val (w0, h0, dpi) = screenSize()
            val scale = minOf(1f, 1280f / maxOf(w0, h0))
            val w = (w0 * scale).toInt() / 2 * 2
            val h = (h0 * scale).toInt() / 2 * 2

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, stamp("FrostByte_", "mp4"))
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/FrostByte")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("no uri")
            recUri = uri
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("no fd")
            recPfd = pfd

            val rec = newRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(6_000_000)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.prepare()
            recorder = rec
            display = p.createVirtualDisplay("frostbyte-rec", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, handler)
            rec.start()
            recording = true
            recStart = System.currentTimeMillis()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF, notification(true))
            toast("Recording started. Stop it from the notification or the panel.")
        } catch (_: Exception) {
            toast("Screen recording failed on this device")
            cleanupRecorder(save = false)
            finishUp()
        }
    }

    private fun stopRecording() {
        val ok = cleanupRecorder(save = true)
        toast(if (ok) "Recording saved to Movies/FrostByte" else "Recording was too short to save")
        finishUp()
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun cleanupRecorder(save: Boolean): Boolean {
        var ok = save
        recording = false
        try { recorder?.stop() } catch (_: RuntimeException) { ok = false }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        display?.release()
        display = null
        try { recPfd?.close() } catch (_: Exception) { }
        recPfd = null
        recUri?.let { uri ->
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, v, null, null)
            } else {
                contentResolver.delete(uri, null, null)
            }
        }
        recUri = null
        return ok
    }

    // ------------------------------------------------------------ helpers

    private fun finishUp() {
        recording = false
        display?.release(); display = null
        reader?.close(); reader = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) cleanupRecorder(save = true)
        display?.release()
        reader?.close()
        try { projection?.stop() } catch (_: Exception) { }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    private fun stamp(prefix: String, ext: String) =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + ext

    private fun notification(rec: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_frost)
            .setContentTitle(if (rec) "\u25CF FrostByte is recording your screen" else "FrostByte is taking a screenshot")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (rec) {
            val stop = PendingIntent.getService(
                this, 5, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.setUsesChronometer(true).setShowWhen(true).setWhen(if (recStart == 0L) System.currentTimeMillis() else recStart)
            b.setContentText("Tap STOP when you're done")
            b.addAction(0, "STOP RECORDING", stop)
        }
        return b.build()
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
