package com.cone.agent.vision

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import com.cone.agent.R
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Saves a model-generated image to the user's device from anywhere in the app (chat or the assistant
 * pill). Two paths:
 *  - `http(s)` URLs go through the system [DownloadManager] → public Downloads, with a notification
 *    and no storage permission needed on any API level.
 *  - `data:image/...;base64,...` URLs are decoded and written to the gallery via [MediaStore].
 *
 * Always shows a short toast so the user knows the download started / saved / failed.
 */
object ImageDownloader {

    /** [model] is an `http(s)` link, a `data:` URL, or a local file path (an uploaded image). */
    fun save(context: Context, model: String) {
        val app = context.applicationContext
        runCatching {
            when {
                model.startsWith("http", ignoreCase = true) -> downloadRemote(app, model)
                model.startsWith("data:image", ignoreCase = true) -> {
                    val bitmap = decodeDataUrl(model)
                        ?: return toast(app, R.string.img_save_failed)
                    // Decode + disk write off the main thread.
                    thread { saveBitmapToGallery(app, bitmap) }
                }
                else -> {
                    // A local file path (an uploaded photo saved in app storage): decode → gallery.
                    val file = java.io.File(model)
                    if (!file.exists()) return toast(app, R.string.img_save_failed)
                    thread {
                        val bitmap = BitmapFactory.decodeFile(model)
                        if (bitmap != null) saveBitmapToGallery(app, bitmap) else toast(app, R.string.img_save_failed)
                    }
                }
            }
        }.onFailure { toast(app, R.string.img_save_failed) }
    }

    private fun downloadRemote(context: Context, url: String) {
        val name = "cone_${System.currentTimeMillis()}.${guessExtension(url)}"
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType("image/*")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        toast(context, R.string.img_downloading)
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
        val saved = runCatching {
            val name = "cone_${System.currentTimeMillis()}.png"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ConeAI")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return@runCatching false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrDefault(false)
        toast(context, if (saved) R.string.img_saved else R.string.img_save_failed)
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        return when {
            path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "jpg"
            path.endsWith(".webp", true) -> "webp"
            path.endsWith(".gif", true) -> "gif"
            path.endsWith(".svg", true) -> "svg"
            else -> "png"
        }
    }

    /** A model image source Coil can render: a decoded [Bitmap] for `data:` URLs, else the URL itself. */
    fun coilModel(url: String): Any =
        if (url.startsWith("data:image", ignoreCase = true)) decodeDataUrl(url) ?: url else url

    fun decodeDataUrl(dataUrl: String): Bitmap? = runCatching {
        val base64 = dataUrl.substringAfter("base64,", "")
        if (base64.isEmpty()) return null
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun toast(context: Context, resId: Int) {
        // DownloadManager.enqueue / contentResolver work off-main; hop back for the Toast.
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
        }
    }
}
