package com.sample.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.AsyncTask
import android.os.Handler
import android.util.Log
import android.widget.ImageView
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap


/**
 * This helper class download images from the Internet and binds those with the provided ImageView.
 * A local cache of downloaded images is maintained internally to improve performance.
 */
class ImageDownloader {

    /*
     * Cache-related fields and methods.
     *
     * We use a hard and a soft cache. A soft reference cache is too aggressively cleared by the
     * Garbage Collector.
     */
    companion object {
        val LOG_TAG = ImageDownloader::class.java.name
        private const val HARD_CACHE_CAPACITY = 10
        private const val DELAY_BEFORE_PURGE = 10 * 1000 // in milliseconds
    }

    /**
     * Download the specified image from the Internet and binds it to the provided ImageView. The
     * binding is immediate if the image is found in the cache and will be done asynchronously
     * otherwise. A null bitmap will be associated to the ImageView if an error occurs.
     *
     * @param url The URL of the image to download.
     * @param imageView The ImageView to bind the downloaded image to.
     */
    fun download(url: String, imageView: ImageView) {
        resetPurgeTimer()
        val bitmap: Bitmap? = getBitmapFromCache(url)
        if (bitmap == null) {
            forceDownload(url, imageView)
        } else {
            cancelPotentialDownload(url, imageView)
            imageView.setImageBitmap(bitmap)
        }
    }


    /**
     * Same as download but the image is always downloaded and the cache is not used.
     * Kept private at the moment as its interest is not clear.
     */
    private fun forceDownload(url: String?, imageView: ImageView) {
        // State sanity: url is guaranteed to never be null in DownloadedDrawable and cache keys.
        if (url == null) {
            imageView.setImageDrawable(null)
            return
        }
        if (cancelPotentialDownload(url, imageView)) {
            val task = BitmapDownloaderTask(imageView)
            val downloadedDrawable = DownloadedDrawable(task)
            imageView.setImageDrawable(downloadedDrawable)
            imageView.minimumHeight = 156
            task.execute(url)
        }
    }


    /**
     * Returns true if the current download has been canceled or if there was no download in
     * progress on this image view.
     * Returns false if the download in progress deals with the same url. The download is not
     * stopped in that case.
     */
    private fun cancelPotentialDownload(url: String, imageView: ImageView): Boolean {
        val bitmapDownloaderTask: BitmapDownloaderTask? = getBitmapDownloaderTask(imageView)
        if (bitmapDownloaderTask != null) {
            val bitmapUrl: String? = bitmapDownloaderTask.url
            if (bitmapUrl == null || bitmapUrl != url) {
                bitmapDownloaderTask.cancel(true)
            } else {
                // The same URL is already being downloaded.
                return false
            }
        }
        return true
    }

    /**
     * @param imageView Any imageView
     * @return Retrieve the currently active download task (if any) associated with this imageView.
     * null if there is no such task.
     */
    private fun getBitmapDownloaderTask(imageView: ImageView?): BitmapDownloaderTask? {
        if (imageView != null) {
            val drawable = imageView.drawable
            if (drawable is DownloadedDrawable) {
                return drawable.bitmapDownloaderTask
            }
        }
        return null
    }


    fun downloadBitmap(url: String): Bitmap? {
        var urlConnection: HttpURLConnection? = null
        var inputStream: InputStream? = null

        try {
            urlConnection = URL(url).openConnection() as HttpURLConnection
            urlConnection.requestMethod = "GET"
            urlConnection.readTimeout = 10000
            urlConnection.connectTimeout = 15000/* milliseconds */
            urlConnection.connect()
            inputStream = urlConnection.inputStream

            // return BitmapFactory.decodeStream(inputStream);
            // Bug on slow connections, fixed in future release.
            return BitmapFactory.decodeStream(FlushedInputStream(inputStream))
        } catch (e: IOException) {
            Log.w(LOG_TAG, "I/O error while retrieving bitmap from $url", e)
        } finally {
            urlConnection?.disconnect()
            // function must handle java.io.IOException here
            inputStream?.close()
        }
        return null
    }


    /*
     * An InputStream that skips the exact number of bytes provided, unless it reaches EOF.
     */
    internal class FlushedInputStream(inputStream: InputStream?) :
        FilterInputStream(inputStream) {
        @Throws(IOException::class)
        override fun skip(n: Long): Long {
            var totalBytesSkipped = 0L
            while (totalBytesSkipped < n) {
                var bytesSkipped: Long = `in`.skip(n - totalBytesSkipped)
                if (bytesSkipped == 0L) {
                    val b: Int = read()
                    bytesSkipped = if (b < 0) {
                        break // we reached EOF
                    } else {
                        1 // we read one byte
                    }
                }
                totalBytesSkipped += bytesSkipped
            }
            return totalBytesSkipped
        }
    }


    /**
     * The actual AsyncTask that will asynchronously download the image.
     */
    inner class BitmapDownloaderTask(imageView: ImageView) : AsyncTask<String?, Void?, Bitmap>() {
        var url: String? = null
        private val imageViewReference: WeakReference<ImageView>?

        init {
            imageViewReference = WeakReference(imageView)
        }

        /**
         * Actual download method.
         */
        override fun doInBackground(vararg params: String?): Bitmap? {
            url = params[0]
            url?.let {
                return downloadBitmap(it)
            } ?: run {
                return null
            }
        }

        /**
         * Once the image is downloaded, associates it to the imageView
         */
        override fun onPostExecute(bitmap: Bitmap?) {
            val newBitmap = if (isCancelled) { null } else { bitmap }
            url?.let { addBitmapToCache(it, newBitmap) }
            if (imageViewReference != null) {
                val imageView = imageViewReference.get()
                val bitmapDownloaderTask: BitmapDownloaderTask? = getBitmapDownloaderTask(imageView)
                // Change bitmap only if this process is still associated with it
                // Or if we don't use any bitmap to task association (NO_DOWNLOADED_DRAWABLE mode)
                if (this === bitmapDownloaderTask) {
                    imageView?.setImageBitmap(newBitmap)
                }
            }
        }
    }

    /**
     * A fake Drawable that will be attached to the imageView while the download is in progress.
     *
     *
     * Contains a reference to the actual download task, so that a download task can be stopped
     * if a new binding is required, and makes sure that only the last started download process can
     * bind its result, independently of the download finish order.
     */
    internal class DownloadedDrawable(bitmapDownloaderTask: BitmapDownloaderTask?) : ColorDrawable(Color.GRAY) {
        private val bitmapDownloaderTaskReference = WeakReference<BitmapDownloaderTask>(bitmapDownloaderTask)
        val bitmapDownloaderTask: BitmapDownloaderTask?
            get() = bitmapDownloaderTaskReference.get()

    }

    // Hard cache, with a fixed maximum capacity and a life duration
    private val sHardBitmapCache: LinkedHashMap<String, Bitmap> =
        object : LinkedHashMap<String, Bitmap>(HARD_CACHE_CAPACITY / 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
                return if (size > HARD_CACHE_CAPACITY) {
                    // Entries push-out of hard reference cache are transferred to soft reference cache
                    sSoftBitmapCache[eldest.key] = SoftReference<Bitmap>(eldest.value)
                    true
                } else false
            }
        }

    // Soft cache for bitmaps kicked out of hard cache
    private val sSoftBitmapCache: ConcurrentHashMap<String, SoftReference<Bitmap>> =
        ConcurrentHashMap<String, SoftReference<Bitmap>>(HARD_CACHE_CAPACITY / 2)

    private val purgeHandler: Handler = Handler()

    private val purger = Runnable { clearCache() }

    /**
     * Adds this bitmap to the cache.
     * @param bitmap The newly downloaded bitmap.
     */
    private fun addBitmapToCache(url: String, bitmap: Bitmap?) {
        if (bitmap != null) {
            synchronized(sHardBitmapCache) { sHardBitmapCache.put(url, bitmap) }
        }
    }

    /**
     * @param url The URL of the image that will be retrieved from the cache.
     * @return The cached bitmap or null if it was not found.
     */
    private fun getBitmapFromCache(url: String): Bitmap? {
        // First try the hard reference cache
        synchronized(sHardBitmapCache) {
            val bitmap: Bitmap? = sHardBitmapCache[url]
            if (bitmap != null) {
                // Bitmap found in hard cache
                // Move element to first position, so that it is removed last
                sHardBitmapCache.remove(url)
                sHardBitmapCache[url] = bitmap
                return bitmap
            }
        }

        // Then try the soft reference cache
        val bitmapReference: SoftReference<Bitmap>? = sSoftBitmapCache[url]
        if (bitmapReference != null) {
            val bitmap: Bitmap? = bitmapReference.get()
            if (bitmap != null) {
                // Bitmap found in soft cache
                return bitmap
            } else {
                // Soft reference has been Garbage Collected
                sSoftBitmapCache.remove(url)
            }
        }
        return null
    }

    /**
     * Clears the image cache used internally to improve performance. Note that for memory
     * efficiency reasons, the cache will automatically be cleared after a certain inactivity delay.
     */
    private fun clearCache() {
        sHardBitmapCache.clear()
        sSoftBitmapCache.clear()
    }

    /**
     * Allow a new delay before the automatic cache clear is done.
     */
    private fun resetPurgeTimer() {
        purgeHandler.removeCallbacks(purger)
        purgeHandler.postDelayed(purger, DELAY_BEFORE_PURGE.toLong())
    }
}