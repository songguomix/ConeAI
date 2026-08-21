package com.cone.agent.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Bitmap helpers for screenshot downscaling, encoding and persistence. */
object ImageUtils {

    /** Scales the bitmap so its longest edge is at most [maxEdge], keeping aspect ratio. */
    fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val software = toSoftwareBitmap(src)
        val longest = maxOf(software.width, software.height)
        if (longest <= maxEdge) return software
        val scale = maxEdge.toFloat() / longest
        val w = (software.width * scale).toInt().coerceAtLeast(1)
        val h = (software.height * scale).toInt().coerceAtLeast(1)
        val result = Bitmap.createScaledBitmap(software, w, h, true)
        if (software !== src) software.recycle()
        return result
    }

    /**
     * Ensures the bitmap is in a software-accessible format (not Hardware). Required before
     * cropping or any pixel-level access on many Android versions.
     */
    fun toSoftwareBitmap(src: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        return src
    }

    fun toJpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
        val software = toSoftwareBitmap(bitmap)
        val out = ByteArrayOutputStream()
        software.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        if (software !== bitmap) software.recycle()
        return bytes
    }

    fun toDataUrl(bitmap: Bitmap, maxEdge: Int, quality: Int): String =
        toDataUrl(downscale(bitmap, maxEdge), quality)

    /** Encodes an already-sized bitmap as a JPEG data URL (no further downscaling). */
    fun toDataUrl(bitmap: Bitmap, quality: Int): String {
        val bytes = toJpegBytes(bitmap, quality)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    /** Persists a downscaled JPEG into the app files dir and returns the absolute path. */
    fun saveScreenshot(dir: File, bitmap: Bitmap, maxEdge: Int, quality: Int): String? = runCatching {
        if (!dir.exists()) dir.mkdirs()
        val scaled = downscale(bitmap, maxEdge)
        val file = File(dir, "screen_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { it.write(toJpegBytes(scaled, quality)) }
        file.absolutePath
    }.getOrNull()

    fun loadBitmap(path: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(path)
    }.getOrNull()

    /**
     * Rotates/flips [src] so its pixels match the given EXIF [orientation]. Many phone photos store
     * the sensor orientation as an EXIF tag rather than baked-into pixels; decoding without applying
     * it (and then re-compressing, which drops EXIF) shows the picture rotated 90°. Returns [src]
     * unchanged for a normal / unknown orientation.
     */
    fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return src
        }
        return runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                .also { if (it !== src) src.recycle() }
        }.getOrDefault(src)
    }

    /** Reads the EXIF orientation tag from a JPEG stream; [ExifInterface.ORIENTATION_NORMAL] if none. */
    fun readOrientation(stream: java.io.InputStream): Int = runCatching {
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}
