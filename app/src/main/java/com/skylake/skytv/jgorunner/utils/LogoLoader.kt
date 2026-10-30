package com.skylake.skytv.jgorunner.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Lightweight, low-memory asynchronous image loader optimized for Android 4.4 KitKat (API 19).
 * Uses RGB_565 decoding (50% less RAM than ARGB_8888), an in-memory LruCache, and disk cache.
 */
object LogoLoader {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)

    // 4 MB in-memory LruCache (plenty for 100x100 RGB_565 channel logos on KitKat)
    private val memoryCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.rowBytes * value.height
        }
    }

    fun load(context: Context, url: String?, imageView: ImageView) {
        if (url.isNullOrBlank()) {
            imageView.setImageBitmap(null)
            imageView.tag = null
            return
        }

        imageView.tag = url
        val cached = memoryCache.get(url)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageBitmap(null)
        val appContext = context.applicationContext

        executor.execute {
            try {
                val diskFile = getDiskCacheFile(appContext, url)
                var bitmap: Bitmap? = null

                if (diskFile.exists() && diskFile.length() > 0) {
                    bitmap = decodeSampledBitmap(diskFile.readBytes(), 96, 96)
                }

                if (bitmap == null) {
                    val req = Request.Builder().url(url).build()
                    val response = Tls12SocketFactory.apiHttpClient.newCall(req).execute()
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body()?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                bitmap = decodeSampledBitmap(bytes, 96, 96)
                                if (bitmap != null) {
                                    try {
                                        FileOutputStream(diskFile).use { fos -> fos.write(bytes) }
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    }
                }

                val finalBitmap = bitmap
                if (finalBitmap != null) {
                    memoryCache.put(url, finalBitmap)
                    mainHandler.post {
                        if (imageView.tag == url) {
                            imageView.setImageBitmap(finalBitmap)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

            var inSampleSize = 1
            if (boundsOpts.outHeight > reqHeight || boundsOpts.outWidth > reqWidth) {
                val halfHeight = boundsOpts.outHeight / 2
                val halfWidth = boundsOpts.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.RGB_565
                this.inPurgeable = true
                this.inInputShareable = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getDiskCacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "logos")
        if (!dir.exists()) dir.mkdirs()
        val md5 = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val sb = StringBuilder()
        for (b in md5) {
            sb.append(String.format("%02x", b))
        }
        return File(dir, sb.toString() + ".img")
    }
}
